package dev.autohotspot

/** Accessed only from the connection's main-thread coroutine scope. */
class ScopeRequestGate {
    var pending = false
        private set
    private var attempted = false
    fun begin(granted: Set<String>, foreground: Boolean, capable: Boolean, manual: Boolean = false,
              required: Set<String> = setOf(SYSTEM_SCOPE)): Boolean {
        if (granted.containsAll(required)) { reconnect(); return false }
        if (!foreground || !capable || pending || (attempted && !manual)) return false
        attempted = true
        pending = true
        return true
    }
    fun finish() { pending = false }
    fun reconnect() { pending = false; attempted = false }
    companion object { const val SYSTEM_SCOPE = "system" }
}
