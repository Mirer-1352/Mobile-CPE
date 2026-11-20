package dev.autohotspot

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FilterInputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Every redirect is checked before connecting; redirects cannot silently downgrade TLS. */
internal object UpdateHttp {
    fun <T> read(url: String, limit: Long, consume: (InputStream, Long) -> T): T {
        var current = https(url)
        val deadline = System.nanoTime() + (if (limit <= METADATA_LIMIT) 20_000L else 600_000L) * 1_000_000
        repeat(6) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            require(remaining > 0) { "更新请求超时" }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(6000, remaining); connection.readTimeout = minOf(8000, remaining)
                connection.setRequestProperty("User-Agent", "Mobile-CPE/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    current = https(URI(current).resolve(connection.getHeaderField("Location") ?: error("重定向无地址")).toString())
                } else {
                    require(code in 200..299) { "HTTP $code" }
                    val length = connection.contentLengthLong
                    require(length <= limit) { "更新文件超过大小限制" }
                    return DeadlineStream(connection.inputStream, connection, deadline).use { consume(it, length) }
                }
            } finally { connection.disconnect() }
        }
        error("更新源重定向过多")
    }
    suspend fun metadata(url: String): ByteArray = runInterruptible(Dispatchers.IO) {
        val busted = url + (if ('?' in url) '&' else '?') + "_cpe=" + System.currentTimeMillis()
        read(busted, METADATA_LIMIT.toLong()) { stream, _ ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                require(out.size() + n <= METADATA_LIMIT) { "更新清单过大" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }
    fun download(url: String, file: File, manifest: UpdateManifest, progress: (Float?) -> Unit) {
        read(url, manifest.size) { input, length ->
            require(length < 0 || length == manifest.size) { "APK 大小不匹配" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L; var lastTick = 0L
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= manifest.size) { "APK 超过声明大小" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    val tick = System.nanoTime() / 1_000_000
                    if (tick - lastTick >= 300) {
                        progress(if (length > 0) (total.toFloat() / length).coerceAtMost(.99f) else null)
                        lastTick = tick
                    }
                }
                output.fd.sync()
            }
            require(total == manifest.size) { "APK 下载不完整" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == manifest.sha) { "APK SHA-256 不匹配" }
        }
    }

    private class DeadlineStream(input: InputStream, private val connection: HttpURLConnection, private val deadline: Long) : FilterInputStream(input) {
        private fun remaining() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("更新请求已取消")
            val millis = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(8000).toInt()
            if (millis <= 0) throw java.io.IOException("更新请求超时")
            connection.readTimeout = millis
        }
        override fun read(): Int { remaining(); return `in`.read() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { remaining(); return `in`.read(buffer, offset, length) }
    }
}

internal class UpdateRepository {
    suspend fun pointer(channel: String, highest: Long, fingerprint: String?): UpdatePointer = supervisorScope {
        val sources = metadataSources("latest-$channel.json")
        require(sources.size >= 5)
        val results = Channel<Result<PointerResponse>>(sources.size)
        val jobs = sources.mapIndexed { index, url -> launch(Dispatchers.IO) {
            val response = try {
                Result.success(PointerResponse(URI(url).host, index == 0,
                    UpdatePointer.parse(UpdateHttp.metadata(url).toString(Charsets.UTF_8), channel)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Router.event("更新源 ${URI(url).host}：${error.message?.take(100)}", updateStatus = false)
                Result.failure(error)
            }
            results.trySend(response)
        } }
        val collected = mutableListOf<PointerResponse>()
        try {
            var received = 0
            var convergeUntil = Long.MAX_VALUE
            val deadline = System.nanoTime() + 23_000_000_000L
            while (received < sources.size) {
                val remaining = (minOf(deadline, convergeUntil) - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                val packet = withTimeoutOrNull(remaining) { results.receive() } ?: break
                received++
                val response = packet.getOrNull() ?: continue
                collected += response
                if (response.authoritative) convergeUntil = System.nanoTime() + 800_000_000L
            }
        } finally { jobs.forEach { it.cancel() }; results.close() }
        UpdateTrust.select(collected, highest, fingerprint)
    }

    suspend fun manifest(pointer: UpdatePointer): UpdateManifest = withContext(Dispatchers.IO) {
        for (url in metadataSources(pointer.path, pointer.commit)) {
            val manifest = try {
                val bytes = UpdateHttp.metadata(url)
                require(sha256(bytes) == pointer.hash) { "清单摘要不匹配" }
                UpdateManifest.parse(bytes.toString(Charsets.UTF_8), pointer.channel).also {
                    require(it.revision == pointer.revision)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (manifest != null) return@withContext manifest
        }
        error("无法取得完整且可信的更新清单")
    }
}
