package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実際のタイマーや View を使わず、擬似時計とフェイクのホストでタッチ・時間・復帰の流れを検証する。 */
class PanelControllerTest {
    private class FakeHost(private val badges: () -> List<String>) : PanelHost {
        val timers = mutableMapOf<PanelTimer, Long>()
        val clockChanges = mutableListOf<Boolean>()

        /** [updateClockBadges] のたびに、そのときの札（モデルの [PanelModel.clockBadges]）を記録する。 */
        val badgeUpdates = mutableListOf<List<String>>()
        var redraws = 0
        override fun requestRedraw() { redraws++ }
        override fun schedule(timer: PanelTimer, delayMs: Long) { timers[timer] = delayMs }
        override fun cancel(timer: PanelTimer) { timers.remove(timer) }
        override fun onClockChanged(clockOn: Boolean) { clockChanges += clockOn }
        override fun updateClockBadges() { badgeUpdates += badges() }
    }

    private var now = 10_000L
    private val host = FakeHost { model.clockBadges() }
    private val model = PanelModel(now)

    /** 端末の今日（API モードの日付の判定）。テストが進める。 */
    private var deviceToday = LocalDate.of(2026, 10, 22)
    private val zonesAsked = mutableListOf<ZoneId?>()
    private val controller = PanelController(model, host, { now }, { 1_700_000_000_000L }) { zone ->
        zonesAsked += zone
        deviceToday
    }

    private val filaria = "filaria-20261020"
    private val frontline = "frontline-20261020"

    private fun scenario(s: Scenario) {
        model.perform(MenuAction.Pick(s), now)
        controller.resume()
    }

    private fun tap(x: Float, y: Float, holdMs: Long = 30) {
        controller.onDown(x, y)
        now += holdMs
        controller.onUp(x, y)
    }

    private fun fire(timer: PanelTimer) {
        host.timers.remove(timer)
        controller.onTimer(timer)
    }

    private val med1 get() = PanelLayout.medBoxes(2)[0]
    private val med2 get() = PanelLayout.medBoxes(2)[1]
    private val clockButton get() = PanelLayout.clockButton

    private fun openFilaria() {
        tap(med1.centerX, med1.centerY)
        assertEquals(Target.Med(filaria), model.confirmation?.target)
    }

    private fun confirmAfterLock() {
        now += 460
        fire(PanelTimer.UNLOCK)
        val d = PanelLayout.dialog(true).done
        tap(d.centerX, d.centerY)
    }

    // ---- 確認と誤記録防止 ----

    @Test
    fun tapOpensConfirmationWithBackFocusedAndRecordsNothing() {
        scenario(Scenario.SOON)
        openFilaria()
        assertEquals(ConfirmFocus.BACK, model.confirmFocus)
        assertFalse(model.isDone(filaria))
        assertTrue(PanelTimer.UNLOCK in host.timers)
    }

    @Test
    fun outsideTapAndBackButtonAndAndroidBackNeverRecord() {
        scenario(Scenario.SOON)
        openFilaria()
        tap(5f, 400f) // 外側（スクリム）
        assertNull(model.confirmation)
        openFilaria()
        val back = PanelLayout.dialog(true).back
        tap(back.centerX, back.centerY)
        assertNull(model.confirmation)
        openFilaria()
        assertTrue(controller.onBack())
        assertNull(model.confirmation)
        assertFalse(model.isDone(filaria))
        assertFalse(model.isDone(frontline))
    }

    @Test
    fun closingCancelsTheUnlockReservation() {
        scenario(Scenario.SOON)
        openFilaria()
        assertTrue(PanelTimer.UNLOCK in host.timers)
        controller.onBack()
        assertFalse(PanelTimer.UNLOCK in host.timers)
    }

    @Test
    fun secondTapOnTheOverlappingPositionRightAfterOpeningDoesNotRecord() {
        scenario(Scenario.SOON)
        // 2 枚目の薬カードの右端は、確認の「やった」ボタンと重なる。その重なりの点を連打する。
        val done = PanelLayout.dialog(true).done
        val x = (done.left + med2.right) / 2f
        val y = done.centerY
        assertTrue(med2.contains(x, y) && done.contains(x, y))
        tap(x, y)
        assertEquals(Target.Med(frontline), model.confirmation?.target)
        now += 100
        tap(x, y) // 連打
        assertFalse(model.isDone(frontline))
        assertTrue(model.confirmation != null)
        // 押し始めが無効の間で、離す時点で有効になっても記録しない。
        controller.onDown(x, y)
        now += 400
        controller.onUp(x, y)
        assertFalse(model.isDone(frontline))
        // ロック明けの新しい押下なら記録できる。
        now += 100
        tap(x, y)
        assertTrue(model.isDone(frontline))
    }

    @Test
    fun handlerGuardRefusesEarlyConfirmEvenWhenButtonStateIsStale() {
        scenario(Scenario.SOON)
        openFilaria()
        // 画面が再描画されないままでも、モデル側の時間判定が拒否する。
        assertEquals(ConfirmResult.TOO_EARLY, model.confirm(now + 100))
        controller.onKey(PanelKey.NEXT)
        controller.onKey(PanelKey.ACTIVATE) // フォーカスは「やった」、でも 450ms 前
        assertFalse(model.isDone(filaria))
        assertTrue(model.confirmation != null)
    }

    @Test
    fun doneAfterTheLockRecordsOnlyThatMedAndShowsUndoToast() {
        scenario(Scenario.SOON)
        openFilaria()
        confirmAfterLock()
        assertTrue(model.isDone(filaria))
        assertFalse(model.isDone(frontline))
        assertEquals("フィラリア 記録しました", model.activeToast(now)?.text)
        assertEquals(listOf("フロントライン"), model.warnings().map { it.occurrence.name })
        // 残り 1 件になったので、薬カードは 1 枚の大きな配置。
        assertEquals(1, PanelLayout.medBoxes(1).size)
        assertEquals(PanelLayout.content.height, PanelLayout.medBoxes(1)[0].height, 0.01f)
    }

    @Test
    fun undoViaTheToastButtonRestoresOnlyTheTarget() {
        scenario(Scenario.SOON)
        openFilaria()
        confirmAfterLock()
        val u = PanelLayout.toastUndoVisual
        now += 2000
        tap(u.centerX, u.centerY)
        assertFalse(model.isDone(filaria))
        assertEquals("取り消しました", model.activeToast(now)?.text)
        // 取消済みのトースト面を叩いても背後の薬カードは開かない。
        val t = PanelLayout.toast
        tap(t.centerX, t.centerY)
        assertNull(model.confirmation)
    }

    @Test
    fun toastExpiryDuringAPressDoesNotActivateTheCardBehindIt() {
        scenario(Scenario.SOON)
        openFilaria()
        confirmAfterLock()
        val t = PanelLayout.toast
        // トースト上で押し始め、8 秒を過ぎてから離す。位置は背後のお世話カードの上でもある。
        controller.onDown(t.centerX, t.centerY)
        now += 9000
        fire(PanelTimer.TOAST)
        controller.onUp(t.centerX, t.centerY)
        assertNull(model.confirmation)
    }

    // ---- 時計 ----

    @Test
    fun idleTimerEntersTheClockAfterThreeMinutes() {
        scenario(Scenario.SOON)
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
        now += 180_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.CLOCK, model.screen)
        assertEquals(true, host.clockChanges.last())
        assertTrue(host.timers.isEmpty()) // 時計中は全タイマー停止
    }

    @Test
    fun clockButtonEntersTheClockAndFirstTouchOnlyWakesAndConsumesTheGesture() {
        scenario(Scenario.SOON)
        tap(clockButton.centerX, clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        // 時計の最初のタッチ位置は、ホームの薬カード（確認を開く位置）。
        val x = med1.centerX
        val y = med1.centerY
        controller.onDown(x, y)
        assertEquals(Screen.HOME, model.screen)
        controller.onMove(x + 3, y)
        controller.onUp(x, y)
        assertNull(model.confirmation)
        assertFalse(model.isDone(filaria))
        assertEquals(false, host.clockChanges.last())
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
        // 次の独立したタッチからは通常操作。
        tap(x, y)
        assertEquals(Target.Med(filaria), model.confirmation?.target)
    }

    @Test
    fun wakeConsumesUntilEveryFingerIsUp() {
        scenario(Scenario.SOON)
        model.enterClock()
        val done = PanelLayout.dialog(true).done
        controller.onDown(med1.centerX, med1.centerY) // 復帰
        controller.onPointerDown() // 2 本目
        controller.onPointerUp()
        controller.onUp(med1.centerX, med1.centerY)
        assertNull(model.confirmation)
        // 全て離れたので、次のタッチは操作になる。
        tap(med1.centerX, med1.centerY)
        assertTrue(model.confirmation != null)
        assertFalse(model.isDone(filaria))
        assertTrue(done.width > 0)
    }

    @Test
    fun secondFingerCancelsTheTapSoNothingBehindIsOperated() {
        scenario(Scenario.SOON)
        controller.onDown(med1.centerX, med1.centerY)
        controller.onPointerDown()
        controller.onPointerUp()
        controller.onUp(med1.centerX, med1.centerY)
        assertNull(model.confirmation)
    }

    @Test
    fun touchAfterTimeoutBeforeTheTimerRunsIsTreatedAsAWakeTouch() {
        scenario(Scenario.SOON)
        now += 180_000 // タイマーはまだ走っていない（切替境界）
        controller.onDown(med1.centerX, med1.centerY)
        controller.onUp(med1.centerX, med1.centerY)
        assertNull(model.confirmation)
        assertEquals(Screen.HOME, model.screen)
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
        // 次のタッチは通常操作。
        tap(med1.centerX, med1.centerY)
        assertTrue(model.confirmation != null)
    }

    @Test
    fun idleDoesNotSwitchWhileAFingerIsDownAndRestartsAfterRelease() {
        scenario(Scenario.SOON)
        controller.onDown(med1.centerX, med1.centerY)
        now += 200_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.HOME, model.screen)
        controller.onUp(5f, 5f)
        assertEquals(Screen.HOME, model.screen)
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
    }

    @Test
    fun cancelAfterHeldIdleRestartsTheWaitInsteadOfEnteringTheClock() {
        scenario(Scenario.SOON)
        controller.onDown(med1.centerX, med1.centerY)
        now += 180_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.HOME, model.screen)
        controller.onCancel()
        assertEquals(Screen.HOME, model.screen)
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
        fire(PanelTimer.IDLE)
        assertEquals(Screen.HOME, model.screen)
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
        now += 180_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.CLOCK, model.screen)
    }

    @Test
    fun cancelledGestureNeverOperatesAndEndsConsumption() {
        scenario(Scenario.SOON)
        controller.onDown(med1.centerX, med1.centerY)
        controller.onCancel()
        assertNull(model.confirmation)
        assertFalse(controller.gestureActive)
        tap(med1.centerX, med1.centerY)
        assertTrue(model.confirmation != null)
    }

    @Test
    fun movingFarFromTheStartCancelsTheTap() {
        scenario(Scenario.SOON)
        controller.onDown(med1.centerX, med1.centerY)
        controller.onMove(med1.centerX + 100, med1.centerY)
        controller.onUp(med1.centerX, med1.centerY)
        assertNull(model.confirmation)
    }

    @Test
    fun clockEntryClosesConfirmationAndEndsUndoWithoutRecording() {
        scenario(Scenario.SOON)
        openFilaria()
        now += 200_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.CLOCK, model.screen)
        assertFalse(model.isDone(filaria))
        assertNull(model.confirmation)
    }

    // ---- ライフサイクル ----

    @Test
    fun pauseStopsEveryTimerAndResumeStartsFromHomeWithFreshIdle() {
        scenario(Scenario.SOON)
        openFilaria()
        controller.pause()
        assertTrue(host.timers.isEmpty())
        assertNull(model.confirmation)
        assertFalse(model.isDone(filaria))
        now += 500_000
        controller.resume()
        assertEquals(Screen.HOME, model.screen)
        assertEquals(false, host.clockChanges.last())
        assertEquals(180_000L, host.timers[PanelTimer.IDLE])
    }

    @Test
    fun pauseWhileOnTheClockStopsTimersAndResumeReturnsHome() {
        scenario(Scenario.SOON)
        now += 180_000
        fire(PanelTimer.IDLE)
        controller.pause()
        assertTrue(host.timers.isEmpty())
        controller.resume()
        assertEquals(Screen.HOME, model.screen)
        assertEquals(false, host.clockChanges.last())
    }

    // ---- シナリオメニュー ----

    @Test
    fun longPressOnTheHeaderOpensTheMockMenuAndTheReleaseDoesNothing() {
        scenario(Scenario.NORMAL)
        controller.onDown(100f, 38f)
        assertEquals(PanelController.HOLD_MS, host.timers[PanelTimer.LONG_PRESS])
        now += 700
        fire(PanelTimer.LONG_PRESS)
        assertTrue(model.menuOpen)
        controller.onUp(100f, 38f)
        assertTrue(model.menuOpen)
        assertEquals(Scenario.NORMAL, model.scenario)
    }

    @Test
    fun shortTapOnTheHeaderDoesNotOpenTheMenu() {
        scenario(Scenario.NORMAL)
        controller.onDown(100f, 38f)
        controller.onUp(100f, 38f)
        assertFalse(model.menuOpen)
        assertFalse(PanelTimer.LONG_PRESS in host.timers)
    }

    @Test
    fun menuSelectionChangesScenarioAndTheClockEntryShowsBadges() {
        scenario(Scenario.NORMAL)
        model.openMenu()
        val items = PanelLayout.menu.items
        val soon = items[MenuAction.all.indexOf(MenuAction.Pick(Scenario.SOON))]
        tap(soon.centerX, soon.centerY)
        assertEquals(Scenario.SOON, model.scenario)
        assertFalse(model.menuOpen)
        model.openMenu()
        val clock = items[MenuAction.all.indexOf(MenuAction.Pick(Scenario.CLOCK))]
        tap(clock.centerX, clock.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        assertEquals(true, host.clockChanges.last())
        assertEquals(listOf("フィラリア", "フロントライン"), model.clockBadges())
    }

    @Test
    fun androidBackClosesMenuWithoutChangingScenario() {
        scenario(Scenario.SOON)
        model.openMenu()
        assertTrue(controller.onBack())
        assertFalse(model.menuOpen)
        assertEquals(Scenario.SOON, model.scenario)
        assertFalse(controller.onBack()) // ホームでは既定の戻る
    }

    @Test
    fun backOnTheClockWakesInsteadOfLeaving() {
        scenario(Scenario.SOON)
        model.enterClock()
        assertTrue(controller.onBack())
        assertEquals(Screen.HOME, model.screen)
    }

    // ---- API モード（取得結果の反映・日付・シナリオの拒否） ----

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val headerSpot = Pair(100f, 38f) // ヘッダーの長押しの位置（何も開かない）

    private fun occ(key: String, series: String, name: String, date: LocalDate, icon: MedIcon = MedIcon.PILL) =
        Occurrence(key, series, name, icon, date)

    private fun loaded(
        vararg occurrences: Occurrence,
        status: SourceStatus = SourceStatus.OK,
        zone: ZoneId? = tokyo,
    ) = ScheduleResult.Loaded(status, 2, zone, 1L, null, occurrences.toList())

    private val soonOcc get() = occ("f-1024", "f", "フィラリア", deviceToday.plusDays(2))
    private val todayOcc get() = occ("l-1022", "l", "フロントライン", deviceToday, MedIcon.DROP)

    private fun apiMode() {
        model.enterApiMode(deviceToday)
        controller.resume()
    }

    private fun holdFinger() = controller.onDown(headerSpot.first, headerSpot.second)

    private fun releaseFinger() = controller.onUp(headerSpot.first, headerSpot.second)

    @Test
    fun apiModeAppliesAResultAtOnceWhenNoFingerIsDownAndStartsThePulse() {
        apiMode()
        assertEquals("予定を取得中", model.statusText())
        controller.onSchedule(loaded(todayOcc))
        assertEquals(listOf("フロントライン"), names())
        assertNull(model.statusText())
        assertTrue(controller.pulsing)
        assertTrue(PanelTimer.PULSE in host.timers)
    }

    private fun names() = model.warnings().map { it.occurrence.name }

    @Test
    fun aResultArrivingWhileAFingerIsDownWaitsForTheReleaseThenApplies() {
        apiMode()
        holdFinger()
        controller.onSchedule(loaded(todayOcc))
        assertTrue(model.warnings().isEmpty()) // 押している途中の表示を変えない
        assertFalse(controller.pulsing)
        assertEquals("予定を取得中", model.statusText())
        releaseFinger()
        assertEquals(listOf("フロントライン"), names())
        assertTrue(controller.pulsing)
        assertEquals(listOf("フロントライン"), host.badgeUpdates.last())
    }

    @Test
    fun onlyTheLatestHeldResultIsApplied() {
        apiMode()
        holdFinger()
        controller.onSchedule(loaded(todayOcc))
        controller.onSchedule(loaded(soonOcc))
        releaseFinger()
        assertEquals(listOf("フィラリア"), names())
        assertFalse(controller.pulsing) // そろそろは明滅しない
    }

    @Test
    fun aHeldResultIsAppliedWhenTheGestureIsCancelled() {
        apiMode()
        holdFinger()
        controller.onSchedule(loaded(todayOcc))
        controller.onCancel()
        assertEquals(listOf("フロントライン"), names())
    }

    @Test
    fun aHeldResultWaitsUntilEveryFingerIsUp() {
        apiMode()
        holdFinger()
        controller.onPointerDown()
        controller.onSchedule(loaded(todayOcc))
        controller.onPointerUp() // まだ 1 本残っている
        controller.onSchedule(loaded(todayOcc, soonOcc))
        assertTrue(model.warnings().isEmpty())
        releaseFinger()
        assertEquals(listOf("フロントライン", "フィラリア"), names())
    }

    @Test
    fun applyingAResultDoesNotRestartTheIdleCount() {
        apiMode()
        val interaction = model.lastInteractionMs
        val idle = host.timers[PanelTimer.IDLE]
        now += 50_000
        controller.onSchedule(loaded(todayOcc))
        assertEquals(interaction, model.lastInteractionMs)
        assertEquals(idle, host.timers[PanelTimer.IDLE])
        now += 130_000 // 最初の 180 秒（結果の反映では数え直されていない）
        fire(PanelTimer.IDLE)
        assertEquals(Screen.CLOCK, model.screen)
    }

    @Test
    fun anOpenConfirmationKeepsItsTitleAndTargetWhenTheListIsReplaced() {
        apiMode()
        controller.onSchedule(loaded(todayOcc))
        val box = PanelLayout.medBoxes(1)[0]
        tap(box.centerX, box.centerY)
        val opened = model.confirmation
        assertEquals(Target.Med("l-1022"), opened?.target)
        assertFalse(controller.pulsing) // 確認の間は止まる
        controller.onSchedule(loaded(soonOcc)) // 開いた回は新しい一覧に無い
        assertEquals(opened, model.confirmation)
        assertEquals("フロントライン", model.confirmTitle())
        assertTrue(PanelTimer.UNLOCK in host.timers) // 誤記録防止の待ちは続く
        assertFalse(controller.pulsing)
        confirmAfterLock()
        assertTrue(model.isDone("l-1022"))
        assertFalse(model.isDone("f-1024"))
    }

    @Test
    fun thePulseStopsWhenTheResultsNoLongerNeedIt() {
        apiMode()
        controller.onSchedule(loaded(todayOcc))
        assertTrue(controller.pulsing)
        controller.onSchedule(loaded(soonOcc)) // そろそろだけ
        assertFalse(controller.pulsing)
        assertFalse(PanelTimer.PULSE in host.timers)
        controller.onSchedule(loaded(todayOcc))
        assertTrue(controller.pulsing)
        controller.onSchedule(ScheduleResult.Disabled(null)) // 表示オフ
        assertFalse(controller.pulsing)
        assertFalse(PanelTimer.PULSE in host.timers)
        assertTrue(model.clockBadges().isEmpty())
        // 失敗は前の予定を残すので、明滅も続く。
        controller.onSchedule(loaded(todayOcc))
        controller.onSchedule(ScheduleResult.Failed(FetchError.TIMEOUT))
        assertTrue(controller.pulsing)
    }

    @Test
    fun theClockBadgesFollowAResultWhileTheClockIsShown() {
        apiMode()
        controller.onSchedule(loaded(soonOcc))
        assertEquals(listOf("フィラリア"), host.badgeUpdates.last())
        tap(clockButton.centerX, clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        val before = host.badgeUpdates.size
        controller.onSchedule(loaded(soonOcc, todayOcc))
        assertEquals(Screen.CLOCK, model.screen)
        assertTrue(host.badgeUpdates.size > before)
        assertEquals(listOf("フロントライン", "フィラリア"), host.badgeUpdates.last())
        controller.onSchedule(ScheduleResult.Disabled(null))
        assertEquals(emptyList<String>(), host.badgeUpdates.last())
    }

    @Test
    fun apiModeRefusesTheScenarioOperations() {
        apiMode()
        controller.onDown(headerSpot.first, headerSpot.second)
        assertFalse(PanelTimer.LONG_PRESS in host.timers) // 長押しは予約しない
        now += 700
        controller.onTimer(PanelTimer.LONG_PRESS) // 予約が残っていても何も開かない
        assertFalse(model.menuOpen)
        controller.onUp(headerSpot.first, headerSpot.second)
        assertFalse(model.menuOpen)
        model.openMenu()
        assertFalse(model.menuOpen)
        model.perform(MenuAction.Pick(Scenario.SOON), now)
        assertNull(model.scenario)
        assertEquals(deviceToday, model.today)
        assertTrue(model.warnings().isEmpty())
    }

    @Test
    fun scenarioModeIgnoresResultsAndNeverUsesTheDateTimer() {
        scenario(Scenario.SOON)
        assertFalse(PanelTimer.DATE in host.timers)
        controller.onSchedule(loaded(todayOcc))
        controller.onTimer(PanelTimer.DATE)
        assertEquals(listOf("フィラリア", "フロントライン"), names())
        assertEquals(LocalDate.of(2026, 10, 18), model.today)
        assertTrue(zonesAsked.isEmpty())
        assertTrue(host.badgeUpdates.isEmpty())
        tap(clockButton.centerX, clockButton.centerY)
        assertTrue(host.timers.isEmpty()) // 時計では今までどおり全タイマー停止
    }

    @Test
    fun theDateTimerRunsOnTheHomeAndOnTheClockAndReschedulesItself() {
        apiMode()
        assertTrue(PanelTimer.DATE in host.timers)
        fire(PanelTimer.DATE)
        assertTrue(PanelTimer.DATE in host.timers)
        tap(clockButton.centerX, clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        assertEquals(setOf(PanelTimer.DATE), host.timers.keys) // 時計では日付の確認だけが残る
        fire(PanelTimer.DATE)
        assertEquals(setOf(PanelTimer.DATE), host.timers.keys)
        // 触れて戻ってから、3 分の無操作で時計に入っても同じ。
        wakeByTouch()
        now += 180_000
        fire(PanelTimer.IDLE)
        assertEquals(Screen.CLOCK, model.screen)
        assertEquals(setOf(PanelTimer.DATE), host.timers.keys)
    }

    private fun wakeByTouch() {
        controller.onDown(5f, 5f)
        controller.onUp(5f, 5f)
    }

    /** 時計の表示中に日付が進み、そろそろ → 今日になり、時計の札も更新される。[before] で結果を先に入れておける。 */
    private fun clockShownWhileTheDateAdvances(before: () -> Unit) {
        apiMode()
        val f = occ("f-1024", "f", "フィラリア", deviceToday.plusDays(2))
        val l = occ("l-1025", "l", "フロントライン", deviceToday.plusDays(3), MedIcon.DROP)
        controller.onSchedule(loaded(f, l))
        assertEquals(listOf(Phase.SOON), model.warnings().map { it.phase })
        assertEquals(listOf("フィラリア"), model.clockBadges())
        tap(clockButton.centerX, clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        before()
        val updates = host.badgeUpdates.size
        deviceToday = deviceToday.plusDays(2)
        fire(PanelTimer.DATE)
        assertEquals(Screen.CLOCK, model.screen)
        assertEquals(deviceToday, model.today)
        assertEquals(listOf(Phase.TODAY, Phase.SOON), model.warnings().map { it.phase })
        assertTrue(host.badgeUpdates.size > updates)
        assertEquals(listOf("フィラリア", "フロントライン"), host.badgeUpdates.last())
        assertTrue(PanelTimer.DATE in host.timers)
    }

    @Test
    fun whenTheDateChangesOnTheClockSoonBecomesTodayAndTheBadgesUpdate() = clockShownWhileTheDateAdvances {}

    @Test
    fun theSameHoldsAfterAFailedFetch() = clockShownWhileTheDateAdvances {
        controller.onSchedule(ScheduleResult.Failed(FetchError.NETWORK))
        assertEquals("予定を取得できません", model.statusText())
    }

    @Test
    fun aDateChangeOnTheHomeUsesTheSameFlowAndKeepsTheOpenConfirmation() {
        apiMode()
        controller.onSchedule(loaded(soonOcc))
        val box = PanelLayout.medBoxes(1)[0]
        tap(box.centerX, box.centerY)
        val opened = model.confirmation
        deviceToday = deviceToday.plusDays(2)
        fire(PanelTimer.DATE)
        assertEquals(Phase.TODAY, model.warnings().single().phase)
        assertEquals(opened, model.confirmation)
        assertTrue(PanelTimer.UNLOCK in host.timers)
    }

    @Test
    fun aDateChangeWhileAFingerIsDownWaitsForTheRelease() {
        apiMode()
        controller.onSchedule(loaded(soonOcc))
        holdFinger()
        deviceToday = deviceToday.plusDays(2)
        fire(PanelTimer.DATE)
        assertEquals(Phase.SOON, model.warnings().single().phase)
        assertEquals(deviceToday.minusDays(2), model.today)
        assertTrue(PanelTimer.DATE in host.timers) // 次の確認は予約される
        releaseFinger()
        assertEquals(Phase.TODAY, model.warnings().single().phase)
        assertEquals(deviceToday, model.today)
        assertTrue(controller.pulsing) // 今日になったので明滅が始まる
    }

    @Test
    fun theDateIsCheckedWithTheScheduleZoneAndFollowsAZoneChange() {
        apiMode()
        assertNull(zonesAsked.lastOrNull())
        val utc = ZoneId.of("UTC")
        controller.onSchedule(loaded(soonOcc, zone = utc))
        assertEquals(utc, zonesAsked.last()) // 応答のゾーンでも今日を求め直す
        fire(PanelTimer.DATE)
        assertEquals(utc, zonesAsked.last())
        controller.onSchedule(loaded(soonOcc, status = SourceStatus.NOT_CONNECTED, zone = utc)) // 未接続は端末のゾーン
        assertNull(zonesAsked.last())
    }

    @Test
    fun pauseStopsTheDateTimerAndResumeChecksTheDateAtOnce() {
        apiMode()
        controller.onSchedule(loaded(soonOcc))
        controller.pause()
        assertTrue(host.timers.isEmpty())
        deviceToday = deviceToday.plusDays(2)
        now += 600_000
        controller.resume()
        assertEquals(deviceToday, model.today)
        assertEquals(Phase.TODAY, model.warnings().single().phase)
        assertTrue(PanelTimer.DATE in host.timers)
        assertTrue(controller.pulsing)
    }

    // ---- 時計からの最初のタッチ（AC10） ----

    @Test
    fun inApiModeTheFirstTouchFromTheClockOnlyWakesAndRecordsNothing() {
        apiMode()
        controller.onSchedule(loaded(todayOcc, soonOcc))
        tap(clockButton.centerX, clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        val box = PanelLayout.medBoxes(2)[0] // 薬カードの位置
        controller.onDown(box.centerX, box.centerY)
        assertEquals(Screen.HOME, model.screen)
        controller.onSchedule(loaded(todayOcc, soonOcc)) // 復帰のタッチの途中に届いても保留
        controller.onUp(box.centerX, box.centerY)
        assertNull(model.confirmation)
        assertFalse(model.isDone("l-1022"))
        assertFalse(model.isDone("f-1024"))
        // 次の独立したタッチからは通常操作。
        tap(box.centerX, box.centerY)
        assertEquals(Target.Med("l-1022"), model.confirmation?.target)
        assertFalse(model.isDone("l-1022"))
    }
}
