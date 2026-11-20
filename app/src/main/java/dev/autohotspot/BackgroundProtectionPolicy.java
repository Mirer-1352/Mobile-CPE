package dev.autohotspot;

import java.util.Set;

/** A shared package exemption is leased across users; pre-existing user choices are never removed. */
final class BackgroundProtectionPolicy {
    enum Action { NONE, ADD, REMOVE }
    static boolean owns(boolean recordedOwnership, Set<String> previousInstalls, Set<String> currentInstalls) {
        if (!recordedOwnership) return false;
        for (String install : previousInstalls) if (currentInstalls.contains(install)) return true;
        return false;
    }
    static Action action(boolean wantedByAnyUser, boolean currentlyExempt, boolean owned) {
        if (wantedByAnyUser && !currentlyExempt) return Action.ADD;
        if (!wantedByAnyUser && currentlyExempt && owned) return Action.REMOVE;
        return Action.NONE;
    }
}
