package dev.autohotspot

data class NetInterface(val name: String, val rx: Long, val tx: Long, val carrier: Boolean, val master: String? = null)

object TrafficAccounting {
    /** Count each downstream once, never its upstream or both a bridge and its member port. */
    fun downstream(all: List<NetInterface>, activeNames: Set<String>): List<NetInterface> {
        val byName = all.associateBy { it.name }
        val candidates = byName.values.filter { it.name in activeNames }
        val availableNames = candidates.map { it.name }.toSet()
        return candidates.filter { net ->
            var parent = net.master
            val visited = mutableSetOf(net.name)
            var covered = false
            while (parent != null && visited.add(parent)) {
                if (parent in availableNames) { covered = true; break }
                parent = byName[parent]?.master
            }
            !covered
        }
    }
}
class RateMeter {
    private var previous: Map<String, NetInterface> = emptyMap()
    private var timestamp = 0L
    fun sample(nets: List<NetInterface>, now: Long): Pair<Double?, Double?> {
        val current = nets.associateBy { it.name }
        val elapsed = (now - timestamp) / 1000.0
        val comparable = current.isNotEmpty() && current.keys == previous.keys && elapsed > 0 && timestamp > 0
        val valid = comparable && current.values.all { n -> previous[n.name]!!.let { n.rx >= it.rx && n.tx >= it.tx && it.rx >= 0 && it.tx >= 0 } }
        val rates = if (valid) current.values.sumOf { it.tx - previous.getValue(it.name).tx } / elapsed to
            current.values.sumOf { it.rx - previous.getValue(it.name).rx } / elapsed else null to null
        previous = current; timestamp = now
        return rates
    }
}
/** Monotonic backoff prevents cable removal or an OEM denial from creating a tight retry loop. */
class RetryPolicy {
    private val attempts = mutableMapOf<Sharing, Int>()
    private val deadlines = mutableMapOf<Sharing, Long>()
    fun ready(mode: Sharing, now: Long) = now >= (deadlines[mode] ?: 0L)
    fun failed(mode: Sharing, now: Long) {
        val count = (attempts[mode] ?: 0).coerceAtMost(5)
        deadlines[mode] = now + (3000L * (1L shl count)).coerceAtMost(60000L)
        attempts[mode] = count + 1
    }
    fun reset(mode: Sharing) { attempts.remove(mode); deadlines.remove(mode) }
}
