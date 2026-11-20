package dev.autohotspot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object OriginalLauncher {
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    private fun usable(context: Context, component: ComponentName): Boolean = runCatching {
        val info = context.packageManager.getActivityInfo(component, 0)
        component.packageName != context.packageName && info.enabled && info.applicationInfo.enabled &&
            info.exported && !info.name.endsWith(".FallbackHome")
    }.getOrDefault(false)

    fun capture(context: Context, prefs: Preferences): ComponentName? {
        ComponentName.unflattenFromString(prefs.originalHome)?.takeIf { usable(context, it) }?.let { return it }
        val pm = context.packageManager
        val preferred = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val saved = preferred?.let { ComponentName(it.packageName, it.name) }?.takeIf { usable(context, it) }
        val candidates = pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }.filter { usable(context, ComponentName(it.packageName, it.name)) }
        val selected = saved ?: candidates.firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?.let { ComponentName(it.packageName, it.name) }
            ?: candidates.firstOrNull()?.let { ComponentName(it.packageName, it.name) }
        selected?.let { prefs.originalHome = it.flattenToString() }
        return selected
    }
    fun open(context: Context, prefs: Preferences) {
        val component = capture(context, prefs) ?: error("未找到原桌面")
        // Explicit target bypasses our optional HOME resolver hook without changing the default HOME.
        context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }
}
