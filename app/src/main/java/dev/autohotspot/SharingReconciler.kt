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
    private data class Rejection(val failure: SharingFailure, val retryAt: Long)
    private val retry = RetryPolicy()
    private val attempted = mutableSetOf<Sharing>()
    private val stopsSubmitted = mutableSetOf<Sharing>()
    private val startingUntil = mutableMapOf<Sharing, Long>()
    private val blocked = mutableMapOf<SharingCommand, Rejection>()
    private var previousSupported: Set<Sharing>? = null
    private var previousEpoch = 0
    private var previousFailures = emptySet<InterfaceFailure>()
    private var previousDesired = emptySet<Sharing>()
    private var previousStops = emptySet<Sharing>()
    private var previousUsb = false
    private var previousEthernet = false
    private var previousEthernetPresent = false
    private var previousTopology = -1L

    fun plan(policy: SharingPolicy, snapshot: Snapshot, usb: Boolean, topology: Long, now: Long): SharingPlan {
        if (!policy.enabled) return SharingPlan(emptyList(), emptySet())
        // Policy/permission changes are not broadcast consistently by OEMs. A slow probe
        // also recovers when a relevant signal was missed, without retrying every poll.
        blocked.entries.removeAll { now >= it.value.retryAt }
        val changed = topology != previousTopology
        val reset = (policy.desired - previousDesired).toMutableSet()
        reset += policy.pendingStops - previousStops
        if (snapshot.supported != previousSupported || snapshot.backendEpoch != previousEpoch) reset += Sharing.entries
        reset.forEach(startingUntil::remove)
        if (previousUsb && !usb || snapshot.usbHostOnly()) startingUntil.remove(Sharing.USB)
        if (snapshot.ethernetPresent != previousEthernetPresent) startingUntil.remove(Sharing.ETHERNET)
        if (changed || usb && !previousUsb) reset += Sharing.USB
        if (changed || snapshot.ethernetConnected && !previousEthernet || snapshot.ethernetPresent != previousEthernetPresent) reset += Sharing.ETHERNET
        reset.forEach { mode -> retry.reset(mode); attempted.remove(mode); blocked.keys.removeAll { it.mode == mode } }
        previousDesired = policy.desired
        previousStops = policy.pendingStops
        previousUsb = usb
        previousEthernet = snapshot.ethernetConnected
        previousEthernetPresent = snapshot.ethernetPresent
        previousTopology = topology
        previousSupported = snapshot.supported
        previousEpoch = snapshot.backendEpoch
        attempted.retainAll(policy.desired)
        stopsSubmitted.retainAll(policy.pendingStops - policy.desired)
        startingUntil.keys.retainAll(policy.desired)
        for (fault in snapshot.failures.toSet() - previousFailures) {
            val mode = fault.mode ?: continue
            if (mode in policy.desired && mode !in snapshot.active) {
                rejected(SharingCommand(mode, true), fault.failure, now)
                retry.failed(mode, now)
            }
        }
        previousFailures = snapshot.failures.toSet()

        val complete = policy.pendingStops.filterTo(mutableSetOf()) {
            it !in policy.desired && (it == Sharing.ETHERNET && !snapshot.ethernetSupported ||
                it in stopsSubmitted && it !in snapshot.active)
        }
        val commands = buildList {
            for (mode in policy.pendingStops - policy.desired - complete) {
                if (mode == Sharing.USB && snapshot.usbHostOnly()) continue
                if (SharingCommand(mode, false) in blocked) continue
                if (retry.ready(mode, now)) add(SharingCommand(mode, false))
            }
            for (mode in policy.desired) {
                if (mode in snapshot.active) {
                    retry.reset(mode)
                    startingUntil.remove(mode)
                    blocked.remove(SharingCommand(mode, true))
                    continue
                }
                if (snapshot.supports(mode) == false || SharingCommand(mode, true) in blocked) continue
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
        blocked.remove(command)
        if (command.start) startingUntil[command.mode] = now + 30_000
        else stopsSubmitted += command.mode
    }

    fun rejected(command: SharingCommand, failure: SharingFailure, now: Long) {
        startingUntil.remove(command.mode)
        if (failure.needsChange) blocked[command] = Rejection(failure, now + 5 * 60_000L)
    }

    fun progress(policy: SharingPolicy, snapshot: Snapshot, usb: Boolean): Map<Sharing, SharingProgress> = Sharing.entries.associateWith { mode ->
        val command = SharingCommand(mode, mode in policy.desired)
        val fault = blocked[command]?.failure ?: snapshot.failures.lastOrNull { it.mode == mode }?.failure
        val detail = when {
            mode in snapshot.active && mode !in policy.pendingStops ->
                if (snapshot.upstreamKnown && !snapshot.upstreamPresent) "已开启，上游未就绪" else "已开启"
            !policy.enabled -> "守护已暂停"
            mode in policy.pendingStops -> if (fault != null) fault.detail else "等待关闭确认"
            mode !in policy.desired -> "已关闭"
            snapshot.supports(mode) == false -> "系统不支持此共享方式"
            blocked.containsKey(command) -> fault!!.detail
            mode == Sharing.USB && snapshot.usbHostOnly() -> "USB Host 模式，等待 Device 连接"
            mode == Sharing.USB && !usb -> "等待接线"
            mode == Sharing.ETHERNET && !snapshot.ethernetPresent -> "等待网卡"
            mode in startingUntil -> "系统已接收，等待接口就绪"
            fault != null -> fault.detail
            else -> "等待开启"
        }
        SharingProgress(detail, fault)
    }
}
