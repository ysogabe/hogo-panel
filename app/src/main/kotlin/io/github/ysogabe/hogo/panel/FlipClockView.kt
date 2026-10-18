package io.github.ysogabe.hogo.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.TimeZone

/** 低輝度のフリップ時計。描画の予約は [start] から [stop] の間だけ行う。 */
class FlipClockView(context: Context) : View(context) {
    private val model = ClockModel()
    private var running = false
    private val tick = Runnable { onTick() }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = DIGIT_TEXT_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val shadePaint = Paint().apply { color = Color.BLACK }
    private val seamPaint = Paint().apply { color = Color.BLACK }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitBounds = Rect().also { digitPaint.getTextBounds("0", 0, 1, it) }

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun start() {
        running = true
        model.snap(localNow(), SystemClock.elapsedRealtime())
        invalidate()
        schedule()
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun onTick() {
        if (!running) return
        model.update(localNow(), SystemClock.elapsedRealtime())
        invalidate()
        schedule()
    }

    private fun schedule() {
        removeCallbacks(tick)
        if (model.isFlipping(SystemClock.elapsedRealtime())) {
            postOnAnimation(tick)
        } else {
            postDelayed(tick, ClockMath.nextTickDelayMs(localNow()))
        }
    }

    private fun localNow(): Long {
        val wall = System.currentTimeMillis()
        return wall + TimeZone.getDefault().getOffset(wall)
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.elapsedRealtime()
        val scale = ClockMath.fitScale(width.toFloat(), height.toFloat())
        if (scale <= 0f) return
        val areaW = width / scale
        val areaH = height / scale
        val max = ClockMath.maxOffsets(areaW, areaH)
        val shade = ClockMath.shade(elapsed)
        val left = (areaW - ClockMath.CLOCK_WIDTH) / 2f + ClockMath.offsetX(elapsed, max[0])
        val top = (areaH - ClockMath.CLOCK_HEIGHT) / 2f + ClockMath.offsetY(elapsed, max[1])

        cardPaint.color = scaled(CARD_COLOR, shade)
        digitPaint.color = scaled(DIGIT_COLOR, shade)
        colonPaint.color = digitPaint.color

        canvas.save()
        canvas.scale(scale, scale)
        var x = left
        for (i in 0 until 4) {
            drawCard(canvas, i, x, top, elapsed)
            x += ClockMath.CARD_WIDTH
            x += when (i) {
                0 -> ClockMath.CARD_GAP
                1 -> ClockMath.COLON_WIDTH.also { drawColon(canvas, x, top) }
                2 -> ClockMath.CARD_GAP
                else -> 0f
            }
        }
        canvas.restore()
    }

    private fun drawColon(canvas: Canvas, left: Float, top: Float) {
        val cx = left + ClockMath.COLON_WIDTH / 2f
        for (f in floatArrayOf(0.34f, 0.66f)) {
            val cy = top + ClockMath.CARD_HEIGHT * f
            canvas.drawRect(cx - COLON_DOT / 2f, cy - COLON_DOT / 2f, cx + COLON_DOT / 2f, cy + COLON_DOT / 2f, colonPaint)
        }
    }

    private fun drawCard(canvas: Canvas, index: Int, left: Float, top: Float, elapsed: Long) {
        val now = model.digits[index]
        val p = model.flipProgress(index, elapsed)
        val old = model.previousDigits?.get(index)
        if (p == null || old == null) {
            drawHalf(canvas, left, top, now, upper = true, scaleY = 1f, dim = 0f)
            drawHalf(canvas, left, top, now, upper = false, scaleY = 1f, dim = 0f)
        } else {
            drawHalf(canvas, left, top, now, upper = true, scaleY = 1f, dim = 0f)
            drawHalf(canvas, left, top, old, upper = false, scaleY = 1f, dim = 0f)
            if (p < 0.5f) {
                val s = 1f - 2f * p
                drawHalf(canvas, left, top, old, upper = true, scaleY = s, dim = 0.6f * (1f - s))
            } else {
                val s = 2f * p - 1f
                drawHalf(canvas, left, top, now, upper = false, scaleY = s, dim = 0.6f * (1f - s))
            }
        }
        val midY = top + ClockMath.CARD_HEIGHT / 2f
        canvas.drawRect(left, midY - SEAM / 2f, left + ClockMath.CARD_WIDTH, midY + SEAM / 2f, seamPaint)
    }

    /** カードの上半分または下半分を、中央の継ぎ目を軸に縦方向へ scaleY 倍して描く。 */
    private fun drawHalf(
        canvas: Canvas,
        left: Float,
        top: Float,
        digit: Int,
        upper: Boolean,
        scaleY: Float,
        dim: Float,
    ) {
        val midY = top + ClockMath.CARD_HEIGHT / 2f
        val right = left + ClockMath.CARD_WIDTH
        val clipTop = if (upper) top else midY
        val clipBottom = if (upper) midY else top + ClockMath.CARD_HEIGHT
        canvas.save()
        canvas.scale(1f, scaleY.coerceAtLeast(0.001f), left, midY)
        canvas.clipRect(left, clipTop, right, clipBottom)
        canvas.drawRoundRect(left, top, right, top + ClockMath.CARD_HEIGHT, CORNER, CORNER, cardPaint)
        canvas.drawText(
            digit.toString(),
            left + ClockMath.CARD_WIDTH / 2f,
            midY + digitBounds.height() / 2f,
            digitPaint,
        )
        if (dim > 0f) {
            shadePaint.alpha = (255 * dim).toInt()
            canvas.drawRect(left, clipTop, right, clipBottom, shadePaint)
        }
        canvas.restore()
    }

    private fun scaled(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(),
        (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt(),
    )

    private companion object {
        const val CARD_COLOR = 0xFF161616.toInt()
        const val DIGIT_COLOR = 0xFF707070.toInt()
        const val DIGIT_TEXT_SIZE = 170f
        const val CORNER = 14f
        const val SEAM = 3f
        const val COLON_DOT = 14f
    }
}
