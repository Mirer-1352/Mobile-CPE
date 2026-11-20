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
}
