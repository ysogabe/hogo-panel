package io.github.ysogabe.hogo.panel

import kotlin.math.PI
import kotlin.math.cos

/** 当日・超過の薬カードを明→暗→明とゆっくり往復させる濃淡。位相は単調増加のミリ秒だけで決める。 */
object PulseMath {
    const val PERIOD_MS = 2_400L

    /** 更新間隔（約30fps）。 */
    const val FRAME_MS = 33L

    /** 最も暗いときの、カード背景・枠を地の色へ寄せる割合。文字・アイコンは寄せない。 */
    const val FILL_DIM = 0.55f
    const val BORDER_DIM = 0.45f

    /** 0（既存の色そのまま）〜1（最も暗い）。周期の始まりと終わりは 0。 */
    fun dim(monotonicMs: Long): Float {
        val p = Math.floorMod(monotonicMs, PERIOD_MS).toFloat() / PERIOD_MS
        return (0.5 - 0.5 * cos(2.0 * PI * p)).toFloat()
    }

    /** 動かすフェーズ。2日前（SOON）・普段・実施後は静止。 */
    fun animates(phase: Phase): Boolean = phase == Phase.TODAY || phase == Phase.LATE

    fun lerpColor(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
