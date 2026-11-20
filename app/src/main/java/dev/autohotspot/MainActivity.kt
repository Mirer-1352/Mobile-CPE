package dev.autohotspot

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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

class MainActivity : ComponentActivity() {
    private var foreground = false
    private var launchingExternal = false
    companion object { private var lastCpePause = -1L }

    override fun onResume() {
        super.onResume()
        Preferences.get(this).refresh()
        foreground = true
        launchingExternal = false
        lastCpePause = -1L
        XposedConnection.resumed()
        AppUpdater.visible(true)
    }

    override fun onPause() {
        lastCpePause = if (launchingExternal) -1L else SystemClock.elapsedRealtime()
        foreground = false
        XposedConnection.paused()
        AppUpdater.visible(false)
        super.onPause()
    }

    private fun homeFromCpe(intent: Intent, recreated: Boolean = false) = HomeNavigationPolicy.redirects(
        intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME),
        foreground, SystemClock.elapsedRealtime(), lastCpePause, recreated)

    override fun onNewIntent(intent: Intent) {
        val redirect = homeFromCpe(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        if (redirect) openOriginalHome()
    }

    private fun openOriginalHome() {
        launchingExternal = true
        runCatching { OriginalLauncher.open(this, Preferences.get(this)) }.onFailure {
            launchingExternal = false
            Toast.makeText(this, it.message ?: "无法打开原桌面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystem(action: String) {
        launchingExternal = true
        runCatching { startActivity(Intent(action)) }.onFailure {
            launchingExternal = false
            Router.event("系统未提供此设置入口")
        }
    }

    private fun chooseDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val role = getSystemService(RoleManager::class.java)
            if (role?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                launchingExternal = true
                if (runCatching { startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME)) }.isSuccess) return
            }
        }
        openSystem(Settings.ACTION_HOME_SETTINGS)
    }

    private fun startGuardian() {
        runCatching { HotspotService.start(this) }.onFailure { Router.event("启动失败：${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val redirectHomeGesture = homeFromCpe(intent, recreated = savedInstanceState != null)
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
                    SettingsActions(::save, ::chooseDefaultHome, XposedConnection::syncAndRequest, ::openSystem,
                        { permission.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) }))
                else RouterDisplay(state, options, { settings = true }, { mode, enabled -> save { it.withSharing(mode, enabled) } }, ::openOriginalHome)
                UpdateHost(this@MainActivity)
            }
        }
        if (redirectHomeGesture) window.decorView.post { openOriginalHome() }
    }
}