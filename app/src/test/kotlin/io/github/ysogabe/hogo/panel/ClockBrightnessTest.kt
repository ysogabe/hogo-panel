package io.github.ysogabe.hogo.panel

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockBrightnessTest {
    private fun at(h: Int, m: Int = 0, s: Int = 0, ms: Int = 0, dayOffset: Long = 20_000L): Long =
        dayOffset * 86_400_000L + h * 3_600_000L + m * 60_000L + s * 1_000L + ms

    @Test
    fun periodBoundaries() {
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(at(0)))
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(at(5, 59, 59, 999)))
        assertEquals(ClockBrightness.DAY, ClockBrightness.levelAt(at(6)))
        assertEquals(ClockBrightness.DAY, ClockBrightness.levelAt(at(17, 59, 59, 999)))
        assertEquals(ClockBrightness.EVENING, ClockBrightness.levelAt(at(18)))
        assertEquals(ClockBrightness.EVENING, ClockBrightness.levelAt(at(21, 59, 59, 999)))
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(at(22)))
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(at(23, 59, 59, 999)))
        assertEquals(0.60f, ClockBrightness.DAY)
        assertEquals(0.20f, ClockBrightness.EVENING)
        assertEquals(0.05f, ClockBrightness.NIGHT)
    }

    @Test
    fun negativeLocalMillisStillMapsToHourOfDay() {
        // 1969-12-31 23:00 は夜、同 06:00 は日中。
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(-3_600_000L))
        assertEquals(ClockBrightness.DAY, ClockBrightness.levelAt(-18 * 3_600_000L))
    }

    @Test
    fun sameInstantDiffersByTimeZone() {
        val wall = at(5) // UTC 05:00
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo").getOffset(wall) // 14:00
        val la = TimeZone.getTimeZone("America/Los_Angeles").getOffset(wall) // 前日 22:00
        assertEquals(ClockBrightness.DAY, ClockBrightness.levelAt(ClockBrightness.localMillis(wall, tokyo)))
        assertEquals(ClockBrightness.NIGHT, ClockBrightness.levelAt(ClockBrightness.localMillis(wall, la)))
    }
}
