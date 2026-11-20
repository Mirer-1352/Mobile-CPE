package dev.autohotspot;

import io.github.libxposed.api.XposedModule;

/** Modern API 102 entry point. Each capability owns its own hooks and state. */
public final class CpeModule extends XposedModule {
    private final VpnHooks vpn = new VpnHooks(this);

    @Override public void onPackageReady(PackageReadyParam parameter) {
        String name = parameter.getPackageName();
        if (name.startsWith("com.android.networkstack") || name.startsWith("com.google.android.networkstack"))
            vpn.install(parameter.getClassLoader());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam parameter) {
        if ((getFrameworkProperties() & PROP_CAP_SYSTEM) == 0) return;
        ClassLoader loader = parameter.getClassLoader();
        new GuardianHooks(this).install(loader);
        new HomeHooks(this).install(loader);
        vpn.install(loader);
    }
}
