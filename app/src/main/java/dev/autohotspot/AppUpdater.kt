package dev.autohotspot

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.UserManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal enum class UpdateStage { NONE, DOWNLOADING, VERIFYING, INSTALLING, READY, FAILED }
internal data class UpdateState(
    val checking: Boolean = false, val known: UpdateManifest? = null, val dialog: Boolean = false,
    val stage: UpdateStage = UpdateStage.NONE, val progress: Float? = null,
    val error: String? = null, val feedback: String? = null, val redDot: Boolean = false
) {
    val required get() = known?.required(BuildConfig.VERSION_CODE.toLong()) == true
    val busy get() = stage in setOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.INSTALLING)
}

internal object AppUpdater {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = UpdateCheckSession()
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val prefs get() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private var installed = false
    private var installing = false
    private var visible = false
    private var nextAutomaticAt = 0L
    private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    private const val RETRY_INTERVAL = 15 * 60 * 1000L
    // Android's multi-user UID layout (UserHandle.PER_USER_RANGE), stable since API 17.
    private val user get() = Process.myUid() / 100_000
    private val current get() = BuildConfig.VERSION_CODE.toLong()
    private val beta get() = BuildConfig.VERSION_NAME.contains(Regex("(?i)(beta|alpha|rc)"))

    fun initialize(app: Context) {
        if (installed) return
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return
        // The service may call this before unlock; no CE storage is accessed until UserManager allows it.
        installed = true; context = app.applicationContext
        nextAutomaticAt = SystemClock.elapsedRealtime() + 2200
        val cached = prefs.getString("known", null)?.let { raw -> runCatching {
            val channel = org.json.JSONObject(raw).getString("channel")
            UpdateManifest.parse(raw, channel).takeIf {
                it.versionCode > current && (beta || channel == "stable") &&
                    sha256(raw.toByteArray(Charsets.UTF_8)) == prefs.getString("manifestHash_$channel", null) &&
                    prefs.getLong("expires_$channel", 0) > System.currentTimeMillis()
            }
        }.getOrNull() }
        mutable.value = UpdateState(known = cached, redDot = cached != null)
        val pending = prefs.getLong("pendingRootCode", 0)
        if (pending in 1..current) {
            prefs.edit().remove("pendingRootCode").remove("known").putBoolean("redDot", false).apply()
            Router.event("Root 自动更新完成：${BuildConfig.VERSION_NAME}", updateStatus = false)
        } else if (pending > current && cached != null) scope.launch { recover(cached) }
        else if (cached != null) scope.launch {
            if (!mutable.value.busy && !mutable.value.checking && mutable.value.known?.sha == cached.sha) download()
        }
        if (cached == null) prefs.edit().remove("known").putBoolean("redDot", false).apply()
    }
    fun visible(value: Boolean) { visible = value }
    fun tick(app: Context) {
        initialize(app)
        if (!installed || mutable.value.busy || mutable.value.checking || installing || SystemClock.elapsedRealtime() < nextAutomaticAt) return
        session.nextAutomaticWindow()
        check(false)
    }

    fun check(manual: Boolean) {
        if (!installed || mutable.value.busy || installing) return
        if (!session.begin(manual)) return
        nextAutomaticAt = SystemClock.elapsedRealtime() + CHECK_INTERVAL
        mutable.update { it.copy(checking = true, feedback = null) }
        scope.launch {
            val result = runCatching {
                val repository = UpdateRepository()
                val channels = if (beta) listOf("stable", "beta") else listOf("stable")
                val loaded = channels.map { channel -> async {
                    runCatching {
                        val pointer = repository.pointer(channel, prefs.getLong("revision_$channel", 0), prefs.getString("fingerprint_$channel", null))
                        val manifest = repository.manifest(pointer)
                        require(manifest.versionCode >= prefs.getLong("code_$channel", 0)) { "拒绝版本回退" }
                        require(manifest.forcedThrough >= prefs.getLong("forced_$channel", 0)) { "拒绝强制边界回退" }
                        check(prefs.edit().putLong("revision_$channel", pointer.revision)
                            .putString("fingerprint_$channel", pointer.fingerprint).putLong("code_$channel", manifest.versionCode)
                            .putLong("forced_$channel", manifest.forcedThrough)
                            .putString("manifestHash_$channel", pointer.hash).putLong("expires_$channel", pointer.expires.toEpochMilli())
                            .commit()) { "无法保存更新策略" }
                        manifest
                    }
                } }.awaitAll()
                // A failed channel must not erase a previously known mandatory boundary.
                val valid = loaded.mapNotNull { it.getOrNull() }
                val selected = valid.maxByOrNull { it.versionCode } ?: throw (loaded.first().exceptionOrNull() ?: IllegalStateException("更新源不可用"))
                val previous = mutable.value.known
                if (previous != null && (previous.versionCode > selected.versionCode ||
                    (previous.required(current) && loaded.any { it.isFailure }))) previous else selected
            }
            val manualResult = session.finish()
            result.fold(onSuccess = { manifest ->
                val available = manifest.versionCode > current
                prefs.edit().apply {
                    if (available) putString("known", manifest.raw) else remove("known")
                    if (!available) putBoolean("redDot", false)
                }.apply()
                mutable.update { old -> old.copy(checking = false, known = manifest.takeIf { available },
                    dialog = available && manualResult, stage = if (old.known?.sha == manifest.sha) old.stage else UpdateStage.NONE,
                    error = null, feedback = if (manualResult && !available) "已是最新版本" else null,
                    redDot = available) }
                if (available) download()
            }, onFailure = { failure ->
                nextAutomaticAt = SystemClock.elapsedRealtime() + RETRY_INTERVAL
                Router.event("检查更新：${failure.message?.take(120)}", updateStatus = false)
                mutable.update { it.copy(checking = false, dialog = it.required || it.dialog,
                    feedback = if (manualResult) "检查失败，暂无可信更新清单，请稍后重试" else null) }
            })
        }
    }
    fun feedbackShown() { mutable.update { it.copy(feedback = null) } }
    fun dismiss() {
        val s = mutable.value; s.known ?: return
        if (s.required || s.busy || installing) return
        mutable.update { it.copy(dialog = false) }
    }
    private fun apkFile(update: UpdateManifest) = File(context.filesDir, "updates/${update.versionCode}-${update.sha}.apk")
    private fun verify(file: File, update: UpdateManifest) {
        verifyUpdateFile(file, update.size, update.sha)
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法读取 APK 身份")
        require(archive.applicationInfo!!.minSdkVersion <= android.os.Build.VERSION.SDK_INT) { "新版本不支持当前 Android 系统" }
        @Suppress("DEPRECATION")
        val local = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        verifyUpdateIdentity(context.packageName, update.versionCode, signerKeys(local.signingInfo),
            archive.packageName, archive.longVersionCode, signerKeys(archive.signingInfo))
    }
    private fun signerKeys(info: SigningInfo?): Set<String> {
        // Conservatively require the current signer set. Key rotation needs an explicit migration.
        return info?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }
    fun download() {
        val update = mutable.value.known ?: return
        if (mutable.value.busy || mutable.value.checking || installing) return
        mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, error = null, progress = null) }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val destination = apkFile(update); check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                // Reuse only after fresh hash AND identity verification against the current manifest.
                if (destination.exists() && runCatching { verify(destination, update) }.isSuccess) return@withContext
                destination.delete()
                destination.parentFile!!.listFiles()?.filter { it.name != destination.name }?.forEach { it.delete() }
                val temporary = File(destination.parentFile, destination.name + ".download")
                updateSourceFallback(update.urls, attempt = { url ->
                        temporary.delete()
                        UpdateHttp.download(url, temporary, update) { value -> mutable.update { it.copy(progress = value) } }
                        mutable.update { it.copy(stage = UpdateStage.VERIFYING) }
                        verify(temporary, update)
                        check(temporary.renameTo(destination)) { "无法保存 APK" }
                }, failed = { url, e ->
                        temporary.delete()
                        Router.event("更新下载 ${java.net.URI(url).host}：${e.message?.take(100)}", updateStatus = false)
                        mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = null) }
                })
            } }
            result.fold(onSuccess = { handoff(update) }, onFailure = { fail(it.message ?: "下载失败") })
        }
    }
    private fun fail(message: String) {
        installing = false
        nextAutomaticAt = SystemClock.elapsedRealtime() + RETRY_INTERVAL
        Router.event("Root 自动更新：${message.take(200)}", updateStatus = false)
        mutable.update { it.copy(stage = UpdateStage.FAILED, error = message, redDot = true) }
    }
    private suspend fun handoff(update: UpdateManifest) {
        if (installing) return
        installing = true
        mutable.update { it.copy(stage = UpdateStage.VERIFYING, progress = 1f) }
        val root = RootClient(context)
        try {
            val file = apkFile(update)
            withContext(Dispatchers.IO) { verify(file, update) }
            check(withContext(Dispatchers.IO) { prefs.edit().putLong("pendingRootCode", update.versionCode).commit() })
            root.request("update", data = org.json.JSONObject().put("path", file.absolutePath)
                .put("source", context.applicationInfo.sourceDir).put("versionCode", update.versionCode)
                .put("sha256", update.sha).put("size", update.size).put("user", user).put("reopen", visible))
            mutable.update { it.copy(stage = UpdateStage.INSTALLING) }
            watch(root, update)
        } catch (e: Exception) { fail(e.message ?: "Root 更新失败") }
        finally { root.close(); installing = false }
    }
    private suspend fun recover(update: UpdateManifest) {
        if (installing) return
        installing = true
        val root = RootClient(context)
        try {
            mutable.update { it.copy(stage = UpdateStage.INSTALLING) }
            watch(root, update)
        } catch (e: Exception) { fail(e.message ?: "无法恢复更新结果") }
        finally { root.close(); installing = false }
    }
    private suspend fun watch(root: RootClient, update: UpdateManifest) {
        repeat(80) {
            val result = root.request("updateStatus", user).getJSONObject("update")
            require(result.optLong("versionCode") == update.versionCode && result.optString("sha256") == update.sha) { "Root 更新任务不匹配，稍后重试" }
            when (result.optString("state")) {
                "failed" -> error(result.optString("detail", "Root 安装失败"))
                "succeeded" -> {
                    mutable.update { it.copy(stage = UpdateStage.READY, feedback = "Root 更新完成", dialog = false, redDot = false) }
                    return
                }
            }
            delay(2000)
        }
        error("Root 更新任务超时，请查看诊断")
    }
}
