package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class AmbrProtocolTest {
    @Test fun epsReturnsAmbrNotTheAdjacentBearerMbr() {
        assertArrayEquals(longArrayOf(1000000, 100000), AmbrProtocol.limit("+CGEQOSRDP: 2,9,,,42000,21000,1000000,100000\r\nOK\r\n", 2, false))
    }
    @Test fun saUsesSessionAmbrAndItsOwnResponsePrefix() {
        val response = "+C5GQOSRDP: 2,9,,,,,2000000,200000,1000"
        assertArrayEquals(longArrayOf(2000000, 200000), AmbrProtocol.limit(response, 2, true))
        assertNull(AmbrProtocol.limit(response, 2, false))
    }
    @Test fun absentMalformedAndUnspecifiedAmbrNeverFallBackToMbr() {
        for (response in listOf("+CGEQOSRDP: 2,9,,,1000000,100000", "+CGEQOSRDP: 2,9,,,,,0,0", "+CGEQOSRDP: 2,9,,,,,?,100", "+CGEQOSRDP: 2,9,,,,,-1,100"))
            assertNull(response, AmbrProtocol.limit(response, 2, false))
    }
    @Test fun repeatedBearersMustAgreeAndWrongCidIsNotUsed() {
        val one = "+CGEQOSRDP: 2,9,,,,,1000000,100000"
        assertArrayEquals(longArrayOf(1000000, 100000), AmbrProtocol.limit("$one\r\n$one", 2, false))
        assertNull(AmbrProtocol.limit("$one\r\n+CGEQOSRDP: 2,8,,,,,2000000,100000", 2, false))
        assertNull(AmbrProtocol.limit(one, 3, false))
    }
    @Test fun selectsOnlyTheActiveContextMatchingTheFrameworkDefaultApn() {
        val definitions = "+CGDCONT: 1,\"IPV4V6\",\"ims\",\"0.0.0.0\"\r\n+CGDCONT: 2,\"IPV4V6\",\"internet.invalid\",\"0.0.0.0\""
        assertEquals(2, AmbrProtocol.context(definitions, "+CGACT: 1,1\r\n+CGACT: 2,1", "internet.invalid"))
        assertEquals(-1, AmbrProtocol.context(definitions, "+CGACT: 1,1\r\n+CGACT: 2,0", "internet.invalid"))
        assertEquals(-1, AmbrProtocol.context(definitions, "+CGACT: 2,1", ""))
        assertEquals(-1, AmbrProtocol.context(definitions, "+CGACT: 2,1", "other.invalid"))
    }
    @Test fun ambiguousContextsAreNotResolvedByHighestSpeedOrFirstRow() {
        val definitions = "+CGDCONT: 2,\"IP\",\"internet.invalid\"\r\n+CGDCONT: 3,\"IPV6\",\"internet.invalid\""
        assertEquals(-2, AmbrProtocol.context(definitions, "+CGACT: 2,1\r\n+CGACT: 3,1", "internet.invalid"))
    }
    @Test fun preservesQuotedCommasAndRejectsAnIncompleteQuotedRow() {
        assertEquals(listOf("1", "a,b", "", "3"), AmbrProtocol.csv("1,\"a,b\",,3"))
        assertTrue(AmbrProtocol.csv("1,\"unterminated").isEmpty())
    }
    @Test fun invalidCidCannotBecomeAnInjectedCommand() {
        assertEquals(-1, AmbrProtocol.context("+CGDCONT: 2;AT+CFUN=0,\"IP\",\"internet.invalid\"", "+CGACT: 2,1", "internet.invalid"))
    }
    private fun transcript(stack: Int = 4, access: Int = 11) = linkedMapOf(
        "AT" to "OK", "AT+ESUO?" to "+ESUO: $stack\r\nOK", "AT+COPS?" to "+COPS: 0,2,\"00101\",$access\r\nOK",
        "AT+CGACT?" to "+CGACT: 2,1\r\nOK", "AT+CGDCONT?" to "+CGDCONT: 2,\"IPV4V6\",\"internet.invalid\"\r\nOK",
        "AT+C5GQOSRDP=2" to "+C5GQOSRDP: 2,9,,,,,2000000,200000\r\nOK",
        "AT+CGEQOSRDP=2" to "+CGEQOSRDP: 2,9,,,,,1000000,100000\r\nOK")
    @Test fun completeQueryUsesSaSessionAmbrWithoutAnyModemSettingCommand() {
        val values = transcript(); val commands = mutableListOf<String>()
        val value = AmbrProtocol.read(0, "internet.invalid") { command -> commands.add(command); values.getValue(command) }
        assertEquals("Session-AMBR", value.kind)
        assertEquals(2000000L, value.downKbps)
        assertEquals(listOf("AT", "AT+ESUO?", "AT+COPS?", "AT+CGACT?", "AT+CGDCONT?", "AT+C5GQOSRDP=2"), commands)
    }
    @Test fun mismatchedSimAbortsBeforeReadingAnotherSimsContexts() {
        val values = transcript(stack = 4); val commands = mutableListOf<String>()
        try {
            AmbrProtocol.read(1, "internet.invalid") { command -> commands.add(command); values.getValue(command) }
            fail("Wrong SIM must not be queried")
        } catch (expected: AmbrProtocol.Unavailable) { assertEquals("SIM_UNCONFIRMED", expected.status) }
        assertEquals(listOf("AT", "AT+ESUO?"), commands)
    }
    @Test fun nsaReadsEpsAmbrWithoutCallingTheSaCommand() {
        val values = transcript(access = 13); val commands = mutableListOf<String>()
        val value = AmbrProtocol.read(0, "internet.invalid") { command -> commands.add(command); values.getValue(command) }
        assertEquals("APN-AMBR", value.kind)
        assertEquals(1000000L, value.downKbps)
        assertFalse(commands.contains("AT+C5GQOSRDP=2"))
    }
}
