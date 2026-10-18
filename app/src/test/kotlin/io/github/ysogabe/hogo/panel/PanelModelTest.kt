package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelModelTest {
    private val t0 = 1_000_000L
    private val filaria = "filaria-20261020"
    private val frontline = "frontline-20261020"

    private fun model(scenario: Scenario = Scenario.NORMAL): PanelModel =
        PanelModel(t0).also { it.perform(MenuAction.Pick(scenario), t0) }

    private fun names(m: PanelModel) = m.warnings().map { it.occurrence.name }

    // ---- 普段 / 警告 ----

    @Test
    fun defaultStartIsEverydayCareOnly() {
        val m = PanelModel(t0)
        assertEquals(Screen.HOME, m.screen)
        assertEquals(Scenario.NORMAL, m.scenario)
        assertFalse(m.hasReminder())
        assertTrue(m.warnings().isEmpty())
        assertTrue(m.clockBadges().isEmpty())
        assertEquals(listOf("ごはん", "トイレ", "目薬"), (0 until m.careCount).map { m.careName(it) })
    }

    @Test
    fun scenariosMatchTheApprovedStates() {
        assertFalse(model(Scenario.NORMAL).hasReminder())
        assertEquals(listOf(Phase.SOON, Phase.SOON), model(Scenario.SOON).warnings().map { it.phase })
        assertEquals(listOf(Phase.TODAY, Phase.TODAY), model(Scenario.TODAY).warnings().map { it.phase })
        assertEquals(listOf(Phase.LATE, Phase.LATE), model(Scenario.LATE).warnings().map { it.phase })
        assertFalse(model(Scenario.DONE).hasReminder())
        val clock = model(Scenario.CLOCK)
        assertEquals(Screen.CLOCK, clock.screen)
        assertEquals(listOf("フィラリア", "フロントライン"), clock.clockBadges())
    }

    @Test
    fun scenarioDatesAreFixedAndIndependentOfTheWallClock() {
        // 時刻は単調増加のミリ秒だけで、日付には影響しない。
        val m = model(Scenario.SOON)
        m.markInteraction(t0 + 10 * 24 * 3_600_000L)
        assertEquals(LocalDate.of(2026, 10, 18), m.today)
        assertEquals(2, m.warnings().size)
    }

    // ---- 確認 ----

    @Test
    fun openingAndClosingNeverRecords() {
        val m = model(Scenario.SOON)
        assertTrue(m.openMed(filaria, t0))
        m.closeConfirmation()
        assertEquals(2, m.warnings().size)
        assertFalse(m.isDone(filaria))
        assertTrue(m.openCare(0, t0))
        m.closeConfirmation()
        assertNull(m.toast)
    }

    @Test
    fun confirmIsRefusedInsideTheLockWindowAndAcceptedAtExactly450ms() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        assertFalse(m.confirmEnabled(t0))
        assertEquals(ConfirmResult.TOO_EARLY, m.confirm(t0))
        assertEquals(ConfirmResult.TOO_EARLY, m.confirm(t0 + 449))
        assertFalse(m.isDone(filaria))
        assertNotNull(m.confirmation)
        assertEquals(1L, m.confirmUnlockRemainingMs(t0 + 449))
        assertTrue(m.confirmEnabled(t0 + 450))
        assertEquals(ConfirmResult.RECORDED, m.confirm(t0 + 450))
        assertTrue(m.isDone(filaria))
    }

    @Test
    fun confirmingRecordsOnlyTheOpenedMedAndIsIdempotent() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        assertEquals(ConfirmResult.RECORDED, m.confirm(t0 + 500))
        assertEquals(listOf("フロントライン"), names(m))
        assertEquals(listOf("フロントライン"), m.clockBadges())
        assertEquals("フィラリア 記録しました", m.toast?.text)
        // 二度目の確認は何もしない。
        assertEquals(ConfirmResult.NOT_OPEN, m.confirm(t0 + 600))
        assertEquals(listOf("フロントライン"), names(m))
    }

    @Test
    fun alreadyRecordedOccurrenceIsNotRecordedAgain() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.markDoneForTest(filaria)
        assertEquals(ConfirmResult.ALREADY_RECORDED, m.confirm(t0 + 500))
        assertEquals("フィラリア は記録済み", m.toast?.text)
        assertNull(m.toast?.undo)
    }

    @Test
    fun allRecordedReturnsToTheEverydayLayout() {
        val m = model(Scenario.TODAY)
        for (key in listOf(filaria, frontline)) {
            m.openMed(key, t0)
            m.confirm(t0 + 500)
        }
        assertFalse(m.hasReminder())
        assertTrue(m.clockBadges().isEmpty())
    }

    @Test
    fun medsCannotBeOpenedOutsideTheWarningPeriod() {
        val m = model(Scenario.NORMAL)
        assertFalse(m.openMed(filaria, t0))
        assertFalse(m.openMed("unknown", t0))
        assertNull(m.confirmation)
    }

    @Test
    fun recordingGoesToTheOccurrenceOpenedEvenIfTheDataMovesOn() {
        val oct = Occurrence("a-oct", "a", "薬A", MedIcon.PILL, LocalDate.of(2026, 10, 20))
        val nov = Occurrence("a-nov", "a", "薬A", MedIcon.PILL, LocalDate.of(2026, 11, 20))
        val m = PanelModel(t0, listOf(oct, nov))
        m.refreshDate(LocalDate.of(2026, 10, 19))
        assertTrue(m.openMed("a-oct", t0))
        // 確認中に日付が進み、表示対象が 11 月の回へ移っても、記録は開いた 10 月の回に付く。
        m.refreshDate(LocalDate.of(2026, 11, 19))
        assertEquals(listOf("a-nov"), m.warnings().map { it.occurrence.key })
        assertEquals(ConfirmResult.RECORDED, m.confirm(t0 + 500))
        assertTrue(m.isDone("a-oct"))
        assertFalse(m.isDone("a-nov"))
        assertEquals(listOf("a-nov"), m.warnings().map { it.occurrence.key })
    }

    @Test
    fun differentOccurrencesAreRecordedSeparately() {
        val oct = Occurrence("a-oct", "a", "薬A", MedIcon.PILL, LocalDate.of(2026, 10, 20))
        val nov = Occurrence("a-nov", "a", "薬A", MedIcon.PILL, LocalDate.of(2026, 11, 20))
        val m = PanelModel(t0, listOf(oct, nov))
        m.refreshDate(LocalDate.of(2026, 10, 20))
        m.openMed("a-oct", t0)
        m.confirm(t0 + 500)
        assertTrue(m.warnings().isEmpty())
        m.refreshDate(LocalDate.of(2026, 11, 18))
        assertEquals(listOf("a-nov"), m.warnings().map { it.occurrence.key })
        assertFalse(m.isDone("a-nov"))
    }

    // ---- 取消 ----

    @Test
    fun undoRestoresOnlyTheTargetAndLeavesOtherMedsAlone() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.openMed(frontline, t0 + 1000)
        m.confirm(t0 + 1500)
        assertTrue(m.warnings().isEmpty())
        // 取消できるのは直近のトースト（フロントライン）だけ。
        assertTrue(m.undo(t0 + 2000))
        assertEquals(listOf("フロントライン"), names(m))
        assertTrue(m.isDone(filaria))
        assertEquals("取り消しました", m.toast?.text)
        assertNull(m.toast?.undo)
        assertFalse(m.undo(t0 + 2100))
        assertEquals(listOf("フロントライン"), names(m))
    }

    @Test
    fun undoIsAcceptedUntilEightSecondsOnly() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        assertNotNull(m.activeToast(t0 + 500 + 7_999))
        assertNull(m.activeToast(t0 + 500 + 8_000))
        assertFalse(m.undo(t0 + 500 + 8_000))
        assertTrue(m.isDone(filaria))
        m.tick(t0 + 500 + 8_000)
        assertNull(m.toast)
    }

    @Test
    fun toastAfterUndoKeepsItsExpiryAndSizeSoTapsDoNotFallThrough() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        val before = m.toast!!
        m.undo(t0 + 1000)
        assertEquals(before.expiresAtMs, m.toast!!.expiresAtMs)
        // 取消後も同じ固定サイズの面が残り、その位置のタップは背後へ通らない。
        val box = PanelLayout.toast
        val inside = PanelHits.at(m, box.centerX, box.centerY, t0 + 1100)
        assertEquals(Hit.Swallow, inside)
        assertEquals(Hit.Swallow, PanelHits.at(m, box.left + 1, box.top + 1, t0 + 1100))
        assertEquals(Hit.Swallow, PanelHits.at(m, box.right - 1, box.bottom - 1, t0 + 1100))
    }

    @Test
    fun openingAConfirmationOrTheClockEndsTheUndoWindow() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.openMed(frontline, t0 + 1000)
        assertNull(m.toast)
        assertFalse(m.undo(t0 + 1100))
        m.closeConfirmation()
        assertTrue(m.isDone(filaria))

        val n = model(Scenario.SOON)
        n.openMed(filaria, t0)
        n.confirm(t0 + 500)
        n.enterClock()
        assertNull(n.toast)
        n.wake(t0 + 2000)
        assertFalse(n.undo(t0 + 2100))
        assertTrue(n.isDone(filaria))
    }

    // ---- お世話 ----

    @Test
    fun careRecordingAndUndoDoNotTouchMedications() {
        val m = model(Scenario.SOON)
        val before = names(m)
        m.openCare(1, t0)
        assertEquals("トイレ", m.confirmTitle())
        assertEquals("50分前", m.careElapsedText(1, t0))
        m.confirm(t0 + 500)
        assertEquals("たった今", m.careElapsedText(1, t0 + 500))
        assertEquals(before, names(m))
        assertFalse(m.isDone(filaria))
        m.undo(t0 + 1000)
        assertEquals(before, names(m))
        // 経過時間は取消で元の起点に戻る。
        assertEquals("50分前", m.careElapsedText(1, t0 + 1000))
    }

    @Test
    fun medicationRecordingAndUndoDoNotTouchCare() {
        val m = model(Scenario.SOON)
        val careBefore = (0 until m.careCount).map { m.careElapsedText(it, t0) }
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.undo(t0 + 600)
        assertEquals(careBefore, (0 until m.careCount).map { m.careElapsedText(it, t0) })
    }

    @Test
    fun careElapsedTextFormats() {
        assertEquals("たった今", ElapsedText.format(0))
        assertEquals("1分前", ElapsedText.format(1))
        assertEquals("59分前", ElapsedText.format(59))
        assertEquals("1時間前", ElapsedText.format(60))
        assertEquals("2時間前", ElapsedText.format(120))
        assertEquals("23時間前", ElapsedText.format(23 * 60 + 59))
        assertEquals("1日前", ElapsedText.format(24 * 60))
    }

    // ---- 待機・時計 ----

    @Test
    fun idleBecomesDueAtThreeMinutesAndInteractionRestartsIt() {
        val m = model()
        assertFalse(m.idleDue(t0 + 179_999))
        assertTrue(m.idleDue(t0 + 180_000))
        assertEquals(1L, m.idleRemainingMs(t0 + 179_999))
        m.markInteraction(t0 + 100_000)
        assertFalse(m.idleDue(t0 + 180_000))
        assertTrue(m.idleDue(t0 + 280_000))
    }

    @Test
    fun enteringTheClockClosesConfirmationMenuAndToastWithoutRecording() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.enterClock()
        assertNull(m.confirmation)
        assertFalse(m.isDone(filaria))
        assertEquals(Screen.CLOCK, m.screen)
        assertFalse(m.idleDue(t0 + 999_999)) // 時計中は待機判定の対象外
        m.wake(t0 + 5000)
        assertEquals(Screen.HOME, m.screen)
        assertEquals(t0 + 5000, m.lastInteractionMs)
    }

    @Test
    fun clockBadgesShowWarningMedsOnlyAndDropRecordedOnes() {
        val m = model(Scenario.TODAY)
        assertEquals(listOf("フィラリア", "フロントライン"), m.clockBadges())
        m.openMed(frontline, t0)
        m.confirm(t0 + 500)
        assertEquals(listOf("フィラリア"), m.clockBadges())
        m.undo(t0 + 600)
        assertEquals(listOf("フィラリア", "フロントライン"), m.clockBadges())
    }

    // ---- シナリオ ----

    @Test
    fun resetRestoresEverythingToTheDefault() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.openCare(0, t0 + 600)
        m.confirm(t0 + 1200)
        m.perform(MenuAction.Reset, t0 + 2000)
        assertEquals(Scenario.NORMAL, m.scenario)
        assertFalse(m.hasReminder())
        assertFalse(m.isDone(filaria))
        assertEquals("2時間前", m.careElapsedText(0, t0 + 2000))
        assertNull(m.toast)
    }

    @Test
    fun switchingScenarioClearsDoneStateOfThePreviousOne() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.perform(MenuAction.Pick(Scenario.TODAY), t0 + 600)
        assertEquals(2, m.warnings().size)
    }

    @Test
    fun menuOpensOnlyOnHomeAndClosesOnClose() {
        val m = model(Scenario.SOON)
        m.openMed(filaria, t0)
        m.openMenu()
        assertTrue(m.menuOpen)
        assertNull(m.confirmation)
        assertFalse(m.openMed(filaria, t0 + 1)) // メニュー中は背後を開かない
        m.perform(MenuAction.Close, t0 + 2)
        assertFalse(m.menuOpen)
        assertFalse(m.isDone(filaria))
    }
}
