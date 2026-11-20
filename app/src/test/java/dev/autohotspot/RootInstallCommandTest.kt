package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class RootInstallCommandTest {
    @Test fun usesStdinWithoutAmbiguousDashOrInteractiveOrDowngradeFlags() {
        val command = RootInstallCommand.build(0, 123)
        assertArrayEquals(arrayOf("/system/bin/cmd", "package", "install", "-r", "--user", "0", "-S", "123"), command)
        assertFalse(command.any { it in setOf("-", "-d", "-g", "--bypass-low-target-sdk-block") })
    }
    @Test fun targetsTheRequestingAndroidUser() {
        val command = RootInstallCommand.build(10, 268435456)
        assertEquals("10", command[5])
        assertEquals("268435456", command[7])
    }
    @Test fun rejectsInvalidUserAndUnboundedSize() {
        assertThrows(IllegalArgumentException::class.java) { RootInstallCommand.build(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RootInstallCommand.build(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RootInstallCommand.build(0, APK_LIMIT + 1) }
    }
}
