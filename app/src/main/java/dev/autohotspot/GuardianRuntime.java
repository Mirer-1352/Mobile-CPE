package dev.autohotspot;

import android.content.*;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

/** A single handler owns all user sessions. Platform calls and storage are separate boundaries. */
final class GuardianRuntime implements AutoCloseable {
    private static final class Session {
        final GuardianEngine engine = new GuardianEngine();
        long updatingUntil;
        JSONObject recovery;
    }
    private final GuardianPlatform platform;
    private final GuardianFiles files;
    private final HandlerThread thread = new HandlerThread("mcpe-system-guardian");
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final List<BroadcastReceiver> receivers = new ArrayList<>();
    private Handler handler;

    GuardianRuntime(Context context, File directory) throws Exception {
        platform = new GuardianPlatform(context);
        files = new GuardianFiles(directory);
    }

    void start() throws Exception {
        thread.start();
        handler = new Handler(thread.getLooper());
        receive(new IntentFilter(GuardianContract.CONTROL), GuardianContract.PERMISSION, this::control);
        IntentFilter packages = new IntentFilter();
        packages.addDataScheme("package");
        for (String action : new String[]{Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_DATA_CLEARED}) packages.addAction(action);
        receive(packages, null, this::packageChanged);
        IntentFilter users = new IntentFilter();
        for (String action : new String[]{"android.intent.action.USER_STARTED", "android.intent.action.USER_STOPPED", Intent.ACTION_USER_UNLOCKED}) users.addAction(action);
        receive(users, null, (receiver, intent) -> {
            int user = eventUser(intent);
            if (user < 0) return;
            if ("android.intent.action.USER_STOPPED".equals(intent.getAction())) sessions.remove(user);
            else check(user);
        });
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                safely("periodic check", () -> { for (int user : files.users()) check(user); });
                handler.postDelayed(this, 15000);
            }
        }, 5000);
    }

    private interface Event { void handle(BroadcastReceiver receiver, Intent intent) throws Exception; }
    private interface Work { void run() throws Exception; }

    private void receive(IntentFilter filter, String permission, Event event) throws Exception {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                safely("broadcast " + intent.getAction(), () -> event.handle(this, intent));
            }
        };
        platform.register(receiver, filter, permission, handler);
        receivers.add(receiver);
    }

    private void control(BroadcastReceiver receiver, Intent intent) throws Exception {
        int senderUser = (Integer) BroadcastReceiver.class.getMethod("getSendingUserId").invoke(receiver);
        String raw = intent.getStringExtra("policy");
        if (raw == null || raw.length() > 4096) return;
        GuardianPolicy policy = GuardianPolicy.parse(new JSONObject(raw));
        if (policy.user != senderUser || !platform.valid(policy) || !files.save(policy)) return;
        GuardianEngine engine = session(policy.user).engine;
        if (policy.enabled && policy.framework) {
            if (intent.getBooleanExtra("arm", false)) engine.arm();
            if (intent.getBooleanExtra("alive", false)) engine.heartbeat(SystemClock.elapsedRealtime());
        }
        check(policy.user);
    }

    private void packageChanged(BroadcastReceiver receiver, Intent intent) throws Exception {
        if (intent.getData() == null || !GuardianContract.PACKAGE.equals(intent.getData().getSchemeSpecificPart())) return;
        int user = eventUser(intent);
        if (user < 0) return;
        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            session(user).updatingUntil = SystemClock.elapsedRealtime() + 180_000;
        } else if (Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            session(user).updatingUntil = 0;
            handler.postDelayed(() -> check(user), 3000);
        } else {
            sessions.remove(user);
            files.remove(user);
            HomeOverrides.set(user, false);
        }
    }

    private static int eventUser(Intent intent) {
        int user = intent.getIntExtra("android.intent.extra.user_handle", -1);
        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        return user >= 0 ? user : uid >= 0 ? uid / 100000 : -1;
    }

    private Session session(int user) { return sessions.computeIfAbsent(user, ignored -> new Session()); }

    void died(int user) {
        Handler target = handler;
        if (target != null) target.post(() -> safely("process death", () -> {
            Session state = sessions.get(user);
            if (state == null) return;
            state.engine.died();
            GuardianPolicy policy = files.policy(user);
            if (policy == null || !state.engine.protects(policy)) return;
            platform.keepAwakeForRecovery();
            target.postDelayed(() -> check(user), 2000);
        }));
    }

    private void check(int user) {
        safely("user " + user, () -> {
            GuardianPolicy policy = files.policy(user);
            if (policy == null || !platform.valid(policy)) return;
            Session state = session(user);
            long now = SystemClock.elapsedRealtime();
            boolean updating = now < state.updatingUntil;
            GuardianEngine.Action action = state.engine.decide(policy, now, platform.userRunning(user), updating, platform.serviceRunning(user));
            long heartbeat = state.engine.lastHeartbeat();
            String detail = !policy.enabled || !policy.framework ? "系统框架守护已暂停" : updating ? "等待应用升级完成"
                : heartbeat >= 0 && now - heartbeat <= GuardianContract.HEARTBEAT_TTL ? "系统框架守护已连接" : "系统框架守护检查中";
            if (action != GuardianEngine.Action.NONE) detail = restore(policy, state, action, now);
            acknowledge(policy, state, detail);
        });
    }

    private String restore(GuardianPolicy policy, Session state, GuardianEngine.Action action, long now) throws Exception {
        state.recovery = new JSONObject().put("action", action.name()).put("elapsed", now).put("success", false).put("path", "context");
        try {
            ComponentName component = platform.restore(policy.user, action == GuardianEngine.Action.RESTART_HUNG);
            state.recovery.put("success", true).put("component", component.flattenToShortString());
            String detail = action == GuardianEngine.Action.RESTART_HUNG ? "已恢复失联的共享服务" : "已拉起共享守护";
            Log.i("MCPE-Guardian", detail + " user=" + policy.user);
            return detail;
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            state.recovery.put("error", cause.toString());
            Log.w("MCPE-Guardian", "Recovery failed for user " + policy.user, error);
            return "系统守护恢复失败：" + cause.getClass().getSimpleName() + " " + String.valueOf(cause.getMessage());
        }
    }

    private void acknowledge(GuardianPolicy policy, Session state, String detail) throws Exception {
        JSONObject value = new JSONObject().put("active", android.os.Process.myUid() == 1000)
            .put("uid", android.os.Process.myUid()).put("pid", android.os.Process.myPid())
            .put("elapsed", SystemClock.elapsedRealtime()).put("detail", detail)
            .put("enabled", policy.enabled).put("boot", policy.boot).put("framework", policy.framework)
            .put("revision", policy.revision).put("heartbeat", state.engine.lastHeartbeat());
        if (state.recovery != null) value.put("lastRecovery", state.recovery);
        files.status(policy.user, value);
        if (android.os.Process.myUid() == 1000) platform.acknowledge(policy.user, value.toString());
    }

    private static void safely(String event, Work work) {
        try { work.run(); }
        catch (Throwable error) { Log.w("MCPE-Guardian", "Guardian " + event + " failed", error); }
    }

    @Override public void close() {
        for (BroadcastReceiver receiver : receivers) {
            try { platform.unregister(receiver); } catch (Exception ignored) { }
        }
        receivers.clear();
        platform.close();
        thread.quitSafely();
    }
}
