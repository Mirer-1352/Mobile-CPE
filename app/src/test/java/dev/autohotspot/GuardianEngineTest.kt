package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class GuardianEngineTest {
    private fun policy(enabled: Boolean = true, boot: Boolean = true, framework: Boolean = true, revision: Long = 1) =
        GuardianPolicy(0, revision, 123, enabled, boot, framework)
    private fun action(engine: GuardianEngine, now: Long, policy: GuardianPolicy = policy(), running: Boolean = true,
                       updating: Boolean = false, alive: Boolean = false) = engine.decide(policy, now, running, updating, alive)
    @Test fun bootStartsOnlyWhenEnabledAndFrameworkAndBootPermitIt() {
        assertEquals(GuardianEngine.Action.START, action(GuardianEngine(), 1000))
        assertEquals(GuardianEngine.Action.NONE, action(GuardianEngine(), 1000, policy(enabled = false)))
        assertEquals(GuardianEngine.Action.NONE, action(GuardianEngine(), 1000, policy(framework = false)))
        assertEquals(GuardianEngine.Action.NONE, action(GuardianEngine(), 1000, policy(boot = false)))
    }
    @Test fun manuallyStartedServiceIsProtectedEvenWithBootOff() {
        val engine = GuardianEngine(); engine.arm()
        assertEquals(GuardianEngine.Action.START, action(engine, 1000, policy(boot = false)))
    }
    @Test fun pausingDisarmsProtectionUntilAnExplicitStart() {
        val engine = GuardianEngine(); engine.arm()
        action(engine, 1000, policy(enabled = false))
        assertEquals(GuardianEngine.Action.NONE, action(engine, 2000, policy(boot = false)))
    }
    @Test fun freshHeartbeatPreventsDuplicateServiceStarts() {
        val engine = GuardianEngine(); engine.heartbeat(1000)
        assertEquals(GuardianEngine.Action.NONE, action(engine, 2000))
        engine.died()
        assertEquals(GuardianEngine.Action.START, action(engine, 3000))
    }
    @Test fun heartbeatExpiryRecoversMissingServiceButGivesRunningServiceGrace() {
        val engine = GuardianEngine(); engine.heartbeat(0)
        assertEquals(GuardianEngine.Action.NONE, action(engine, 46000, alive = true))
        assertEquals(GuardianEngine.Action.RESTART_HUNG, action(engine, 91000, alive = true))
    }
    @Test fun noHeartbeatYetDoesNotKillAStartingService() {
        val engine = GuardianEngine()
        assertEquals(GuardianEngine.Action.NONE, action(engine, 1000, alive = true))
        assertEquals(GuardianEngine.Action.NONE, action(engine, 90000, alive = true))
        assertEquals(GuardianEngine.Action.RESTART_HUNG, action(engine, 92000, alive = true))
    }
    @Test fun stoppedUsersAndUpdatingPackagesAreNotRestarted() {
        assertEquals(GuardianEngine.Action.NONE, action(GuardianEngine(), 1000, running = false))
        assertEquals(GuardianEngine.Action.NONE, action(GuardianEngine(), 1000, updating = true))
    }
    @Test fun failuresBackOffAndBurstIsLimitedToThreeInFiveMinutes() {
        val engine = GuardianEngine()
        assertEquals(GuardianEngine.Action.START, action(engine, 0))
        assertEquals(GuardianEngine.Action.NONE, action(engine, 4999))
        assertEquals(GuardianEngine.Action.START, action(engine, 5000))
        assertEquals(GuardianEngine.Action.START, action(engine, 15000))
        assertEquals(GuardianEngine.Action.NONE, action(engine, 35000))
        assertEquals(GuardianEngine.Action.NONE, action(engine, 299999))
        assertEquals(GuardianEngine.Action.START, action(engine, 300000))
    }
    @Test fun staleHeartbeatCannotUndoNewerPausePolicy() {
        val current = policy(enabled = false, revision = 10)
        assertFalse(current.accepts(policy(revision = 9)))
        assertFalse(current.accepts(policy(revision = 10)))
        assertTrue(current.accepts(policy(enabled = false, revision = 10)))
    }
    @Test fun policyIsBoundToUserAndActualInstallationIdentity() {
        val current = policy()
        assertFalse(current.accepts(GuardianPolicy(10, 20, 123, true, true, true)))
        assertTrue(current.accepts(GuardianPolicy(0, 0, 100, true, true, true)))
    }
}
