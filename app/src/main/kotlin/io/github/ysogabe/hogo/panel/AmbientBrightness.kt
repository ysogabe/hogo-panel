package io.github.ysogabe.hogo.panel

import kotlin.math.abs

/** Lux とウィンドウ輝度の区分線形補間。無効な読み取りは採用しない。 */
object AmbientBrightness {
    private val lux = floatArrayOf(0f, 5f, 20f, 100f, 300f, 1000f)
    private val levels = floatArrayOf(.05f, .10f, .20f, .40f, .60f, .85f)

    fun levelAt(value: Float): Float? {
        if (!value.isFinite() || value < 0f) return null
        for (i in 1 until lux.size) {
            if (value <= lux[i]) {
                val fraction = (value - lux[i - 1]) / (lux[i] - lux[i - 1])
                return levels[i - 1] + fraction * (levels[i] - levels[i - 1])
            }
        }
        return levels.last()
    }
}

/** コールバックは呼出元と同じスレッドに直列化する。start 失敗時も stop で後片付けできる。 */
interface LightReadings {
    fun start(accept: (FloatArray) -> Unit): Boolean
    fun stop()
}

/**
 * 前面セッション全体の制御。Android に依存せず、時刻・センサー・予約を注入する。
 * on-change の値は失効させず、イベントなしでもタイマーだけで目標へ到達する。
 */
class AmbientBrightnessKeeper(
    private val readings: LightReadings,
    private val localMillis: () -> Long,
    private val apply: (Float?) -> Unit,
    /** 同時に一つだけ予約。cancel は古いコールバックも取り除く。 */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
) {
    private var active = false
    private var acceptingReadings = false
    private var clock = false
    private var generation = 0L
    private var timerVersion = 0L
    private var pending = false
    private var applied: Float? = null
    private var target: Float? = null
    var latestLux: Float? = null
        private set
    val isAtTarget: Boolean get() = target == applied

    fun start() {
        if (active) return
        active = true
        val session = ++generation
        latestLux = null
        target = null
        fallback()
        acceptingReadings = true
        if (!readings.start { values ->
                if (active && acceptingReadings && session == generation) onReading(values)
            }) {
            acceptingReadings = false
            readings.stop()
            latestLux = null
            target = null
            fallback()
        }
    }

    fun stop() {
        if (!active) return
        active = false
        acceptingReadings = false
        generation++
        clearTimer()
        readings.stop()
        latestLux = null
        target = null
        update(null)
    }

    fun setClock(visible: Boolean) {
        if (clock == visible) return
        clock = visible
        if (active && target == null) fallback()
    }

    private fun onReading(values: FloatArray) {
        val lux = values.firstOrNull() ?: return
        val level = AmbientBrightness.levelAt(lux) ?: return
        latestLux = lux
        val previous = target
        if (previous == null) {
            // 最初だけ即時適用。システムの輝度は不明なので、そこからの補間はしない。
            clearTimer()
            target = level
            update(level)
        } else if (abs(level - previous) >= SIGNIFICANT_CHANGE) {
            target = level
            if (!pending) reserve(TICK_MS, ::step)
        }
    }

    private fun step() {
        val goal = target ?: return
        val current = applied ?: goal
        val delta = goal - current
        update(if (abs(delta) <= MAX_STEP) goal else current + delta.coerceIn(-MAX_STEP, MAX_STEP))
        if (applied != goal) reserve(TICK_MS, ::step)
    }

    private fun fallback() {
        clearTimer()
        if (clock) {
            val now = localMillis()
            update(ClockBrightness.levelAt(now))
            reserve(ClockMath.untilNextMinuteMs(now) + ClockMath.BOUNDARY_MARGIN_MS, ::fallback)
        } else {
            update(null)
        }
    }

    private fun update(level: Float?) {
        if (level == applied) return
        applied = level
        apply(level)
    }

    private fun reserve(delayMs: Long, task: () -> Unit) {
        val session = generation
        val version = ++timerVersion
        pending = true
        schedule(delayMs) {
            if (active && generation == session && timerVersion == version) {
                pending = false
                task()
            }
        }
    }

    private fun clearTimer() {
        timerVersion++
        pending = false
        cancel()
    }

    companion object {
        const val TICK_MS = 250L
        const val MAX_STEP = .025f
        const val SIGNIFICANT_CHANGE = .02f
    }
}
