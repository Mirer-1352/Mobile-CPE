package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class ScopeRequestGateTest {
    @Test fun systemScopeAloneDoesNotAuthorizeTetheringModule() {
        val gate = ScopeRequestGate()
        val required = setOf("system", "com.android.networkstack.tethering")
        assertTrue(gate.begin(setOf("system"), true, true, required = required))
        gate.finish()
        assertFalse(gate.begin(setOf("system"), true, true, required = required))
        assertTrue(gate.begin(setOf("system"), true, true, manual = true, required = required))
    }
    @Test fun completeScopeDoesNotRequestExtraPackages() {
        assertFalse(ScopeRequestGate().begin(setOf("system", "com.android.networkstack.tethering"), true, true,
            required = setOf("system", "com.android.networkstack.tethering")))
    }
    @Test fun androidPackageDoesNotAuthorizeSystemServer() {
        assertTrue(ScopeRequestGate().begin(setOf("android"), true, true))
    }
    @Test fun revokedScopeCanBeRequestedAfterPreviouslyGranted() {
        val gate = ScopeRequestGate()
        gate.begin(emptySet(), true, true)
        gate.finish()
        assertFalse(gate.begin(setOf("system"), true, true))
        assertTrue(gate.begin(emptySet(), true, true))
    }
    @Test fun automaticallyRequestsMissingScopeOncePerConnection() {
        val gate = ScopeRequestGate()
        assertTrue(gate.begin(emptySet(), true, true))
        assertFalse(gate.begin(emptySet(), true, true))
        gate.finish()
        assertFalse(gate.begin(emptySet(), true, true))
    }
    @Test fun backgroundAndUnsupportedFrameworkNeverRequest() {
        val gate = ScopeRequestGate()
        assertFalse(gate.begin(emptySet(), false, true))
        assertFalse(gate.begin(emptySet(), true, false))
        assertTrue(gate.begin(emptySet(), true, true))
    }
    @Test fun grantedScopeIsNotRequestedEvenManually() {
        assertFalse(ScopeRequestGate().begin(setOf("system"), true, true, manual = true))
    }
    @Test fun denialAllowsManualRetryButNotParallelRequests() {
        val gate = ScopeRequestGate()
        assertTrue(gate.begin(emptySet(), true, true))
        assertFalse(gate.begin(emptySet(), true, true, manual = true))
        gate.finish()
        assertTrue(gate.begin(emptySet(), true, true, manual = true))
    }
    @Test fun reconnectResetsAttemptAndPendingState() {
        val gate = ScopeRequestGate()
        gate.begin(emptySet(), true, true)
        gate.reconnect()
        assertFalse(gate.pending)
        assertTrue(gate.begin(emptySet(), true, true))
    }
}

