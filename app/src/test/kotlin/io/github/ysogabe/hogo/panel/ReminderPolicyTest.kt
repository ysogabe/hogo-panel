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

    // ---- 警告日数の引数 ----

    @Test
    fun leadDaysDefaultsToTwoAndKeepsTheOldBehaviour() {
        val due = d(2026, 10, 20)
        assertEquals(d(2026, 10, 18), ReminderPolicy.warnStart(due))
        assertEquals(ReminderPolicy.warnStart(due), ReminderPolicy.warnStart(due, 2L))
        assertEquals(Phase.NORMAL, ReminderPolicy.phase(due, d(2026, 10, 17), false, 2L))
        assertEquals(Phase.SOON, ReminderPolicy.phase(due, d(2026, 10, 18), false, 2L))
    }

    @Test
    fun leadDaysThreeStartsTheWarningThreeDaysBefore() {
        val due = d(2026, 10, 20)
        assertEquals(d(2026, 10, 17), ReminderPolicy.warnStart(due, 3L))
        assertEquals(Phase.NORMAL, ReminderPolicy.phase(due, d(2026, 10, 16), false, 3L))
        assertEquals(Phase.SOON, ReminderPolicy.phase(due, d(2026, 10, 17), false, 3L))
        val o = Occurrence("a", "s", "薬", MedIcon.PILL, due)
        assertTrue(ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 16), 3L).isEmpty())
        assertEquals(listOf(Phase.SOON), ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 17), 3L).map { it.phase })
        assertTrue(ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 17)).isEmpty())
    }

    @Test
    fun leadDaysZeroWarnsOnlyOnTheDayAndAfter() {
        val due = d(2026, 10, 20)
        assertEquals(Phase.NORMAL, ReminderPolicy.phase(due, d(2026, 10, 19), false, 0L))
        assertEquals(Phase.TODAY, ReminderPolicy.phase(due, d(2026, 10, 20), false, 0L))
        assertEquals(Phase.LATE, ReminderPolicy.phase(due, d(2026, 10, 21), false, 0L))
    }

    @Test
    fun leadDaysFourteenWarnsTwoWeeksBefore() {
        val due = d(2026, 10, 20)
        assertEquals(Phase.NORMAL, ReminderPolicy.phase(due, d(2026, 10, 5), false, 14L))
        assertEquals(Phase.SOON, ReminderPolicy.phase(due, d(2026, 10, 6), false, 14L))
    }

    @Test
    fun leadDaysChangesWhichRecurrenceIsCurrent() {
        val oct = Occurrence("a-oct", "a", "薬", MedIcon.PILL, d(2026, 10, 20))
        val nov = Occurrence("a-nov", "a", "薬", MedIcon.PILL, d(2026, 11, 20))
        val today = d(2026, 11, 17)
        assertEquals(listOf("a-oct"), ReminderPolicy.warnings(listOf(oct, nov), emptySet(), today, 2L).map { it.occurrence.key })
        assertEquals(listOf("a-nov"), ReminderPolicy.warnings(listOf(oct, nov), emptySet(), today, 3L).map { it.occurrence.key })
    }

    // ---- dayOnly（当日だけ警告する回） ----

    private fun dayOnlyOcc(key: String, series: String, due: LocalDate) =
        Occurrence(key, series, series, MedIcon.TRASH, due, dayOnly = true)

    @Test
    fun dayOnlyPhaseIsTodayOnlyOnTheDay() {
        val due = d(2026, 10, 20)
        fun p(today: LocalDate, done: Boolean = false, lead: Long = 2L) = ReminderPolicy.phase(due, today, done, lead, dayOnly = true)
        assertEquals(Phase.NORMAL, p(d(2026, 10, 17)))
        assertEquals(Phase.NORMAL, p(d(2026, 10, 18)))
        assertEquals(Phase.NORMAL, p(d(2026, 10, 19)))
        assertEquals(Phase.TODAY, p(d(2026, 10, 20)))
        assertEquals(Phase.NORMAL, p(d(2026, 10, 21)))
        assertEquals(Phase.NORMAL, p(d(2026, 12, 1)))
        assertEquals(Phase.DONE, p(d(2026, 10, 20), done = true))
        for (lead in listOf(0L, 2L, 14L)) assertEquals(Phase.NORMAL, p(d(2026, 10, 19), lead = lead))
    }

    @Test
    fun dayOnlyOccurrenceWarnsOnlyOnTheDayWhateverLeadDaysIs() {
        val o = dayOnlyOcc("g-1020", "g", d(2026, 10, 20))
        for (lead in listOf(0L, 2L, 14L)) {
            assertTrue(ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 19), lead).isEmpty())
            assertEquals(Phase.TODAY, ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 20), lead).single().phase)
            assertTrue(ReminderPolicy.warnings(listOf(o), emptySet(), d(2026, 10, 21), lead).isEmpty())
        }
    }

    @Test
    fun dayOnlyDoneOccurrenceIsNotShownAndYesterdayIsNotCarriedOver() {
        val yesterday = dayOnlyOcc("g-1019", "g", d(2026, 10, 19))
        val today = dayOnlyOcc("g-1020", "g", d(2026, 10, 20))
        assertTrue(ReminderPolicy.warnings(listOf(today), setOf("g-1020"), d(2026, 10, 20)).isEmpty())
        // 昨日の回が未確認のままでも、今日の回が無い日は何も出さない
        assertTrue(ReminderPolicy.warnings(listOf(yesterday), emptySet(), d(2026, 10, 20)).isEmpty())
        // 今日の回があれば、昨日の未確認は繰り上げずに今日の回が出る
        assertEquals(listOf("g-1020"), ReminderPolicy.warnings(listOf(yesterday, today), emptySet(), d(2026, 10, 20)).map { it.occurrence.key })
        // 今日の回が済んでいれば、昨日の回も出ない
        assertTrue(ReminderPolicy.warnings(listOf(yesterday, today), setOf("g-1020"), d(2026, 10, 20)).isEmpty())
    }

    @Test
    fun dayOnlySeriesDoesNotChangeTheWarningOfMedicines() {
        val med = occ("m-1022", "m", d(2026, 10, 22))
        // 明日のごみは予告しない（薬なら leadDays 内でそろそろになる日）
        val trash = dayOnlyOcc("g-1021", "g", d(2026, 10, 21))
        val w = ReminderPolicy.warnings(listOf(med, trash), emptySet(), d(2026, 10, 20))
        assertEquals(listOf("m-1022" to Phase.SOON), w.map { it.occurrence.key to it.phase })
    }
}
