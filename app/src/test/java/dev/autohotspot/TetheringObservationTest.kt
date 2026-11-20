package dev.autohotspot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TetheringObservationTest {
    @Test fun callbackEmptyOverridesAnOldLegacyActiveList() {
        val state = TetheringObservation()
        assertEquals("legacy", state.snapshot(arrayOf("eth0")).getString("stateSource"))
        state.interfaces("active", emptyMap())
        val read = state.snapshot(arrayOf("eth0"))
        assertEquals("callback", read.getString("stateSource"))
        assertEquals(0, read.getJSONArray("active").length())
    }

    @Test fun legacyAndTypedCallbacksCanCoexistWithoutLosingTypes() {
        val state = TetheringObservation()
        state.interfaces("available", mapOf("usb0" to 5))
        state.interfaces("active", mapOf("usb0" to -1))
        assertEquals(5, state.snapshot(emptyArray()).getJSONObject("interfaceTypes").getInt("usb0"))
        state.interfaces("active", mapOf("usb0" to 1))
        state.interfaces("active", mapOf("usb0" to -1))
        assertEquals(1, state.snapshot(emptyArray()).getJSONObject("interfaceTypes").getInt("usb0"))
    }

    @Test fun unsupportedAndNotYetKnownAreDifferent() {
        val state = TetheringObservation()
        assertNull(state.supports(5))
        assertTrue(state.snapshot(emptyArray()).isNull("supportedTypes"))
        state.supported(setOf(0, 1))
        assertEquals(false, state.supports(5))
        state.overall(false)
        assertEquals(0, state.snapshot(emptyArray()).getJSONArray("supportedTypes").length())
    }

    @Test fun repeatedErrorsHaveDistinctEventsAndSuccessClearsTheError() {
        val state = TetheringObservation()
        state.error("eth9", 5, 14, "TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION")
        val before = state.snapshot(emptyArray()).getJSONArray("interfaceErrors").getJSONObject(0).getLong("sequence")
        state.error("eth9", 5, 14, "TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION")
        assertTrue(state.snapshot(emptyArray()).getJSONArray("interfaceErrors").getJSONObject(0).getLong("sequence") > before)
        state.error("eth9", 5, 0, "TETHER_ERROR_NO_ERROR")
        assertEquals(0, state.snapshot(emptyArray()).getJSONArray("interfaceErrors").length())
    }

    @Test fun upstreamAbsenceIsKnownOnlyAfterTheFirstCallback() {
        val state = TetheringObservation()
        assertFalse(state.snapshot(emptyArray()).getBoolean("upstreamKnown"))
        state.upstream(null)
        assertTrue(state.snapshot(emptyArray()).getBoolean("upstreamKnown"))
        state.upstream(JSONObject().put("network", "100"))
        val snapshot = state.snapshot(emptyArray())
        snapshot.getJSONObject("upstream").put("network", "changed")
        assertEquals("100", state.snapshot(emptyArray()).getJSONObject("upstream").getString("network"))
    }
}
