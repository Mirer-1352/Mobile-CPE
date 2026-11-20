package dev.autohotspot;

import android.content.Context;
import android.os.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/** Receives the platform's stop result instead of treating a void legacy call as success. */
final class TetheringStop {
    @androidx.annotation.RequiresApi(30)
    static boolean request(Object manager, Context context, int type, IntFunction<String> names) throws Exception {
        Method getConnector = null;
        for (Class<?> c = manager.getClass(); c != null && getConnector == null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) if (method.getName().equals("getConnector") &&
                method.getParameterCount() == 1 && method.getParameterTypes()[0].isInterface()) { getConnector = method; break; }
        }
        if (getConnector == null) {
            manager.getClass().getMethod("stopTethering", int.class).invoke(manager, type);
            return false; // Completion must still be verified by a later interface observation.
        }
        getConnector.setAccessible(true);
        NativeReply result = new NativeReply();
        Class<?> consumer = getConnector.getParameterTypes()[0];
        Object proxy = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[]{consumer}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return TetheringBackend.objectMethod(self, method, args);
            if (!method.getName().equals("onConnectorAvailable")) return null;
            try {
                Object connector = args[0];
                Method stop = null;
                for (Method candidate : connector.getClass().getMethods()) {
                    Class<?>[] p = candidate.getParameterTypes();
                    if (!candidate.getName().equals("stopTethering") || (p.length != 3 && p.length != 4) ||
                        p[0] != int.class || p[1] != String.class || !p[p.length - 1].getName().endsWith(".IIntResultListener")) continue;
                    if (stop == null || p.length > stop.getParameterCount()) stop = candidate;
                }
                if (stop == null) throw new NoSuchMethodException("No compatible tethering stop result API");
                stop.setAccessible(true);
                Class<?> listener = stop.getParameterTypes()[stop.getParameterCount() - 1];
                Object callback = result.attach(listener);
                if (stop.getParameterCount() == 4) stop.invoke(connector, type, context.getOpPackageName(), null, callback);
                else stop.invoke(connector, type, context.getOpPackageName(), callback);
            } catch (Throwable failure) { result.fail(failure); }
            return null;
        });
        getConnector.invoke(manager, proxy);
        if (!result.done.await(12, TimeUnit.SECONDS)) throw new IOException("停止共享回调超时，将重新确认接口");
        if (result.failure != null) {
            if (result.failure instanceof Exception) throw (Exception) result.failure;
            throw new IOException("停止共享失败", result.failure);
        }
        if (result.code != 0) throw new TetheringFailure(result.code, names.apply(result.code));
        return true;
    }

    private static final class NativeReply extends Binder {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int code = -1;
        volatile Throwable failure;
        private int resultTransaction;
        private String descriptor;

        Object attach(Class<?> type) throws Exception {
            type.getMethod("onResult", int.class); // Validate the callback ABI before binding it.
            descriptor = type.getName();
            // Read the generated callback transaction ID from this ROM; never guess service IDs.
            resultTransaction = AndroidPlatform.field(Class.forName(descriptor + "$Stub"), "TRANSACTION_onResult").getInt(null);
            Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
                switch (method.getName()) {
                    case "asBinder": return this;
                    case "onResult": complete((Integer) args[0]); return null;
                    default: return TetheringBackend.objectMethod(self, method, args);
                }
            });
            attachInterface((IInterface) proxy, descriptor);
            return proxy;
        }
        void complete(int value) { code = value; done.countDown(); }
        void fail(Throwable error) { failure = error; done.countDown(); }
        @Override protected boolean onTransact(int transaction, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (transaction != resultTransaction) return super.onTransact(transaction, data, reply, flags);
            try {
                data.enforceInterface(descriptor);
                complete(data.readInt());
                if (reply != null) reply.writeNoException();
            } catch (RuntimeException error) { fail(error); }
            return true;
        }
    }
}
