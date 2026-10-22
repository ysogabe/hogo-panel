package io.github.ysogabe.hogo.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BADGE_STROKE
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = BADGE_TEXT_SIZE
    }
    private val badgeRect = RectF()
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = DATE_TEXT_SIZE
    }
    private val dateBounds = Rect().also { datePaint.getTextBounds("0", 0, 1, it) }
    private val glyphBounds = Rect()
    private var dateText = ""
    private var dateXs = FloatArray(0)

    /**
     * 時計の下に添える短い札（警告中の薬名だけ）。時計と一緒に動かして焼き付きを避ける。
     * 空なら従来どおり時計だけを描く。
     */
    var badges: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

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
        val hasBadges = badges.isNotEmpty()
        val groupHeight = ClockMath.groupHeight(hasBadges)
        val badgeWidths = badgeWidths()
        val groupWidth = maxOf(ClockMath.CLOCK_WIDTH, badgeWidths.sum() + BADGE_SPACING * (badges.size - 1).coerceAtLeast(0))
        val max = ClockMath.maxOffsets(areaW, areaH, groupWidth, groupHeight)
        val shade = ClockMath.shade(elapsed)
        // 時刻・日付・札のまとまりを画面の中央に置き、そのまま動かす。
        val left = (areaW - ClockMath.CLOCK_WIDTH) / 2f + ClockMath.offsetX(elapsed, max[0])
        val top = (areaH - groupHeight) / 2f + ClockMath.offsetY(elapsed, max[1])

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
        val dateTop = top + ClockMath.CLOCK_HEIGHT + ClockMath.DATE_GAP
        drawDate(canvas, left, dateTop)
        if (hasBadges) {
            val badgeTop = dateTop + ClockMath.DATE_HEIGHT + ClockMath.BADGE_GAP
            drawBadges(canvas, areaW / 2f + ClockMath.offsetX(elapsed, max[0]), badgeTop, badgeWidths, shade)
        }
        canvas.restore()
    }

    /**
     * 日付を時計と同じ幅に置く。文字サイズは固定で字間だけを調整し、
     * 先頭・末尾の字の余白（全角括弧など）を除いた見た目の左右端を時刻全体の幅に揃える。
     */
    private fun drawDate(canvas: Canvas, left: Float, top: Float) {
        val text = model.dateLabel
        if (text != dateText) layoutDate(text)
        datePaint.color = digitPaint.color
        val baseline = top + ClockMath.DATE_HEIGHT / 2f + dateBounds.height() / 2f
        for (i in text.indices) canvas.drawText(text, i, i + 1, left + dateXs[i], baseline, datePaint)
    }

    private fun layoutDate(text: String) {
        dateText = text
        val advances = FloatArray(text.length)
        datePaint.getTextWidths(text, advances)
        datePaint.getTextBounds(text, 0, 1, glyphBounds)
        val inkLeft = glyphBounds.left.toFloat()
        datePaint.getTextBounds(text, text.length - 1, text.length, glyphBounds)
        val inkRightInset = advances.last() - glyphBounds.right
        val natural = advances.sum() - inkLeft - inkRightInset
        val tracking = if (text.length > 1) (ClockMath.CLOCK_WIDTH - natural) / (text.length - 1) else 0f
        dateXs = FloatArray(text.length)
        var x = -inkLeft
        for (i in text.indices) {
            dateXs[i] = x
            x += advances[i] + tracking
        }
    }

    /** 札の文字。幅に収まらない薬名は末尾を「…」で切る。 */
    private fun badgeLabel(name: String): String =
        TextEllipsis.fit(name, BADGE_MAX_TEXT_WIDTH) { badgeText.measureText(it) }

    private fun badgeWidths(): List<Float> = badges.map { badgeText.measureText(badgeLabel(it)) + BADGE_PAD_X * 2 }

    /** [centerX] を中心に、[top] から札を並べる。 */
    private fun drawBadges(canvas: Canvas, centerX: Float, top: Float, widths: List<Float>, shade: Float) {
        badgeFill.color = scaled(BADGE_FILL, shade)
        badgeStroke.color = scaled(BADGE_BORDER, shade)
        badgeText.color = scaled(BADGE_TEXT, shade)
        val total = widths.sum() + BADGE_SPACING * (badges.size - 1)
        var x = centerX - total / 2f
        val metrics = badgeText.fontMetrics
        val baseline = top + ClockMath.BADGE_HEIGHT / 2f - (metrics.ascent + metrics.descent) / 2f
        for ((i, name) in badges.withIndex()) {
            badgeRect.set(x, top, x + widths[i], top + ClockMath.BADGE_HEIGHT)
            canvas.drawRoundRect(badgeRect, BADGE_CORNER, BADGE_CORNER, badgeFill)
            canvas.drawRoundRect(badgeRect, BADGE_CORNER, BADGE_CORNER, badgeStroke)
            canvas.drawText(badgeLabel(name), badgeRect.centerX(), baseline, badgeText)
            x += widths[i] + BADGE_SPACING
        }
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
        const val DIGIT_TEXT_SIZE = 221f
        const val DATE_TEXT_SIZE = 62f
        const val CORNER = 18f
        const val SEAM = 4f
        const val COLON_DOT = 18f

        const val BADGE_FILL = 0xFF2D2818.toInt()
        const val BADGE_BORDER = 0xFF786638.toInt()
        const val BADGE_TEXT = 0xFFD9C17E.toInt()
        const val BADGE_TEXT_SIZE = 23f
        const val BADGE_PAD_X = 18f

        /** 札 1 枚の文字の最大幅。札 2 枚と「ほか N 件」を並べても 960 に収まる。 */
        const val BADGE_MAX_TEXT_WIDTH = 270f
        const val BADGE_SPACING = 18f
        const val BADGE_STROKE = 1f
        const val BADGE_CORNER = 11f
    }
}
