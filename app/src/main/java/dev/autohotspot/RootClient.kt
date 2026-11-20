package dev.autohotspot

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Android bootstrap; protocol and session lifetime live in RootChannel. */
class RootClient(context: Context) : AutoCloseable {
    companion object { private val requestIds = AtomicLong() }
    private val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
    private val channel = RootChannel(::spawn)

    suspend fun request(op: String, type: Int? = null, data: JSONObject? = null): JSONObject {
        val id = requestIds.incrementAndGet()
        val tracked = op in setOf("start", "stop", "configure", "update")
        fun detail(phase: String) = JSONObject().put("id", id).put("op", op).put("type", type ?: JSONObject.NULL).put("phase", phase)
        if (tracked) AppDiagnostics.record("root.operation", detail("requested"))
        try {
            val result = channel.request(JSONObject().put("op", op).apply {
                type?.let { put("type", it) }
                data?.let { put("data", it) }
            })
            if (tracked) AppDiagnostics.record("root.operation", detail("submitted")
                .put("stopAcknowledged", result.opt("stopAcknowledged") ?: JSONObject.NULL)
                .put("nativeType", result.opt("nativeType") ?: JSONObject.NULL)
                .put("home", result.opt("home") ?: JSONObject.NULL)
                .put("backgroundProtection", result.opt("backgroundProtection") ?: JSONObject.NULL))
            return result
        } catch (error: Exception) {
            if (tracked) {
                val record = detail(if (error is kotlinx.coroutines.CancellationException) "cancelled" else "failed").put("message", error.message?.take(1000))
                if (error is RootOperationException) record.put("errorCode", error.errorCode ?: JSONObject.NULL)
                    .put("errorName", error.errorName ?: JSONObject.NULL)
                AppDiagnostics.record("root.operation", record)
            }
            throw error
        }
    }

    private fun spawn(): Process {
        var missing: IOException? = null
        for (binary in listOf("su", "/debug_ramdisk/su", "/sbin/su", "/system/xbin/su", "/system/bin/su")) {
            try {
                return ProcessBuilder(binary, "-c", "CLASSPATH='$apk' /system/bin/app_process /system/bin dev.autohotspot.RootMain")
                    .redirectErrorStream(true).start()
            } catch (error: IOException) { missing = error }
        }
        throw IOException("找不到可执行的 su，请检查 Root 安装与本应用授权", missing)
    }

    override fun close() = channel.close()
}
