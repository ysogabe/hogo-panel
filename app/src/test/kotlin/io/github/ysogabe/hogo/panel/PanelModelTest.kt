package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.time.ZoneId
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

    // ---- API モード ----

    private val apiToday = LocalDate.of(2026, 10, 22)
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun occ(key: String, series: String, name: String, date: LocalDate, icon: MedIcon = MedIcon.PILL) =
        Occurrence(key, series, name, icon, date)

    private fun loadedOf(
        occurrences: List<Occurrence>,
        status: SourceStatus = SourceStatus.OK,
        leadDays: Int = 2,
        zone: ZoneId? = tokyo,
    ) = ScheduleResult.Loaded(status, leadDays, zone, 1792629000L, 15, occurrences)

    private fun apiModel(): PanelModel = PanelModel(t0).also { it.enterApiMode(apiToday) }

    private val late = occ("m-1021", "m", "長い薬", apiToday.minusDays(1))
    private val todayOcc = occ("l-1022", "l", "フロントライン", apiToday, MedIcon.DROP)
    private val soon = occ("f-1024", "f", "フィラリア", apiToday.plusDays(2))

    @Test
    fun apiModeStartsEmptyAndFetching() {
        val m = apiModel()
        assertTrue(m.apiMode)
        assertEquals(ApiPhase.UNFETCHED, m.apiPhase)
        assertNull(m.scenario)
        assertEquals(apiToday, m.today)
        assertTrue(m.warnings().isEmpty())
        assertFalse(m.hasReminder())
        assertTrue(m.clockBadges().isEmpty())
        assertEquals("予定を取得中", m.statusText())
        assertEquals(2L, m.leadDays)
        assertNull(m.scheduleZone)
        assertEquals(3, m.careCount)
    }

    @Test
    fun scenarioModeHasNoStatusAndNoApiPhase() {
        val m = PanelModel(t0)
        assertFalse(m.apiMode)
        assertNull(m.apiPhase)
        assertNull(m.statusText())
    }

    @Test
    fun loadedOkReplacesTheListAndUsesTheResponseValues() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(late, todayOcc, soon), leadDays = 3, zone = ZoneId.of("UTC")), apiToday)
        assertEquals(ApiPhase.LOADED, m.apiPhase)
        assertEquals(listOf("l-1022", "m-1021", "f-1024").sorted(), m.warnings().map { it.occurrence.key }.sorted())
        assertNull(m.statusText())
        assertEquals(3L, m.leadDays)
        assertEquals(ZoneId.of("UTC"), m.scheduleZone)
        m.applySchedule(loadedOf(listOf(soon)), apiToday)
        assertEquals(listOf("f-1024"), m.warnings().map { it.occurrence.key })
    }

    @Test
    fun loadedStaleFetchFailedAndReauthKeepWarningsAndShowTheirStatus() {
        val expected = mapOf(
            SourceStatus.STALE to "予定が古い",
            SourceStatus.FETCH_FAILED to "予定を更新できません",
            SourceStatus.REAUTH_REQUIRED to "カレンダーの再接続が必要",
        )
        for ((status, text) in expected) {
            val m = apiModel()
            m.applySchedule(loadedOf(listOf(todayOcc), status), apiToday)
            assertEquals(status.name, listOf(Phase.TODAY), m.warnings().map { it.phase })
            assertEquals(status.name, text, m.statusText())
            assertEquals(tokyo, m.scheduleZone)
        }
    }

    @Test
    fun loadedNotConnectedEmptiesTheListAndUsesTheDeviceZone() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.applySchedule(loadedOf(listOf(todayOcc), SourceStatus.NOT_CONNECTED, leadDays = 5), apiToday)
        assertTrue(m.warnings().isEmpty())
        assertEquals("カレンダー未接続", m.statusText())
        assertEquals(5L, m.leadDays)
        assertNull(m.scheduleZone)
    }

    @Test
    fun disabledEmptiesTheListAndShowsNothing() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(late, todayOcc), leadDays = 4), apiToday)
        m.applySchedule(ScheduleResult.Disabled(60), apiToday)
        assertEquals(ApiPhase.DISABLED, m.apiPhase)
        assertTrue(m.warnings().isEmpty())
        assertTrue(m.shownWarnings().isEmpty())
        assertTrue(m.clockBadges().isEmpty())
        assertNull(m.moreText())
        assertFalse(m.hasReminder())
        assertNull(m.statusText())
        assertEquals(4L, m.leadDays) // そのまま
        assertEquals(tokyo, m.scheduleZone)
    }

    @Test
    fun disabledOnTheFirstResponseIsAlsoSilent() {
        val m = apiModel()
        m.applySchedule(ScheduleResult.Disabled(null), apiToday)
        assertNull(m.statusText())
        assertEquals(2L, m.leadDays)
    }

    @Test
    fun failedAfterLoadedKeepsTheListLeadDaysAndZone() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(late, todayOcc), leadDays = 3), apiToday)
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday)
        assertEquals(2, m.warnings().size)
        assertEquals("予定を取得できません", m.statusText())
        assertEquals(3L, m.leadDays)
        assertEquals(tokyo, m.scheduleZone)
        // 次の成功で消える。
        m.applySchedule(loadedOf(listOf(late, todayOcc), leadDays = 3), apiToday)
        assertNull(m.statusText())
    }

    @Test
    fun failedBeforeAnyDataAndAfterDisabledStaysEmpty() {
        val first = apiModel()
        first.applySchedule(ScheduleResult.Failed(FetchError.TIMEOUT), apiToday)
        assertTrue(first.warnings().isEmpty())
        assertEquals("予定を取得できません", first.statusText())

        val afterDisabled = apiModel()
        afterDisabled.applySchedule(ScheduleResult.Disabled(null), apiToday)
        afterDisabled.applySchedule(ScheduleResult.Failed(FetchError.INVALID), apiToday)
        assertTrue(afterDisabled.warnings().isEmpty())
        assertEquals("予定を取得できません", afterDisabled.statusText())
    }

    @Test
    fun failedThenFetchFailedStatusShowsTheLatestState() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc), SourceStatus.STALE), apiToday)
        m.applySchedule(ScheduleResult.Failed(FetchError.HTTP_401), apiToday)
        assertEquals("予定を取得できません", m.statusText())
        m.applySchedule(loadedOf(listOf(todayOcc), SourceStatus.STALE), apiToday)
        assertEquals("予定が古い", m.statusText())
    }

    @Test
    fun configErrorShowsItsMessageHasNoDataAndIgnoresResults() {
        val m = PanelModel(t0).also { it.enterApiMode(apiToday, configError = true) }
        assertEquals(ApiPhase.CONFIG_ERROR, m.apiPhase)
        assertEquals("接続設定を確認してください", m.statusText())
        assertTrue(m.warnings().isEmpty())
        assertEquals(2L, m.leadDays)
        assertNull(m.scheduleZone)
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday)
        assertTrue(m.warnings().isEmpty())
        assertEquals("接続設定を確認してください", m.statusText())
    }

    @Test
    fun scenarioModeIgnoresSchedules() {
        val m = model(Scenario.TODAY)
        m.applySchedule(loadedOf(emptyList()), apiToday)
        assertEquals(2, m.warnings().size)
        assertNull(m.apiPhase)
        assertEquals(LocalDate.of(2026, 10, 20), m.today)
    }

    @Test
    fun applyScheduleSetsTheTodayItWasGiven() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(soon)), apiToday.plusDays(2))
        assertEquals(apiToday.plusDays(2), m.today)
        assertEquals(listOf(Phase.TODAY), m.warnings().map { it.phase })
    }

    @Test
    fun leadDaysFromTheResponseDecidesWhenTheWarningStarts() {
        val far = occ("far", "far", "遠い薬", apiToday.plusDays(3))
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(far), leadDays = 2), apiToday)
        assertTrue(m.warnings().isEmpty())
        m.applySchedule(loadedOf(listOf(far), leadDays = 3), apiToday)
        assertEquals(listOf(Phase.SOON), m.warnings().map { it.phase })
        m.applySchedule(loadedOf(listOf(todayOcc), leadDays = 0), apiToday)
        assertEquals(listOf(Phase.TODAY), m.warnings().map { it.phase })
    }

    @Test
    fun leadDaysUsedIsTheDefaultUntilTheFirstSuccess() {
        val far = occ("far", "far", "遠い薬", apiToday.plusDays(3))
        val m = apiModel()
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday)
        assertEquals(2L, m.leadDays)
        m.applySchedule(loadedOf(listOf(far), leadDays = 3), apiToday)
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday)
        assertEquals(3L, m.leadDays) // 失敗では戻さない
        assertEquals(1, m.warnings().size)
    }

    // ---- API モード: 確認・実施済み ----

    @Test
    fun theConfirmationKeepsItsTitleIconAndRecordTargetWhenTheListIsReplaced() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        assertTrue(m.openMed("l-1022", t0))
        assertEquals("フロントライン", m.confirmTitle())
        assertEquals(MedIcon.DROP, m.confirmIcon())
        // 件名と key が変わった一覧、さらに空の一覧に差し替わる。
        m.applySchedule(loadedOf(listOf(occ("l-1022", "l", "名前が変わった", apiToday, MedIcon.PILL))), apiToday)
        assertEquals("フロントライン", m.confirmTitle())
        assertEquals(MedIcon.DROP, m.confirmIcon())
        m.applySchedule(loadedOf(listOf(occ("other", "o", "別の薬", apiToday))), apiToday)
        assertEquals("フロントライン", m.confirmTitle())
        assertEquals(ConfirmResult.RECORDED, m.confirm(t0 + 500))
        assertTrue(m.isDone("l-1022"))
        assertFalse(m.isDone("other"))
        assertEquals("フロントライン 記録しました", m.toast?.text)
    }

    @Test
    fun anOpenConfirmationSurvivesDisabledAndFailedResults() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.openMed("l-1022", t0)
        val opened = m.confirmation
        m.applySchedule(ScheduleResult.Disabled(null), apiToday)
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday)
        assertEquals(opened, m.confirmation)
        assertEquals(ConfirmResult.RECORDED, m.confirm(t0 + 500))
        assertTrue(m.isDone("l-1022"))
    }

    @Test
    fun doneKeysSurviveReplacingTheList() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc, soon)), apiToday)
        m.openMed("l-1022", t0)
        m.confirm(t0 + 500)
        assertEquals(listOf("f-1024"), m.warnings().map { it.occurrence.key })
        m.applySchedule(loadedOf(listOf(todayOcc, soon, late)), apiToday)
        assertFalse(m.warnings().any { it.occurrence.key == "l-1022" })
        // 一覧から消えても、また現れても済のまま。
        m.applySchedule(loadedOf(listOf(soon)), apiToday)
        m.applySchedule(ScheduleResult.Disabled(null), apiToday)
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        assertTrue(m.isDone("l-1022"))
        assertTrue(m.warnings().isEmpty())
    }

    @Test
    fun applyScheduleDoesNotTouchTheIdleTimeToastOrConfirmation() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.markInteraction(t0 + 1000)
        m.openMed("l-1022", t0 + 2000)
        val before = m.confirmation
        m.applySchedule(loadedOf(listOf(late)), apiToday)
        assertEquals(t0 + 1000, m.lastInteractionMs)
        assertEquals(before, m.confirmation)
        m.closeConfirmation()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.openMed("l-1022", t0 + 3000)
        m.confirm(t0 + 4000)
        val toast = m.toast
        m.applySchedule(loadedOf(listOf(late)), apiToday)
        assertEquals(toast, m.toast)
        assertEquals(t0 + 1000, m.lastInteractionMs)
    }

    @Test
    fun undoStillRemovesTheRecordAfterTheListWasReplaced() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.openMed("l-1022", t0)
        m.confirm(t0 + 500)
        m.applySchedule(loadedOf(listOf(occ("x", "x", "別", apiToday))), apiToday)
        assertTrue(m.undo(t0 + 1000))
        assertFalse(m.isDone("l-1022"))
    }

    // ---- API モード: 日付の更新・シナリオの拒否 ----

    @Test
    fun refreshDateRejudgesWithTheLastLoadedListEvenAfterAFailure() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(soon)), apiToday.minusDays(1)) // 10-21: まだ 3 日前
        assertTrue(m.warnings().isEmpty())
        m.applySchedule(ScheduleResult.Failed(FetchError.NETWORK), apiToday.minusDays(1))
        m.refreshDate(apiToday)
        assertEquals(listOf(Phase.SOON), m.warnings().map { it.phase })
        m.refreshDate(apiToday.plusDays(2))
        assertEquals(listOf(Phase.TODAY), m.warnings().map { it.phase })
        m.refreshDate(apiToday.plusDays(3))
        assertEquals(listOf(Phase.LATE), m.warnings().map { it.phase })
        assertEquals("予定を取得できません", m.statusText())
    }

    @Test
    fun apiModeRefusesScenarioActionsAndTheMenu() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc)), apiToday)
        m.openMenu()
        assertFalse(m.menuOpen)
        m.perform(MenuAction.Pick(Scenario.SOON), t0)
        m.perform(MenuAction.Reset, t0)
        m.perform(MenuAction.Close, t0)
        assertNull(m.scenario)
        assertEquals(apiToday, m.today)
        assertEquals(listOf("l-1022"), m.warnings().map { it.occurrence.key })
    }

    @Test
    fun enteringApiModeDropsTheScenarioState() {
        val m = model(Scenario.TODAY)
        m.openMed(filaria, t0)
        m.confirm(t0 + 500)
        m.enterApiMode(apiToday)
        assertNull(m.scenario)
        assertNull(m.confirmation)
        assertNull(m.toast)
        assertFalse(m.isDone(filaria))
        assertTrue(m.warnings().isEmpty())
    }

    // ---- 薬カードは最大 2 枚 ----

    @Test
    fun threeWarningsShowTwoCardsAndMoreOne() {
        val m = apiModel()
        // 並びは入力順と無関係: 未確認 → 今日 → そろそろ。
        m.applySchedule(loadedOf(listOf(soon, todayOcc, late)), apiToday)
        assertEquals(3, m.warnings().size)
        assertEquals(listOf("m-1021", "l-1022"), m.shownWarnings().map { it.occurrence.key })
        assertEquals(listOf(Phase.LATE, Phase.TODAY), m.shownWarnings().map { it.phase })
        assertEquals(1, m.hiddenWarningCount())
        assertEquals("ほか 1 件", m.moreText())
        assertEquals(listOf("長い薬", "フロントライン", "ほか 1 件"), m.clockBadges())
        assertTrue(m.hasReminder())
    }

    @Test
    fun noMoreTextWithTwoOrFewerWarnings() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(todayOcc, soon)), apiToday)
        assertEquals(2, m.shownWarnings().size)
        assertEquals(0, m.hiddenWarningCount())
        assertNull(m.moreText())
        assertEquals(listOf("フロントライン", "フィラリア"), m.clockBadges())
        m.applySchedule(loadedOf(emptyList()), apiToday)
        assertNull(m.moreText())
    }

    @Test
    fun moreCountGrowsWithManyWarningsAndSameStageIsOrderedByDate() {
        val a = occ("a", "a", "A", apiToday)
        val b = occ("b", "b", "B", apiToday.plusDays(1))
        val c = occ("c", "c", "C", apiToday.plusDays(2))
        val d = occ("d", "d", "D", apiToday.plusDays(2))
        val e = occ("e", "e", "E", apiToday.plusDays(1))
        val m = apiModel()
        // 取得側（MedicationsJson）が日付・key の順に並べてから渡す。
        m.applySchedule(loadedOf(listOf(a, b, e, c, d)), apiToday)
        assertEquals(listOf("a", "b"), m.shownWarnings().map { it.occurrence.key })
        assertEquals("ほか 3 件", m.moreText())
        assertEquals(listOf("A", "B", "ほか 3 件"), m.clockBadges())
    }

    @Test
    fun aHiddenWarningCannotBeOpenedButAShownOneCan() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(soon, todayOcc, late)), apiToday)
        assertFalse(m.openMed("f-1024", t0))
        assertNull(m.confirmation)
        assertTrue(m.openMed("m-1021", t0))
    }

    @Test
    fun recordingAShownCardBringsTheHiddenOneIn() {
        val m = apiModel()
        m.applySchedule(loadedOf(listOf(soon, todayOcc, late)), apiToday)
        m.openMed("m-1021", t0)
        m.confirm(t0 + 500)
        assertEquals(listOf("l-1022", "f-1024"), m.shownWarnings().map { it.occurrence.key })
        assertNull(m.moreText())
    }

    @Test
    fun scenarioOrderIsUnchangedBySorting() {
        // 同じ段階・同じ日付なら元の並び（系列の初出順）のまま。
        assertEquals(listOf("フィラリア", "フロントライン"), model(Scenario.SOON).warnings().map { it.occurrence.name })
        assertEquals(listOf("フィラリア", "フロントライン"), model(Scenario.LATE).clockBadges())
        assertEquals(listOf(0, 0), listOf(model(Scenario.TODAY).hiddenWarningCount(), model(Scenario.SOON).hiddenWarningCount()))
    }
}
