package io.github.ysogabe.hogo.panel

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var clockView: FlipClockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.screenBrightness = CLOCK_BRIGHTNESS }
        clockView = FlipClockView(this)
        setContentView(clockView)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        clockView.start()
    }

    override fun onPause() {
        clockView.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val CLOCK_BRIGHTNESS = 0.05f
    }
}
