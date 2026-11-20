package dev.autohotspot

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.io.File
import java.util.concurrent.CancellationException

internal const val UPDATE_REPO = "daxiaamu/Mobile-CPE"
internal const val METADATA_LIMIT = 1_048_576
internal const val APK_LIMIT = 512L * 1024 * 1024
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun verifyUpdateFile(file: File, size: Long, hash: String) {
    require(file.isFile && file.length() == size) { "APK 大小不匹配" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "APK SHA-256 不匹配" }
}
internal fun verifyUpdateIdentity(expectedPackage: String, expectedCode: Long, currentSigners: Set<String>,
    actualPackage: String, actualCode: Long, actualSigners: Set<String>) {
    require(expectedPackage == actualPackage && expectedCode == actualCode) { "APK 包名或版本不匹配" }
    require(currentSigners.isNotEmpty() && currentSigners == actualSigners) { "APK 签名与已安装版本不一致" }
}
internal fun <T> updateSourceFallback(urls: List<String>, attempt: (String) -> T, failed: (String, Exception) -> Unit): T {
    var last: Exception? = null
    for (url in urls) try { return attempt(url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { last = e; failed(url, e) }
    throw (last ?: IllegalStateException("所有更新源均不可用"))
}
internal fun https(value: String): String = value.also {
    val uri = URI(it)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "无效 HTTPS 地址" }
}
private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long) { "$key 必须是整数" }
    return (value as Number).toLong()
}
private fun JSONObject.urls(key: String): List<String> {
    val a = getJSONArray(key)
    require(a.length() in 1..20)
    return (0 until a.length()).map { https(a.getString(it)) }.distinct()
}
internal fun metadataSources(path: String, ref: String = "updates"): List<String> = listOf(
    "https://api.github.com/repos/$UPDATE_REPO/contents/$path?ref=$ref",
    "https://raw.githubusercontent.com/$UPDATE_REPO/$ref/$path",
    "https://cdn.jsdelivr.net/gh/$UPDATE_REPO@$ref/$path",
    "https://fastly.jsdelivr.net/gh/$UPDATE_REPO@$ref/$path",
    "https://gcore.jsdelivr.net/gh/$UPDATE_REPO@$ref/$path",
    "https://testingcf.jsdelivr.net/gh/$UPDATE_REPO@$ref/$path",
    "https://cdn.statically.io/gh/$UPDATE_REPO/$ref/$path"
)

internal data class UpdateManifest(
    val channel: String, val versionCode: Long, val versionName: String, val revision: Long,
    val forcedThrough: Long, val sha: String, val size: Long, val urls: List<String>,
    val notes: String, val published: Instant?, val raw: String
) {
    fun required(current: Long) = current <= forcedThrough && versionCode > current
    companion object {
        fun parse(raw: String, expectedChannel: String): UpdateManifest {
            require(raw.toByteArray(Charsets.UTF_8).size <= METADATA_LIMIT)
            val j = JSONObject(raw)
            require(j.integer("schemaVersion") == 1L)
            val channel = j.getString("channel")
            require(channel in setOf("stable", "beta") && channel == expectedChannel)
            val code = j.integer("versionCode"); val revision = j.integer("policyRevision")
            val forced = j.integer("maxForcedVersionCode"); val size = j.integer("size")
            require(code > 0 && revision > 0 && forced in 0 until code && size in 1..APK_LIMIT)
            val hash = j.getString("sha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
            val urls = j.urls("urls")
            // GitHub origin is an additional fallback, not one of the five CDN hosts.
            require(urls.map { URI(it).host.lowercase() }.filter { it != "github.com" }.distinct().size >= 5)
            require(urls.any { URI(it).host == "github.com" })
            val name = j.getString("versionName"); require(name.isNotBlank() && name.length <= 128)
            val published = j.optString("publishedAt").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return UpdateManifest(channel, code, name, revision, forced, hash, size, urls,
                j.optString("changelog"), published, raw)
        }
    }
}

internal data class UpdatePointer(val channel: String, val revision: Long, val hash: String,
    val commit: String, val path: String, val expires: Instant) {
    val fingerprint: String get() = sha256("$channel\n$revision\n$hash\n$commit\n$path\n$expires".toByteArray())
    companion object {
        fun parse(raw: String, channel: String, now: Instant = Instant.now()): UpdatePointer {
            val j = JSONObject(raw)
            require(j.integer("schemaVersion") == 1L && j.getString("channel") == channel)
            val revision = j.integer("policyRevision"); require(revision > 0)
            val hash = j.getString("manifestSha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
            val commit = j.getString("commit"); require(commit.matches(Regex("[0-9a-f]{40}")))
            val path = j.getString("path"); require(path == "manifests/$revision-$hash.json")
            val expires = Instant.parse(j.getString("expiresAt")); require(expires > now) { "更新指针已过期" }
            return UpdatePointer(channel, revision, hash, commit, path, expires)
        }
    }
}

internal data class PointerResponse(val host: String, val authoritative: Boolean, val pointer: UpdatePointer)
internal object UpdateTrust {
    fun select(responses: List<PointerResponse>, highest: Long, acceptedFingerprint: String?): UpdatePointer {
        require(responses.isNotEmpty()) { "更新源暂不可用" }
        val conflicts = responses.groupBy { it.pointer.revision }
            .filterValues { it.map { r -> r.pointer.fingerprint }.distinct().size > 1 }.keys
        val authority = responses.firstOrNull { it.authoritative }
        val chosen = if (authority != null) authority.pointer else responses
            .filter { it.pointer.revision !in conflicts }
            .groupBy { it.pointer.fingerprint }.values
            .filter { group -> group.map { it.host }.distinct().size >= 2 }
            .maxByOrNull { it.first().pointer.revision }?.first()?.pointer
            ?: error("没有可信的一致更新清单")
        require(chosen.revision !in conflicts) { "同一更新修订存在冲突" }
        require(chosen.revision >= highest) { "拒绝过期更新缓存" }
        require(chosen.revision != highest || acceptedFingerprint == null || chosen.fingerprint == acceptedFingerprint) { "更新修订内容发生变化" }
        return chosen
    }
}

/** Shared automatic/manual session; a manual join upgrades the eventual visibility. */
internal class UpdateCheckSession {
    var checking = false; private set
    private var manual = false
    private var automaticUsed = false
    @Synchronized fun begin(isManual: Boolean): Boolean {
        if (checking) { manual = manual || isManual; return false }
        if (!isManual && automaticUsed) return false
        automaticUsed = true; checking = true; manual = isManual
        return true
    }
    @Synchronized fun finish(): Boolean { checking = false; return manual.also { manual = false } }
    @Synchronized fun nextAutomaticWindow() { if (!checking) automaticUsed = false }
}
