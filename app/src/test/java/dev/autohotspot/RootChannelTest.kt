package dev.autohotspot

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

class RootChannelTest {
    private class FakeProcess(private val reply: (String) -> JSONObject?) : Process() {
        private val input = PipedInputStream()
        private val serverOutput = PipedOutputStream(input)
        private val serverInput = PipedInputStream()
        private val output = PipedOutputStream(serverInput)
        private val alive = AtomicBoolean(true)
        init {
            Thread({
                try {
                    val writer = serverOutput.bufferedWriter()
                    fun send(value: JSONObject) { writer.write("AUTOHOTSPOT:$value\n"); writer.flush() }
                    send(JSONObject().put("ok", true))
                    serverInput.bufferedReader().forEachLine { line -> reply(JSONObject(line).getString("op"))?.let(::send) }
                } catch (_: IOException) { }
            }, "fake-root").apply { isDaemon = true; start() }
        }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = alive.get()
        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
        override fun waitFor(): Int { while (isAlive) Thread.sleep(1); return 0 }
        override fun destroy() {
            alive.set(false)
            listOf(input, output, serverInput, serverOutput).forEach { runCatching { it.close() } }
        }
    }
    private fun command(op: String) = JSONObject().put("op", op)

    @Test fun rejectedOperationDoesNotTearDownAHealthyConnection() = runBlocking {
        var starts = 0
        val channel = RootChannel({ starts++; FakeProcess { JSONObject().put("ok", it != "deny").put("error", "denied") } })
        try {
            try { channel.request(command("deny")); fail("Expected rejection") } catch (_: RootOperationException) { }
            assertTrue(channel.request(command("ok")).getBoolean("ok"))
            assertEquals(1, starts)
        } finally { channel.close() }
    }

    @Test fun cancellationDiscardsOnlyTheInterruptedSession() = runBlocking {
        val seen = CompletableDeferred<Unit>()
        val processes = mutableListOf<FakeProcess>()
        val channel = RootChannel({ FakeProcess { if (it == "hang") { seen.complete(Unit); null } else JSONObject().put("ok", true) }.also { processes += it } })
        try {
            val request = launch { channel.request(command("hang")) }
            withTimeout(3000) { seen.await() }
            request.cancelAndJoin()
            assertFalse(processes.first().isAlive)
            assertTrue(channel.request(command("ok")).getBoolean("ok"))
            assertEquals(2, processes.size)
        } finally { channel.close() }
    }

    @Test fun timeoutReleasesTheReaderBeforeReconnecting() = runBlocking {
        val processes = mutableListOf<FakeProcess>()
        val channel = RootChannel({ FakeProcess { if (it == "hang") null else JSONObject().put("ok", true) }.also { processes += it } }, timeoutMillis = 500)
        try {
            try { channel.request(command("hang")); fail("Expected timeout") } catch (_: IOException) { }
            assertTrue(channel.request(command("ok")).getBoolean("ok"))
            assertEquals(2, processes.size)
            assertFalse(processes.first().isAlive)
        } finally { channel.close() }
    }

    @Test fun closedClientCannotSpawnAnotherRootProcess() = runBlocking {
        var starts = 0
        val channel = RootChannel({ starts++; FakeProcess { JSONObject().put("ok", true) } })
        channel.close()
        try { channel.request(command("ok")); fail("Expected closed channel") } catch (_: IllegalStateException) { }
        assertEquals(0, starts)
    }

    @Test fun structuredPlatformRejectionKeepsItsCodeAndNameWithoutReconnecting() = runBlocking {
        var starts = 0
        val channel = RootChannel({ starts++; FakeProcess { op ->
            JSONObject().put("ok", op != "stop").put("error", "denied")
                .put("errorCode", 14).put("errorName", "TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION")
        } })
        try {
            try { channel.request(command("stop")); fail("Expected rejection") }
            catch (error: RootOperationException) {
                assertEquals(14, error.errorCode)
                assertEquals("TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION", error.errorName)
            }
            assertTrue(channel.request(command("snapshot")).getBoolean("ok"))
            assertEquals(1, starts)
        } finally { channel.close() }
    }
}
