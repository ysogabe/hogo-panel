package io.github.ysogabe.hogo.panel

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 架空データだけの操作モック。通常はホーム、3 分の無操作か時計ボタンで時間帯別の輝度の時計。
 * 通信・保存・権限は持たない。
 */
class MainActivity : Activity() {
    private lateinit var root: PanelRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = PanelRoot(this) { level -> applyBrightness(level) }
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

    /** 時計中だけ時間帯別のウィンドウ輝度。null（ホーム等）は端末の明るさ設定に戻す。同じ値では更新しない。 */
    private fun applyBrightness(level: Float?) {
        val target = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness == target) return
        window.attributes = window.attributes.also { it.screenBrightness = target }
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
