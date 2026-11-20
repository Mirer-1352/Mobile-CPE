package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class SharingReconcilerTest {
    private fun snapshot(active: Set<Sharing> = emptySet(), ethernet: Boolean = true) =
        Snapshot(active, null, emptyList(), false, ethernet)
    private fun options(desired: Set<Sharing> = emptySet(), stops: Set<Sharing> = emptySet(), recover: Boolean = true) =
        Options(desired = desired, pendingStops = stops, recover = recover).sharingPolicy()

    @Test fun inactiveSnapshotCannotAcknowledgeAStopThatWasNeverSent() {
        val controller = SharingReconciler()
        val policy = options(stops = setOf(Sharing.USB))
        val plan = controller.plan(policy, snapshot(), false, 0, 1000)
        assertEquals(listOf(SharingCommand(Sharing.USB, false)), plan.commands)
        assertTrue(plan.completedStops.isEmpty())
        controller.submitted(plan.commands.single(), 1000)
        assertTrue(controller.plan(policy, snapshot(), false, 0, 2000).completedStops.isEmpty())
        controller.accepted(plan.commands.single(), 2000)
        assertEquals(setOf(Sharing.USB), controller.plan(policy, snapshot(), false, 0, 3000).completedStops)
    }

    @Test fun userStopDoesNotWaitForAnEarlierStartBackoff() {
        val controller = SharingReconciler()
        val start = controller.plan(options(setOf(Sharing.USB)), snapshot(), true, 0, 1000).commands.single()
        controller.submitted(start, 1000)
        val plan = controller.plan(options(stops = setOf(Sharing.USB)), snapshot(), true, 0, 1001)
        assertEquals(listOf(SharingCommand(Sharing.USB, false)), plan.commands)
    }

    @Test fun aPlannedCommandIsRejectedAfterUserChangesIntent() {
        val start = SharingCommand(Sharing.USB, true)
        val stop = SharingCommand(Sharing.USB, false)
        assertFalse(start.appliesTo(options()))
        assertFalse(stop.appliesTo(options(setOf(Sharing.USB), setOf(Sharing.USB))))
        assertFalse(start.appliesTo(Options(enabled = false, desired = setOf(Sharing.USB)).sharingPolicy()))
    }

    @Test fun cableReconnectRearmsOneShotModeWithoutChangingDesiredSelection() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.USB), recover = false)
        val initial = controller.plan(policy, snapshot(), true, 0, 1000).commands.single()
        controller.submitted(initial, 1000)
        assertTrue(controller.plan(policy, snapshot(), true, 0, 9000).commands.isEmpty())
        controller.plan(policy, snapshot(), false, 1, 10000)
        assertEquals(listOf(initial), controller.plan(policy, snapshot(), true, 2, 11000).commands)
        assertEquals(setOf(Sharing.USB), policy.desired)
    }

    @Test fun unsupportedEthernetHasNoStopOperationButKeepsItsDesiredChoice() {
        val controller = SharingReconciler()
        val wanted = options(setOf(Sharing.ETHERNET))
        assertTrue(controller.plan(wanted, snapshot(ethernet = false), false, 0, 1000).commands.isEmpty())
        val stopped = controller.plan(options(stops = setOf(Sharing.ETHERNET)), snapshot(ethernet = false), false, 0, 2000)
        assertEquals(setOf(Sharing.ETHERNET), stopped.completedStops)
        assertEquals(setOf(Sharing.ETHERNET), wanted.desired)
    }

    @Test fun switchingOffWhilePausedDoesNotResumeOtherSharing() {
        val paused = Options(enabled = false, desired = Sharing.entries.toSet()).withSharing(Sharing.USB, false)
        assertFalse(paused.enabled)
        assertEquals(setOf(Sharing.WIFI, Sharing.ETHERNET), paused.desired)
        assertTrue(SharingReconciler().plan(paused.sharingPolicy(), snapshot(), true, 0, 1000).commands.isEmpty())
    }

    @Test fun hostModeDoesNotStartUsbOrStopItWhileAnAdapterIsAttached() {
        val controller = SharingReconciler()
        val host = snapshot().copy(usbHost = true, ethernetPresent = true)
        val policy = options(Sharing.entries.toSet())
        assertEquals(setOf(Sharing.WIFI, Sharing.ETHERNET), controller.plan(policy, host, true, 0, 1000).commands.map { it.mode }.toSet())
        val stop = options(stops = setOf(Sharing.USB))
        val deferred = controller.plan(stop, host, false, 1, 2000)
        assertTrue(deferred.commands.isEmpty())
        assertTrue(deferred.completedStops.isEmpty())
        val device = snapshot().copy(usbDevice = true)
        assertEquals(listOf(SharingCommand(Sharing.USB, false)), controller.plan(stop, device, true, 2, 3000).commands)
    }

    @Test fun ethernetWaitsForAnInterfaceButDoesNotRequireCarrier() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.ETHERNET))
        assertTrue(controller.plan(policy, snapshot(), false, 0, 1000).commands.isEmpty())
        val adapter = snapshot().copy(ethernetPresent = true, ethernetConnected = false)
        assertEquals(listOf(SharingCommand(Sharing.ETHERNET, true)), controller.plan(policy, adapter, false, 0, 2000).commands)
        assertEquals(setOf(Sharing.ETHERNET), policy.desired)
    }

    @Test fun acceptedStartGetsTimeToSettleEvenWhenCarrierOrOtherUsbEventsChange() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.ETHERNET))
        val adapter = snapshot().copy(ethernetPresent = true)
        val command = controller.plan(policy, adapter, false, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.accepted(command, 1100)
        val carrier = adapter.copy(ethernetConnected = true)
        assertTrue(controller.plan(policy, carrier, false, 1, 5000).commands.isEmpty())
        assertTrue(controller.plan(policy, carrier, false, 1, 31099).commands.isEmpty())
        assertEquals(listOf(command), controller.plan(policy, carrier, false, 1, 31100).commands)
    }

    @Test fun userStopDoesNotWaitForAcceptedStartGrace() {
        val controller = SharingReconciler()
        val adapter = snapshot().copy(ethernetPresent = true)
        val command = controller.plan(options(setOf(Sharing.ETHERNET)), adapter, false, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.accepted(command, 1100)
        assertEquals(listOf(SharingCommand(Sharing.ETHERNET, false)),
            controller.plan(options(stops = setOf(Sharing.ETHERNET)), adapter, false, 0, 1200).commands)
    }

    @Test fun observingStartedInterfaceRemovesGraceSoExternalStopRecoversImmediately() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.ETHERNET))
        val adapter = snapshot().copy(ethernetPresent = true)
        val command = controller.plan(policy, adapter, false, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.accepted(command, 1100)
        controller.plan(policy, adapter.copy(active = setOf(Sharing.ETHERNET)), false, 0, 2000)
        assertEquals(listOf(command), controller.plan(policy, adapter, false, 0, 3000).commands)
    }

    @Test fun realCapabilitiesBlockUnsupportedStartWithoutErasingDesired() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.ETHERNET))
        val adapter = snapshot().copy(ethernetPresent = true, supported = setOf(Sharing.WIFI, Sharing.USB))
        assertTrue(controller.plan(policy, adapter, false, 0, 1000).commands.isEmpty())
        assertEquals(setOf(Sharing.ETHERNET), policy.desired)
        assertEquals(1, controller.plan(policy, adapter.copy(supported = Sharing.entries.toSet()), false, 0, 2000).commands.size)
    }

    @Test fun permissionFailureWaitsForAChangeRatherThanRetryingEveryPoll() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.USB))
        val state = snapshot()
        val command = controller.plan(policy, state, true, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.rejected(command, SharingFailure(14, "TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION"), 1000)
        assertTrue(controller.plan(policy, state, true, 0, 100000).commands.isEmpty())
        assertEquals(listOf(command), controller.plan(policy, state, true, 1, 100001).commands)
    }

    @Test fun aRejectedStartCannotBlockTheUsersNewStopRequest() {
        val controller = SharingReconciler()
        val command = controller.plan(options(setOf(Sharing.USB)), snapshot(), true, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.rejected(command, SharingFailure(14, "SECURITY_EXCEPTION"), 1000)
        assertEquals(listOf(SharingCommand(Sharing.USB, false)),
            controller.plan(options(stops = setOf(Sharing.USB)), snapshot(), true, 0, 2000).commands)
    }

    @Test fun asyncPermissionErrorBlocksOnlyTheFailedModeAndARepeatEventDoesNotDisappear() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.USB, Sharing.ETHERNET))
        val fault = InterfaceFailure("eth0", Sharing.ETHERNET, SharingFailure(14, "SECURITY_EXCEPTION"), 1)
        val state = snapshot().copy(ethernetPresent = true, failures = listOf(fault))
        assertEquals(listOf(SharingCommand(Sharing.USB, true)), controller.plan(policy, state, true, 0, 1000).commands)
        val changed = state.copy(failures = listOf(fault.copy(sequence = 2)))
        assertFalse(controller.plan(policy, changed, true, 0, 100000).commands.any { it.mode == Sharing.ETHERNET })
    }

    @Test fun losingUpstreamNeverTearsDownOrRestartsAnActiveDownstream() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.WIFI))
        val state = snapshot(setOf(Sharing.WIFI)).copy(upstreamKnown = true, upstreamPresent = false)
        assertTrue(controller.plan(policy, state, false, 0, 1000).commands.isEmpty())
        assertEquals("已开启，上游未就绪", controller.progress(policy, state, false).getValue(Sharing.WIFI).detail)
    }

    @Test fun transientRequestFailureStillUsesBoundedRetry() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.USB))
        val command = controller.plan(policy, snapshot(), true, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.rejected(command, SharingFailure(4, "TETHER_ERROR_UNAVAIL_IFACE"), 1000)
        assertTrue(controller.plan(policy, snapshot(), true, 0, 3999).commands.isEmpty())
        assertEquals(listOf(command), controller.plan(policy, snapshot(), true, 0, 4000).commands)
    }

    @Test fun missedOemPolicyNotificationStillRecoversWithASlowProbe() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.WIFI))
        val command = controller.plan(policy, snapshot(), false, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.rejected(command, SharingFailure(11, "TETHER_ERROR_PROVISIONING_FAILED"), 1000)
        assertTrue(controller.plan(policy, snapshot(), false, 0, 300999).commands.isEmpty())
        assertEquals(listOf(command), controller.plan(policy, snapshot(), false, 0, 301000).commands)
        assertEquals(setOf(Sharing.WIFI), policy.desired)
    }

    @Test fun observedSuccessClearsAnEarlierPermissionRejectionBeforeTheNextDisconnect() {
        val controller = SharingReconciler()
        val policy = options(setOf(Sharing.WIFI))
        val command = controller.plan(policy, snapshot(), false, 0, 1000).commands.single()
        controller.submitted(command, 1000)
        controller.rejected(command, SharingFailure(14, "SECURITY_EXCEPTION"), 1000)
        controller.plan(policy, snapshot(setOf(Sharing.WIFI)), false, 0, 2000)
        assertEquals(listOf(command), controller.plan(policy, snapshot(), false, 0, 3000).commands)
    }
}
