package dev.autohotspot

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class SettingsActions(
    val change: ((Options) -> Options) -> Boolean,
    val defaultHome: () -> Unit,
    val requestScopes: () -> Unit,
    val openSystem: (String) -> Unit,
    val requestPhone: () -> Unit
)

@Composable internal fun SettingsScreen(state: RouterState, options: Options, xposed: XposedState,
    guardian: FrameworkGuardianState, root: RootSettingsState, scroll: ScrollState, actions: SettingsActions) {
    var showLogs by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize().displayCutoutPadding()) {
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MCPE", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("网络共享方式") {
                    SharingOptions(state, options) { mode, enabled -> actions.change { it.withSharing(mode, enabled) } }
                }
                Setting("共享流量走 VPN", when {
                    !xposed.compatible -> "LSPosed 未连接"
                    !xposed.remoteSupported -> "框架不支持 VPN 配置同步"
                    !xposed.granted -> "待授权"
                    else -> ""
                }, options.vpnSharing) { value -> actions.change { it.copy(vpnSharing = value) } }
                Setting("共享守护", "", options.enabled) { value -> actions.change { it.copy(enabled = value) } }
                Setting("开机自动共享", "", options.boot) { value -> actions.change { it.copy(boot = value) } }
                Setting("系统框架守护", when {
                    guardian.active -> guardian.message
                    !xposed.compatible -> "LSPosed 未连接"
                    ScopeRequestGate.SYSTEM_SCOPE !in xposed.scopes -> "等待系统框架作用域授权"
                    else -> "等待注入，重启生效"
                }, options.frameworkGuardian) { value -> actions.change { it.copy(frameworkGuardian = value) } }
                Setting("断线自动恢复", "", options.recover) { value -> actions.change { it.copy(recover = value) } }
                Section("屏幕") {
                    Setting("屏幕常亮", "面板显示时保持亮屏", options.keepScreen) { value -> actions.change { it.copy(keepScreen = value) } }
                    Setting("防烧屏位移", "每 3 分钟轻微移动面板，缓解固定画面残留", options.burnInProtection) { value -> actions.change { it.copy(burnInProtection = value) } }
                }
                Section("桌面模式") {
                    OutlinedButton(onClick = actions.defaultHome) { Text("设为默认桌面") }
                    Text("${xposed.framework} · API ${if (xposed.api == 0) "—" else xposed.api}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("实际作用域：${xposed.scopes.sorted().joinToString().ifEmpty { "尚未授权" }}", fontSize = 12.sp, color = Muted)
                    Text(xposed.message, fontSize = 12.sp, color = if (xposed.granted) Teal else Orange)
                    OutlinedButton(onClick = actions.requestScopes, enabled = xposed.canRequest) {
                        Text(if (xposed.requesting) "等待管理器确认" else if (xposed.granted) "同步作用域" else "同步并申请作用域")
                    }
                    Setting("LSPosed 桌面接管", when {
                        root.error != null -> "Root 同步失败：${root.error}"
                        root.pending -> "正在同步 Root 配置"
                        else -> "重启生效"
                    }, options.forceHome) { value -> actions.change { it.copy(forceHome = value) } }
                }
                Section("系统配置") {
                    OutlinedButton(onClick = { actions.openSystem(Settings.ACTION_WIRELESS_SETTINGS) }) { Text("热点名称、密码与系统网络设置") }
                    OutlinedButton(onClick = actions.requestPhone) { Text("授权运营商与信号读取") }
                    OutlinedButton(onClick = { actions.openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("配置电池优化与后台运行") }
                    OutlinedButton(onClick = { actions.openSystem(Settings.ACTION_SETTINGS) }) { Text("打开系统设置") }
                }
                Section("版本") { UpdateSetting() }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.root) "ROOT 已连接" else "ROOT 待授权 / 不可用", fontWeight = FontWeight.Bold, color = if (state.root) Teal else Orange)
                        Text(state.status, fontSize = 13.sp, color = Muted)
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "收起诊断" else "查看诊断") }
                        if (showLogs) {
                            Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", fontSize = 12.sp)
                            state.events.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable private fun SharingOptions(state: RouterState, options: Options, change: (Sharing, Boolean) -> Unit) {
    Sharing.entries.forEach { mode ->
        val wanted = mode in options.desired
        val detail = when {
            mode == Sharing.ETHERNET && !state.ethernetSupported -> "此系统不支持"
            !state.fresh -> "状态未知"
            mode in state.active -> if (mode == Sharing.WIFI) state.clients?.let { "$it 台设备" } ?: "已开启" else "已开启"
            wanted && !state.running -> "待启动"
            wanted && mode == Sharing.USB && !state.usbConnected -> "等待接线"
            wanted -> "等待开启"
            else -> "已关闭"
        }
        Setting(mode.label, detail, wanted) { change(mode, it) }
    }
}

@Composable private fun Setting(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = Muted)
        }
        Switch(checked, change)
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
