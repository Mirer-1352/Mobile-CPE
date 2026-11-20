package dev.autohotspot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class AppUpdateTest {
    private val hash = "a".repeat(64)
    private fun manifest() = JSONObject().apply {
        put("schemaVersion", 1); put("channel", "stable"); put("versionCode", 20)
        put("versionName", "0.9.0"); put("policyRevision", 3); put("maxForcedVersionCode", 10)
        put("sha256", hash); put("size", 123); put("publishedAt", "2026-08-12T08:00:00Z")
        put("changelog", "## 更新\n\n- **修复**")
        put("urls", JSONArray((1..5).map { "https://cdn$it.example/app.apk" } + "https://github.com/daxiaamu/Mobile-CPE/releases/download/v0.9.0/app.apk"))
    }
    private fun parsed(change: (JSONObject) -> Unit = {}) = UpdateManifest.parse(manifest().also(change).toString(), "stable")
    private fun pointer(revision: Long = 3, digest: String = hash) = UpdatePointer("stable", revision, digest,
        "b".repeat(40), "manifests/$revision-$digest.json", Instant.parse("2027-08-01T00:00:00Z"))
    private fun response(host: String, authority: Boolean = false, p: UpdatePointer = pointer()) = PointerResponse(host, authority, p)
    private fun rejected(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun automaticAndManualJoinOneSessionAndReportManualResult() {
        val session = UpdateCheckSession()
        assertTrue(session.begin(false)); assertFalse(session.begin(true)); assertTrue(session.finish())
        assertFalse(session.begin(false)); assertTrue(session.begin(true)); assertTrue(session.finish())
    }
    @Test fun automaticJoinDoesNotEraseManualVisibility() {
        val session = UpdateCheckSession()
        assertTrue(session.begin(true)); assertFalse(session.begin(false)); assertTrue(session.finish())
    }
    @Test fun versionCodeAloneDeterminesForcedBoundary() {
        val m = parsed { it.put("versionName", "0.0.1") }
        assertTrue(m.required(10)); assertFalse(m.required(11)); assertFalse(m.required(20))
    }
    @Test fun rejectsWrongChannelOrMissingHash() {
        rejected { parsed { it.put("channel", "beta") } }
        rejected { parsed { it.remove("sha256") } }
        rejected { parsed { it.put("sha256", "A".repeat(64)) } }
    }
    @Test fun rejectsInvalidNumericFieldsAndForcedBounds() {
        rejected { parsed { it.put("versionCode", "20") } }
        rejected { parsed { it.put("policyRevision", 0) } }
        rejected { parsed { it.put("maxForcedVersionCode", 20) } }
        rejected { parsed { it.put("size", APK_LIMIT + 1) } }
    }
    @Test fun fivePathsOnOneHostDoNotCountAsFiveCdns() {
        rejected { parsed { it.put("urls", JSONArray((1..6).map { "https://same.example/$it.apk" })) } }
    }
    @Test fun rejectsHttpAndUserInfoUrls() {
        rejected { https("http://cdn.example/app.apk") }
        rejected { https("https://user:pass@cdn.example/app.apk") }
    }
    @Test fun invalidDateIsHiddenAndValidDateIsAnInstant() {
        assertNull(parsed { it.put("publishedAt", "not-a-date") }.published)
        assertEquals(Instant.parse("2026-08-12T08:00:00Z"), parsed().published)
        assertNull(parsed { it.put("publishedAt", "2026-08-12T08:00:00") }.published)
    }
    @Test fun authorityWinsOverHigherOrFasterMirrors() {
        val authority = pointer()
        assertEquals(authority, UpdateTrust.select(listOf(response("mirror", p = pointer(9)), response("api.github.com", true)), 0, null))
    }
    @Test fun sameRevisionConflictRejectsEvenAuthority() {
        rejected { UpdateTrust.select(listOf(response("api.github.com", true), response("mirror", p = pointer(digest = "c".repeat(64)))), 0, null) }
    }
    @Test fun fallbackRequiresTwoDifferentHostsWithIdenticalContent() {
        rejected { UpdateTrust.select(listOf(response("one")), 0, null) }
        rejected { UpdateTrust.select(listOf(response("one"), response("one")), 0, null) }
        assertEquals(pointer(), UpdateTrust.select(listOf(response("one"), response("two")), 0, null))
    }
    @Test fun persistentFloorRejectsRollbackAndSameRevisionMutation() {
        rejected { UpdateTrust.select(listOf(response("api.github.com", true)), 4, null) }
        rejected { UpdateTrust.select(listOf(response("api.github.com", true)), 3, "different") }
        assertEquals(pointer(), UpdateTrust.select(listOf(response("api.github.com", true)), 3, pointer().fingerprint))
    }
    @Test fun expiredPointerAndMutablePathsAreRejected() {
        val j = JSONObject().apply {
            put("schemaVersion", 1); put("channel", "stable"); put("policyRevision", 3)
            put("manifestSha256", hash); put("commit", "b".repeat(40))
            put("path", "manifests/3-$hash.json"); put("expiresAt", "2027-08-01T00:00:00Z")
        }
        assertEquals(pointer(), UpdatePointer.parse(j.toString(), "stable", Instant.parse("2026-11-20T00:00:00Z")))
        rejected { UpdatePointer.parse(j.toString(), "stable", Instant.parse("2028-01-01T00:00:00Z")) }
        j.put("commit", "main")
        rejected { UpdatePointer.parse(j.toString(), "stable") }
    }
    @Test fun damagedOrReplacedFileFailsFreshInstallVerification() {
        val file = File.createTempFile("cpe-update", ".apk")
        try {
            file.writeText("valid")
            val digest = sha256(file.readBytes())
            verifyUpdateFile(file, 5, digest)
            file.writeText("other") // Same size and existing path, different bytes.
            rejected { verifyUpdateFile(file, 5, digest) }
        } finally { file.delete() }
    }
    @Test fun currentMetadataRejectsPreviouslyVerifiedOldApk() {
        val file = File.createTempFile("cpe-update", ".apk")
        try {
            file.writeText("old")
            verifyUpdateFile(file, 3, sha256("old".toByteArray()))
            rejected { verifyUpdateFile(file, 3, sha256("new".toByteArray())) }
        } finally { file.delete() }
    }
    @Test fun rejectsWrongPackageVersionSignerAndMissingSigner() {
        fun verify(pkg: String = "dev.autohotspot", code: Long = 20, signer: Set<String> = setOf("A")) =
            verifyUpdateIdentity("dev.autohotspot", 20, setOf("A"), pkg, code, signer)
        verify()
        rejected { verify(pkg = "other") }; rejected { verify(code = 19) }
        rejected { verify(signer = setOf("B")) }; rejected { verify(signer = emptySet()) }
    }
    @Test fun checksumOrHttpFailureAdvancesToNextDeclaredSource() {
        val attempted = mutableListOf<String>(); val failures = mutableListOf<String>()
        val result = updateSourceFallback(listOf("http-error", "bad-sha", "valid"), attempt = { url ->
            attempted += url
            when (url) { "http-error" -> error("HTTP 503"); "bad-sha" -> error("SHA-256"); else -> "verified" }
        }, failed = { url, _ -> failures += url })
        assertEquals("verified", result); assertEquals(listOf("http-error", "bad-sha", "valid"), attempted)
        assertEquals(listOf("http-error", "bad-sha"), failures)
    }
    @Test fun metadataHasAtLeastFiveSourcesAndPinnedManifestCommit() {
        assertTrue(metadataSources("latest-stable.json").size >= 5)
        assertTrue(metadataSources(pointer().path, pointer().commit).all { "b".repeat(40) in it })
    }
    @Test fun cancellingDownloadDoesNotTryAnotherSource() {
        val attempted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            updateSourceFallback(listOf("first", "second"), attempt = { url ->
                attempted += url
                throw kotlinx.coroutines.CancellationException("cancelled")
            }, failed = { url, _ -> failures += url })
        }
        assertEquals(listOf("first"), attempted)
        assertTrue(failures.isEmpty())
    }
}
