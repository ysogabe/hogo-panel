package io.github.ysogabe.hogo.panel

/** 時計表示中のウィンドウ輝度を現地時刻の時間帯で決める。Android に依存しない。 */
object ClockBrightness {
    /** 06:00 以上 18:00 未満。 */
    const val DAY = 0.60f

    /** 18:00 以上 22:00 未満。 */
    const val EVENING = 0.20f

    /** 22:00 以上、または 06:00 未満（従来の固定値）。 */
    const val NIGHT = 0.05f

    private const val DAY_START_HOUR = 6
    private const val EVENING_START_HOUR = 18
    private const val NIGHT_START_HOUR = 22
    private const val HOUR_MS = 3_600_000L

    /** 現地時刻（UTC 起点にタイムゾーンのずれを足したミリ秒）に対応する輝度。 */
    fun levelAt(localMillis: Long): Float {
        val hour = Math.floorMod(Math.floorDiv(localMillis, HOUR_MS), 24L).toInt()
        return when {
            hour < DAY_START_HOUR -> NIGHT
            hour < EVENING_START_HOUR -> DAY
            hour < NIGHT_START_HOUR -> EVENING
            else -> NIGHT
        }
    }

    /** 壁時計の [wallMillis] に、そのときのタイムゾーンのずれ [offsetMs] を足した現地時刻。 */
    fun localMillis(wallMillis: Long, offsetMs: Int): Long = wallMillis + offsetMs
}

/**
 * 時計が出ている間だけ輝度を決めて適用する。分の境界ごとに現在の壁時計とタイムゾーンで評価し直すので、
 * 6/18/22 時を越えたときも、時刻・タイムゾーンの補正後も 1 分以内に追従する。待ち時間は相対値で、
 * 時間帯の判定には使わない。[stop] 後は予約も再予約もせず、指定を解除（null）する。
 */
class ClockBrightnessKeeper(
    private val localMillis: () -> Long,
    /** null は指定の解除（`BRIGHTNESS_OVERRIDE_NONE`）。 */
    private val apply: (Float?) -> Unit,
    /** 同じ予約があれば置き換える。 */
    private val schedule: (delayMs: Long) -> Unit,
    private val cancel: () -> Unit,
) {
    private var active = false
    private var applied: Float? = null

    val isActive: Boolean get() = active

    fun start() {
        active = true
        evaluate()
    }

    fun stop() {
        active = false
        cancel()
        if (applied != null) {
            applied = null
            apply(null)
        }
    }

    fun onTimer() {
        if (active) evaluate()
    }

    private fun evaluate() {
        val now = localMillis()
        val level = ClockBrightness.levelAt(now)
        if (level != applied) {
            applied = level
            apply(level)
        }
        schedule(ClockMath.untilNextMinuteMs(now) + ClockMath.BOUNDARY_MARGIN_MS)
    }
}
