package io.github.ysogabe.hogo.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import java.util.TimeZone

/**
 * ホーム（[PanelView]）と既存の低輝度時計（[FlipClockView]）を重ね、タッチとタイマーを [PanelController] へ中継する。
 * 子 View には一切タッチを渡さない。時計の最初のタッチは、全ての指が離れるまで消費してホームへ戻るだけ。
 */
@SuppressLint("ViewConstructor") // コードからだけ生成する（XML では使わない）
class PanelRoot(
    context: Context,
    /** 時計中のウィンドウ輝度。null は指定の解除（端末の明るさ設定に戻す）。 */
    private val onBrightness: (level: Float?) -> Unit,
) : FrameLayout(context), PanelHost {
    private val handler = Handler(Looper.getMainLooper())
    private val model = PanelModel(SystemClock.elapsedRealtime())
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

    init {
        setBackgroundColor(Color.BLACK)
        isMotionEventSplittingEnabled = false
        addView(home, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clock.visibility = GONE
    }

    fun resume() = controller.resume()

    /** 描画・タイマー・コールバックを全て止める。 */
    fun pause() {
        controller.pause()
        stopEverything()
    }

    /** Android の戻る。確認・メニューを閉じた、または時計から戻ったら true。 */
    fun handleBack(): Boolean = controller.onBack()

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        brightness.stop()
        clock.stop()
    }

    override fun onDetachedFromWindow() {
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
}
