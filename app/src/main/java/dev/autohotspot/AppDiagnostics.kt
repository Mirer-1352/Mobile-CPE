package dev.autohotspot

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One I/O owner persists changes only. Report collection never invokes tethering or changes settings. */
internal object AppDiagnostics {
    private sealed interface Message {
        data class Event(val kind: String, val data: JSONObject, val at: Long, val elapsed: Long) : Message
        data class Report(val extras: JSONObject, val result: CompletableDeferred<String>) : Message
    }
    private val queue = Channel<Message>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    private var lastSnapshot: String? = null
    private var lastFailure: String? = null
    private var backgroundProtection: String? = null
    private var dropped = 0

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val log = DiagnosticLog()
            var storageError: String? = null
            val store = runCatching {
                val directory = File(app.createDeviceProtectedStorageContext().filesDir, "diagnostics")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
                AtomicJsonFile(File(directory, "journal.json"), File(directory, "journal.lock"), 96 * 1024, Process.myUid())
            }.onFailure { storageError = it.toString() }.getOrNull()
            runCatching { log.restore(store?.read()) }.onFailure { storageError = it.toString() }
            for (message in queue) when (message) {
                is Message.Event -> {
                    log.append(message.kind, message.data, message.at, message.elapsed)
                    runCatching { store?.write(log.json()) }.onFailure { storageError = it.toString() }
                }
                is Message.Report -> {
                    val report = JSONObject().put("schema", 1).put("capturedAt", System.currentTimeMillis())
                        .put("app", "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}")
                        .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
                        .put("runtime", message.extras).put("journal", log.json())
                        .put("droppedEvents", synchronized(this@AppDiagnostics) { dropped })
                        .put("storageError", storageError ?: JSONObject.NULL)
                    runCatching {
                        val directory = File(app.createDeviceProtectedStorageContext().filesDir, "diagnostics")
                        AtomicJsonFile(File(directory, "last-report.json"), File(directory, "report.lock"), 96 * 1024, Process.myUid()).write(report)
                    }.onFailure { report.put("reportSaveError", it.toString()) }
                    message.result.complete(report.toString(2))
                }
            }
        }
        record("app.start", JSONObject().put("pid", Process.myPid()).put("version", BuildConfig.VERSION_CODE))
    }

    fun record(kind: String, data: JSONObject) {
        val message = Message.Event(kind, JSONObject(data.toString()), System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (queue.trySend(message).isFailure) synchronized(this) { dropped++ }
    }

    @Synchronized fun observe(raw: JSONObject) {
        // Explicit allowlist excludes packet counters, client addresses, SSIDs and credentials.
        val selected = JSONObject()
        for (key in listOf("active", "available", "localOnly", "interfaceTypes", "supportedTypes", "tetheringSupported",
            "upstream", "upstreamKnown", "offload", "interfaceErrors", "stateSource", "observerRegistered", "observerError",
            "ethernetInterfaces", "usbHost", "usbDevice", "usbConnected", "rootUid", "rootPid", "backend", "platform",
            "vpnCandidates", "networkObservationError", "usbHardware")) {
            if (raw.has(key)) selected.put(key, raw.get(key))
        }
        raw.optJSONArray("interfaces")?.let { interfaces ->
            val links = JSONArray()
            for (i in 0 until interfaces.length()) {
                val net = interfaces.getJSONObject(i)
                links.put(JSONObject().put("name", net.getString("name")).put("master", net.optString("master"))
                    .put("carrier", net.optBoolean("carrier")))
            }
            selected.put("links", links)
        }
        val fingerprint = selected.toString()
        if (fingerprint == lastSnapshot) return
        lastSnapshot = fingerprint
        lastFailure = null
        record("tethering.state", selected)
    }

    @Synchronized fun transportFailure(message: String) {
        if (lastFailure == message) return
        lastFailure = message
        record("root.unavailable", JSONObject().put("message", message.take(1000)))
    }

    @Synchronized fun backgroundProtection(value: JSONObject) {
        val fingerprint = value.toString()
        if (fingerprint == backgroundProtection) return
        backgroundProtection = fingerprint
        record("background.protection", value)
    }

    suspend fun report(context: Context): String {
        initialize(context)
        val options = Preferences.get(context).read()
        val xposed = XposedConnection.state.value
        val guardian = FrameworkGuardian.state.value
        val runtime = JSONObject().put("rootConnected", Router.state.value.root).put("fresh", Router.state.value.fresh)
            .put("desired", JSONArray(options.desired.map { it.name })).put("pendingStops", JSONArray(options.pendingStops.map { it.name }))
            .put("enabled", options.enabled).put("boot", options.boot).put("recover", options.recover)
            .put("vpnSharing", options.vpnSharing).put("frameworkGuardian", options.frameworkGuardian)
            .put("frameworkConnected", guardian.active).put("frameworkStatus", guardian.message)
            .put("backgroundExempt", RootSettingsSync.state.value.backgroundExempt ?: JSONObject.NULL)
            .put("vendorBackgroundProtection", synchronized(this) { backgroundProtection?.let(::JSONObject) } ?: JSONObject.NULL)
            .put("defaultHome", options.forceHome).put("homeEffective", RootSettingsSync.state.value.homeEffective ?: JSONObject.NULL)
            .put("xposed", JSONObject().put("connected", xposed.connected).put("api", xposed.api)
                .put("framework", xposed.framework).put("scopes", JSONArray(xposed.scopes.toList())))
        val result = CompletableDeferred<String>()
        queue.send(Message.Report(runtime, result))
        return result.await()
    }
}
