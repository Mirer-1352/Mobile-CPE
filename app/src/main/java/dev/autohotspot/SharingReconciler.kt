package dev.autohotspot

internal data class SharingPolicy(val enabled: Boolean, val recover: Boolean,
    val desired: Set<Sharing>, val pendingStops: Set<Sharing>)

internal fun Options.sharingPolicy() = SharingPolicy(enabled, recover, desired, pendingStops)

internal data class SharingCommand(val mode: Sharing, val start: Boolean) {
    fun appliesTo(policy: SharingPolicy) = policy.enabled &&
        if (start) mode in policy.desired else mode in policy.pendingStops && mode !in policy.desired
}

internal data class SharingPlan(val commands: List<SharingCommand>, val completedStops: Set<Sharing>)

/** Coordinates intent with observations. It never changes the user's desired selection. */
internal class SharingReconciler {
    private val retry = RetryPolicy()
    private val attempted = mutableSetOf<Sharing>()
    private val stopsSubmitted = mutableSetOf<Sharing>()
    private val startingUntil = mutableMapOf<Sharing, Long>()
    private var previousDesired = emptySet<Sharing>()
    private var previousStops = emptySet<Sharing>()
    private var previousUsb = false
    private var previousEthernet = false
    private var previousEthernetPresent = false
    private var previousTopology = -1L

    fun plan(policy: SharingPolicy, snapshot: Snapshot, usb: Boolean, topology: Long, now: Long): SharingPlan {
        if (!policy.enabled) return SharingPlan(emptyList(), emptySet())
        val changed = topology != previousTopology
        val reset = (policy.desired - previousDesired).toMutableSet()
        reset += policy.pendingStops - previousStops
        reset.forEach(startingUntil::remove)
        if (previousUsb && !usb || snapshot.usbHostOnly()) startingUntil.remove(Sharing.USB)
        if (snapshot.ethernetPresent != previousEthernetPresent) startingUntil.remove(Sharing.ETHERNET)
        if (changed || usb && !previousUsb) reset += Sharing.USB
        if (changed || snapshot.ethernetConnected && !previousEthernet || snapshot.ethernetPresent != previousEthernetPresent) reset += Sharing.ETHERNET
        reset.forEach { retry.reset(it); attempted.remove(it) }
        previousDesired = policy.desired
        previousStops = policy.pendingStops
        previousUsb = usb
        previousEthernet = snapshot.ethernetConnected
        previousEthernetPresent = snapshot.ethernetPresent
        previousTopology = topology
        attempted.retainAll(policy.desired)
        stopsSubmitted.retainAll(policy.pendingStops - policy.desired)
        startingUntil.keys.retainAll(policy.desired)

        val complete = policy.pendingStops.filterTo(mutableSetOf()) {
            it !in policy.desired && (it == Sharing.ETHERNET && !snapshot.ethernetSupported ||
                it in stopsSubmitted && it !in snapshot.active)
        }
        val commands = buildList {
            for (mode in policy.pendingStops - policy.desired - complete) {
                if (mode == Sharing.USB && snapshot.usbHostOnly()) continue
                if (retry.ready(mode, now)) add(SharingCommand(mode, false))
            }
            for (mode in policy.desired) {
                if (mode in snapshot.active) { retry.reset(mode); startingUntil.remove(mode); continue }
                if (mode == Sharing.ETHERNET && (!snapshot.ethernetSupported || !snapshot.ethernetPresent)) continue
                if (mode == Sharing.USB && (!usb || snapshot.usbHostOnly())) continue
                if (!policy.recover && mode in attempted) continue
                if (now < (startingUntil[mode] ?: 0)) continue
                if (retry.ready(mode, now)) add(SharingCommand(mode, true))
            }
        }
        return SharingPlan(commands, complete)
    }

    fun submitted(command: SharingCommand, now: Long) {
        if (command.start) attempted += command.mode
        retry.failed(command.mode, now)
    }

    fun accepted(command: SharingCommand, now: Long) {
        if (command.start) startingUntil[command.mode] = now + 30_000
        else stopsSubmitted += command.mode
    }
}
