package io.github.ysogabe.hogo.panel

/**
 * 表示中の 4 桁とフリップの状態。時刻は現地時刻のミリ秒、進行は経過時間（単調増加）で受け取る。
 * 通常の 1 分の進みだけ変わった桁をフリップし、初回・時刻補正・前後ジャンプは即時に合わせる。
 */
class ClockModel {
    var digits: IntArray = intArrayOf(0, 0, 0, 0)
        private set

    /** フリップ前の桁。フリップ中のみ非 null。 */
    var previousDigits: IntArray? = null
        private set
    private var flipChanged = BooleanArray(4)
    private var flipStartElapsed = 0L

    private var hasSample = false
    private var lastLocal = 0L
    private var lastElapsed = 0L

    /** 現在時刻へ直接合わせ、進行中のフリップを破棄する。 */
    fun snap(localMillis: Long, elapsedMs: Long) {
        digits = ClockMath.digitsOf(localMillis)
        previousDigits = null
        sample(localMillis, elapsedMs)
    }

    fun update(localMillis: Long, elapsedMs: Long) {
        if (!hasSample) {
            snap(localMillis, elapsedMs)
            return
        }
        val jumped = ClockMath.isClockJump(localMillis - lastLocal, elapsedMs - lastElapsed)
        val minuteDelta = ClockMath.minuteIndex(localMillis) - ClockMath.minuteIndex(lastLocal)
        when {
            jumped || minuteDelta < 0L || minuteDelta > 1L -> snap(localMillis, elapsedMs)
            minuteDelta == 1L -> {
                val next = ClockMath.digitsOf(localMillis)
                val changed = BooleanArray(4) { digits[it] != next[it] }
                if (changed.any { it }) {
                    previousDigits = digits
                    flipChanged = changed
                    flipStartElapsed = elapsedMs
                    digits = next
                }
                sample(localMillis, elapsedMs)
            }
            else -> sample(localMillis, elapsedMs)
        }
        finishFlipIfDone(elapsedMs)
    }

    /** フリップ中なら 0..1。フリップしていない桁・期間外は null。 */
    fun flipProgress(index: Int, elapsedMs: Long): Float? {
        if (previousDigits == null || !flipChanged[index]) return null
        val p = (elapsedMs - flipStartElapsed).toFloat() / ClockMath.FLIP_DURATION_MS
        return if (p in 0f..1f) p else null
    }

    fun isFlipping(elapsedMs: Long): Boolean {
        finishFlipIfDone(elapsedMs)
        return previousDigits != null
    }

    private fun finishFlipIfDone(elapsedMs: Long) {
        if (previousDigits != null && elapsedMs - flipStartElapsed >= ClockMath.FLIP_DURATION_MS) {
            previousDigits = null
        }
    }

    private fun sample(localMillis: Long, elapsedMs: Long) {
        hasSample = true
        lastLocal = localMillis
        lastElapsed = elapsedMs
    }
}
