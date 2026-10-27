package io.github.ysogabe.hogo.panel

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 通常はホーム、3 分の無操作か時計ボタンで時計。全画面で照度センサーに合わせた輝度。
 * 接続設定（`files/api-config.json`。`adb shell run-as` で置く）が無ければ、架空データだけの操作モック（通信しない）。
 * あれば API モードで、debug ビルドだけが 127.0.0.1 の Mock API から投薬予定を取る。
 */
class MainActivity : Activity() {
    private lateinit var root: PanelRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = PanelRoot(this, ApiConfig.load(filesDir)) { level -> applyBrightness(level) }
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

    /** アプリのウィンドウ輝度。null は端末の明るさ設定に戻す。同じ値では更新しない。 */
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
