package dev.autohotspot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TetherSnapshotTest {
    private fun parse(extra: String) = Snapshot.parse(JSONObject("""{"active":["usb0"],"clients":null,"interfaces":[{"name":"usb0","rx":12,"tx":24,"carrier":true}],"ethernetSupported":true,$extra}"""))

    @Test fun platformTypeOverridesNamesAndUsbRegex() {
        val read = parse(""""interfaceTypes":{"usb0":5},"usbRegex":["usb.*"]""")
        assertEquals(setOf(Sharing.ETHERNET), read.active)
        assertTrue(read.ethernetPresent)
        assertTrue(read.ethernetConnected)
        assertEquals(1, read.nets.size)
    }

    @Test fun ethernetManagerNamesWorkOnOlderStringCallbacks() {
        assertEquals(setOf(Sharing.ETHERNET), parse(""""ethernetInterfaces":["usb0"]""").active)
    }

    @Test fun aKnownDifferentNativeTypeMustNotFallBackToUsbByName() {
        assertTrue(parse(""""interfaceTypes":{"usb0":2}""").active.isEmpty())
    }

    @Test fun ncmIsOneUsbChoiceAndKnownCapabilitiesAreNotSdkGuesses() {
        val read = parse(""""interfaceTypes":{"usb0":4},"supportedTypes":[0,4]""")
        assertEquals(setOf(Sharing.USB), read.active)
        assertEquals(true, read.supports(Sharing.USB))
        assertEquals(false, read.supports(Sharing.ETHERNET))
        assertNull(parse(""""supportedTypes":null""").supports(Sharing.ETHERNET))
    }

    @Test fun nativeErrorKeepsItsCodeNameInterfaceAndSequence() {
        val read = parse(""""interfaceErrors":[{"interface":"usb0","type":5,"code":14,"name":"TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION","sequence":10}]""")
        val error = read.failures.single()
        assertEquals(Sharing.ETHERNET, error.mode)
        assertEquals(SharingFailureKind.PERMISSION, error.failure.kind)
        assertEquals(14, error.failure.code)
        assertEquals(10, error.sequence)
    }
}
