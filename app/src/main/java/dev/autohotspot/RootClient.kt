package dev.autohotspot

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/** Android bootstrap; protocol and session lifetime live in RootChannel. */
class RootClient(context: Context) : AutoCloseable {
    private val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
    private val channel = RootChannel(::spawn)

    suspend fun request(op: String, type: Int? = null, data: JSONObject? = null): JSONObject =
        channel.request(JSONObject().put("op", op).apply {
            type?.let { put("type", it) }
            data?.let { put("data", it) }
        })

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
