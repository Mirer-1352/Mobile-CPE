package dev.autohotspot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CellularInfoTest {
    private fun carrier(rat: Int, band: Int, channel: Int, role: Int = 2) = JSONObject()
        .put("rat", rat).put("band", band).put("channel", channel).put("role", role).put("downKhz", 20000).put("upKhz", -1)
    private fun radio(vararg rows: JSONObject) = JSONObject().put("subscription", 7).put("slot", 0)
        .put("channelsKnown", true).put("channels", JSONArray(rows.toList())).put("channelStatus", "OK")
    @Test fun nsaDualConnectivityAloneIsNotCarrierAggregation() {
        val value = CellularInfo.parse(radio(carrier(13, 3, 1300, 1), carrier(20, 78, 640000)), 100000)
        assertEquals("LTE+NR 双连接", value.aggregation)
        assertFalse(value.aggregation.contains("2CC"))
    }
    @Test fun lteAndNrAggregationAreCountedSeparately() {
        val value = CellularInfo.parse(radio(carrier(13, 3, 1300, 1), carrier(13, 1, 300),
            carrier(20, 78, 640000), carrier(20, 78, 650000)), 100000)
        assertEquals("LTE 2CC + NR 2CC + LTE+NR 双连接", value.aggregation)
    }
    @Test fun multipleEntriesForTheSameFrequencyDoNotInventExtraCarriers() {
        val value = CellularInfo.parse(radio(carrier(13, 3, 1300, 1), carrier(19, 3, 1300), carrier(13, 3, 1450)), 100000)
        assertEquals(2, value.carriers.size)
        assertEquals("LTE 2CC", value.aggregation)
    }
    @Test fun overlappingNrBandLabelsForOneFrequencyRemainOnePrimaryCarrier() {
        val value = CellularInfo.parse(radio(carrier(20, 77, 640000), carrier(20, 78, 640000, 1)), 100000)
        assertEquals(1, value.carriers.size)
        assertEquals(1, value.carriers.single().role)
        assertEquals("NR 1CC", value.aggregation)
    }
    @Test fun neighbouringOrUnknownConnectionStatusNeverCountsAsServing() {
        val value = CellularInfo.parse(radio(carrier(20, 78, 640000, 1), carrier(20, 78, 650000, 0), carrier(20, 78, 660000, -1)), 100000)
        assertEquals("NR 1CC", value.aggregation)
    }
    @Test fun legacyRowsWithoutFrequenciesAreNotCollapsedIntoOneCarrier() {
        val value = CellularInfo.parse(radio(carrier(-1, -1, -1, 1), carrier(-1, -1, -1), carrier(-1, -1, -1)), 100000)
        assertEquals("未知制式 3CC", value.aggregation)
    }
    @Test fun unknownOrClearedObservationsNeverReusePreviousAggregation() {
        val value = CellularInfo.parse(radio(carrier(13, 3, 1300)).put("channelsKnown", false), 100000)
        assertEquals("CA —", value.aggregation)
        assertTrue(value.carriers.isEmpty())
        assertEquals("CA —", CellularInfo.parse(JSONObject().put("subscription", -1), 100000).aggregation)
    }
    @Test fun convertsKbitToDecimalMegabytesAndRetainsTheAmbrKind() {
        val info = JSONObject().put("subscription", 7).put("observed", 100000).put("downKbps", 1000000)
            .put("upKbps", 100000).put("kind", "Session-AMBR")
        val value = CellularInfo.parse(radio().put("ambr", info), 100001)
        assertEquals("签约 ↓125.00 ↑12.50 M/s", value.limit)
        assertEquals("Session-AMBR", value.ambr?.kind)
    }
    @Test fun staleWrongSimFutureAndUnrecognisedRatesAreRejected() {
        val base = JSONObject().put("subscription", 7).put("observed", 100000).put("downKbps", 1000000)
            .put("upKbps", 100000).put("kind", "APN-AMBR")
        assertNull(CellularInfo.parse(radio().put("ambr", base), 190001).ambr)
        assertNull(CellularInfo.parse(radio().put("ambr", JSONObject(base.toString()).put("subscription", 8)), 100001).ambr)
        assertNull(CellularInfo.parse(radio().put("ambr", base), 99999).ambr)
        assertNull(CellularInfo.parse(radio().put("ambr", JSONObject(base.toString()).put("kind", "LCE")), 100001).ambr)
        assertNull(CellularInfo.parse(radio().put("ambr", JSONObject(base.toString()).put("downKbps", 0)), 100001).ambr)
    }
    @Test fun bandwidthSentinelsAreUnknownAndRealValuesUseMhz() {
        val value = CellularInfo.parse(radio(carrier(20, 78, 640000, 1).put("downKhz", 100000).put("upKhz", Int.MAX_VALUE)), 100000)
        assertEquals("主载波 NR n78 · ↓100 ↑— MHz", value.carriers.single().detail)
    }
}
