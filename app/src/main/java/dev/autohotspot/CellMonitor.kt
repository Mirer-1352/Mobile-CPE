package dev.autohotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class CellMonitor(private val context: Context) {
    private var manager: TelephonyManager? = null
    private var subscription = Int.MIN_VALUE
    private var overrideType = 0
    private val listener: PhoneStateListener = if (Build.VERSION.SDK_INT >= 30) ModernListener() else LegacyListener()
    private open inner class LegacyListener : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signal: SignalStrength) {
            val dbm = if (Build.VERSION.SDK_INT >= 29) signal.cellSignalStrengths
                .filter { it.dbm in -160..-20 }.maxByOrNull { it.level }?.dbm else null
            Router.update { it.copy(signal = signal.level, dbm = dbm) }
        }
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) { refreshLabel(networkType) }
    }
    @androidx.annotation.RequiresApi(30)
    private inner class ModernListener : LegacyListener() {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { overrideType = info.overrideNetworkType; refreshLabel(info.networkType) }
    }
    fun bind() {
        val granted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!granted || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            close(); subscription = Int.MIN_VALUE; overrideType = 0
            Router.update { it.copy(operator = if (granted) "无蜂窝网络" else "请授予电话状态权限", signal = null, dbm = null, generation = "—") }
            return
        }
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subscription == id && manager != null) return
        close(); subscription = id; overrideType = 0
        Router.update { it.copy(signal = null, dbm = null, generation = "—") }
        manager = context.getSystemService(TelephonyManager::class.java)?.let {
            if (id >= 0) it.createForSubscriptionId(id) else it
        }
        runCatching {
            manager!!.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                if (Build.VERSION.SDK_INT >= 30) PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else 0)
            refreshLabel(manager!!.dataNetworkType)
        }.onFailure {
            close(); subscription = Int.MIN_VALUE
            Router.update { it.copy(operator = "无法读取 SIM 状态", signal = null, dbm = null, generation = "—") }
        }
    }
    private fun refreshLabel(type: Int) {
        val name = runCatching { manager?.networkOperatorName }.getOrNull().orEmpty().ifBlank { "无运营商 / 无服务" }
        val generation = when {
            type == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            Build.VERSION.SDK_INT >= 30 && overrideType in setOf(3, 4, 5) -> "5G NSA"
            type == TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            type in setOf(3, 5, 6, 8, 9, 10, 12, 14, 15) -> "3G"
            type in setOf(1, 2, 4, 7, 11, 16) -> "2G"
            else -> "—"
        }
        Router.update { it.copy(operator = name, generation = generation) }
    }
    fun close() { runCatching { manager?.listen(listener, PhoneStateListener.LISTEN_NONE) }; manager = null }
}
