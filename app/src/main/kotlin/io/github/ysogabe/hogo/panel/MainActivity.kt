package io.github.ysogabe.hogo.panel

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 架空データだけの操作モック。通常はホーム、3 分の無操作か時計ボタンで低輝度の時計。
 * 通信・保存・権限は持たない。
 */
class MainActivity : Activity() {
    private lateinit var root: PanelRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = PanelRoot(this) { clock -> applyBrightness(clock) }
        setContentView(root)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        root.resume()
    }

    override fun onPause() {
        root.pause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!root.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 時計だけウィンドウ輝度 0.05。ホームは端末の明るさ設定に戻す。 */
    private fun applyBrightness(clock: Boolean) {
        window.attributes = window.attributes.also {
            it.screenBrightness = if (clock) CLOCK_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
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
