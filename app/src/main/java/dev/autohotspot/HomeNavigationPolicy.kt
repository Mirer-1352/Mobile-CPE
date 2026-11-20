package dev.autohotspot

internal object HomeNavigationPolicy {
    fun redirects(homeIntent: Boolean, foreground: Boolean, now: Long, lastPause: Long, recreated: Boolean = false): Boolean {
        if (recreated || !homeIntent) return false
        return foreground || lastPause >= 0 && now - lastPause in 0..1500
    }
}
