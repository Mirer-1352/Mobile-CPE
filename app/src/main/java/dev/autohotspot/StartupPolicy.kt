package dev.autohotspot

internal object StartupPolicy {
    fun shouldStart(action: String?, boot: Boolean, enabled: Boolean): Boolean = enabled &&
        (action == "android.intent.action.MY_PACKAGE_REPLACED" ||
            (boot && action in setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED")))
}
