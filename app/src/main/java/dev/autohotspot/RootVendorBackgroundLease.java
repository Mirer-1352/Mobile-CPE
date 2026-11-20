package dev.autohotspot;

import android.content.Context;
import android.os.*;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Optional ROM API adapter. A short, Binder-owned lease protects only our service's UID. */
final class RootVendorBackgroundLease {
    private static final long DURATION = 60_000, RENEW = 30_000, RETRY = 300_000;
    private boolean discovered, supported;
    private Object service, connection;
    private Method request, remaining;
    private int uid = -1;
    private long nextAttempt;
    private String error;
    private Reply callback;

    JSONObject sync(Context context, GuardianPolicy policy) throws Exception {
        JSONObject status = new JSONObject().put("adapter", "oplus-hans");
        try {
            int target = AndroidPlatform.asUser(context, policy.user).getPackageManager()
                .getApplicationInfo(GuardianContract.PACKAGE, 0).uid;
            if (uid >= 0 && uid != target) throw new SecurityException("Background lease cannot change user");
            uid = target;
            long now = SystemClock.elapsedRealtime();
            if (!discovered && now >= nextAttempt) discover();
            if (!supported || !policy.enabled || service == null || callback == null) return status.put("supported", supported).put("active", false)
                .put("error", error == null ? JSONObject.NULL : error);
            if (now >= nextAttempt) {
                nextAttempt = now + RETRY;
                callback.prepare();
                request.invoke(service, uid, GuardianContract.PACKAGE, DURATION, "手机CPE共享守护", connection);
                if (!callback.done.await(2, TimeUnit.SECONDS)) error = "ROM background protection callback timed out";
                else error = callback.error;
                if (error == null && callback.active) nextAttempt = now + RENEW;
            }
            // Optional adapter failures must never disturb native tethering monitoring.
            long duration = callback.active ? (Long) remaining.invoke(service, uid) : 0;
            if (duration > 0) nextAttempt = Math.min(nextAttempt, now + RENEW);
            String detail = callback.error != null ? callback.error : error;
            return status.put("supported", true).put("active", callback.active && duration > 0)
                .put("error", detail == null ? JSONObject.NULL : detail);
        } catch (Exception | LinkageError failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            error = cause.toString();
            discovered = false;
            service = null;
            callback = null;
            nextAttempt = SystemClock.elapsedRealtime() + RETRY;
            return status.put("supported", supported).put("active", false).put("error", error);
        }
    }

    private void discover() throws Exception {
        discovered = true;
        Class<?> manager;
        try { manager = Class.forName("com.oplus.app.OplusHansFreezeManager"); }
        catch (ClassNotFoundException otherRom) { return; }
        supported = true;
        Class<?> api = Class.forName("com.oplus.app.IOplusHansFreezeManager");
        Class<?> listener = Class.forName("com.oplus.app.IOplusProtectConnection");
        request = api.getMethod("requestFrozenDelay", int.class, String.class, long.class, String.class, listener);
        remaining = api.getMethod("getFrozenDelayTime", int.class);
        Method getter = manager.getDeclaredMethod("getService");
        getter.setAccessible(true);
        service = getter.invoke(manager.getMethod("getInstance").invoke(null));
        if (service == null) throw new IllegalStateException("ROM background protection service unavailable");
        callback = new Reply();
        connection = callback.attach(listener);
        error = null;
    }

    private static final class Reply extends Binder {
        volatile CountDownLatch done = new CountDownLatch(1);
        volatile boolean active;
        volatile String error;
        private String descriptor;
        private int success, rejected, timeout;

        Object attach(Class<?> listener) throws Exception {
            listener.getMethod("onSuccess");
            listener.getMethod("onError", int.class);
            listener.getMethod("onTimeout");
            descriptor = listener.getName();
            Class<?> stub = Class.forName(descriptor + "$Stub");
            success = AndroidPlatform.field(stub, "TRANSACTION_onSuccess").getInt(null);
            rejected = AndroidPlatform.field(stub, "TRANSACTION_onError").getInt(null);
            timeout = AndroidPlatform.field(stub, "TRANSACTION_onTimeout").getInt(null);
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (self, method, args) -> {
                if (method.getName().equals("asBinder")) return this;
                if (method.getName().equals("onSuccess")) { complete(null); return null; }
                if (method.getName().equals("onError")) { complete("ROM rejected background protection: " + args[0]); return null; }
                if (method.getName().equals("onTimeout")) { complete("ROM background protection expired"); return null; }
                return TetheringBackend.objectMethod(self, method, args);
            });
            attachInterface((IInterface) proxy, descriptor);
            return proxy;
        }
        void prepare() { error = null; done = new CountDownLatch(1); }
        void complete(String failure) { active = failure == null; error = failure; done.countDown(); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != success && code != rejected && code != timeout) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(descriptor);
            if (code == success) complete(null);
            else if (code == rejected) complete("ROM rejected background protection: " + data.readInt());
            else complete("ROM background protection expired");
            if (reply != null) reply.writeNoException();
            return true;
        }
    }
}
