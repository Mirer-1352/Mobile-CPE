package dev.autohotspot

import android.content.Context
import org.json.JSONObject

class Preferences private constructor(context: Context) {
    companion object {
        @Volatile private var instance: Preferences? = null
        fun get(context: Context): Preferences = instance ?: synchronized(this) {
            instance ?: Preferences(context.applicationContext).also { instance = it }
        }
    }
    private val defaults = Options()
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("router", Context.MODE_PRIVATE)
    @Suppress("DEPRECATION")
    private val installedAt = app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
    private val store = run {
        if (!prefs.contains("controlRevision")) check(prefs.edit().putLong("controlRevision", System.currentTimeMillis().coerceAtLeast(1)).commit())
        OptionsStore(::load, ::persist)
    }
    val state get() = store.state
    fun read() = store.read()
    fun refresh() = store.refresh()
    fun update(block: (Options) -> Options): Options = store.update(block)
    private fun load() = Options(
        enabled = prefs.getBoolean("enabled", defaults.enabled), boot = prefs.getBoolean("boot", defaults.boot),
        recover = prefs.getBoolean("recover", true), keepScreen = prefs.getBoolean("screen", true),
        burnInProtection = prefs.getBoolean("burnInProtection", true),
        forceHome = prefs.getBoolean("forceHome", false),
        vpnSharing = prefs.getBoolean("vpnSharing", true),
        frameworkGuardian = prefs.getBoolean("frameworkGuardian", true),
        desired = Sharing.entries.filter { prefs.getBoolean("want_${it.type}", it in defaults.desired) }.toSet(),
        pendingStops = Sharing.entries.filter { prefs.getBoolean("stop_${it.type}", false) }.toSet()
    )
    private fun persist(before: Options, next: Options) {
        check(prefs.edit().apply {
            if (before.enabled != next.enabled || before.boot != next.boot || before.frameworkGuardian != next.frameworkGuardian || before.forceHome != next.forceHome)
                putLong("controlRevision", maxOf(prefs.getLong("controlRevision", 0) + 1, System.currentTimeMillis()))
            if (before.enabled != next.enabled) putBoolean("enabled", next.enabled)
            if (before.boot != next.boot) putBoolean("boot", next.boot)
            if (before.recover != next.recover) putBoolean("recover", next.recover)
            if (before.keepScreen != next.keepScreen) putBoolean("screen", next.keepScreen)
            if (before.burnInProtection != next.burnInProtection) putBoolean("burnInProtection", next.burnInProtection)
            if (before.forceHome != next.forceHome) putBoolean("forceHome", next.forceHome)
            if (before.vpnSharing != next.vpnSharing) putBoolean("vpnSharing", next.vpnSharing)
            if (before.frameworkGuardian != next.frameworkGuardian) putBoolean("frameworkGuardian", next.frameworkGuardian)
            Sharing.entries.forEach { mode ->
                if ((mode in before.desired) != (mode in next.desired)) putBoolean("want_${mode.type}", mode in next.desired)
                if ((mode in before.pendingStops) != (mode in next.pendingStops)) putBoolean("stop_${mode.type}", mode in next.pendingStops)
            }
        }.commit()) { "无法保存配置" }
    }
    internal fun guardianConfiguration(): JSONObject = guardianConfiguration(prefs.all)
    internal fun systemConfiguration(): JSONObject = prefs.all.let { values ->
        JSONObject().put("policy", guardianConfiguration(values)).put("forceHome", values["forceHome"] as? Boolean ?: false)
    }
    private fun guardianConfiguration(values: Map<String, *>): JSONObject {
        return JSONObject().put("schema", 1).put("user", android.os.Process.myUid() / 100000)
            .put("revision", values["controlRevision"] as? Long ?: 0).put("installedAt", installedAt)
            .put("enabled", values["enabled"] as? Boolean ?: true)
            .put("boot", values["boot"] as? Boolean ?: true)
            .put("framework", values["frameworkGuardian"] as? Boolean ?: true)
    }
    var originalHome: String
        get() = prefs.getString("originalHome", "").orEmpty()
        set(value) { check(prefs.edit().putString("originalHome", value).commit()) }
}
