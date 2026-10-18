package io.github.ysogabe.hogo.panel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun phase(due: LocalDate, today: LocalDate, done: Boolean = false) = ReminderPolicy.phase(due, today, done)

    @Test
    fun threeDaysBeforeIsNormalAndTwoDaysBeforeIsSoon() {
        val due = d(2026, 10, 20)
        assertEquals(Phase.NORMAL, phase(due, d(2026, 10, 17)))
        assertEquals(Phase.SOON, phase(due, d(2026, 10, 18)))
        assertEquals(Phase.SOON, phase(due, d(2026, 10, 19)))
        assertEquals(Phase.TODAY, phase(due, d(2026, 10, 20)))
        assertEquals(Phase.LATE, phase(due, d(2026, 10, 21)))
        assertEquals(Phase.LATE, phase(due, d(2026, 12, 1)))
    }

    @Test
    fun doneWinsOverEveryOtherPhase() {
        val due = d(2026, 10, 20)
        for (day in 17..22) assertEquals(Phase.DONE, phase(due, d(2026, 10, day), done = true))
    }

    @Test
    fun monthBoundaryCountsCalendarDays() {
        val due = d(2026, 11, 1)
        assertEquals(Phase.NORMAL, phase(due, d(2026, 10, 29)))
        assertEquals(Phase.SOON, phase(due, d(2026, 10, 30)))
        assertEquals(Phase.SOON, phase(due, d(2026, 10, 31)))
        assertEquals(Phase.TODAY, phase(due, d(2026, 11, 1)))
    }

    @Test
    fun yearBoundaryCountsCalendarDays() {
        val due = d(2027, 1, 1)
        assertEquals(Phase.NORMAL, phase(due, d(2026, 12, 29)))
        assertEquals(Phase.SOON, phase(due, d(2026, 12, 30)))
        assertEquals(Phase.LATE, phase(due, d(2027, 1, 2)))
    }

    @Test
    fun leapDayIsCountedOnlyInLeapYears() {
        val leap = d(2028, 3, 1)
        assertEquals(Phase.NORMAL, phase(leap, d(2028, 2, 27)))
        assertEquals(Phase.SOON, phase(leap, d(2028, 2, 28)))
        assertEquals(Phase.SOON, phase(leap, d(2028, 2, 29)))
        val common = d(2027, 3, 1)
        assertEquals(Phase.NORMAL, phase(common, d(2027, 2, 26)))
        assertEquals(Phase.SOON, phase(common, d(2027, 2, 27)))
        assertEquals(Phase.SOON, phase(common, d(2027, 2, 28)))
    }

    @Test
    fun warningStartIsTwoCalendarDaysEvenAcrossMonthAndYear() {
        assertEquals(d(2026, 10, 30), ReminderPolicy.warnStart(d(2026, 11, 1)))
        assertEquals(d(2026, 12, 30), ReminderPolicy.warnStart(d(2027, 1, 1)))
        assertEquals(d(2028, 2, 28), ReminderPolicy.warnStart(d(2028, 3, 1)))
    }

    @Test
    fun regionCalendarDateDecidesNotElapsedHours() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val due = d(2026, 10, 20)
        // 東京の 00:05 は予定日の 09:00 まで 56 時間以上あるが、暦日では 2 日前。
        val justAfterMidnight = Instant.parse("2026-10-17T15:05:00Z")
        assertEquals(d(2026, 10, 18), ReminderPolicy.localDate(justAfterMidnight, tokyo))
        assertEquals(Phase.SOON, phase(due, ReminderPolicy.localDate(justAfterMidnight, tokyo)))
        // その 6 分前は東京ではまだ前日。
        val before = Instant.parse("2026-10-17T14:59:00Z")
        assertEquals(Phase.NORMAL, phase(due, ReminderPolicy.localDate(before, tokyo)))
        // 同じ瞬間でも、予定地域が UTC なら暦日が違う。
        assertEquals(Phase.NORMAL, phase(due, ReminderPolicy.localDate(justAfterMidnight, ZoneId.of("UTC"))))
    }

    private fun occ(key: String, series: String, due: LocalDate) =
        Occurrence(key, series, series, MedIcon.PILL, due)

    @Test
    fun warningsPickOnlyTheLatestReachedOccurrencePerSeries() {
        val oct = occ("a-oct", "a", d(2026, 10, 20))
        val nov = occ("a-nov", "a", d(2026, 11, 20))
        val b = occ("b-oct", "b", d(2026, 10, 20))
        val all = listOf(oct, nov, b)
        // 11/18 には 10 月の未実施が残っていても、新しい 11 月の回を選ぶ。
        val w = ReminderPolicy.warnings(all, emptySet(), d(2026, 11, 18))
        assertEquals(listOf("a-nov", "b-oct"), w.map { it.occurrence.key })
        assertEquals(listOf(Phase.SOON, Phase.LATE), w.map { it.phase })
    }

    @Test
    fun completingNewerOccurrenceDoesNotPromoteTheOlderOne() {
        val oct = occ("a-oct", "a", d(2026, 10, 20))
        val nov = occ("a-nov", "a", d(2026, 11, 20))
        val w = ReminderPolicy.warnings(listOf(oct, nov), setOf("a-nov"), d(2026, 11, 20))
        assertTrue(w.isEmpty())
    }

    @Test
    fun futureOccurrenceIsHeldBackUntilItsWarningStarts() {
        val oct = occ("a-oct", "a", d(2026, 10, 20))
        val nov = occ("a-nov", "a", d(2026, 11, 20))
        val w = ReminderPolicy.warnings(listOf(oct, nov), emptySet(), d(2026, 10, 25))
        assertEquals(listOf("a-oct"), w.map { it.occurrence.key })
        assertEquals(Phase.LATE, w.single().phase)
    }

    @Test
    fun normalPeriodAndDoneProduceNoWarnings() {
        val o = listOf(occ("a", "a", d(2026, 10, 20)))
        assertTrue(ReminderPolicy.warnings(o, emptySet(), d(2026, 10, 17)).isEmpty())
        assertTrue(ReminderPolicy.warnings(o, setOf("a"), d(2026, 10, 20)).isEmpty())
    }
}
