package dev.autohotspot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sole owner of user intent. Hardware snapshots must never be written into this store. */
internal class OptionsStore(private val load: () -> Options, private val persist: (Options, Options) -> Unit) {
    private val lock = Any()
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<Options> = mutable.asStateFlow()
    fun read(): Options = mutable.value
    fun refresh() = synchronized(lock) { mutable.value = load() }
    fun update(block: (Options) -> Options): Options = synchronized(lock) {
        val before = mutable.value
        val next = block(before)
        if (next != before) {
            persist(before, next) // Publish only after durable persistence succeeds.
            mutable.value = next
        }
        next
    }
}

internal fun Options.withSharing(mode: Sharing, on: Boolean): Options =
    if (on) copy(enabled = true, desired = desired + mode, pendingStops = pendingStops - mode)
    else copy(desired = desired - mode, pendingStops = pendingStops + mode)
