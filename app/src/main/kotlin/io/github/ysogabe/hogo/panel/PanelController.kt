package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.time.ZoneId

enum class PanelTimer { UNLOCK, TOAST, IDLE, LONG_PRESS, TICK, PULSE, DATE }

enum class PanelKey { NEXT, PREVIOUS, ACTIVATE, ESCAPE, OTHER }

/** Android 側が実装する、描画・タイマー・画面モードの窓口。 */
interface PanelHost {
    fun requestRedraw()

    /** 同じ [timer] の予約があれば置き換える。 */
    fun schedule(timer: PanelTimer, delayMs: Long)

    fun cancel(timer: PanelTimer)

    /** 時計（低輝度）とホーム（system の明るさ）の切替。 */
    fun onClockChanged(clockOn: Boolean)

    /** 時計の札を [PanelModel.clockBadges] に合わせる（時計の表示中に予定や日付が変わったとき）。 */
    fun updateClockBadges()
}

/**
 * タッチ・キー・タイマーの流れを [PanelModel] へつなぐ。Android に依存しないので、
 * 復帰ジェスチャーや切替境界を単体テストできる。座標は基準座標（960×480）。
 */
class PanelController(
    val model: PanelModel,
    private val host: PanelHost,
    private val nowMs: () -> Long,
    private val localMillis: () -> Long,
    /** 予定のゾーン（無ければ端末のゾーン）での今日。API モードの日付の判定に使う。 */
    private val todayIn: (ZoneId?) -> LocalDate = { zone -> LocalDate.now(zone ?: ZoneId.systemDefault()) },
) {
    private val gate = TouchGate()
    private var press: Hit? = null
    private var downX = 0f
    private var downY = 0f

    /** 移動してもタップとみなす距離（基準座標）。 */
    var slop = 16f

    /** キーボード操作を始めたらフォーカスの輪郭を出す。 */
    var focusVisible = false
        private set

    val pressed: Hit? get() = press
    val gestureActive: Boolean get() = gate.gestureActive

    private var pulseScheduled = false

    /** 指が触れている間に届いた、最後の成功の応答（Loaded・Disabled）。離してから反映する。 */
    private var pendingSuccess: ScheduleResult? = null

    /** [pendingSuccess] より後に届いた直近の失敗。成功が後から届いたら捨てる。 */
    private var pendingFailure: ScheduleResult.Failed? = null

    /** 当日・超過の薬カードを明滅させている間だけ true。 */
    val pulsing: Boolean get() = pulseScheduled

    // ---- ライフサイクル ----

    fun resume() {
        val now = nowMs()
        gate.reset()
        press = null
        focusVisible = false
        cancelAll()
        model.resume(now)
        host.onClockChanged(false)
        syncTimers(now)
        scheduleIdle(now)
        scheduleDate()
        applyPending() // 止めている間に日付が変わっていたら、すぐ判定し直す
        host.requestRedraw()
    }

    /** 全てのタイマーと進行中の入力を止める。確認とメニューは閉じ、記録は残す。 */
    fun pause() {
        cancelAll()
        gate.reset()
        press = null
        model.enterClock()
        model.wake(nowMs())
        host.onClockChanged(false)
    }

    private fun cancelAll() {
        pulseScheduled = false
        PanelTimer.entries.forEach { host.cancel(it) }
    }

    // ---- タッチ ----

    fun onDown(x: Float, y: Float) {
        val now = nowMs()
        gate.down()
        host.cancel(PanelTimer.LONG_PRESS)
        press = null
        // タイムアウトに達していてタイマーがまだ走っていない瞬間のタッチも、時計中のタッチとして扱う。
        if (model.screen == Screen.HOME && model.idleDue(now)) enterClock()
        if (model.screen == Screen.CLOCK) {
            wake(now)
            gate.consume()
            return
        }
        model.markInteraction(now)
        model.tick(now)
        downX = x
        downY = y
        press = PanelHits.at(model, x, y, now)
        // API モードにはシナリオ選択が無いので、長押しは何もしない。
        if (press == Hit.HeaderHold && !model.apiMode) host.schedule(PanelTimer.LONG_PRESS, HOLD_MS)
        scheduleIdle(now)
        host.requestRedraw()
    }

    fun onPointerDown() {
        gate.pointerDown()
        cancelPress()
    }

    fun onPointerUp() {
        gate.pointerUp()
    }

    fun onMove(x: Float, y: Float) {
        if (press == null) return
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy > slop * slop) {
            cancelPress()
            host.requestRedraw()
        }
    }

    fun onUp(x: Float, y: Float) {
        val now = nowMs()
        val deliver = gate.up()
        host.cancel(PanelTimer.LONG_PRESS)
        val started = press
        press = null
        if (deliver && started != null && model.screen == Screen.HOME) {
            model.tick(now)
            if (PanelHits.at(model, x, y, now) == started) perform(started, now)
        }
        if (model.screen == Screen.HOME) {
            model.markInteraction(now)
            scheduleIdle(now)
        }
        applyPending()
        host.requestRedraw()
    }

    fun onCancel() {
        gate.cancel()
        host.cancel(PanelTimer.LONG_PRESS)
        press = null
        if (model.screen == Screen.HOME) {
            // 保留していた待機時間の満了直後に中断されても、中断した時刻から数え直す。
            val now = nowMs()
            model.markInteraction(now)
            scheduleIdle(now)
        }
        applyPending()
        host.requestRedraw()
    }

    private fun cancelPress() {
        press = null
        host.cancel(PanelTimer.LONG_PRESS)
    }

    private fun perform(hit: Hit, now: Long) {
        when (hit) {
            Hit.ClockButton -> enterClock()
            is Hit.Med -> model.openMed(hit.occurrenceKey, now)
            is Hit.Care -> model.openCare(hit.index, now)
            Hit.DialogBack, Hit.Scrim -> model.closeConfirmation()
            Hit.DialogDone -> model.confirm(now)
            Hit.ToastUndo -> model.undo(now)
            is Hit.MenuItem -> {
                model.perform(hit.action, now)
                if (model.screen == Screen.CLOCK) enterClockUi()
            }
            Hit.MenuScrim -> model.closeMenu()
            Hit.HeaderHold, Hit.Swallow -> Unit
        }
        syncTimers(now)
    }

    // ---- キー・戻る ----

    /** キー入力。処理したら true。 */
    fun onKey(key: PanelKey): Boolean {
        val now = nowMs()
        if (model.screen == Screen.CLOCK) {
            wake(now)
            return true
        }
        model.markInteraction(now)
        scheduleIdle(now)
        var handled = true
        if (model.menuOpen) {
            if (key == PanelKey.ESCAPE) model.closeMenu() else handled = key != PanelKey.OTHER
        } else if (model.confirmation != null) {
            focusVisible = true
            when (key) {
                PanelKey.NEXT, PanelKey.PREVIOUS -> model.moveConfirmFocus()
                PanelKey.ESCAPE -> model.closeConfirmation()
                PanelKey.ACTIVATE ->
                    if (model.confirmFocus == ConfirmFocus.DONE) model.confirm(now) else model.closeConfirmation()
                PanelKey.OTHER -> handled = false
            }
        } else {
            handled = false
        }
        syncTimers(now)
        host.requestRedraw()
        return handled
    }

    /** Android の戻る。何かを閉じたら true（未記録）、ホームで何もなければ false。 */
    fun onBack(): Boolean {
        val now = nowMs()
        if (model.screen == Screen.CLOCK) {
            wake(now)
            return true
        }
        val handled = when {
            model.menuOpen -> { model.closeMenu(); true }
            model.confirmation != null -> { model.closeConfirmation(); true }
            else -> false
        }
        if (handled) {
            model.markInteraction(now)
            scheduleIdle(now)
            syncTimers(now)
            host.requestRedraw()
        }
        return handled
    }

    // ---- タイマー ----

    fun onTimer(timer: PanelTimer) {
        val now = nowMs()
        when (timer) {
            PanelTimer.UNLOCK -> host.requestRedraw()
            PanelTimer.TOAST -> {
                model.tick(now)
                syncTimers(now)
                host.requestRedraw()
            }
            PanelTimer.IDLE -> {
                if (model.screen != Screen.HOME) return
                // 指が触れている間は切り替えない。最後の指が離れたときの操作で待機時間を数え直す。
                if (gate.gestureActive) return
                if (model.idleDue(now)) enterClock() else scheduleIdle(now)
            }
            PanelTimer.LONG_PRESS -> {
                if (!model.apiMode && press == Hit.HeaderHold && !gate.isConsumed && gate.activePointers == 1 && model.screen == Screen.HOME) {
                    press = null
                    gate.consume()
                    model.openMenu()
                    syncTimers(now)
                    host.requestRedraw()
                }
            }
            PanelTimer.TICK -> {
                host.requestRedraw()
                if (model.screen == Screen.HOME) {
                    host.schedule(PanelTimer.TICK, ClockMath.untilNextMinuteMs(localMillis()) + ClockMath.BOUNDARY_MARGIN_MS)
                }
            }
            PanelTimer.DATE -> {
                if (!model.apiMode) return
                scheduleDate()
                applyPending()
            }
            PanelTimer.PULSE -> {
                // 条件を外れていたら再予約しない（停止後にコールバックが残っても動き続けない）。
                if (pulseScheduled && pulseNeeded()) {
                    host.requestRedraw()
                    host.schedule(PanelTimer.PULSE, PulseMath.FRAME_MS)
                } else {
                    pulseScheduled = false
                }
            }
        }
    }

    /** ホームで確認・メニューが開いておらず、当日・超過の薬が未記録で残っているときだけ動かす。 */
    private fun pulseNeeded(): Boolean =
        model.screen == Screen.HOME && !model.menuOpen && model.confirmation == null &&
            model.shownWarnings().any { PulseMath.animates(it.phase) }

    private fun syncPulse() {
        val need = pulseNeeded()
        if (need && !pulseScheduled) {
            pulseScheduled = true
            host.schedule(PanelTimer.PULSE, PulseMath.FRAME_MS)
        } else if (!need && pulseScheduled) {
            pulseScheduled = false
            host.cancel(PanelTimer.PULSE)
        }
    }

    // ---- 取得結果と日付（API モード） ----

    /**
     * 取得結果を受ける（メインスレッドで呼ぶ）。指が触れている間は、最後の成功の応答と、それより後の直近の失敗を
     * 保留し、全ての指が離れてから成功、失敗の順に反映する（押していないときに同じ順で届いた場合と同じ状態になる。
     * 失敗が成功の応答を消すことはない）。API モード以外では何もしない。
     */
    fun onSchedule(result: ScheduleResult) {
        if (!model.apiMode) return
        when (result) {
            is ScheduleResult.Failed -> pendingFailure = result
            else -> {
                pendingSuccess = result
                pendingFailure = null // 後から届いた成功は、前の失敗を消す
            }
        }
        applyPending()
    }

    /**
     * 保留中の取得結果（成功、失敗の順）を反映し、日付が変わっていれば判定し直す。指が触れている間は何もしない（離した後の
     * [onUp]・[onCancel]、[resume] がもう一度呼ぶので、保留した日付の変化も失われない）。変わったときだけ
     * 明滅・時計の札・再描画を合わせる。無操作の時間・開いている確認・トーストには触れない（[PanelModel.applySchedule]）。
     */
    private fun applyPending() {
        if (!model.apiMode || gate.gestureActive) return
        val success = pendingSuccess
        val failure = pendingFailure
        pendingSuccess = null
        pendingFailure = null
        var changed = false
        for (result in listOfNotNull(success, failure)) {
            model.applySchedule(result, todayIn(model.scheduleZone))
            changed = true
        }
        // 応答でゾーンが変わったときも含め、今のゾーンの今日に合わせる。
        val today = todayIn(model.scheduleZone)
        if (today != model.today) {
            model.refreshDate(today)
            changed = true
        }
        if (!changed) return
        syncTimers(nowMs())
        host.updateClockBadges()
        host.requestRedraw()
    }

    private fun scheduleDate() {
        if (!model.apiMode) return
        host.schedule(PanelTimer.DATE, ClockMath.untilNextMinuteMs(localMillis()) + ClockMath.BOUNDARY_MARGIN_MS)
    }

    // ---- 内部 ----

    private fun enterClock() {
        model.enterClock()
        enterClockUi()
    }

    private fun enterClockUi() {
        cancelAll()
        press = null
        scheduleDate() // 時計の間も日付は確かめる
        host.onClockChanged(true)
        host.requestRedraw()
    }

    private fun wake(now: Long) {
        model.wake(now)
        host.onClockChanged(false)
        syncTimers(now)
        scheduleIdle(now)
        host.requestRedraw()
    }

    private fun scheduleIdle(now: Long) {
        if (model.screen != Screen.HOME) return
        host.schedule(PanelTimer.IDLE, model.idleRemainingMs(now).coerceAtLeast(1L))
    }

    /** モデルの状態に合わせて、ホームで必要なタイマーだけを予約し直す。 */
    private fun syncTimers(now: Long) {
        syncPulse()
        host.cancel(PanelTimer.UNLOCK)
        host.cancel(PanelTimer.TOAST)
        if (model.screen != Screen.HOME) return
        model.confirmUnlockRemainingMs(now)?.let {
            host.schedule(PanelTimer.UNLOCK, it + ClockMath.BOUNDARY_MARGIN_MS)
        }
        model.toastRemainingMs(now)?.let {
            host.schedule(PanelTimer.TOAST, it + ClockMath.BOUNDARY_MARGIN_MS)
        }
        host.schedule(PanelTimer.TICK, ClockMath.untilNextMinuteMs(localMillis()) + ClockMath.BOUNDARY_MARGIN_MS)
    }

    companion object {
        const val HOLD_MS = 700L
    }
}
