package io.github.ysogabe.hogo.panel

/** 照度未受信時の時計のウィンドウ輝度を現地時刻の時間帯で決める。Android に依存しない。 */
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
