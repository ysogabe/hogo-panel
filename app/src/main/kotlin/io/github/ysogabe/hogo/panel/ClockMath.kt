package io.github.ysogabe.hogo.panel

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** 時計の配置・動き・時刻計算。Android に依存しない純粋な関数だけを置く。 */
object ClockMath {
    const val BASE_WIDTH = 960f
    const val BASE_HEIGHT = 480f

    const val CARD_WIDTH = 140f
    const val CARD_HEIGHT = 216f
    const val CARD_GAP = 9f
    const val COLON_WIDTH = 48f
    const val CLOCK_WIDTH = CARD_WIDTH * 4 + CARD_GAP * 2 + COLON_WIDTH
    const val CLOCK_HEIGHT = CARD_HEIGHT

    const val MAX_OFFSET_X = 48f
    const val MAX_OFFSET_Y = 32f
    const val PERIOD_X_SEC = 317.0
    const val PERIOD_Y_SEC = 211.0
    const val PERIOD_SHADE_SEC = 463.0
    const val SHADE_MIN = 0.86f
    const val SHADE_MAX = 1.00f

    const val FLIP_DURATION_MS = 450L
    const val BOUNDARY_MARGIN_MS = 20L
    const val IDLE_TICK_MS = 1000L
    const val CLOCK_JUMP_THRESHOLD_MS = 2000L

    private const val MINUTE_MS = 60_000L
    private const val DAY_MINUTES = 1440L

    /** 現地時刻（UTC 起点にタイムゾーンのずれを足したミリ秒）から [H1, H2, M1, M2]。 */
    fun digitsOf(localMillis: Long): IntArray {
        val minuteOfDay = Math.floorMod(Math.floorDiv(localMillis, MINUTE_MS), DAY_MINUTES).toInt()
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        return intArrayOf(hour / 10, hour % 10, minute / 10, minute % 10)
    }

    fun minuteIndex(localMillis: Long): Long = Math.floorDiv(localMillis, MINUTE_MS)

    /** 次の描画までの待ち時間。通常は 1 秒、分境界が近ければ境界の直後。 */
    fun nextTickDelayMs(localMillis: Long): Long {
        val untilBoundary = MINUTE_MS - Math.floorMod(localMillis, MINUTE_MS)
        return min(IDLE_TICK_MS, untilBoundary + BOUNDARY_MARGIN_MS)
    }

    /** 次の分境界までの時間。ホームの時刻表示を分が変わる直後に更新するために使う。 */
    fun untilNextMinuteMs(localMillis: Long): Long = MINUTE_MS - Math.floorMod(localMillis, MINUTE_MS)

    /** 壁時計と経過時間の進みの差が閾値を超えたら、時刻（またはタイムゾーン）の補正とみなす。 */
    fun isClockJump(localDeltaMs: Long, elapsedDeltaMs: Long): Boolean =
        abs(localDeltaMs - elapsedDeltaMs) > CLOCK_JUMP_THRESHOLD_MS

    /** 960x480 の基準座標へ縦横比を保って収めるための倍率。 */
    fun fitScale(viewWidth: Float, viewHeight: Float): Float =
        min(viewWidth / BASE_WIDTH, viewHeight / BASE_HEIGHT)

    /** 画面端から欠けない移動量の上限。基準座標系での [x, y]。 */
    fun maxOffsets(areaWidth: Float, areaHeight: Float): FloatArray = floatArrayOf(
        min(MAX_OFFSET_X, ((areaWidth - CLOCK_WIDTH) / 2f).coerceAtLeast(0f)),
        min(MAX_OFFSET_Y, ((areaHeight - CLOCK_HEIGHT) / 2f).coerceAtLeast(0f)),
    )

    fun offsetX(elapsedMs: Long, max: Float): Float = max * wave(elapsedMs, PERIOD_X_SEC)

    fun offsetY(elapsedMs: Long, max: Float): Float = max * wave(elapsedMs, PERIOD_Y_SEC)

    /** 濃淡の係数。[SHADE_MIN, SHADE_MAX] をなめらかに往復する。 */
    fun shade(elapsedMs: Long): Float {
        val mid = (SHADE_MIN + SHADE_MAX) / 2f
        val amp = (SHADE_MAX - SHADE_MIN) / 2f
        return mid + amp * wave(elapsedMs, PERIOD_SHADE_SEC)
    }

    private fun wave(elapsedMs: Long, periodSec: Double): Float =
        sin(2.0 * PI * (elapsedMs / 1000.0) / periodSec).toFloat()
}
