package dev.autohotspot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Sharing(val type: Int, val label: String) {
    WIFI(0, "Wi-Fi 热点"),
    USB(1, "USB 共享"),
    ETHERNET(5, "以太网共享")
}
data class Options(
    val enabled: Boolean = true, val boot: Boolean = true, val recover: Boolean = true,
    val keepScreen: Boolean = true, val forceHome: Boolean = false,
    val burnInProtection: Boolean = true,
    val vpnSharing: Boolean = true,
    val frameworkGuardian: Boolean = true,
    val desired: Set<Sharing> = setOf(Sharing.WIFI), val pendingStops: Set<Sharing> = emptySet()
)
data class RouterState(
    val running: Boolean = false, val root: Boolean = false, val fresh: Boolean = false,
    val active: Set<Sharing> = emptySet(), val clients: Int? = null,
    val down: Double? = null, val up: Double? = null,
    val operator: String = "未读取运营商", val generation: String = "—",
    val signal: Int? = null, val dbm: Int? = null,
    val status: String = "正在连接 Root", val events: List<String> = emptyList(),
    val ethernetSupported: Boolean = android.os.Build.VERSION.SDK_INT >= 30,
    val usbConnected: Boolean = false, val ethernetConnected: Boolean = false,
    val sharingProgress: Map<Sharing, SharingProgress> = emptyMap()
)
object Router {
    private val mutable = MutableStateFlow(RouterState())
    val state = mutable.asStateFlow()
    fun update(block: (RouterState) -> RouterState) { synchronized(this) { mutable.value = block(mutable.value) } }
    fun event(message: String, updateStatus: Boolean = true) = update {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        it.copy(status = if (updateStatus) message else it.status, events = (listOf("$time  $message") + it.events).take(30))
    }
}
