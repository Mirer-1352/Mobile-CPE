package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class StartupPolicyTest {
    @Test fun packageReplacementRecoversEnabledServiceEvenWhenBootIsOff() {
        assertTrue(StartupPolicy.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", false, true))
    }
    @Test fun replacementDoesNotEnableAnExplicitlyPausedGuardian() {
        assertFalse(StartupPolicy.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", true, false))
    }
    @Test fun lockedAndUnlockedBootBothRespectBootPreference() {
        for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED")) {
            assertTrue(StartupPolicy.shouldStart(action, true, true))
            assertFalse(StartupPolicy.shouldStart(action, false, true))
            assertFalse(StartupPolicy.shouldStart(action, true, false))
        }
    }
    @Test fun unrelatedOrMissingBroadcastCannotStartService() {
        assertFalse(StartupPolicy.shouldStart(null, true, true))
        assertFalse(StartupPolicy.shouldStart("android.intent.action.PACKAGE_REPLACED", true, true))
    }
    @Test fun periodicChecksNeverDuplicateAnInflightSessionAndKeepManualVisibility() {
        val gate = UpdateCheckSession()
        assertTrue(gate.begin(false))
        gate.nextAutomaticWindow()
        assertFalse(gate.begin(true))
        assertTrue(gate.finish())
        assertFalse(gate.begin(false))
        gate.nextAutomaticWindow()
        assertTrue(gate.begin(false))
        assertFalse(gate.finish())
    }
}
