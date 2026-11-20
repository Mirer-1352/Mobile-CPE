package dev.autohotspot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsbRoleTest {
    private fun snapshot(extra: String = "") = Snapshot.parse(JSONObject(
        """{"active":[],"clients":null,"interfaces":[],"usbConnected":true$extra}"""))

    @Test fun aHostAdapterOverridesStaleGadgetAndConnectedBroadcasts() {
        val host = snapshot(""", "usbHost":true""")
        assertFalse(host.usbReady(true))
        assertTrue(host.usbHostOnly())
        assertFalse(snapshot().usbReady(true, broadcastHost = true))
    }

    @Test fun authoritativeDataRoleOverridesLegacyConnectedFlags() {
        assertFalse(snapshot(""", "usbDevice":false""").usbReady(true))
        assertTrue(snapshot(""", "usbDevice":true""").usbReady(false))
    }

    @Test fun separateHostAndDevicePortsCanCoexist() {
        val both = snapshot(""", "usbHost":true, "usbDevice":true""")
        assertTrue(both.usbReady(true, broadcastHost = true))
        assertFalse(both.usbHostOnly(broadcastHost = true))
    }

    @Test fun aLegacyDeviceWithoutRoleApisStillUsesGadgetOrBroadcastEvidence() {
        assertTrue(snapshot().usbReady(false))
        val legacy = Snapshot(emptySet(), null, emptyList(), false, true)
        assertTrue(legacy.usbReady(true))
        assertFalse(legacy.usbReady(false))
    }
}
