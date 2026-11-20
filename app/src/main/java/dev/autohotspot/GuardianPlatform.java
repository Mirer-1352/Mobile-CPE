package dev.autohotspot;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.lang.reflect.Method;

/** Versioned platform effects. GuardianRuntime owns policy and scheduling only. */
final class GuardianPlatform {
    private final Context context;
    private final Method register, start, stop, send;
    private final PowerManager.WakeLock recoveryWindow;

    GuardianPlatform(Context context) throws Exception {
        this.context = context;
        recoveryWindow = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcpe:framework-recovery");
        recoveryWindow.setReferenceCounted(false);
        Method registration;
        try {
            registration = Context.class.getMethod("registerReceiverAsUser", BroadcastReceiver.class, UserHandle.class,
                IntentFilter.class, String.class, Handler.class, int.class);
        } catch (NoSuchMethodException legacy) {
            if (Build.VERSION.SDK_INT >= 33) throw legacy;
            registration = Context.class.getMethod("registerReceiverAsUser", BroadcastReceiver.class, UserHandle.class,
                IntentFilter.class, String.class, Handler.class);
        }
        register = registration;
        start = Context.class.getMethod("startForegroundServiceAsUser", Intent.class, UserHandle.class);
        stop = ActivityManager.class.getMethod("forceStopPackageAsUser", String.class, int.class);
        send = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class, String.class);
    }

    void register(BroadcastReceiver receiver, IntentFilter filter, String permission, Handler handler) throws Exception {
        if (register.getParameterTypes().length == 6) {
            register.invoke(context, receiver, AndroidPlatform.user(-1), filter, permission, handler,
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0);
        } else register.invoke(context, receiver, AndroidPlatform.user(-1), filter, permission, handler);
    }

    void unregister(BroadcastReceiver receiver) { context.unregisterReceiver(receiver); }

    boolean valid(GuardianPolicy policy) throws Exception {
        try {
            PackageInfo info = AndroidPlatform.asUser(context, policy.user).getPackageManager().getPackageInfo(GuardianContract.PACKAGE,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            return info.applicationInfo != null && info.applicationInfo.enabled && info.firstInstallTime == policy.installedAt;
        } catch (PackageManager.NameNotFoundException removed) { return false; }
    }

    boolean userRunning(int user) throws Exception {
        return context.getSystemService(UserManager.class).isUserRunning(AndroidPlatform.user(user));
    }

    @SuppressWarnings("deprecation")
    boolean serviceRunning(int user) {
        for (ActivityManager.RunningServiceInfo service : context.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE)) {
            if (service.uid / 100000 == user && GuardianContract.PACKAGE.equals(service.service.getPackageName())
                && GuardianContract.SERVICE_CLASS.equals(service.service.getClassName()) && service.started && service.foreground) return true;
        }
        return false;
    }

    ComponentName restore(int user, boolean restart) throws Exception {
        keepAwakeForRecovery();
        if (restart) stop.invoke(context.getSystemService(ActivityManager.class), GuardianContract.PACKAGE, user);
        Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class.forName("android.content.pm.IPackageManager").getMethod("setPackageStoppedState", String.class, boolean.class, int.class)
            .invoke(pm, GuardianContract.PACKAGE, false, user);
        Intent intent = new Intent().setComponent(GuardianContract.serviceComponent()).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        GuardianLaunchGate.enter();
        try {
            ComponentName component = (ComponentName) start.invoke(context, intent, AndroidPlatform.user(user));
            if (component == null) throw new IllegalStateException("系统未能启动共享服务，请检查厂商启动策略");
            return component;
        } finally { GuardianLaunchGate.leave(); }
    }

    void keepAwakeForRecovery() { recoveryWindow.acquire(15_000); }

    void close() { if (recoveryWindow.isHeld()) recoveryWindow.release(); }

    void acknowledge(int user, String status) throws Exception {
        Intent intent = new Intent(GuardianContract.STATUS).setPackage(GuardianContract.PACKAGE).putExtra("status", status);
        send.invoke(context, intent, AndroidPlatform.user(user), GuardianContract.PERMISSION);
    }
}
