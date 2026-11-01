package io.github.ysogabe.hogo.panel

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class ContrastPaletteTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val s = ((color ushr shift) and 255) / 255.0
            return if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    private fun ratio(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }
    private fun composite(front: Int, back: Int, alpha: Float): Int = ContrastPalette.blend(back, front, alpha)
    private fun scaled(color: Int, shade: Float): Int {
        fun ch(s: Int) = (((color ushr s) and 255) * shade).toInt() shl s
        return (255 shl 24) or ch(16) or ch(8) or ch(0)
    }

    @Test fun sensorThresholdsAndFallbackAreSeparate() {
        assertEquals(0f, ContrastPalette.factor(.6f, null), 0f)
        assertEquals(0f, ContrastPalette.factor(null, 100f), 0f)
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, ContrastPalette.factor(.6f, invalid), 0f)
            assertEquals(0f, ContrastPalette.factor(invalid, 100f), 0f)
        }
        assertEquals(0f, ContrastPalette.factor(.20f, 20f), 0f)
        assertEquals(.5f, ContrastPalette.factor(.30f, 60f), .00001f)
        assertEquals(1f, ContrastPalette.factor(.40f, 100f), 0f)
        assertEquals(1f, ContrastPalette.factor(.85f, 1000f), 0f)
    }

    @Test fun darkPaletteIsExactAndMidpointInterpolatesEveryRoleIncludingAlpha() {
        for (role in PanelColor.entries) {
            assertEquals(role.dark, ContrastPalette(0f).color(role))
            assertEquals(role.bright, ContrastPalette(1f).color(role))
            assertEquals(ContrastPalette.blend(role.dark, role.bright, .5f), ContrastPalette(.5f).color(role))
        }
        assertEquals(0xD9070C09.toInt(), ContrastPalette(.5f).color(PanelColor.SCRIM))
        assertEquals(0x22FFFFFF, ContrastPalette(1f).color(PanelColor.PRESS))
        assertEquals(0xFF707070.toInt(), ContrastPalette(0f).clockDigit())
        assertEquals(0xFF161616.toInt(), ContrastPalette(0f).clockCard())
    }

    private val pairs = listOf(
        PanelColor.HEADER_NAME to PanelColor.BG, PanelColor.HEADER_TIME to PanelColor.BG,
        PanelColor.HEADER_BUTTON to PanelColor.BG_ATTENTION, PanelColor.STATUS_TEXT to PanelColor.BG_ATTENTION,
        PanelColor.CARE_TEXT to PanelColor.CARE_FILL, PanelColor.CARE_SUB to PanelColor.CARE_FILL,
        PanelColor.CARE_PLUS to PanelColor.CARE_FILL, PanelColor.DIALOG_TITLE to PanelColor.DIALOG_FILL,
        PanelColor.DIALOG_SYMBOL to PanelColor.DIALOG_FILL, PanelColor.MENU_CAPTION to PanelColor.DIALOG_FILL,
        PanelColor.BUTTON_TEXT to PanelColor.BUTTON_FILL, PanelColor.PRIMARY_TEXT to PanelColor.PRIMARY_FILL,
        PanelColor.SELECTED_TEXT to PanelColor.SELECTED_FILL, PanelColor.TOAST_TEXT to PanelColor.TOAST_FILL,
        PanelColor.TOAST_UNDO to PanelColor.TOAST_FILL,
    )
    @Test fun pairedTextContrastIncreasesMonotonicallyAndBrightExceedsSeven() {
        for ((text, fill) in pairs) {
            var previous = 0.0
            for (step in 0..100) {
                val p = ContrastPalette(step / 100f)
                val next = ratio(p.color(text), p.color(fill))
                assertTrue("$text $step: $next < $previous", next + .025 >= previous)
                previous = next
            }
            assertTrue("$text: $previous", previous >= 7.0)
        }
    }

    @Test fun brightBordersFocusAndPressedLabelsRemainDistinct() {
        val p = ContrastPalette(1f)
        for ((border, fill) in listOf(PanelColor.CARE_BORDER to PanelColor.CARE_FILL,
            PanelColor.BUTTON_BORDER to PanelColor.BUTTON_FILL, PanelColor.DIALOG_BORDER to PanelColor.DIALOG_FILL,
            PanelColor.FOCUS to PanelColor.DIALOG_FILL)) {
            assertTrue(ratio(p.color(border), p.color(fill)) >= 3)
        }
        // Press is drawn after text: composite both foreground and background, not only fill.
        for ((text, fill) in pairs) {
            assertTrue("pressed $text", ratio(composite(-1, p.color(text), 34f / 255),
                composite(-1, p.color(fill), 34f / 255)) >= 4.5)
        }
    }

    @Test fun primarySelectedAndDisabledKeepTheirPolarityAndGuardDistinction() {
        for (step in 0..20) {
            val p = ContrastPalette(step / 20f)
            for ((text, fill) in listOf(PanelColor.PRIMARY_TEXT to PanelColor.PRIMARY_FILL,
                PanelColor.SELECTED_TEXT to PanelColor.SELECTED_FILL)) {
                assertTrue(luminance(p.color(text)) < luminance(p.color(fill)))
            }
            val panel = p.color(PanelColor.DIALOG_FILL)
            val fill = p.color(PanelColor.PRIMARY_FILL)
            val text = p.color(PanelColor.PRIMARY_TEXT)
            val disabledFill = composite(fill, panel, 122f / 255)
            val disabledText = composite(text, disabledFill, 122f / 255)
            assertTrue(luminance(disabledText) < luminance(disabledFill))
            assertTrue(ratio(disabledText, disabledFill) < ratio(text, fill))
            assertTrue(ratio(disabledText, disabledFill) >= 1.5)
        }
    }

    @Test fun medicationSemanticsAndPulseWorstCaseKeepContrast() {
        for (phase in listOf(Phase.SOON, Phase.TODAY, Phase.LATE)) {
            var previous = 0.0
            for (step in 0..100) {
                val p = ContrastPalette(step / 100f); val m = p.med(phase)
                val contrast = ratio(m.state, m.fill)
                assertTrue(contrast + .025 >= previous)
                previous = contrast
                assertTrue(ratio(m.text, m.fill) >= 7)
                assertTrue(ratio(m.icon, m.iconFill) >= 4.5)
            }
            val p = ContrastPalette(1f); val m = p.med(phase)
            val fill = PulseMath.lerpColor(m.fill, p.color(PanelColor.BG_ATTENTION), PulseMath.FILL_DIM)
            val border = PulseMath.lerpColor(m.border, p.color(PanelColor.BG_ATTENTION), PulseMath.BORDER_DIM)
            assertTrue(ratio(m.state, fill) >= 7)
            assertTrue(ratio(border, fill) >= 3)
            if (phase == Phase.LATE) assertTrue(((m.state ushr 16) and 255) > (m.state and 255))
            else assertTrue(((m.state ushr 8) and 255) > (m.state and 255))
        }
    }

    @Test fun clockShadeDoesNotRedimBrightDigitsDateOrBadges() {
        var previous = 0.0
        for (step in 0..100) {
            val p = ContrastPalette(step / 100f)
            val shade = p.clockShade(.86f)
            val contrast = ratio(scaled(p.clockDigit(), shade), scaled(p.clockCard(), shade))
            assertTrue(contrast + .025 >= previous)
            previous = contrast
        }
        val p = ContrastPalette(1f)
        assertEquals(1f, p.clockShade(.86f), 0f)
        assertTrue(ratio(p.clockDigit(), p.clockCard()) >= 17)
        assertTrue(ratio(p.badgeText(), p.badgeFill()) >= 12)
        assertTrue(ratio(p.clockBorder(), p.clockCard()) >= 3)
        // Existing flip shadow is temporary (up to 60% black), separate from slow shade.
        assertTrue(ratio(scaled(p.clockDigit(), .4f), scaled(p.clockCard(), .4f)) >= 3)
        println("bright clock=${ratio(p.clockDigit(), p.clockCard())}, badge=${ratio(p.badgeText(), p.badgeFill())}")
        for ((text, fill) in pairs) println("$text=${ratio(p.color(text), p.color(fill))}")
    }
}
