package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class BackgroundProtectionPolicyTest {
    @Test fun enabledGuardianRequestsOnlyItsMissingExemption() {
        assertEquals(BackgroundProtectionPolicy.Action.ADD, BackgroundProtectionPolicy.action(true, false, false))
        assertEquals(BackgroundProtectionPolicy.Action.NONE, BackgroundProtectionPolicy.action(true, true, false))
    }

    @Test fun pausingRestoresOnlyAnExemptionWeCreated() {
        assertEquals(BackgroundProtectionPolicy.Action.REMOVE, BackgroundProtectionPolicy.action(false, true, true))
        assertEquals(BackgroundProtectionPolicy.Action.NONE, BackgroundProtectionPolicy.action(false, true, false))
        assertEquals(BackgroundProtectionPolicy.Action.NONE, BackgroundProtectionPolicy.action(false, false, true))
    }

    @Test fun anotherUsersGuardianKeepsTheSharedPackageExemption() {
        assertEquals(BackgroundProtectionPolicy.Action.NONE, BackgroundProtectionPolicy.action(true, true, true))
    }

    @Test fun reinstallDoesNotClaimOwnershipOfTheNewInstallationsUserChoice() {
        assertFalse(BackgroundProtectionPolicy.owns(true, setOf("0:123"), setOf("0:456")))
    }

    @Test fun oneRemainingValidUserRetainsOwnership() {
        assertTrue(BackgroundProtectionPolicy.owns(true, setOf("0:123", "10:234"), setOf("10:234")))
        assertFalse(BackgroundProtectionPolicy.owns(false, setOf("0:123"), setOf("0:123")))
    }
}
