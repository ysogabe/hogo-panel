package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 当日・超過の明滅の対象と、予約の開始・停止の条件。 */
class PulseTest {
    private class FakeHost : PanelHost {
        val timers = mutableMapOf<PanelTimer, Long>()
        override fun requestRedraw() {}
        override fun schedule(timer: PanelTimer, delayMs: Long) { timers[timer] = delayMs }
        override fun cancel(timer: PanelTimer) { timers.remove(timer) }
        override fun onClockChanged(clockOn: Boolean) {}
    }

    private var now = 10_000L
    private val host = FakeHost()
    private val model = PanelModel(now)
    private val controller = PanelController(model, host, { now }, { 1_700_000_000_000L })
    private val filaria = "filaria-20261020"

    private fun scenario(s: Scenario) {
        model.perform(MenuAction.Pick(s), now)
        controller.resume()
    }

    private fun tap(x: Float, y: Float) {
        controller.onDown(x, y)
        now += 30
        controller.onUp(x, y)
    }

    private fun fire(timer: PanelTimer) {
        host.timers.remove(timer)
        controller.onTimer(timer)
    }

    private val med1 get() = PanelLayout.medBoxes(2)[0]
    private val pulseOn get() = PanelTimer.PULSE in host.timers

    @Test
    fun periodIsOneSlowCycleBrightDarkBright() {
        assertEquals(0f, PulseMath.dim(0), 1e-4f)
        assertEquals(1f, PulseMath.dim(PulseMath.PERIOD_MS / 2), 1e-4f)
        assertEquals(0f, PulseMath.dim(PulseMath.PERIOD_MS), 1e-4f)
        assertEquals(PulseMath.dim(700), PulseMath.dim(700 + 5 * PulseMath.PERIOD_MS), 1e-4f)
    }

    @Test
    fun onlyTodayAndLateAnimate() {
        assertTrue(PulseMath.animates(Phase.TODAY))
        assertTrue(PulseMath.animates(Phase.LATE))
        assertFalse(PulseMath.animates(Phase.SOON))
        assertFalse(PulseMath.animates(Phase.NORMAL))
        assertFalse(PulseMath.animates(Phase.DONE))
    }

    @Test
    fun staticScenariosNeverSchedulePulse() {
        for (s in listOf(Scenario.NORMAL, Scenario.SOON, Scenario.DONE)) {
            scenario(s)
            assertFalse(s.name, pulseOn)
            assertFalse(s.name, controller.pulsing)
        }
    }

    @Test
    fun todayAndLatePulseAndKeepRescheduling() {
        for (s in listOf(Scenario.TODAY, Scenario.LATE)) {
            scenario(s)
            assertTrue(s.name, pulseOn)
            fire(PanelTimer.PULSE)
            assertTrue(s.name, pulseOn)
        }
    }

    @Test
    fun confirmationPausesPulseAndBackResumesIt() {
        scenario(Scenario.TODAY)
        tap(med1.centerX, med1.centerY)
        assertFalse(pulseOn)
        assertFalse(controller.pulsing)
        assertTrue(controller.onBack())
        assertTrue(pulseOn)
    }

    @Test
    fun staleCallbackAfterStopDoesNotReschedule() {
        scenario(Scenario.TODAY)
        tap(med1.centerX, med1.centerY) // 確認が開き、動きが止まる
        controller.onTimer(PanelTimer.PULSE) // 取り消せなかった残りのコールバック
        assertFalse(pulseOn)
        controller.pause()
        controller.onTimer(PanelTimer.PULSE)
        assertFalse(pulseOn)
    }

    @Test
    fun recordingAllTodayMedsStopsPulseAndUndoRestoresIt() {
        scenario(Scenario.TODAY)
        for (i in 0..1) {
            tap(PanelLayout.medBoxes(2 - i)[0].centerX, PanelLayout.medBoxes(2 - i)[0].centerY)
            now += 460
            fire(PanelTimer.UNLOCK)
            val d = PanelLayout.dialog(true).done
            tap(d.centerX, d.centerY)
            if (i == 0) assertTrue("one med left", pulseOn)
        }
        assertTrue(model.warnings().isEmpty())
        assertFalse(pulseOn)
        val u = PanelLayout.toastUndoHit
        tap(u.centerX, u.centerY)
        assertEquals(1, model.warnings().size)
        assertTrue(pulseOn)
    }

    @Test
    fun pauseAndClockStopPulseAndResumeRestartsOnlyWhenNeeded() {
        scenario(Scenario.LATE)
        controller.pause()
        assertFalse(pulseOn)
        controller.resume()
        assertTrue(pulseOn)
        tap(PanelLayout.clockButton.centerX, PanelLayout.clockButton.centerY)
        assertEquals(Screen.CLOCK, model.screen)
        assertFalse(pulseOn)
        controller.onTimer(PanelTimer.PULSE)
        assertFalse(pulseOn)
    }

    @Test
    fun menuOpenPausesPulse() {
        scenario(Scenario.TODAY)
        controller.onDown(60f, 38f)
        now += 800
        fire(PanelTimer.LONG_PRESS)
        assertTrue(model.menuOpen)
        assertFalse(pulseOn)
    }
}
