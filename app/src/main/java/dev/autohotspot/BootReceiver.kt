package dev.autohotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val options = Preferences.get(context).read()
        if (StartupPolicy.shouldStart(intent.action, options.boot, options.enabled)) runCatching { HotspotService.start(context) }
            .onFailure { Router.event("系统阻止开机守护：${it.message}") }
    }
}
