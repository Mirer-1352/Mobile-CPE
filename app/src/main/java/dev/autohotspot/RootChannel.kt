package dev.autohotspot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** A command rejection is a valid reply; only a broken transport invalidates the session. */
internal class RootOperationException(message: String) : IOException(message)

internal class RootChannel(private val spawn: () -> Process, private val timeoutMillis: Long = 45_000) : AutoCloseable {
    private val requests = Mutex()
    private val lifecycle = Any()
    private var session: Session? = null
    private var closed = false

    suspend fun request(command: JSONObject): JSONObject = requests.withLock {
        val response = runInterruptible(Dispatchers.IO) {
            var current: Session? = null
            try {
                current = connection()
                current.write(command)
                current.read()
            } catch (error: Exception) {
                current?.let(::discard)
                throw error
            }
        }
        requireSuccess(response)
        response
    }

    private fun connection(): Session {
        synchronized(lifecycle) {
            check(!closed) { "Root 通道已关闭" }
            session?.let { return it }
        }
        val created = Session(spawn())
        val accepted = synchronized(lifecycle) {
            if (closed) false else { session = created; true }
        }
        if (!accepted) { created.close(); error("Root 通道已关闭") }
        try { requireSuccess(created.read()) }
        catch (error: Exception) { discard(created); throw error }
        return created
    }

    private fun discard(current: Session) {
        synchronized(lifecycle) { if (session === current) session = null }
        current.close()
    }

    override fun close() {
        val current = synchronized(lifecycle) {
            closed = true
            session.also { session = null }
        }
        current?.close()
    }

    private fun requireSuccess(reply: JSONObject) {
        if (!reply.optBoolean("ok")) throw RootOperationException(reply.optString("error", "Root 操作失败"))
    }

    private inner class Session(private val process: Process) : AutoCloseable {
        private val input = process.inputStream
        private val output = process.outputStream
        private val reader = input.bufferedReader()
        private val writer = output.bufferedWriter()
        private val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "mcpe-root-reply").apply { isDaemon = true } }

        fun write(command: JSONObject) {
            writer.write(command.toString())
            writer.newLine()
            writer.flush()
        }

        fun read(): JSONObject {
            val pending = reads.submit<JSONObject> {
                val diagnostics = ArrayDeque<String>()
                while (true) {
                    val line = reader.readLine() ?: throw IOException("Root 进程已退出：" +
                        diagnostics.joinToString(" / ").ifEmpty { "请检查 Root 授权和 SELinux 日志" })
                    if (line.startsWith("AUTOHOTSPOT:")) return@submit JSONObject(line.removePrefix("AUTOHOTSPOT:"))
                    diagnostics.addLast(line.take(300))
                    if (diagnostics.size > 5) diagnostics.removeFirst()
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            try { return pending.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw (error.cause as? Exception ?: IOException("Root 响应解析失败", error)) }
            catch (error: TimeoutException) { throw IOException("Root 响应超时", error) }
            finally { if (!pending.isDone) pending.cancel(true) }
        }

        override fun close() {
            // BufferedReader.close would wait on the lock held by readLine.
            reads.shutdownNow()
            runCatching { input.close() }
            runCatching { output.close() }
            process.destroy()
        }
    }
}
