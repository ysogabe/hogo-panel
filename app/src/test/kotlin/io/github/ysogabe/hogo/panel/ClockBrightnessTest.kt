package io.github.ysogabe.hogo.panel

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private class Fixture(var local: Long) {
        val applied = mutableListOf<Float?>()
        var pending: Long? = null
        var scheduleCalls = 0
        val keeper = ClockBrightnessKeeper(
            localMillis = { local },
            apply = { applied += it },
            schedule = { pending = it; scheduleCalls++ },
            cancel = { pending = null },
        )
    }

    @Test
    fun startAppliesCurrentLevelAndSchedulesNextMinute() {
        val f = Fixture(at(12, 0, 30))
        f.keeper.start()
        assertEquals(listOf<Float?>(ClockBrightness.DAY), f.applied)
        assertEquals(30_000L + ClockMath.BOUNDARY_MARGIN_MS, f.pending)
    }

    @Test
    fun crossingBoundariesWhileShownChangesLevelOnlyThen() {
        val f = Fixture(at(17, 59, 30))
        f.keeper.start()
        f.local = at(17, 59, 59, 990)
        f.keeper.onTimer() // 同じ値では更新しない
        assertEquals(1, f.applied.size)
        f.local = at(18, 0, 0, 20)
        f.keeper.onTimer()
        assertEquals(listOf<Float?>(ClockBrightness.DAY, ClockBrightness.EVENING), f.applied)
        f.local = at(21, 59, 59, 990)
        f.keeper.onTimer()
        f.local = at(22, 0, 0, 20)
        f.keeper.onTimer()
        assertEquals(ClockBrightness.NIGHT, f.applied.last())
        // 日跨ぎ後の 06:00 で日中へ。
        f.local = at(23, 59, 59, 990, dayOffset = 20_000L)
        f.keeper.onTimer()
        f.local = at(6, 0, 0, 20, dayOffset = 20_001L)
        f.keeper.onTimer()
        assertEquals(ClockBrightness.DAY, f.applied.last())
        assertEquals(4, f.applied.size)
    }

    @Test
    fun timeCorrectionIsFollowedAtNextEvaluation() {
        val f = Fixture(at(23, 0))
        f.keeper.start()
        f.local = at(12, 0) // 手動補正またはタイムゾーン変更
        f.keeper.onTimer()
        assertEquals(listOf<Float?>(ClockBrightness.NIGHT, ClockBrightness.DAY), f.applied)
        assertTrue(f.pending!! <= 60_000L + ClockMath.BOUNDARY_MARGIN_MS)
    }

    @Test
    fun stopReleasesOverrideAndIgnoresStaleCallback() {
        val f = Fixture(at(12))
        f.keeper.start()
        f.keeper.stop()
        assertEquals(listOf(ClockBrightness.DAY, null), f.applied)
        assertNull(f.pending)
        assertFalse(f.keeper.isActive)
        val calls = f.scheduleCalls
        f.local = at(19)
        f.keeper.onTimer() // 停止後に古いコールバックが届いた
        assertEquals(calls, f.scheduleCalls)
        assertNull(f.pending)
        assertEquals(2, f.applied.size)
    }

    @Test
    fun stopWithoutStartDoesNotTouchWindow() {
        val f = Fixture(at(12))
        f.keeper.stop()
        f.keeper.stop()
        assertTrue(f.applied.isEmpty())
    }

    @Test
    fun reenteringClockKeepsSingleReservationAndReappliesAfterRelease() {
        val f = Fixture(at(19))
        f.keeper.start()
        f.keeper.start() // 二重に出し直しても予約は 1 つ（置き換え）
        assertEquals(listOf<Float?>(ClockBrightness.EVENING), f.applied)
        assertEquals(60_000L + ClockMath.BOUNDARY_MARGIN_MS, f.pending)
        f.keeper.stop() // 時計終了 / pause
        f.local = at(23)
        f.keeper.start() // 再表示は今の時刻で再評価
        assertEquals(listOf(ClockBrightness.EVENING, null, ClockBrightness.NIGHT), f.applied)
        assertTrue(f.keeper.isActive)
    }
}
