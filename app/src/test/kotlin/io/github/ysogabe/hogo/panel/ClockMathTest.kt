package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockMathTest {
    private fun local(hour: Int, minute: Int, second: Int = 0, ms: Int = 0, day: Long = 20_000L): Long =
        day * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L + ms

    @Test
    fun digitsAreZeroPaddedTwentyFourHour() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ClockMath.digitsOf(local(0, 0)))
        assertArrayEquals(intArrayOf(0, 9, 5, 9), ClockMath.digitsOf(local(9, 59)))
        assertArrayEquals(intArrayOf(1, 0, 0, 0), ClockMath.digitsOf(local(10, 0)))
        assertArrayEquals(intArrayOf(2, 3, 5, 9), ClockMath.digitsOf(local(23, 59, 59, 999)))
    }

    @Test
    fun digitsHandleNegativeMillis() {
        assertArrayEquals(intArrayOf(2, 3, 5, 9), ClockMath.digitsOf(-1L))
    }

    @Test
    fun tickWaitsForMinuteBoundaryPlusMargin() {
        assertEquals(1000L, ClockMath.nextTickDelayMs(local(12, 0, 10)))
        assertEquals(500L + ClockMath.BOUNDARY_MARGIN_MS, ClockMath.nextTickDelayMs(local(12, 0, 59, 500)))
        assertEquals(1000L, ClockMath.nextTickDelayMs(local(12, 0, 58, 500)))
    }

    @Test
    fun clockJumpUsesTwoSecondThreshold() {
        assertFalse(ClockMath.isClockJump(61_000, 60_000))
        assertFalse(ClockMath.isClockJump(62_000, 60_000))
        assertTrue(ClockMath.isClockJump(62_001, 60_000))
        assertTrue(ClockMath.isClockJump(-3_600_000, 1_000))
    }

    @Test
    fun clockFitsInsideScreenForAllMotion() {
        val scale = ClockMath.fitScale(960f, 480f)
        assertEquals(1f, scale, 0f)
        val max = ClockMath.maxOffsets(960f, 480f)
        assertEquals(48f, max[0], 0f)
        assertEquals(32f, max[1], 0f)
        val tight = ClockMath.maxOffsets(640f, 220f)
        assertEquals(7f, tight[0], 0f)
        assertEquals(2f, tight[1], 0f)
        assertEquals(0f, ClockMath.maxOffsets(500f, 100f)[0], 0f)
        var t = 0L
        while (t < 700_000L) {
            assertTrue(kotlin.math.abs(ClockMath.offsetX(t, max[0])) <= 48f)
            assertTrue(kotlin.math.abs(ClockMath.offsetY(t, max[1])) <= 32f)
            val s = ClockMath.shade(t)
            assertTrue(s >= ClockMath.SHADE_MIN - 1e-4f && s <= ClockMath.SHADE_MAX + 1e-4f)
            t += 997
        }
    }

    @Test
    fun clockSizeMatchesReferenceLayout() {
        assertEquals(626f, ClockMath.CLOCK_WIDTH, 0f)
    }

    @Test
    fun untilNextMinuteIsTimeToTheMinuteBoundary() {
        assertEquals(60_000L, ClockMath.untilNextMinuteMs(0L))
        assertEquals(1L, ClockMath.untilNextMinuteMs(59_999L))
        assertEquals(30_000L, ClockMath.untilNextMinuteMs(90_000L))
        assertEquals(1L, ClockMath.untilNextMinuteMs(-1L))
    }
}
