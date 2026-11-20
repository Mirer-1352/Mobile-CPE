package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class RouterLogicTest {
    @Test fun forwardedDownloadIsCountedOnceAndAckIsSeparate() {
        val meter = RateMeter()
        val active = setOf("rndis0")
        val baseline = listOf(NetInterface("rmnet_data2", 0, 0, true), NetInterface("rndis0", 0, 0, true))
        meter.sample(TrafficAccounting.downstream(baseline, active), 1000)
        val forwarded = listOf(NetInterface("rmnet_data2", 1_000_000, 20_000, true), NetInterface("rndis0", 20_000, 1_000_000, true))
        val rate = meter.sample(TrafficAccounting.downstream(forwarded, active), 2000)
        assertEquals(1_000_000.0, rate.first!!, .01)
        assertEquals(20_000.0, rate.second!!, .01)
    }
    @Test fun forwardedUploadIsNotAddedAgainAtCellularEgress() {
        val meter = RateMeter()
        val active = setOf("rndis0")
        val baseline = listOf(NetInterface("rmnet_data2", 0, 0, true), NetInterface("rndis0", 0, 0, true))
        meter.sample(TrafficAccounting.downstream(baseline, active), 1000)
        val forwarded = listOf(NetInterface("rmnet_data2", 20_000, 1_000_000, true), NetInterface("rndis0", 1_000_000, 20_000, true))
        val rate = meter.sample(TrafficAccounting.downstream(forwarded, active), 2000)
        assertEquals(20_000.0, rate.first!!, .01)
        assertEquals(1_000_000.0, rate.second!!, .01)
    }
    @Test fun bridgeAndMemberPortsAreNotSummedTogether() {
        val all = listOf(NetInterface("ap_br_wlan0", 100, 1000, true),
            NetInterface("wlan1", 100, 1000, true, "ap_br_wlan0"), NetInterface("rmnet_data2", 1000, 100, true))
        assertEquals(listOf("ap_br_wlan0"), TrafficAccounting.downstream(all, all.map { it.name }.toSet() - "rmnet_data2").map { it.name })
        assertEquals(listOf("wlan1"), TrafficAccounting.downstream(all, setOf("wlan1")).map { it.name })
    }
    @Test fun independentDownstreamsAreAggregatedWithoutUpstreamOrLoopback() {
        val all = listOf(NetInterface("wlan1", 10, 100, true), NetInterface("rndis0", 20, 200, true),
            NetInterface("lo", 99999, 99999, true), NetInterface("rmnet_data2", 300, 30, true))
        val selected = TrafficAccounting.downstream(all, setOf("wlan1", "rndis0"))
        assertEquals(300L, selected.sumOf { it.tx })
        assertEquals(30L, selected.sumOf { it.rx })
    }
    @Test fun duplicateRecordsDoNotDoubleTheRate() {
        val meter = RateMeter()
        meter.sample(listOf(NetInterface("rndis0", 0, 0, true)), 1000)
        val one = NetInterface("rndis0", 100, 1000, true)
        val rate = meter.sample(listOf(one, one), 2000)
        assertEquals(1000.0, rate.first!!, .01)
        assertEquals(100.0, rate.second!!, .01)
    }
    @Test fun ratesUseDownstreamTxForDownloadAndRxForUpload() {
        val meter = RateMeter()
        assertNull(meter.sample(listOf(NetInterface("wlan1", 100, 200, true)), 1000).first)
        val next = meter.sample(listOf(NetInterface("wlan1", 2100, 6200, true)), 3000)
        assertEquals(3000.0, next.first!!, 0.01)
        assertEquals(1000.0, next.second!!, 0.01)
    }
    @Test fun resetsAndInterfaceChangesNeverProduceSpikes() {
        val meter = RateMeter()
        meter.sample(listOf(NetInterface("rndis0", 9000, 9000, true)), 1000)
        assertNull(meter.sample(listOf(NetInterface("rndis0", 10, 20, true)), 4000).first)
        assertNull(meter.sample(listOf(NetInterface("wlan1", 999999, 999999, true)), 7000).first)
        assertNull(meter.sample(emptyList(), 10000).first)
    }
    @Test fun countersUnavailableDoNotLookLikeZeroTraffic() {
        val meter = RateMeter()
        meter.sample(listOf(NetInterface("eth0", -1, -1, true)), 1000)
        assertNull(meter.sample(listOf(NetInterface("eth0", -1, -1, true)), 4000).first)
    }
    @Test fun retriesBackOffAndResetAfterRecovery() {
        val policy = RetryPolicy()
        assertTrue(policy.ready(Sharing.USB, 0))
        policy.failed(Sharing.USB, 0)
        assertFalse(policy.ready(Sharing.USB, 2999))
        assertTrue(policy.ready(Sharing.USB, 3000))
        policy.failed(Sharing.USB, 3000)
        assertFalse(policy.ready(Sharing.USB, 8999))
        assertTrue(policy.ready(Sharing.USB, 9000))
        assertTrue(policy.ready(Sharing.WIFI, 0))
        policy.reset(Sharing.USB)
        assertTrue(policy.ready(Sharing.USB, 3001))
    }
    @Test fun repeatedFailureIsCappedAtOneMinute() {
        val policy = RetryPolicy()
        repeat(20) { policy.failed(Sharing.ETHERNET, 1000) }
        assertFalse(policy.ready(Sharing.ETHERNET, 60999))
        assertTrue(policy.ready(Sharing.ETHERNET, 61000))
    }
}
