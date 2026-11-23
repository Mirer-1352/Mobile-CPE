package dev.autohotspot

import org.json.JSONObject
import java.util.Locale

data class ComponentCarrier(val rat: Int, val role: Int, val band: Int?, val channel: Int?,
    val downKhz: Int?, val upKhz: Int?) {
    val radio get() = when (rat) { 13, 19 -> "LTE"; 20 -> "NR"; else -> "未知制式" }
    val bandLabel get() = band?.let { if (rat == 20) "n$it" else if (rat in setOf(13, 19)) "B$it" else "频段 $it" } ?: "频段 —"
    val detail get() = "${if (role == 1) "主载波" else "辅载波"} $radio $bandLabel · ↓${mhz(downKhz)} ↑${mhz(upKhz)} MHz"
    private fun mhz(khz: Int?) = khz?.let { String.format(Locale.ROOT, "%.2f", it / 1000.0).trimEnd('0').trimEnd('.') } ?: "—"
}

data class Ambr(val downKbps: Long, val upKbps: Long, val kind: String) {
    val summary get() = "签约 ↓${rate(downKbps)} ↑${rate(upKbps)} M/s"
    private fun rate(kbps: Long) = String.format(Locale.ROOT, "%.2f", kbps / 8000.0)
}

/** Only serving physical channels count. Display branding, neighbour cells and LCE are not evidence. */
data class CellularInfo(val subscription: Int = -1, val slot: Int = -1,
    val carriers: List<ComponentCarrier> = emptyList(), val channelsKnown: Boolean = false,
    val ambr: Ambr? = null, val channelStatus: String = "PENDING", val ambrStatus: String = "PENDING") {
    val aggregation: String get() {
        if (!channelsKnown || carriers.isEmpty()) return "CA —"
        val lte = carriers.count { it.rat == 13 || it.rat == 19 }
        val nr = carriers.count { it.rat == 20 }
        val unknown = carriers.size - lte - nr
        return buildList {
            if (lte > 1) add("LTE ${lte}CC")
            if (nr > 1) add("NR ${nr}CC")
            if (lte > 0 && nr > 0) add("LTE+NR 双连接")
            if (unknown > 0) add("未知制式 ${unknown}CC")
            if (isEmpty()) add(if (nr == 1) "NR 1CC" else "LTE 1CC")
        }.joinToString(" + ")
    }
    val limit get() = ambr?.summary ?: "签约 —"
    companion object {
        fun parse(raw: JSONObject, now: Long): CellularInfo {
            val sub = raw.optInt("subscription", -1)
            if (sub < 0) return CellularInfo(channelStatus = raw.optString("channelStatus", "NO_SIM"), ambrStatus = "NO_SIM")
            val channels = raw.optJSONArray("channels")
            val rows = buildList {
                if (channels != null) for (i in 0 until channels.length()) {
                    val item = channels.optJSONObject(i) ?: continue
                    val role = item.optInt("role")
                    if (role != 1 && role != 2) continue
                    fun number(key: String) = item.optInt(key, -1).takeIf { it > 0 && it != Int.MAX_VALUE }
                    add(ComponentCarrier(item.optInt("rat"), role, number("band"),
                        item.optInt("channel", -1).takeIf { it >= 0 && it != Int.MAX_VALUE }, number("downKhz"), number("upKhz")))
                }
            }.withIndex().groupBy { entry ->
                val carrier = entry.value
                if (carrier.channel != null) Pair(if (carrier.rat == 19) 13 else carrier.rat, carrier.channel)
                else "unknown-${entry.index}"
            }.values.map { group -> group.minBy { it.value.role }.value }.sortedBy { it.role }
            val known = raw.optBoolean("channelsKnown")
            val ambrJson = raw.optJSONObject("ambr")
            val ambr = ambrJson?.let {
                val age = now - it.optLong("observed", -1)
                val down = it.optLong("downKbps"); val up = it.optLong("upKbps")
                if (it.optInt("subscription", -1) == sub && it.optLong("observed", -1) >= 0 && age in 0..90_000 &&
                    down in 1..1_000_000_000L && up in 1..1_000_000_000L &&
                    it.optString("kind") in setOf("APN-AMBR", "Session-AMBR")) Ambr(down, up, it.getString("kind")) else null
            }
            return CellularInfo(sub, raw.optInt("slot", -1), rows.takeIf { known } ?: emptyList(), known, ambr,
                raw.optString("channelStatus", "UNAVAILABLE"), raw.optString("ambrStatus", "UNAVAILABLE"))
        }
    }
}

internal fun cellularStatus(code: String): String = when (code) {
    "PENDING" -> "正在读取"
    "NO_SIM" -> "无可用数据 SIM"
    "OK" -> "已读取"
    "NO_CHANNELS" -> "当前无活跃物理信道"
    "UNSUPPORTED" -> "系统未提供此接口"
    "NO_PORT" -> "没有可用的基带查询通道"
    "BUSY" -> "基带通道正被系统占用"
    "EXCLUSIVE_UNAVAILABLE" -> "基带通道不支持独占查询"
    "SIM_UNCONFIRMED" -> "无法确认通道所属 SIM"
    "NO_CONTEXT" -> "没有可确认的活跃互联网连接"
    "AMBIGUOUS" -> "存在多个连接，无法确认对应上限"
    "NO_AMBR" -> "网络未返回 AMBR"
    "ROOT_UNAVAILABLE" -> "ROOT 通道不可用"
    "PAUSED" -> "守护已暂停"
    "STALE" -> "上次数据已过期"
    else -> "当前无法读取"
}
