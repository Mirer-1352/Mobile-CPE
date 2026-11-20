package dev.autohotspot

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal data class RootSettingsState(val pending: Boolean = true, val error: String? = null)

/** One actor applies the latest durable intent; retries cannot outlive or overtake each other. */
internal object RootSettingsSync {
    private data class Selection(val enabled: Boolean, val boot: Boolean, val framework: Boolean, val home: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(RootSettingsState())
    val state = mutable.asStateFlow()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val preferences = Preferences.get(app)
        initialized = true
        scope.launch {
            preferences.state.map { Selection(it.enabled, it.boot, it.frameworkGuardian, it.forceHome) }
                .distinctUntilChanged().collect {
                    mutable.value = RootSettingsState()
                    changes.trySend(Unit)
                }
        }
        scope.launch {
            for (ignored in changes) {
                var saved = false
                while (!saved) {
                    val root = RootClient(app)
                    try {
                        val configuration = preferences.systemConfiguration()
                        val reply = root.request("configure", data = configuration)
                        check(reply.optBoolean("saved")) { "系统中已有更新的配置" }
                        saved = true
                        val latest = preferences.guardianConfiguration().getLong("revision")
                        mutable.value = RootSettingsState(pending = latest != configuration.getJSONObject("policy").getLong("revision"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        mutable.value = RootSettingsState(error = error.message)
                        Router.event("系统配置同步：${error.message}", updateStatus = false)
                    } finally { root.close() }
                    if (!saved) withTimeoutOrNull(15_000) { changes.receive() }
                }
            }
        }
    }
}
