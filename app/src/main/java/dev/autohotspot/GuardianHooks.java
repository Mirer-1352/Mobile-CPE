package dev.autohotspot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.lang.reflect.*;

/** Modern API 102 hooks; callbacks only enqueue work outside AMS locks. */
final class GuardianHooks {
    private final XposedModule api;
    private volatile GuardianRuntime runtime;
    private boolean starting;
    GuardianHooks(XposedModule api) { this.api = api; }
    void install(ClassLoader loader) {
        int count = bootstrapHooks(loader) + deathHooks(loader) + vendorHooks(loader);
        api.log(Log.INFO, "MCPE-Guardian", "Installed framework guardian adapters: " + count);
    }

    private int bootstrapHooks(ClassLoader loader) {
        int count = 0;
        for (String[] target : new String[][]{{"com.android.server.am.ActivityManagerService", "systemReady"}, {"com.android.server.SystemServer", "startOtherServices"}}) {
            try {
                for (Method method : Class.forName(target[0], false, loader).getDeclaredMethods()) if (method.getName().equals(target[1])) {
                    api.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        try { start(); } catch (Throwable error) { api.log(Log.ERROR, "MCPE-Guardian", "Bootstrap scheduling failed", error); }
                        return result;
                    }); count++;
                }
            } catch (Throwable error) { api.log(Log.WARN, "MCPE-Guardian", "Bootstrap adapter " + target[0], error); }
        }
        return count;
    }

    private int deathHooks(ClassLoader loader) {
        int count = 0;
        try {
            for (Method method : Class.forName("com.android.server.am.ActivityManagerService", false, loader).getDeclaredMethods()) {
                if (!method.getName().equals("handleAppDiedLocked")) continue;
                api.hook(method).intercept(chain -> {
                    int user = -1;
                    try {
                        for (Object argument : chain.getArgs()) if (argument != null && argument.getClass().getName().equals("com.android.server.am.ProcessRecord")) {
                            ApplicationInfo info = (ApplicationInfo) AndroidPlatform.field(argument, "info");
                            if (GuardianContract.PACKAGE.equals(info.packageName)) user = info.uid / 100000;
                        }
                    } catch (Throwable ignored) { }
                    Object result = chain.proceed();
                    GuardianRuntime current = runtime;
                    if (user >= 0 && current != null) try { current.died(user); } catch (Throwable ignored) { }
                    return result;
                }); count++;
            }
        } catch (Throwable error) { api.log(Log.WARN, "MCPE-Guardian", "Process-death adapter unavailable; periodic checks remain", error); }
        return count;
    }

    private int vendorHooks(ClassLoader loader) {
        int count = 0;
        for (String name : new String[]{"com.android.server.am.OplusAppStartupManager", "com.android.server.am.ColorAppStartupManager"}) {
            try {
                for (Method method : Class.forName(name, false, loader).getDeclaredMethods()) {
                    if (!method.getName().equals("isAllowStartFromService") || method.getReturnType() != boolean.class) continue;
                    boolean hasIntent = false;
                    for (Class<?> argument : method.getParameterTypes()) if (argument == android.content.Intent.class) hasIntent = true;
                    if (!hasIntent) continue;
                    api.hook(method).intercept(chain -> {
                        boolean ours = false;
                        try { ours = GuardianLaunchGate.matches(chain.getArgs()); } catch (Throwable ignored) { }
                        Object original = chain.proceed();
                        return ours && Boolean.FALSE.equals(original) ? Boolean.TRUE : original;
                    }); count++;
                }
            } catch (Throwable error) { api.log(Log.DEBUG, "MCPE-Guardian", "Optional startup adapter unavailable: " + name, error); }
        }
        return count;
    }
    private synchronized void start() {
        if (runtime != null || starting) return;
        starting = true;
        // Bootstrap callbacks can run under AMS locks. All registration and file
        // access must happen after leaving those locks, including initial setup.
        new Thread(this::initialize, "mcpe-guardian-init").start();
    }
    private void initialize() {
        GuardianRuntime created = null;
        try {
            Context context = AndroidPlatform.systemContext();
            created = new GuardianRuntime(context, new File(GuardianContract.DIRECTORY));
            created.start(); runtime = created;
            api.log(Log.INFO, "MCPE-Guardian", "system_server guardian started");
        } catch (Throwable error) {
            if (created != null) created.close();
            api.log(Log.ERROR, "MCPE-Guardian", "Framework guardian initialization failed", error);
        } finally { synchronized (this) { starting = false; } }
    }
}
