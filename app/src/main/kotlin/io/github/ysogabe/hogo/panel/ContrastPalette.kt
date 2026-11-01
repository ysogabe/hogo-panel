package io.github.ysogabe.hogo.panel

/** Rendering roles keep dark surfaces and light labels separate, including inverted buttons. */
enum class PanelColor(val dark: Int, val bright: Int) {
    BG(0xFF181E1B.toInt(), 0xFF101610.toInt()),
    BG_ATTENTION(0xFF25231B.toInt(), 0xFF17140E.toInt()),
    HEADER_ICON(0xFFB5C1AC.toInt(), 0xFFEAF6E0.toInt()),
    HEADER_NAME(0xFFC9D0C3.toInt(), 0xFFFAFFF5.toInt()),
    HEADER_TIME(0xFFA9B1A2.toInt(), 0xFFEFFFE5.toInt()),
    HEADER_BUTTON(0xFF949F8B.toInt(), 0xFFE1F5D5.toInt()),
    STATUS_TEXT(0xFFC0614A.toInt(), 0xFFFFB19A.toInt()),
    CARE_FILL(0xFF212A23.toInt(), 0xFF141D15.toInt()),
    CARE_BORDER(0xFF354034.toInt(), 0xFFA0B892.toInt()),
    CARE_TEXT(0xFFD2DDCB.toInt(), 0xFFF6FFEF.toInt()),
    CARE_SUB(0xFF92A28A.toInt(), 0xFFCEE6BE.toInt()),
    CARE_PLUS(0xFF718A67.toInt(), 0xFFC3E5AE.toInt()),
    SCRIM(0xD9070C09.toInt(), 0xD9070C09.toInt()),
    DIALOG_FILL(0xFF29332B.toInt(), 0xFF182219.toInt()),
    DIALOG_BORDER(0xFF56634F.toInt(), 0xFFBAD1AA.toInt()),
    DIALOG_SYMBOL(0xFFE3CD91.toInt(), 0xFFFFE6A0.toInt()),
    DIALOG_TITLE(0xFFEEF0E9.toInt(), 0xFFFAFFF5.toInt()),
    BUTTON_FILL(0xFF344231.toInt(), 0xFF202D1C.toInt()),
    BUTTON_BORDER(0xFF4A5A43.toInt(), 0xFFB4CEA2.toInt()),
    BUTTON_TEXT(0xFFC6D3BE.toInt(), 0xFFEEFFE2.toInt()),
    PRIMARY_FILL(0xFFD4DDA9.toInt(), 0xFFEFFFC0.toInt()),
    PRIMARY_TEXT(0xFF28371E.toInt(), 0xFF142009.toInt()),
    SELECTED_FILL(0xFFE5CC87.toInt(), 0xFFFFE3A0.toInt()),
    SELECTED_TEXT(0xFF30291A.toInt(), 0xFF211800.toInt()),
    MENU_CAPTION(0xFF9AAB92.toInt(), 0xFFD8ECCC.toInt()),
    TOAST_FILL(0xFFD9E6C8.toInt(), 0xFFF1FFDE.toInt()),
    TOAST_TEXT(0xFF2D3B22.toInt(), 0xFF17230C.toInt()),
    TOAST_UNDO(0xFF354E29.toInt(), 0xFF203A0F.toInt()),
    FOCUS(0xFFAAC2ED.toInt(), 0xFFCDE4FF.toInt()),
    PRESS(0x22FFFFFF, 0x22FFFFFF),
}

data class MedStyle(
    val fill: Int,
    val border: Int,
    val text: Int,
    val iconFill: Int,
    val icon: Int,
    val state: Int,
)

/** Pure Kotlin palettes. factor=0 preserves the original night colors exactly. */
class ContrastPalette(val factor: Float) {
    fun color(role: PanelColor): Int = blend(role.dark, role.bright, factor)

    fun med(phase: Phase): MedStyle {
        val dark = when (phase) {
            Phase.TODAY -> MedStyle(0xFF473524.toInt(), 0xFFC49B53.toInt(), 0xFFFFF0D4.toInt(), 0xFF685032.toInt(), 0xFFF3D399.toInt(), 0xFFF4D08B.toInt())
            Phase.LATE -> MedStyle(0xFF422D28.toInt(), 0xFFB67866.toInt(), 0xFFF4DED3.toInt(), 0xFF634238.toInt(), 0xFFEDBAA4.toInt(), 0xFFECB49E.toInt())
            else -> MedStyle(0xFF393326.toInt(), 0xFFA18B52.toInt(), 0xFFF5ECD5.toInt(), 0xFF574B2D.toInt(), 0xFFE9CF85.toInt(), 0xFFE8CC82.toInt())
        }
        val bright = when (phase) {
            Phase.TODAY -> MedStyle(0xFF271B0C.toInt(), 0xFFFFCF79.toInt(), 0xFFFFF6E6.toInt(), 0xFF39290F.toInt(), 0xFFFFDE9D.toInt(), 0xFFFFD58A.toInt())
            Phase.LATE -> MedStyle(0xFF26130E.toInt(), 0xFFFFAD91.toInt(), 0xFFFFF1E9.toInt(), 0xFF391D14.toInt(), 0xFFFFC9AE.toInt(), 0xFFFFBCA4.toInt())
            else -> MedStyle(0xFF211D10.toInt(), 0xFFEAD18A.toInt(), 0xFFFFFAE9.toInt(), 0xFF302810.toInt(), 0xFFFFE8A5.toInt(), 0xFFF8DE96.toInt())
        }
        return MedStyle(blend(dark.fill, bright.fill, factor), blend(dark.border, bright.border, factor),
            blend(dark.text, bright.text, factor), blend(dark.iconFill, bright.iconFill, factor),
            blend(dark.icon, bright.icon, factor), blend(dark.state, bright.state, factor))
    }

    fun clockShade(original: Float): Float = original + (1f - original) * safe(factor)
    fun clockCard() = blend(0xFF161616.toInt(), 0xFF101410.toInt(), factor)
    fun clockDigit() = blend(0xFF707070.toInt(), 0xFFF5FFF0.toInt(), factor)
    fun clockBorder() = blend(0xFF161616.toInt(), 0xFF91A58A.toInt(), factor)
    fun badgeFill() = blend(0xFF2D2818.toInt(), 0xFF211B0D.toInt(), factor)
    fun badgeBorder() = blend(0xFF786638.toInt(), 0xFFEAD18A.toInt(), factor)
    fun badgeText() = blend(0xFFD9C17E.toInt(), 0xFFFFE8A5.toInt(), factor)

    companion object {
        fun safe(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        /** Only a sensor-derived level may raise contrast; wall-clock fallback is excluded. */
        fun factor(level: Float?, latestLux: Float?): Float =
            if (level == null || !level.isFinite() || latestLux == null || AmbientBrightness.levelAt(latestLux) == null) 0f
            else safe((level - .20f) / .20f)

        fun blend(from: Int, to: Int, factor: Float): Int {
            val t = safe(factor)
            fun ch(shift: Int): Int {
                val a = (from ushr shift) and 255
                val b = (to ushr shift) and 255
                return (a + (b - a) * t).toInt() shl shift
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }
    }
}
