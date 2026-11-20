package dev.autohotspot

import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import androidx.core.content.ContextCompat

internal data class FrameworkGuardianState(val active: Boolean = false, val message: String = "等待系统框架注入")

/** Authenticated broadcasts provide immediate control; Root persists a pre-boot policy seed. */
internal object FrameworkGuardian {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(FrameworkGuardianState())
    val state = mutable.asStateFlow()
    private lateinit var app: Context
    private lateinit var preferences: Preferences
    private var initialized = false
    private var serviceAlive = false
    private var lastAck = -1L
    fun initialize(context: Context) {
        if (initialized) return
        app = context.applicationContext
        preferences = Preferences.get(app)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runCatching {
                    val record = JSONObject(intent?.getStringExtra("status") ?: return)
                    if (!record.optBoolean("active") || record.optInt("uid") != 1000) return
                    val elapsed = record.getLong("elapsed")
                    if (SystemClock.elapsedRealtime() - elapsed !in 0..45_000) return
                    lastAck = SystemClock.elapsedRealtime()
                    mutable.value = FrameworkGuardianState(true, record.optString("detail", "系统框架守护已连接"))
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(GuardianContract.STATUS), GuardianContract.PERMISSION,
            Handler(Looper.getMainLooper()), ContextCompat.RECEIVER_EXPORTED)
        initialized = true
        scope.launch {
            preferences.state.map { Triple(it.enabled, it.boot, it.frameworkGuardian) }.distinctUntilChanged().collect {
                publish(alive = serviceAlive, arm = serviceAlive)
            }
        }
        scope.launch {
            while (isActive) {
                if (serviceAlive) publish(alive = true, arm = true)
                if (lastAck >= 0 && SystemClock.elapsedRealtime() - lastAck > 45_000) {
                    mutable.value = FrameworkGuardianState(false, "系统框架心跳未确认")
                }
                delay(10_000)
            }
        }
    }
    fun publish(context: Context? = null, alive: Boolean = false, arm: Boolean = false) {
        if (!ready(context)) return
        runCatching {
            app.sendBroadcast(Intent(GuardianContract.CONTROL).setPackage("android")
                .putExtra("policy", preferences.guardianConfiguration().toString())
                .putExtra("alive", alive).putExtra("arm", arm))
        }.onFailure { Router.event("框架守护消息：${it.message}", updateStatus = false) }
    }
    fun service(context: Context, running: Boolean) {
        if (!ready(context)) return
        serviceAlive = running
        publish(alive = running, arm = running)
    }
    private fun ready(context: Context?): Boolean {
        if (initialized) return true
        if (context == null) return false
        return runCatching { initialize(context); true }.getOrElse {
            Router.event("框架守护初始化：${it.message}", updateStatus = false)
            false
        }
    }
}
