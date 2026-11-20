package dev.autohotspot

import org.json.JSONObject

/** Platform observations are separate from the durable user selection. */
data class Snapshot(val active: Set<Sharing>, val clients: Int?, val nets: List<NetInterface>, val usb: Boolean,
                    val ethernetSupported: Boolean, val ethernetConnected: Boolean = false,
                    val ethernetPresent: Boolean = ethernetConnected,
                    val usbHost: Boolean = false, val usbDevice: Boolean? = null,
                    val supported: Set<Sharing>? = null, val failures: List<InterfaceFailure> = emptyList(),
                    val source: String = "legacy", val revision: Long = 0,
                    val upstreamKnown: Boolean = false, val upstreamPresent: Boolean = false,
                    val offload: Int? = null, val backendEpoch: Int = 0) {
    fun usbReady(broadcastConnected: Boolean, broadcastHost: Boolean = false): Boolean =
        usbDevice ?: ((usb || broadcastConnected) && !usbHost && !broadcastHost)
    fun usbHostOnly(broadcastHost: Boolean = false) = (usbHost || broadcastHost) && usbDevice != true
    fun supports(mode: Sharing): Boolean? = if (mode == Sharing.ETHERNET && !ethernetSupported) false else supported?.contains(mode)

    companion object {
        private val usbName = Regex("(rndis|usb|ncm)\\d+")
        private val wifiName = Regex("(wlan|ap|softap|swlan)\\d+")
        private val ethernetName = Regex("(eth\\d+|en[opxsd][a-zA-Z0-9_.-]+)")

        fun parse(json: JSONObject): Snapshot {
            fun strings(key: String): List<String> = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val wifi = strings("wifiRegex")
            val usb = strings("usbRegex")
            val typed = json.optJSONObject("interfaceTypes") ?: JSONObject()
            val ethernet = strings("ethernetInterfaces").toMutableSet()
            typed.keys().forEach { iface -> if (typed.optInt(iface, -1) == 5) ethernet += iface }
            fun matches(name: String, patterns: List<String>) = patterns.any { runCatching { Regex(it).matches(name) }.getOrDefault(false) }
            fun type(name: String): Sharing? = when {
                typed.has(name) -> sharingType(typed.getInt(name))
                name in ethernet -> Sharing.ETHERNET
                matches(name, wifi) -> Sharing.WIFI
                matches(name, usb) -> Sharing.USB
                usbName.matches(name) -> Sharing.USB
                wifiName.matches(name) -> Sharing.WIFI
                ethernetName.matches(name) -> Sharing.ETHERNET
                else -> null
            }
            val names = strings("active").toSet()
            val array = json.getJSONArray("interfaces")
            val nets = (0 until array.length()).map { array.getJSONObject(it) }.map {
                NetInterface(it.getString("name"), it.getLong("rx"), it.getLong("tx"), it.optBoolean("carrier"),
                    it.optString("master").takeIf(String::isNotBlank))
            }
            val supported = json.optJSONArray("supportedTypes")?.let { a ->
                (0 until a.length()).mapNotNull { sharingType(a.getInt(it)) }.toSet()
            }
            val errors = json.optJSONArray("interfaceErrors")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("code") != 0 }.map {
                    val iface = it.getString("interface")
                    InterfaceFailure(iface, sharingType(it.optInt("type", -1)) ?: type(iface),
                        SharingFailure(it.getInt("code"), it.getString("name")), it.optLong("sequence"))
                }
            } ?: emptyList()
            return Snapshot(names.mapNotNull(::type).toSet(), if (json.isNull("clients")) null else json.getInt("clients"),
                TrafficAccounting.downstream(nets, names), json.optBoolean("usbConnected"), json.optBoolean("ethernetSupported"),
                nets.any { it.carrier && type(it.name) == Sharing.ETHERNET },
                nets.any { type(it.name) == Sharing.ETHERNET } || names.any { type(it) == Sharing.ETHERNET },
                json.optBoolean("usbHost"), json.opt("usbDevice") as? Boolean, supported, errors,
                json.optString("stateSource", "legacy"), json.optLong("revision"),
                json.optBoolean("upstreamKnown"), json.optJSONObject("upstream") != null,
                (json.opt("offload") as? Number)?.toInt(), json.optInt("rootPid"))
        }
    }
}
