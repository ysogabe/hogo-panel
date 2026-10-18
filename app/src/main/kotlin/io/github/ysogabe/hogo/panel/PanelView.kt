package io.github.ysogabe.hogo.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.TimeZone

/**
 * ホーム（お世話 3 列 / 警告中は薬カード＋小さなお世話）、確認、取消トースト、モック用シナリオ選択の描画。
 * 承認済み HTML の 960×480 を基準座標とし、View の大きさに合わせて縦横比を保って縮尺する（dp は使わない）。
 * タッチは [PanelRoot] が受けて [PanelController] へ渡すので、この View は描くだけ。
 */
@SuppressLint("ViewConstructor") // コードからだけ生成する（XML では使わない）
class PanelView(
    context: Context,
    private val model: PanelModel,
    private val controller: PanelController,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val stage = Stage.fit(width.toFloat(), height.toFloat())
        if (stage.scale <= 0f) return
        val reminder = model.hasReminder()
        canvas.drawColor(if (reminder) BG_ATTENTION else BG)
        canvas.save()
        canvas.translate(stage.offsetX, stage.offsetY)
        canvas.scale(stage.scale, stage.scale)
        canvas.clipRect(0f, 0f, ClockMath.BASE_WIDTH, ClockMath.BASE_HEIGHT)
        drawHeader(canvas)
        if (reminder) drawMeds(canvas, now)
        drawCares(canvas, reminder, now)
        when {
            model.menuOpen -> drawMenu(canvas)
            model.confirmation != null -> drawDialog(canvas, now)
            else -> model.activeToast(now)?.let { drawToast(canvas, it) }
        }
        canvas.restore()
    }

    // ---- ヘッダー ----

    private fun drawHeader(c: Canvas) {
        drawIcon(c, Icon.CAT, Box(26f, 24f, 28f, 28f), HEADER_ICON, 1.8f)
        drawText(c, "こむぎ", 66f, 38f, 25f, HEADER_NAME, bold = true)
        drawText(c, localTimeText(), 852f, 38f, 26f, HEADER_TIME, Paint.Align.RIGHT)
        val button = PanelLayout.clockButton
        if (controller.pressed == Hit.ClockButton) {
            fill.color = PRESS
            c.drawCircle(button.centerX, button.centerY, button.width / 2f, fill)
        }
        drawIcon(c, Icon.CLOCK, Box(button.centerX - 13.5f, button.centerY - 13.5f, 27f, 27f), HEADER_BUTTON, 1.7f)
    }

    private fun localTimeText(): String {
        val wall = System.currentTimeMillis()
        val d = ClockMath.digitsOf(wall + TimeZone.getDefault().getOffset(wall))
        return "${d[0]}${d[1]}:${d[2]}${d[3]}"
    }

    // ---- 薬カード ----

    private fun drawMeds(c: Canvas, now: Long) {
        val dim = if (controller.pulsing) PulseMath.dim(now) else 0f
        val warnings = model.warnings()
        val boxes = PanelLayout.medBoxes(warnings.size)
        warnings.forEachIndexed { i, w ->
            val box = boxes[i]
            val s = MedStyle.of(w.phase)
            val t = if (PulseMath.animates(w.phase)) dim else 0f
            roundBox(
                c, box, 20f,
                PulseMath.lerpColor(s.fill, BG_ATTENTION, PulseMath.FILL_DIM * t),
                PulseMath.lerpColor(s.border, BG_ATTENTION, PulseMath.BORDER_DIM * t),
            )
            val iconBox = Box(box.left + 24f, box.centerY - 32f, 64f, 64f)
            roundBox(c, iconBox, 20f, s.iconFill, null)
            val glyph = if (w.occurrence.icon == MedIcon.PILL) Icon.PILL else Icon.DROP
            drawIcon(c, glyph, Box(iconBox.centerX - 16.5f, iconBox.centerY - 16.5f, 33f, 33f), s.icon, 2.5f)
            val textX = box.left + 108f
            val maxW = box.width - 108f - 36f - 20f - 24f
            drawText(c, w.occurrence.name, textX, box.centerY - 19f, 38f, s.text, bold = true, maxWidth = maxW)
            drawText(c, stateLabel(w.phase), textX, box.centerY + 27f, 24f, s.state, maxWidth = maxW)
            fill.color = s.state
            c.drawCircle(box.right - 24f - 18f, box.centerY, 9f, fill)
            if (controller.pressed == Hit.Med(w.occurrence.key)) pressOverlay(c, box, 20f)
        }
    }

    private fun stateLabel(p: Phase): String = when (p) {
        Phase.SOON -> "そろそろ"
        Phase.TODAY -> "今日"
        Phase.LATE -> "未確認"
        else -> ""
    }

    // ---- お世話 ----

    private fun drawCares(c: Canvas, reminder: Boolean, now: Long) {
        val boxes = PanelLayout.careBoxes(model.careCount, reminder)
        boxes.forEachIndexed { i, box ->
            roundBox(c, box, 18f, CARE_FILL, CARE_BORDER)
            val name = model.careName(i)
            val elapsed = model.careElapsedText(i, now)
            if (reminder) {
                val x = box.left + 22f
                val maxW = box.width - 44f - 40f
                drawText(c, name, x, box.centerY - 15f, 27f, CARE_TEXT, maxWidth = maxW)
                drawText(c, elapsed, x, box.centerY + 19f, 20f, CARE_SUB, maxWidth = maxW)
                drawPlus(c, box.right - 22f - 15f, box.centerY, 26f)
            } else {
                val maxW = box.width - 40f
                drawText(c, name, box.centerX, box.centerY - 31f, 34f, CARE_TEXT, Paint.Align.CENTER, maxWidth = maxW)
                drawText(c, elapsed, box.centerX, box.centerY + 27f, 42f, CARE_SUB, Paint.Align.CENTER, maxWidth = maxW)
                drawPlus(c, box.right - 26f - 16f, box.bottom - 22f - 20f, 28f)
            }
            if (controller.pressed == Hit.Care(i)) pressOverlay(c, box, 18f)
        }
    }

    private fun drawPlus(c: Canvas, cx: Float, cy: Float, size: Float) {
        stroke.color = CARE_PLUS
        stroke.strokeWidth = 3.5f
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - size / 2f, cy, cx + size / 2f, cy, stroke)
        c.drawLine(cx, cy - size / 2f, cx, cy + size / 2f, stroke)
    }

    // ---- 確認 ----

    private fun drawDialog(c: Canvas, now: Long) {
        scrim(c)
        val icon = model.confirmIcon()
        val d = PanelLayout.dialog(icon != null)
        roundBox(c, d.panel, 24f, DIALOG_FILL, DIALOG_BORDER)
        d.symbolBox?.let { box ->
            drawIcon(c, if (icon == MedIcon.PILL) Icon.PILL else Icon.DROP, box, DIALOG_SYMBOL, 2.5f)
        }
        drawText(
            c, model.confirmTitle().orEmpty(), d.panel.centerX, d.titleCenterY, 38f, DIALOG_TITLE,
            Paint.Align.CENTER, bold = true, maxWidth = d.panel.width - 64f,
        )
        val enabled = model.confirmEnabled(now)
        button(c, d.back, "戻る", primary = false, enabled = true, hit = Hit.DialogBack)
        button(c, d.done, "やった", primary = true, enabled = enabled, hit = Hit.DialogDone)
        if (controller.focusVisible) {
            val target = if (model.confirmFocus == ConfirmFocus.BACK) d.back else d.done
            stroke.color = FOCUS
            stroke.strokeWidth = 3f
            rect.set(target.left - 4f, target.top - 4f, target.right + 4f, target.bottom + 4f)
            c.drawRoundRect(rect, 18f, 18f, stroke)
        }
    }

    private fun button(c: Canvas, box: Box, label: String, primary: Boolean, enabled: Boolean, hit: Hit, selected: Boolean = false) {
        val fillColor = if (primary) PRIMARY_FILL else if (selected) SELECTED_FILL else BUTTON_FILL
        val borderColor = if (primary) PRIMARY_FILL else if (selected) SELECTED_FILL else BUTTON_BORDER
        val textColor = if (primary) PRIMARY_TEXT else if (selected) SELECTED_TEXT else BUTTON_TEXT
        val alpha = if (enabled) 255 else 122
        roundBox(c, box, 14f, withAlpha(fillColor, alpha), withAlpha(borderColor, alpha))
        drawText(c, label, box.centerX, box.centerY, 25f, withAlpha(textColor, alpha), Paint.Align.CENTER, bold = primary, maxWidth = box.width - 16f)
        if (enabled && controller.pressed == hit) pressOverlay(c, box, 14f)
    }

    // ---- トースト ----

    private fun drawToast(c: Canvas, toast: Toast) {
        val box = PanelLayout.toast
        roundBox(c, box, 14f, TOAST_FILL, null)
        val maxW = box.width - 36f - PanelLayout.toastUndoVisual.width - 25f
        drawText(c, toast.text, box.left + 18f, box.centerY, 20f, TOAST_TEXT, maxWidth = maxW)
        if (toast.undo != null) {
            val undo = PanelLayout.toastUndoVisual
            if (controller.pressed == Hit.ToastUndo) pressOverlay(c, undo, 10f)
            drawText(c, "取消", undo.centerX, undo.centerY, 20f, TOAST_UNDO, Paint.Align.CENTER, underline = true)
        }
    }

    // ---- モック用シナリオ選択 ----

    private fun drawMenu(c: Canvas) {
        scrim(c)
        val m = PanelLayout.menu
        roundBox(c, m.panel, 24f, DIALOG_FILL, DIALOG_BORDER)
        drawText(c, "モック：シナリオ選択", m.panel.centerX, m.titleCenterY, 32f, DIALOG_TITLE, Paint.Align.CENTER, bold = true)
        drawText(
            c, "架空データ・この端末のメモリ内だけ・再起動で初期化", m.panel.centerX, m.captionCenterY, 20f, MENU_CAPTION,
            Paint.Align.CENTER, maxWidth = m.panel.width - 64f,
        )
        MenuAction.all.forEachIndexed { i, action ->
            val label = when (action) {
                is MenuAction.Pick -> action.scenario.label
                MenuAction.Reset -> "リセット"
                MenuAction.Close -> "閉じる"
            }
            val selected = action is MenuAction.Pick && action.scenario == model.scenario
            button(c, m.items[i], label, primary = false, enabled = true, hit = Hit.MenuItem(action), selected = selected)
        }
    }

    // ---- 部品 ----

    private fun scrim(c: Canvas) {
        fill.color = SCRIM
        c.drawRect(0f, 0f, ClockMath.BASE_WIDTH, ClockMath.BASE_HEIGHT, fill)
    }

    private fun roundBox(c: Canvas, box: Box, radius: Float, fillColor: Int, borderColor: Int?) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = fillColor
        c.drawRoundRect(rect, radius, radius, fill)
        if (borderColor != null) {
            stroke.color = borderColor
            stroke.strokeWidth = 1f
            rect.inset(0.5f, 0.5f)
            c.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun pressOverlay(c: Canvas, box: Box, radius: Float) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = PRESS
        c.drawRoundRect(rect, radius, radius, fill)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** [cy] を行の縦中心として描く。[maxWidth] を超える文字は縮めて枠内に収める。 */
    private fun drawText(
        c: Canvas,
        s: String,
        x: Float,
        cy: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        bold: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
        underline: Boolean = false,
    ) {
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.textSize = size
        text.color = color
        text.textAlign = align
        text.isUnderlineText = underline
        val w = text.measureText(s)
        if (w > maxWidth && w > 0f) text.textSize = size * maxWidth / w
        val m = text.fontMetrics
        c.drawText(s, x, cy - (m.ascent + m.descent) / 2f, text)
    }

    private enum class Icon { CAT, CLOCK, PILL, DROP }

    /** 承認済み HTML のアイコンを、[box] へ収まる線画として描く。 */
    private fun drawIcon(c: Canvas, icon: Icon, box: Box, color: Int, strokeWidth: Float) {
        stroke.color = color
        stroke.strokeWidth = strokeWidth
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val grid = if (icon == Icon.PILL || icon == Icon.DROP) 48f else 32f
        c.save()
        c.translate(box.left, box.top)
        c.scale(box.width / grid, box.height / grid)
        when (icon) {
            Icon.CAT -> {
                path.reset()
                path.moveTo(5f, 14f)
                path.lineTo(4f, 4f)
                path.lineTo(13f, 10f)
                path.lineTo(19f, 10f)
                path.lineTo(28f, 4f)
                path.lineTo(27f, 14f)
                path.cubicTo(30f, 27f, 2f, 27f, 5f, 14f)
                path.close()
                c.drawPath(path, stroke)
                c.drawPoint(11f, 17f, stroke)
                c.drawPoint(21f, 17f, stroke)
                c.drawLine(14f, 22f, 18f, 22f, stroke)
            }
            Icon.CLOCK -> {
                c.drawCircle(16f, 16f, 11f, stroke)
                path.reset()
                path.moveTo(16f, 9f)
                path.lineTo(16f, 17f)
                path.lineTo(21f, 20f)
                c.drawPath(path, stroke)
            }
            Icon.PILL -> {
                c.save()
                c.rotate(-45f, 24f, 24f)
                rect.set(6f, 15f, 42f, 33f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawLine(24f, 15f, 24f, 33f, stroke)
                c.restore()
            }
            Icon.DROP -> {
                path.reset()
                path.moveTo(24f, 5f)
                path.cubicTo(24f, 5f, 10f, 22f, 10f, 30f)
                rect.set(10f, 16f, 38f, 44f)
                path.arcTo(rect, 180f, -180f)
                path.cubicTo(38f, 22f, 24f, 5f, 24f, 5f)
                path.close()
                c.drawPath(path, stroke)
                rect.set(17f, 22f, 31f, 36f)
                c.drawArc(rect, 90f, 90f, false, stroke)
            }
        }
        c.restore()
    }

    private class MedStyle(
        val fill: Int,
        val border: Int,
        val text: Int,
        val iconFill: Int,
        val icon: Int,
        val state: Int,
    ) {
        companion object {
            private val soon = MedStyle(0xFF393326.toInt(), 0xFFA18B52.toInt(), 0xFFF5ECD5.toInt(), 0xFF574B2D.toInt(), 0xFFE9CF85.toInt(), 0xFFE8CC82.toInt())
            private val today = MedStyle(0xFF473524.toInt(), 0xFFC49B53.toInt(), 0xFFFFF0D4.toInt(), 0xFF685032.toInt(), 0xFFF3D399.toInt(), 0xFFF4D08B.toInt())
            private val late = MedStyle(0xFF422D28.toInt(), 0xFFB67866.toInt(), 0xFFF4DED3.toInt(), 0xFF634238.toInt(), 0xFFEDBAA4.toInt(), 0xFFECB49E.toInt())

            fun of(p: Phase): MedStyle = when (p) {
                Phase.TODAY -> today
                Phase.LATE -> late
                else -> soon
            }
        }
    }

    private companion object {
        const val BG = 0xFF181E1B.toInt()
        const val BG_ATTENTION = 0xFF25231B.toInt()
        const val HEADER_ICON = 0xFFB5C1AC.toInt()
        const val HEADER_NAME = 0xFFC9D0C3.toInt()
        const val HEADER_TIME = 0xFFA9B1A2.toInt()
        const val HEADER_BUTTON = 0xFF949F8B.toInt()
        const val CARE_FILL = 0xFF212A23.toInt()
        const val CARE_BORDER = 0xFF354034.toInt()
        const val CARE_TEXT = 0xFFD2DDCB.toInt()
        const val CARE_SUB = 0xFF92A28A.toInt()
        const val CARE_PLUS = 0xFF718A67.toInt()
        const val SCRIM = 0xD9070C09.toInt()
        const val DIALOG_FILL = 0xFF29332B.toInt()
        const val DIALOG_BORDER = 0xFF56634F.toInt()
        const val DIALOG_SYMBOL = 0xFFE3CD91.toInt()
        const val DIALOG_TITLE = 0xFFEEF0E9.toInt()
        const val BUTTON_FILL = 0xFF344231.toInt()
        const val BUTTON_BORDER = 0xFF4A5A43.toInt()
        const val BUTTON_TEXT = 0xFFC6D3BE.toInt()
        const val PRIMARY_FILL = 0xFFD4DDA9.toInt()
        const val PRIMARY_TEXT = 0xFF28371E.toInt()
        const val SELECTED_FILL = 0xFFE5CC87.toInt()
        const val SELECTED_TEXT = 0xFF30291A.toInt()
        const val MENU_CAPTION = 0xFF9AAB92.toInt()
        const val TOAST_FILL = 0xFFD9E6C8.toInt()
        const val TOAST_TEXT = 0xFF2D3B22.toInt()
        const val TOAST_UNDO = 0xFF354E29.toInt()
        const val FOCUS = 0xFFAAC2ED.toInt()
        const val PRESS = 0x22FFFFFF
    }
}
