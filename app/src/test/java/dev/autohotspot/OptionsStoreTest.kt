package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class OptionsStoreTest {
    @Test fun allSharingIntentSurvivesACompletelyNewStore() {
        val disk = AtomicReference(Options())
        fun open() = OptionsStore(disk::get) { _, next -> disk.set(next) }
        val first = open()
        first.update { it.withSharing(Sharing.USB, true) }
        first.update { it.withSharing(Sharing.ETHERNET, true) }
        val restarted = open()
        assertEquals(setOf(Sharing.WIFI, Sharing.USB, Sharing.ETHERNET), restarted.read().desired)
        assertTrue(restarted.read().pendingStops.isEmpty())
    }
    @Test fun settingsAndGuardianSeeTheSameStateWithoutAnActivityRecreation() {
        var disk = Options()
        val store = OptionsStore({ disk }) { _, next -> disk = next }
        val display = store.state
        store.update { it.withSharing(Sharing.ETHERNET, true) }
        assertTrue(Sharing.ETHERNET in display.value.desired)
        store.update { it.copy(pendingStops = emptySet()) }
        assertTrue(Sharing.ETHERNET in display.value.desired)
        assertEquals(disk, display.value)
    }
    @Test fun aFailedDurableWriteDoesNotPretendTheSelectionWasSaved() {
        val store = OptionsStore({ Options() }) { _, _ -> throw IOException("storage unavailable") }
        assertThrows(IOException::class.java) { store.update { it.withSharing(Sharing.USB, true) } }
        assertFalse(Sharing.USB in store.state.value.desired)
    }
    @Test fun serviceCleanupAndTwoSelectionsCannotOverwriteEachOther() {
        repeat(30) {
            val disk = AtomicReference(Options(desired = emptySet(), pendingStops = setOf(Sharing.WIFI)))
            val store = OptionsStore(disk::get) { _, next -> disk.set(next) }
            val go = CountDownLatch(1)
            val writes = listOf<(Options) -> Options>(
                { it.withSharing(Sharing.USB, true) },
                { it.withSharing(Sharing.ETHERNET, true) },
                { it.copy(pendingStops = it.pendingStops - Sharing.WIFI) }
            ).map { block -> Thread { go.await(); store.update(block) }.also { it.start() } }
            go.countDown(); writes.forEach { it.join(2000); assertFalse(it.isAlive) }
            assertEquals(setOf(Sharing.USB, Sharing.ETHERNET), disk.get().desired)
            assertTrue(disk.get().pendingStops.isEmpty())
            assertEquals(disk.get(), store.state.value)
        }
    }
    @Test fun reenablingCancelsOnlyThatModesQueuedStop() {
        val options = Options(desired = setOf(Sharing.ETHERNET), pendingStops = setOf(Sharing.USB, Sharing.WIFI))
            .withSharing(Sharing.USB, true)
        assertEquals(setOf(Sharing.USB, Sharing.ETHERNET), options.desired)
        assertEquals(setOf(Sharing.WIFI), options.pendingStops)
    }
    @Test fun reloadingPersistedSelectionUpdatesTheDisplayButDoesNotWriteAnything() {
        var disk = Options(); var writes = 0
        val store = OptionsStore({ disk }) { _, next -> writes++; disk = next }
        disk = disk.withSharing(Sharing.USB, true).withSharing(Sharing.ETHERNET, true)
        store.refresh()
        assertEquals(disk, store.state.value)
        assertEquals(0, writes)
    }
    @Test fun anEthernetLinkIsDetectedBeforeItBecomesAnActiveSharedInterface() {
        val json = JSONObject("""{"active":[],"clients":null,"interfaces":[{"name":"eth0","rx":0,"tx":0,"carrier":true}],"ethernetSupported":true}""")
        val snapshot = Snapshot.parse(json)
        assertTrue(snapshot.ethernetConnected)
        assertTrue(snapshot.active.isEmpty())
        assertTrue(snapshot.nets.isEmpty()) // Link detection must not inflate the traffic counters.
    }
}
