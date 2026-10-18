package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** 時計の配置・動き・時刻計算。Android に依存しない純粋な関数だけを置く。 */
object ClockMath {
    const val BASE_WIDTH = 960f
    const val BASE_HEIGHT = 480f

    const val CARD_WIDTH = 182f
    const val CARD_HEIGHT = 281f
    const val CARD_GAP = 12f
    const val COLON_WIDTH = 62f
    const val CLOCK_WIDTH = CARD_WIDTH * 4 + CARD_GAP * 2 + COLON_WIDTH
    const val CLOCK_HEIGHT = CARD_HEIGHT

    /** 時刻の直下に、時計と同じ幅で置く日付の行。 */
    const val DATE_GAP = 18f
    const val DATE_HEIGHT = 64f

    /** 日付の下に並べる薬名の札。 */
    const val BADGE_GAP = 24f
    const val BADGE_HEIGHT = 50f

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
    private const val DAY_MS = 86_400_000L
    private const val WEEKDAYS = "月火水木金土日"

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

    /** 時刻・日付（・札）をひとまとめにした高さ。札があるときは日付の下に札の分を足す。 */
    fun groupHeight(hasBadges: Boolean): Float =
        CLOCK_HEIGHT + DATE_GAP + DATE_HEIGHT + if (hasBadges) BADGE_GAP + BADGE_HEIGHT else 0f

    /** 画面端から欠けない移動量の上限。基準座標系での [x, y]。まとまりの大きさを渡す。 */
    fun maxOffsets(
        areaWidth: Float,
        areaHeight: Float,
        contentWidth: Float = CLOCK_WIDTH,
        contentHeight: Float = groupHeight(false),
    ): FloatArray = floatArrayOf(
        min(MAX_OFFSET_X, ((areaWidth - contentWidth) / 2f).coerceAtLeast(0f)),
        min(MAX_OFFSET_Y, ((areaHeight - contentHeight) / 2f).coerceAtLeast(0f)),
    )

    /** 現地時刻から `2026/10/19（月）` 形式。暦日は現地時刻のミリ秒の日付で、時刻の桁と同じ値から求める。 */
    fun dateLabelOf(localMillis: Long): String {
        val date = LocalDate.ofEpochDay(Math.floorDiv(localMillis, DAY_MS))
        val weekday = WEEKDAYS[date.dayOfWeek.value - 1]
        return String.format(Locale.ROOT, "%04d/%02d/%02d（%c）", date.year, date.monthValue, date.dayOfMonth, weekday)
    }

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
