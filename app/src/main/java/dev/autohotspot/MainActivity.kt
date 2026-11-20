package dev.autohotspot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Preferences.get(this).refresh()
        RootSettingsSync.refresh()
        XposedConnection.resumed()
        AppUpdater.visible(true)
    }

    override fun onPause() {
        XposedConnection.paused()
        AppUpdater.visible(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun openOriginalHome() {
        runCatching { OriginalLauncher.open(this, Preferences.get(this)) }.onFailure {
            Toast.makeText(this, it.message ?: "无法打开原桌面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystem(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure {
            Router.event("系统未提供此设置入口")
        }
    }

    private fun startGuardian() {
        runCatching { HotspotService.start(this) }.onFailure { Router.event("启动失败：${it.message}") }
    }

    private fun viewSource() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$UPDATE_REPO"))) }
            .onFailure { Toast.makeText(this, "无法打开链接，请安装浏览器", Toast.LENGTH_SHORT).show() }
    }

    private fun copyDiagnostics() {
        lifecycleScope.launch {
            try {
                val report = AppDiagnostics.report(this@MainActivity)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("手机CPE诊断", report))
                Toast.makeText(this@MainActivity, "诊断报告已复制", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(this@MainActivity, "复制失败：${error.message}", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val preferences = Preferences.get(this)
        AppUpdater.initialize(this)
        OriginalLauncher.capture(this, preferences)
        if (preferences.read().enabled) startGuardian()
        setContent {
            MaterialTheme(colorScheme = Palette) {
                val state by Router.state.collectAsStateWithLifecycle()
                val xposed by XposedConnection.state.collectAsStateWithLifecycle()
                val guardian by FrameworkGuardian.state.collectAsStateWithLifecycle()
                val rootSettings by RootSettingsSync.state.collectAsStateWithLifecycle()
                val options by preferences.state.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(false) }
                val scroll = rememberScrollState()
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                BackHandler(enabled = settings) { settings = false }

                fun save(change: (Options) -> Options): Boolean {
                    val before = preferences.read()
                    val next = try { preferences.update(change) }
                    catch (error: Exception) {
                        Router.event("配置保存失败：${error.message}")
                        Toast.makeText(this@MainActivity, "配置未能保存，请重试", Toast.LENGTH_LONG).show()
                        return false
                    }
                    if (next.vpnSharing && !before.vpnSharing || next.frameworkGuardian && !before.frameworkGuardian || next.forceHome && !before.forceHome)
                        XposedConnection.syncAndRequest()
                    if (before.enabled && !next.enabled) stopService(Intent(this@MainActivity, HotspotService::class.java))
                    if (!before.enabled && next.enabled) permission.launch(buildList {
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                    if (next.enabled && !Router.state.value.running) startGuardian()
                    return true
                }

                DisposableEffect(options.keepScreen) {
                    if (options.keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                DisposableEffect(Unit) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                }
                if (settings) SettingsScreen(state, options, xposed, guardian, rootSettings, scroll,
                    SettingsActions(::save, XposedConnection::syncAndRequest, ::openSystem,
                        { permission.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) }, ::copyDiagnostics, ::viewSource))
                else RouterDisplay(state, options, { settings = true }, { mode, enabled -> save { it.withSharing(mode, enabled) } }, ::openOriginalHome)
                UpdateHost(this@MainActivity)
            }
        }
    }
}
