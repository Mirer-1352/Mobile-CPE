package dev.autohotspot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class XposedState(
    val connected: Boolean = false, val api: Int = 0, val framework: String = "未连接现代 Xposed 服务",
    val scopes: Set<String> = emptySet(), val systemSupported: Boolean = false,
    val remoteSupported: Boolean = false,
    val required: Set<String> = setOf(ScopeRequestGate.SYSTEM_SCOPE),
    val requesting: Boolean = false,
    val message: String = "请在支持 API 102 的 LSPosed 中启用模块，再返回应用；连接后自动申请系统框架作用域。"
) {
    val compatible get() = connected && api >= 102
    val granted get() = scopes.containsAll(required)
    val canRequest get() = compatible && systemSupported && !requesting
}

/** Framework-owned scope; approval requests are only submitted while the UI is foreground. */
object XposedConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val preferenceWrites = Mutex()
    private val gate = ScopeRequestGate()
    private val mutable = MutableStateFlow(XposedState())
    val state = mutable.asStateFlow()
    private var service: XposedService? = null
    private var initialized = false
    private var foreground = false
    private var requestNumber = 0L
    private lateinit var appContext: Context
    private var required = setOf(ScopeRequestGate.SYSTEM_SCOPE)

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        required = requiredScopes(context)
        mutable.value = XposedState(required = required)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                scope.launch {
                    service = bound; gate.reconnect(); requestNumber++
                    mutable.value = XposedState(required = required, message = "正在同步作用域")
                    syncPreferences(bound)
                    refresh()
                }
            }
            override fun onServiceDied(dead: XposedService) {
                scope.launch {
                    if (service !== dead) return@launch
                    service = null; gate.reconnect(); requestNumber++
                    mutable.value = XposedState(required = required, message = "框架服务已断开")
                }
            }
        })
        scope.launch {
            Preferences.get(appContext).state.map { it.vpnSharing }.distinctUntilChanged().collect {
                service?.let { syncPreferences(it) }
            }
        }
    }
    fun resumed() { foreground = true; scope.launch { refresh() } }
    fun paused() { foreground = false }
    fun syncAndRequest() { scope.launch { refresh(manual = true) } }

    private suspend fun syncPreferences(current: XposedService) = preferenceWrites.withLock {
        if (service !== current) return@withLock
        try {
            withContext(Dispatchers.IO) {
                if (current.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) {
                    val enabled = Preferences.get(appContext).read().vpnSharing
                    check(current.getRemotePreferences("router").edit().putBoolean("vpnSharing", enabled).commit())
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Router.event("VPN 设置同步失败：${e.message}") }
    }

    private fun requiredScopes(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < 29) return setOf(ScopeRequestGate.SYSTEM_SCOPE)
        val pm = context.packageManager
        val resolved = runCatching { pm.resolveService(Intent("android.net.ITetheringConnector"), PackageManager.MATCH_SYSTEM_ONLY)?.serviceInfo?.packageName }.getOrNull()
        val known = listOf("com.android.networkstack.tethering", "com.google.android.networkstack.tethering",
            "com.android.networkstack", "com.google.android.networkstack")
        val tethering = resolved ?: known.firstOrNull {
            runCatching {
                val flags = pm.getPackageInfo(it, 0).applicationInfo?.flags ?: 0
                flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }.getOrDefault(false)
        }
        return setOfNotNull(ScopeRequestGate.SYSTEM_SCOPE, tethering)
    }

    private suspend fun refresh(manual: Boolean = false, completion: String? = null) = lock.withLock {
        val current = service ?: return@withLock
        try {
            val snapshot = withContext(Dispatchers.IO) {
                XposedState(connected = true, api = current.apiVersion, required = required,
                    framework = "${current.frameworkName} ${current.frameworkVersion}",
                    scopes = current.scope.toSet(), systemSupported = current.frameworkProperties and XposedService.PROP_CAP_SYSTEM != 0L,
                    remoteSupported = current.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L)
            }
            if (service !== current) return@withLock
            val message = when {
                !snapshot.compatible -> "框架 API ${snapshot.api} 过旧，需要 API 102+；Root 共享功能仍可独立使用"
                !snapshot.systemSupported -> "框架未声明系统进程 Hook 能力，无法接管系统桌面"
                snapshot.granted -> "作用域已授权，重启生效"
                gate.pending -> "等待管理器确认"
                else -> completion ?: "尚未授权所需作用域"
            }
            mutable.value = snapshot.copy(requesting = gate.pending, message = message)
            val shouldRequest = gate.begin(snapshot.scopes, foreground, snapshot.compatible && snapshot.systemSupported, manual, required)
            mutable.value = mutable.value.copy(requesting = gate.pending)
            if (shouldRequest) request(current)
        } catch (e: Exception) {
            if (service === current) {
                gate.finish()
                mutable.value = mutable.value.copy(requesting = false, message = "作用域同步失败：${e.message}")
            }
        }
    }

    private suspend fun request(current: XposedService) {
        val number = ++requestNumber
        val missing = (required - mutable.value.scopes).toList()
        mutable.value = mutable.value.copy(requesting = true, message = "等待管理器确认")
        try {
            withContext(Dispatchers.IO) {
                current.requestScope(missing, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        completed(current, number, if (approved.containsAll(missing)) "正在核对作用域" else "所需作用域未全部批准")
                    }
                    override fun onScopeRequestFailed(message: String) { completed(current, number, "申请未通过：$message") }
                })
            }
            scope.launch {
                delay(60000)
                if (service === current && requestNumber == number && gate.pending) {
                    gate.finish()
                    refresh(completion = "等待审批超时，已停止自动申请；可在管理器确认后返回同步，或手动重试")
                }
            }
        } catch (e: Exception) { completed(current, number, "无法提交作用域申请：${e.message}") }
    }
    private fun completed(current: XposedService, number: Long, message: String) {
        scope.launch {
            if (service !== current || requestNumber != number) return@launch
            gate.finish()
            // Re-read actual scope instead of treating an approval callback as a running hook.
            refresh(completion = message)
        }
    }
}
