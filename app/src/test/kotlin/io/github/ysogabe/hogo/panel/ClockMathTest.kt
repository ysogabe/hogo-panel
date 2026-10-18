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
    fun clockGroupFitsInsideScreenForAllMotion() {
        assertEquals(1f, ClockMath.fitScale(960f, 480f), 0f)
        // 時刻+日付だけなら従来どおりの移動量。札が付くと縦の余白が減るので移動量も減る。
        val plain = ClockMath.maxOffsets(960f, 480f)
        assertEquals(48f, plain[0], 0f)
        assertEquals(32f, plain[1], 0f)
        val withBadges = ClockMath.maxOffsets(960f, 480f, ClockMath.CLOCK_WIDTH, ClockMath.groupHeight(true))
        assertTrue(withBadges[1] in 0f..32f && withBadges[1] < plain[1])
        assertEquals(0f, ClockMath.maxOffsets(500f, 100f)[0], 0f)
        for (hasBadges in listOf(false, true)) {
            val h = ClockMath.groupHeight(hasBadges)
            val max = ClockMath.maxOffsets(960f, 480f, ClockMath.CLOCK_WIDTH, h)
            var t = 0L
            while (t < 700_000L) {
                val top = (480f - h) / 2f + ClockMath.offsetY(t, max[1])
                val left = (960f - ClockMath.CLOCK_WIDTH) / 2f + ClockMath.offsetX(t, max[0])
                assertTrue(top >= 0f && top + h <= 480f)
                assertTrue(left >= 0f && left + ClockMath.CLOCK_WIDTH <= 960f)
                val s = ClockMath.shade(t)
                assertTrue(s >= ClockMath.SHADE_MIN - 1e-4f && s <= ClockMath.SHADE_MAX + 1e-4f)
                t += 997
            }
        }
        // 札が画面幅に近い場合も、まとまりの幅で移動量が減る。
        assertEquals(10f, ClockMath.maxOffsets(960f, 480f, 940f, 300f)[0], 0f)
    }

    @Test
    fun clockIsAboutOnePointThreeTimesTheReferenceLayout() {
        assertEquals(814f, ClockMath.CLOCK_WIDTH, 0f)
        assertEquals(281f, ClockMath.CLOCK_HEIGHT, 0f)
        assertEquals(626f * 1.3f, ClockMath.CLOCK_WIDTH, 1f)
    }

    @Test
    fun dateLabelUsesCalendarYearMonthDayAndJapaneseWeekday() {
        fun label(year: Int, month: Int, day: Int, hour: Int = 12): String =
            ClockMath.dateLabelOf(java.time.LocalDate.of(year, month, day).toEpochDay() * 86_400_000L + hour * 3_600_000L)
        assertEquals("2026/10/19（月）", label(2026, 10, 19))
        assertEquals("2026/10/25（日）", label(2026, 10, 25))
        // 年末は暦年（YYYY の week year ではない）、年初・うるう日・月末も暦日。
        assertEquals("2024/12/30（月）", label(2024, 12, 30))
        assertEquals("2024/12/31（火）", label(2024, 12, 31))
        assertEquals("2025/01/01（水）", label(2025, 1, 1))
        assertEquals("2028/02/29（火）", label(2028, 2, 29))
        assertEquals("2026/03/01（日）", label(2026, 3, 1, 0))
        assertEquals("2026/02/28（土）", ClockMath.dateLabelOf(local(23, 59, 59, 999, day = java.time.LocalDate.of(2026, 2, 28).toEpochDay())))
        assertEquals("1969/12/31（水）", ClockMath.dateLabelOf(-1L))
    }

    @Test
    fun untilNextMinuteIsTimeToTheMinuteBoundary() {
        assertEquals(60_000L, ClockMath.untilNextMinuteMs(0L))
        assertEquals(1L, ClockMath.untilNextMinuteMs(59_999L))
        assertEquals(30_000L, ClockMath.untilNextMinuteMs(90_000L))
        assertEquals(1L, ClockMath.untilNextMinuteMs(-1L))
    }
}
