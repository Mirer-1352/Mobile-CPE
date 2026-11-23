package dev.autohotspot

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*

/** Radio collection owns a separate ROOT channel and cannot stall the sharing reconciler. */
internal class CellularMonitor(context: Context) : AutoCloseable {
    private val root = RootClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastState: String? = null
    init {
        scope.launch {
            while (isActive) {
                val value = try {
                    val raw = root.request("cellular").getJSONObject("cellular")
                    val fingerprint = raw.toString()
                    if (lastState != fingerprint) {
                        lastState = fingerprint
                        AppDiagnostics.record("cellular.state", raw)
                    }
                    CellularInfo.parse(raw, SystemClock.elapsedRealtime())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (unavailable: Exception) { CellularInfo(channelStatus = "ROOT_UNAVAILABLE", ambrStatus = "ROOT_UNAVAILABLE") }
                Router.update { it.copy(cellular = value) }
                delay(5000)
            }
        }
    }
    override fun close() {
        scope.cancel(); root.close()
        Router.update { it.copy(cellular = CellularInfo(channelStatus = "PAUSED", ambrStatus = "PAUSED")) }
    }
}
