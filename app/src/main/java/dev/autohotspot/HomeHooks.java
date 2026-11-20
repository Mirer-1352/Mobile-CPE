package dev.autohotspot;

import android.content.*;
import android.content.pm.*;
import android.util.Log;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** HOME adaptation is independent of module bootstrap and guardian recovery. */
final class HomeHooks {
    private final XposedModule api;
    HomeHooks(XposedModule api) { this.api = api; }

    void install(ClassLoader loader) {
        for (String[] target : new String[][]{
                {"com.android.server.pm.PackageManagerService", "resolveIntent"},
                {"com.android.server.pm.ComputerEngine", "resolveIntentInternal"},
                {"com.android.server.pm.ResolveIntentHelper", "resolveIntentInternal"}}) {
            try {
                for (Method method : Class.forName(target[0], false, loader).getDeclaredMethods()) {
                    if (!method.getName().equals(target[1]) || !ResolveInfo.class.isAssignableFrom(method.getReturnType())) continue;
                    int userArgument = userArgument(method.getParameterTypes());
                    if (userArgument < 0) continue;
                    api.hook(method).intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof ResolveInfo)) return original;
                        try { return resolve((ResolveInfo) original, chain.getArgs(), (Integer) chain.getArgs().get(userArgument)); }
                        catch (Throwable ignored) { return original; }
                    });
                }
            } catch (Throwable error) { api.log(Log.DEBUG, "MCPE-Home", "Optional resolver: " + target[0], error); }
        }
    }

    // AOSP resolver signatures: [Computer,] Intent, String, flags, [privateFlags,] userId.
    // Do not infer the requested user from ResolverActivity's system UID.
    private static int userArgument(Class<?>[] parameters) {
        for (int intent = 0; intent + 3 < parameters.length; intent++) {
            if (parameters[intent] != Intent.class || parameters[intent + 1] != String.class) continue;
            Class<?> flags = parameters[intent + 2];
            if (flags != int.class && flags != long.class) return -1;
            int user = intent + 3;
            if (parameters[user] == long.class) user++;
            return user < parameters.length && parameters[user] == int.class ? user : -1;
        }
        return -1;
    }

    private ResolveInfo resolve(ResolveInfo original, Iterable<?> arguments, int user) throws Exception {
        Intent intent = null;
        for (Object value : arguments) if (value instanceof Intent) { intent = (Intent) value; break; }
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_HOME)
            || intent.getComponent() != null || intent.getPackage() != null) return original;
        if (user < 0 || user > 21474) return original;
        if (!HomeOverrides.enabled(user)) return original;
        Context context = AndroidPlatform.asUser(AndroidPlatform.systemContext(), user);
        ActivityInfo activity = context.getPackageManager().getActivityInfo(
            new ComponentName(GuardianContract.PACKAGE, "dev.autohotspot.MainActivity"), 0);
        if (!activity.enabled || !activity.applicationInfo.enabled) return original;
        ResolveInfo result = new ResolveInfo(original);
        result.activityInfo = activity;
        return result;
    }
}
