package dev.autohotspot;

import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Shared Android hidden-API boundary; no device-wide policy changes. */
final class AndroidPlatform {
    static UserHandle user(int id) throws Exception {
        Constructor<UserHandle> constructor = UserHandle.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id);
    }

    static Context asUser(Context context, int id) throws Exception {
        return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, user(id), 0);
    }

    static Context systemContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("ActivityThread is not ready");
        return (Context) type.getMethod("getSystemContext").invoke(thread);
    }

    static Context rootContext() throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root required");
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
            .invoke(vm.getDeclaredMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        return systemContext();
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }
}
