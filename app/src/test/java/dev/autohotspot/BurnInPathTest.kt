package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BurnInPathTest {
    @Test fun cyclesCoverEveryPositionWithinTheReservedMargin() {
        val path = BurnInPath(Random(42))
        repeat(10) {
            val cycle = List(81) { path.next() }
            assertEquals(81, cycle.toSet().size)
            assertTrue(cycle.all { it.x in -16..16 && it.y in -16..16 && it.x % 4 == 0 && it.y % 4 == 0 })
        }
    }
    @Test fun initialMoveAndCycleBoundariesNeverStayOnTheSamePixels() {
        repeat(100) { seed ->
            val path = BurnInPath(Random(seed))
            var previous = PixelShift(0, 0)
            repeat(250) {
                val next = path.next()
                assertNotEquals(previous, next)
                previous = next
            }
        }
    }
}
