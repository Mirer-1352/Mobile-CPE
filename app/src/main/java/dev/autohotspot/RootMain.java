package dev.autohotspot;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs from this APK's dex under su/app_process. No exported IPC or network port. */
public final class RootMain {
    private static Context context;
    private static final HandlerThread callbacks = new HandlerThread("router-callbacks");
    private static volatile Integer clients;
    private static Object wifiCallback;
    private static Object tether;
    private static final Executor executor = Runnable::run;

    public static void main(String[] args) {
        try {
            context = AndroidPlatform.rootContext();
            callbacks.start();
            reply(new JSONObject().put("ok", true).put("sdk", Build.VERSION.SDK_INT));
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    JSONObject request = new JSONObject(line);
                    switch (request.getString("op")) {
                        case "snapshot": reply(snapshot()); break;
                        case "start": reply(change(request.getInt("type"), true)); break;
                        case "stop": reply(change(request.getInt("type"), false)); break;
                        case "update": reply(RootUpdateWorker.prepare(context, request.getJSONObject("data"))); break;
                        case "updateStatus": reply(new JSONObject().put("ok", true).put("update", RootUpdateWorker.status(request.getInt("type")))); break;
                        case "configure":
                            JSONObject configuration = request.getJSONObject("data");
                            GuardianPolicy policy = GuardianPolicy.parse(configuration.getJSONObject("policy"));
                            Context policyContext = AndroidPlatform.asUser(context, policy.user);
                            if (policyContext.getPackageManager().getPackageInfo(GuardianContract.PACKAGE, 0).firstInstallTime != policy.installedAt)
                                throw new SecurityException("Guardian install identity mismatch");
                            reply(new JSONObject().put("ok", true).put("saved", new GuardianFiles(new File(GuardianContract.DIRECTORY))
                                .save(policy, () -> HomeOverrides.set(policy.user, configuration.getBoolean("forceHome")))));
                            break;
                        default: throw new IllegalArgumentException("未知操作");
                    }
                } catch (Throwable error) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    reply(new JSONObject().put("ok", false).put("error", cause.toString()));
                }
            }
        } catch (Throwable error) {
            try { reply(new JSONObject().put("ok", false).put("error", error.toString())); }
            catch (Exception ignored) { }
        } finally { System.exit(0); }
    }

    private static void reply(JSONObject value) {
        System.out.println("AUTOHOTSPOT:" + value);
        System.out.flush();
    }

    private static JSONObject change(int type, boolean start) throws Exception {
        if (type != 0 && type != 1 && type != 5) throw new IllegalArgumentException("不支持的共享类型");
        if (type == 5 && Build.VERSION.SDK_INT < 30)
            throw new UnsupportedOperationException("此 Android 版本没有原生以太网共享 API（需 Android 11+）");
        // Recheck at the execution boundary: the cable role may have changed since the snapshot.
        if (type == 1 && UsbState.read(context).hostOnly())
            throw new IllegalStateException("USB 正处于 Host 模式，暂不改变 USB 共享，以免断开外接设备");
        Object tether = tethering();
        if (!start) {
            tether.getClass().getMethod("stopTethering", int.class).invoke(tether, type);
            // Native NCM is a separate tethering type on Android 11+, but is one USB option in our UI.
            if (type == 1 && Build.VERSION.SDK_INT >= 30) {
                try { tether.getClass().getMethod("stopTethering", int.class).invoke(tether, 4); }
                catch (ReflectiveOperationException ignored) { } // ROMs without NCM still stop ordinary USB above.
            }
            return new JSONObject().put("ok", true);
        }
        CountDownLatch done = new CountDownLatch(1);
        int[] result = {-1};
        if (Build.VERSION.SDK_INT >= 30) {
            Class<?> callback = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class[]{callback}, (p, m, a) -> {
                if (m.getName().equals("onTetheringStarted")) { result[0] = 0; done.countDown(); }
                if (m.getName().equals("onTetheringFailed")) { result[0] = (Integer) a[0]; done.countDown(); }
                return objectMethod(p, m, a);
            });
            Class<?> builderClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Object builder = builderClass.getConstructor(int.class).newInstance(type);
            Object request = builderClass.getMethod("build").invoke(builder);
            tether.getClass().getMethod("startTethering", request.getClass(), Executor.class, callback)
                .invoke(tether, request, executor, proxy);
        } else {
            Field field = AndroidPlatform.field(tether.getClass(), "mService");
            Object service = field.get(tether);
            ResultReceiver receiver = new ResultReceiver(new Handler(callbacks.getLooper())) {
                @Override protected void onReceiveResult(int code, Bundle data) { result[0] = code; done.countDown(); }
            };
            Class<?> iface = Class.forName("android.net.IConnectivityManager");
            iface.getMethod("startTethering", int.class, ResultReceiver.class, boolean.class, String.class)
                .invoke(service, type, receiver, false, "android");
        }
        if (!done.await(12, TimeUnit.SECONDS)) throw new IOException("共享请求回调超时，将重新读取实际状态");
        if (result[0] != 0) throw new IOException("系统拒绝开启共享，错误码 " + result[0]);
        return new JSONObject().put("ok", true);
    }

    private static Object objectMethod(Object p, Method m, Object[] a) {
        switch (m.getName()) {
            case "hashCode": return System.identityHashCode(p);
            case "equals": return p == a[0];
            case "toString": return "AutoHotspotCallback";
            default: return null;
        }
    }
    private static void registerClients() {
        try {
            Object wifi = context.getSystemService(Context.WIFI_SERVICE);
            Class<?> cb = Class.forName("android.net.wifi.WifiManager$SoftApCallback");
            wifiCallback = Proxy.newProxyInstance(cb.getClassLoader(), new Class[]{cb}, (p, m, a) -> {
                if (m.getName().equals("onNumClientsChanged")) clients = (Integer) a[0];
                if (m.getName().equals("onConnectedClientsChanged") && a != null && a.length == 1 && a[0] instanceof List)
                    clients = ((List<?>) a[0]).size();
                return objectMethod(p, m, a);
            });
            if (Build.VERSION.SDK_INT >= 30)
                wifi.getClass().getMethod("registerSoftApCallback", Executor.class, cb).invoke(wifi, executor, wifiCallback);
            else wifi.getClass().getMethod("registerSoftApCallback", cb, Handler.class)
                .invoke(wifi, wifiCallback, new Handler(callbacks.getLooper()));
        } catch (Throwable ignored) { clients = null; }
    }

    private static JSONArray regex(String name) {
        try { return new JSONArray(Arrays.asList((String[]) tether.getClass().getMethod(name).invoke(tether))); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject snapshot() throws Exception {
        Object tether = tethering();
        String[] active = (String[]) tether.getClass().getMethod("getTetheredIfaces").invoke(tether);
        JSONArray interfaces = new JSONArray();
        File[] dirs = new File("/sys/class/net").listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            interfaces.put(new JSONObject().put("name", dir.getName())
                .put("master", masterName(dir))
                .put("rx", readLong(new File(dir, "statistics/rx_bytes")))
                .put("tx", readLong(new File(dir, "statistics/tx_bytes")))
                .put("carrier", readLong(new File(dir, "carrier")) == 1));
        }
        return UsbState.read(context).appendTo(new JSONObject().put("ok", true).put("active", new JSONArray(Arrays.asList(active)))
            .put("interfaces", interfaces).put("clients", clients == null ? JSONObject.NULL : clients)
            .put("wifiRegex", regex("getTetherableWifiRegexs"))
            .put("usbRegex", regex("getTetherableUsbRegexs"))
            .put("ethernetSupported", Build.VERSION.SDK_INT >= 30));
    }
    @android.annotation.SuppressLint("InlinedApi") // "tethering" exists since API 30 as a hidden service name.
    private static Object tethering() {
        if (tether == null) {
            // This service exists since API 30; its public constant was exposed only in API 36.
            tether = context.getSystemService(Build.VERSION.SDK_INT >= 30 ? Context.TETHERING_SERVICE : Context.CONNECTIVITY_SERVICE);
            if (tether == null) throw new UnsupportedOperationException("此 ROM 未提供共享管理服务");
            registerClients();
        }
        return tether;
    }
    private static String masterName(File dir) {
        try {
            File master = new File(dir, "master");
            return master.exists() ? master.getCanonicalFile().getName() : "";
        } catch (IOException ignored) { return ""; }
    }
    private static long readLong(File path) {
        try { return Long.parseLong(new String(Files.readAllBytes(path.toPath())).trim()); }
        catch (Exception ignored) { return -1; }
    }
}
