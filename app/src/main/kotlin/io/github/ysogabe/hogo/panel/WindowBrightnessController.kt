package io.github.ysogabe.hogo.panel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Android 部分は TYPE_LIGHT の受信とメインスレッドの予約のみ。設定・権限は変更しない。 */
class WindowBrightnessController(context: Context, localMillis: () -> Long, apply: (Float?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var lastLogMs: Long? = null
    private var timer: Runnable? = null
    private val keeper = AmbientBrightnessKeeper(
        readings = AndroidLightReadings(context, handler) { registered ->
            if (debug) Log.d(TAG, if (registered) "TYPE_LIGHT registered" else "TYPE_LIGHT unavailable; fallback")
        },
        localMillis = localMillis,
        apply = { level ->
            apply(level)
            logApplied(level)
        },
        schedule = { delay, task ->
            cancelTimer()
            val callback = Runnable { task() }
            timer = callback
            handler.postDelayed(callback, delay)
        },
        cancel = ::cancelTimer,
    )

    fun start() {
        lastLogMs = null
        keeper.start()
    }

    fun stop() = keeper.stop()
    fun setClock(visible: Boolean) = keeper.setClock(visible)

    private fun cancelTimer() {
        timer?.let { handler.removeCallbacks(it) }
        timer = null
    }

    private fun logApplied(level: Float?) {
        if (!debug) return
        val now = SystemClock.elapsedRealtime()
        val last = lastLogMs
        if (level == null || keeper.isAtTarget || last == null || now - last >= 1000L) {
            Log.d(TAG, "lux=${keeper.latestLux ?: "unreceived"} brightness=${level ?: "system"}")
            lastLogMs = now
        }
    }

    private companion object {
        const val TAG = "HogoBrightness"
    }
}

private class AndroidLightReadings(
    context: Context,
    private val handler: Handler,
    private val onRegistration: (Boolean) -> Unit,
) : LightReadings {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    override fun start(accept: (FloatArray) -> Unit): Boolean {
        if (listener != null) return true
        val registered = try {
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (sensor == null) false else {
                val candidate = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        // unregister 後、または前セッションのキューに残っていたイベントを無視。
                        if (listener === this && event.sensor.type == Sensor.TYPE_LIGHT) accept(event.values)
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                listener = candidate
                manager.registerListener(candidate, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!registered) stop()
        onRegistration(registered)
        return registered
    }

    override fun stop() {
        val previous = listener ?: return
        listener = null
        try {
            manager?.unregisterListener(previous)
        } catch (_: RuntimeException) {
            // 既に無効な登録も停止済みとして扱う。listener の一致検査で遅延イベントを遮断する。
        }
    }
}
