package io.github.ysogabe.hogo.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import java.time.LocalDate
import java.util.TimeZone

/**
 * ホーム（[PanelView]）と既存の低輝度時計（[FlipClockView]）を重ね、タッチとタイマーを [PanelController] へ中継する。
 * 子 View には一切タッチを渡さない。時計の最初のタッチは、全ての指が離れるまで消費してホームへ戻るだけ。
 * [apiConfig] が [ApiConfig.Loaded.Absent] 以外なら API モード（投薬予定を取得する。誤りなら取得せず状態だけ出す）。
 */
@SuppressLint("ViewConstructor") // コードからだけ生成する（XML では使わない）
class PanelRoot(
    context: Context,
    apiConfig: ApiConfig.Loaded,
    /** 時計中のウィンドウ輝度。null は指定の解除（端末の明るさ設定に戻す）。 */
    private val onBrightness: (level: Float?) -> Unit,
) : FrameLayout(context), PanelHost {
    private val handler = Handler(Looper.getMainLooper())
    private val model = PanelModel(SystemClock.elapsedRealtime()).also { m ->
        when (apiConfig) {
            ApiConfig.Loaded.Absent -> Unit
            is ApiConfig.Loaded.Valid -> m.enterApiMode(LocalDate.now())
            is ApiConfig.Loaded.Invalid -> {
                // どの規則に外れたかだけを出す（値は出さない）。
                Log.w(TAG, "api config invalid: ${apiConfig.rule.name}")
                m.enterApiMode(LocalDate.now(), configError = true)
            }
        }
    }
    private val controller = PanelController(model, this, { SystemClock.elapsedRealtime() }, ::localNow)
    private val home = PanelView(context, model, controller)
    private val clock = FlipClockView(context)
    private val timers = PanelTimer.entries.associateWith { timer -> Runnable { controller.onTimer(timer) } }
    private val brightnessRunnable: Runnable = Runnable { brightness.onTimer() }
    private val brightness: ClockBrightnessKeeper = ClockBrightnessKeeper(
        localMillis = ::localNow,
        apply = { onBrightness(it) },
        schedule = { delayMs ->
            handler.removeCallbacks(brightnessRunnable)
            handler.postDelayed(brightnessRunnable, delayMs)
        },
        cancel = { handler.removeCallbacks(brightnessRunnable) },
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** API モードで設定が正しいときだけ。通信は作業用スレッドで、結果はメインスレッドで [PanelController.onSchedule] へ。 */
    private val fetcher: ScheduleFetcher? = (apiConfig as? ApiConfig.Loaded.Valid)?.let { valid ->
        ScheduleFetcher(
            source = HttpScheduleSource(valid.config),
            poster = HandlerPoster(Handler(Looper.getMainLooper())),
            executor = { task -> Thread(task, "hogo-fetch").apply { isDaemon = true }.start() },
            onResult = ::onFetched,
        )
    }

    init {
        setBackgroundColor(Color.BLACK)
        isMotionEventSplittingEnabled = false
        addView(home, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clock.visibility = GONE
    }

    fun resume() {
        controller.resume()
        fetcher?.resume()
    }

    /** 描画・タイマー・コールバックを全て止める。 */
    fun pause() {
        fetcher?.pause()
        controller.pause()
        stopEverything()
    }

    private fun onFetched(result: ScheduleResult) {
        // 結果の種類と除いた件数だけを出す（URL・ヘッダー・設定の値は出さない）。
        when (result) {
            is ScheduleResult.Loaded -> if (result.excludedCount > 0) Log.i(TAG, "excluded ${result.excludedCount} occurrences")
            is ScheduleResult.Failed -> Log.w(TAG, "fetch failed: ${result.error.name}")
            is ScheduleResult.Disabled -> Unit
        }
        controller.onSchedule(result)
    }

    /** Android の戻る。確認・メニューを閉じた、または時計から戻ったら true。 */
    fun handleBack(): Boolean = controller.onBack()

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        brightness.stop()
        clock.stop()
    }

    override fun onDetachedFromWindow() {
        fetcher?.pause()
        stopEverything()
        super.onDetachedFromWindow()
    }

    private fun localNow(): Long {
        val wall = System.currentTimeMillis()
        return ClockBrightness.localMillis(wall, TimeZone.getDefault().getOffset(wall))
    }

    // ---- PanelHost ----

    override fun requestRedraw() {
        home.invalidate()
    }

    override fun schedule(timer: PanelTimer, delayMs: Long) {
        val r = timers.getValue(timer)
        handler.removeCallbacks(r)
        handler.postDelayed(r, delayMs)
    }

    override fun cancel(timer: PanelTimer) {
        handler.removeCallbacks(timers.getValue(timer))
    }

    override fun updateClockBadges() {
        clock.badges = model.clockBadges()
    }

    override fun onClockChanged(clockOn: Boolean) {
        if (clockOn) {
            clock.badges = model.clockBadges()
            home.visibility = INVISIBLE
            clock.visibility = VISIBLE
            clock.start()
            brightness.start()
        } else {
            brightness.stop()
            clock.stop()
            clock.visibility = GONE
            home.visibility = VISIBLE
        }
    }

    private class HandlerPoster(private val handler: Handler) : MainPoster {
        override fun post(task: Runnable) {
            handler.post(task)
        }

        override fun postDelayed(task: Runnable, delayMs: Long) {
            handler.postDelayed(task, delayMs)
        }

        override fun removeCallbacks(task: Runnable) {
            handler.removeCallbacks(task)
        }
    }

    // ---- 入力 ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val stage = Stage.fit(width.toFloat(), height.toFloat())
        if (stage.scale <= 0f) return true
        controller.slop = maxOf(16f, touchSlop * 2f / stage.scale)
        val x = stage.toBaseX(ev.x)
        val y = stage.toBaseY(ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> controller.onDown(x, y)
            MotionEvent.ACTION_POINTER_DOWN -> controller.onPointerDown()
            MotionEvent.ACTION_MOVE -> controller.onMove(x, y)
            MotionEvent.ACTION_POINTER_UP -> controller.onPointerUp()
            MotionEvent.ACTION_UP -> controller.onUp(x, y)
            MotionEvent.ACTION_CANCEL -> controller.onCancel()
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return super.dispatchKeyEvent(event)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) PanelKey.PREVIOUS else PanelKey.NEXT
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> PanelKey.PREVIOUS
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> PanelKey.NEXT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE ->
                PanelKey.ACTIVATE
            KeyEvent.KEYCODE_ESCAPE -> PanelKey.ESCAPE
            else -> return super.dispatchKeyEvent(event)
        }
        return controller.onKey(key) || super.dispatchKeyEvent(event)
    }

    private companion object {
        const val TAG = "HogoPanel"
    }
}
