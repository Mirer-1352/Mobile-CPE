package dev.autohotspot;

import android.content.ComponentName;
import android.content.Intent;

/** In-process provenance only: no exported marker, no disk access under AMS locks. */
final class GuardianLaunchGate {
    private static final ThreadLocal<Boolean> restoring = new ThreadLocal<>();
    static void enter() { restoring.set(true); }
    static void leave() { restoring.remove(); }
    static boolean matches(Iterable<?> arguments) {
        if (!Boolean.TRUE.equals(restoring.get()) || android.os.Process.myUid() != 1000) return false;
        for (Object argument : arguments) if (argument instanceof Intent) {
            ComponentName component = ((Intent) argument).getComponent();
            if (component != null && GuardianContract.PACKAGE.equals(component.getPackageName())
                && GuardianContract.SERVICE_CLASS.equals(component.getClassName())) return true;
        }
        return false;
    }
}
