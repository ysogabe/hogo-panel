package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockModelTest {
    private val day = 20_000L * 86_400_000L
    private fun at(hour: Int, minute: Int, second: Int = 0, ms: Int = 0) =
        day + hour * 3_600_000L + minute * 60_000L + second * 1000L + ms

    private fun started(hour: Int, minute: Int, second: Int, elapsed: Long = 1_000_000L) =
        ClockModel().also { it.update(at(hour, minute, second), elapsed) }

    @Test
    fun firstUpdateShowsCurrentTimeWithoutFlip() {
        val m = ClockModel()
        m.update(at(9, 59, 30), 1_000)
        assertArrayEquals(intArrayOf(0, 9, 5, 9), m.digits)
        assertFalse(m.isFlipping(1_000))
    }

    @Test
    fun onlyChangedDigitFlipsOnNormalMinute() {
        val m = started(12, 0, 59)
        m.update(at(12, 1, 0, 20), 1_000_000 + 1_020)
        assertArrayEquals(intArrayOf(1, 2, 0, 1), m.digits)
        val t = 1_000_000L + 1_020L + 100L
        assertNull(m.flipProgress(0, t))
        assertNull(m.flipProgress(1, t))
        assertNull(m.flipProgress(2, t))
        assertNotNull(m.flipProgress(3, t))
        assertEquals(0, m.previousDigits!![3])
    }

    @Test
    fun nineFiftyNineToTenFlipsAllDigits() {
        val m = started(9, 59, 59)
        m.update(at(10, 0, 0, 20), 1_000_000 + 1_020)
        assertArrayEquals(intArrayOf(1, 0, 0, 0), m.digits)
        for (i in 0..3) assertNotNull(m.flipProgress(i, 1_001_030L))
    }

    @Test
    fun midnightRollsOver() {
        val m = started(23, 59, 59)
        m.update(at(0, 0, 0, 20) + 86_400_000L, 1_000_000 + 1_020)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), m.digits)
        assertArrayEquals(intArrayOf(2, 3, 5, 9), m.previousDigits)
    }

    @Test
    fun flipEndsAfterDuration() {
        val m = started(12, 0, 59)
        m.update(at(12, 1, 0, 20), 1_001_020)
        assertTrue(m.isFlipping(1_001_020 + ClockMath.FLIP_DURATION_MS - 1))
        assertFalse(m.isFlipping(1_001_020 + ClockMath.FLIP_DURATION_MS))
        assertNull(m.flipProgress(3, 1_001_020 + ClockMath.FLIP_DURATION_MS + 10))
    }

    @Test
    fun wallClockJumpForwardSnapsWithoutFlip() {
        val m = started(12, 0, 30)
        m.update(at(12, 1, 30), 1_000_000 + 1_000)
        assertArrayEquals(intArrayOf(1, 2, 0, 1), m.digits)
        assertFalse(m.isFlipping(1_001_010))
    }

    @Test
    fun wallClockJumpBackwardSnapsWithoutFlip() {
        val m = started(12, 5, 30)
        m.update(at(12, 0, 30), 1_000_000 + 1_000)
        assertArrayEquals(intArrayOf(1, 2, 0, 0), m.digits)
        assertFalse(m.isFlipping(1_001_010))
    }

    @Test
    fun timezoneShiftSnapsWithoutFlip() {
        val m = started(12, 0, 30)
        m.update(at(12, 0, 31) + 9 * 3_600_000L, 1_000_000 + 1_000)
        assertArrayEquals(intArrayOf(2, 1, 0, 0), m.digits)
        assertFalse(m.isFlipping(1_001_010))
    }

    @Test
    fun skippedMinutesSnapEvenWhenClocksAgree() {
        val m = started(12, 0, 30)
        m.update(at(12, 3, 30), 1_000_000 + 180_000)
        assertArrayEquals(intArrayOf(1, 2, 0, 3), m.digits)
        assertFalse(m.isFlipping(1_180_010))
    }

    @Test
    fun snapDiscardsFlipInProgressAndResumesFromNow() {
        val m = started(12, 0, 59)
        m.update(at(12, 1, 0, 20), 1_001_020)
        m.snap(at(12, 1, 0, 100), 1_001_100)
        assertFalse(m.isFlipping(1_001_100))
        assertArrayEquals(intArrayOf(1, 2, 0, 1), m.digits)
    }

    @Test
    fun dateRollsOverWithMidnightDigitsAndFollowsTimezoneJump() {
        val m = started(23, 59, 59)
        val before = m.dateLabel
        m.update(at(23, 59, 59, 500), 1_000_500)
        assertEquals(before, m.dateLabel)
        m.update(at(0, 0, 0, 20) + 86_400_000L, 1_001_020)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), m.digits)
        assertEquals(ClockMath.dateLabelOf(at(0, 0) + 86_400_000L), m.dateLabel)
        assertTrue(m.dateLabel != before)
        // 時刻・タイムゾーンの大きな補正は日付も即時に現在へ合わせる（フリップなし）。
        m.update(at(0, 0, 30) - 9 * 3_600_000L, 1_031_020)
        assertEquals(ClockMath.dateLabelOf(at(0, 0, 30) - 9 * 3_600_000L), m.dateLabel)
        assertNull(m.previousDigits)
    }
}
