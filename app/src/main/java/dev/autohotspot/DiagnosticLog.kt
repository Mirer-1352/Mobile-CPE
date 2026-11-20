package dev.autohotspot

import org.json.JSONArray
import org.json.JSONObject

/** A bounded, structured journal. Kept independent of Android and the storage mechanism. */
internal class DiagnosticLog(private val countLimit: Int = 160, private val byteLimit: Int = 64 * 1024) {
    private val entries = ArrayDeque<JSONObject>()
    private var next = 1L

    fun restore(value: JSONObject?) {
        entries.clear()
        val array = value?.optJSONArray("events") ?: JSONArray()
        for (i in 0 until array.length()) entries += JSONObject(array.getJSONObject(i).toString())
        next = (entries.maxOfOrNull { it.optLong("id") } ?: 0) + 1
        trim()
    }

    fun append(kind: String, data: JSONObject, at: Long, elapsed: Long) {
        val copy = if (data.toString().toByteArray(Charsets.UTF_8).size <= 12 * 1024) JSONObject(data.toString())
            else JSONObject().put("truncated", true).put("summary", data.toString().take(1500))
        entries += JSONObject().put("id", next++).put("at", at).put("elapsed", elapsed).put("kind", kind).put("data", copy)
        trim()
    }

    private fun trim() {
        while (entries.size > countLimit || entries.isNotEmpty() && json().toString().toByteArray(Charsets.UTF_8).size > byteLimit) entries.removeFirst()
    }

    fun json(): JSONObject = JSONObject().put("schema", 1).put("events", JSONArray(entries.toList()))
}
