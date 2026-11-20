package dev.autohotspot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogTest {
    @Test fun restartPreservesHistoryAndContinuesSequence() {
        val first = DiagnosticLog()
        first.append("requested", JSONObject().put("op", "start"), 100, 1)
        val next = DiagnosticLog()
        next.restore(JSONObject(first.json().toString()))
        next.append("failed", JSONObject().put("error", 14), 120, 21)
        val events = next.json().getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals(1, events.getJSONObject(0).getLong("id"))
        assertEquals(2, events.getJSONObject(1).getLong("id"))
    }

    @Test fun bothEntryCountAndUtf8BytesAreBounded() {
        val log = DiagnosticLog(countLimit = 10, byteLimit = 1800)
        repeat(100) { log.append("state", JSONObject().put("message", "网".repeat(120)), it.toLong(), it.toLong()) }
        assertTrue(log.json().getJSONArray("events").length() <= 10)
        assertTrue(log.json().toString().toByteArray(Charsets.UTF_8).size <= 1800)
        assertEquals(100, log.json().getJSONArray("events").let { it.getJSONObject(it.length()-1) }.getLong("id"))
    }

    @Test fun callerMutationCannotRewriteAnEarlierRecord() {
        val log = DiagnosticLog()
        val data = JSONObject().put("code", 14)
        log.append("failed", data, 100, 1)
        data.put("code", 0)
        assertEquals(14, log.json().getJSONArray("events").getJSONObject(0).getJSONObject("data").getInt("code"))
    }
}
