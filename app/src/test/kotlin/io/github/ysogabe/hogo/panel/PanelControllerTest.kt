package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実際のタイマーや View を使わず、擬似時計とフェイクのホストでタッチ・時間・復帰の流れを検証する。 */
class PanelControllerTest {
    private class FakeHost : PanelHost {
        val timers = mutableMapOf<PanelTimer, Long>()
        val clockChanges = mutableListOf<Boolean>()
        var redraws = 0
        override fun requestRedraw() { redraws++ }
        override fun schedule(timer: PanelTimer, delayMs: Long) { timers[timer] = delayMs }
        override fun cancel(timer: PanelTimer) { timers.remove(timer) }
        override fun onClockChanged(clockOn: Boolean) { clockChanges += clockOn }
    }

    private var now = 10_000L
    private val host = FakeHost()
    private val model = PanelModel(now)
    private val controller = PanelController(model, host, { now }, { 1_700_000_000_000L })

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
}
