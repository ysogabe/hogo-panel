package io.github.ysogabe.hogo.panel

/** 基準座標（960×480 の物理 px 相当）での矩形。 */
data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun inset(d: Float): Box = Box(left + d, top + d, width - 2 * d, height - 2 * d)
}

/** 基準座標から View への配置。縦横比を保って収め、余りは中央に寄せる。 */
data class Stage(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toBaseX(viewX: Float): Float = (viewX - offsetX) / scale
    fun toBaseY(viewY: Float): Float = (viewY - offsetY) / scale

    companion object {
        fun fit(viewWidth: Float, viewHeight: Float): Stage {
            val scale = ClockMath.fitScale(viewWidth, viewHeight)
            return Stage(
                scale,
                (viewWidth - ClockMath.BASE_WIDTH * scale) / 2f,
                (viewHeight - ClockMath.BASE_HEIGHT * scale) / 2f,
            )
        }
    }
}

/** 承認済み HTML（960×480）の寸法を基準座標へ写したもの。dp ではなく px を基準にして倍率で縮尺する。 */
object PanelLayout {
    val stage = Box(0f, 0f, ClockMath.BASE_WIDTH, ClockMath.BASE_HEIGHT)
    const val HEADER_HEIGHT = 76f
    val header = Box(0f, 0f, ClockMath.BASE_WIDTH, HEADER_HEIGHT)
    val clockButton = Box(874f, 8f, 60f, 60f)

    /** 長押しでシナリオ選択を開く範囲（時計ボタンを除くヘッダー）。 */
    val headerHold = Box(0f, 0f, 874f, HEADER_HEIGHT)

    val content = Box(24f, 92f, 912f, 364f)
    private const val MEDS_WIDTH = 560f
    private const val COLUMN_GAP = 16f
    private const val CARE_GAP = 12f

    /** 「ほか N 件」の帯の高さ（カードの下）。 */
    private const val MORE_HEIGHT = 30f
    private const val MORE_GAP = 6f

    /**
     * 警告中の薬カード。1 件なら高さいっぱいの 1 枚、2 件なら上下に分ける。
     * [withMore] のときは、下に「ほか N 件」の帯（[moreBox]）の分だけカードを低くする。
     */
    fun medBoxes(count: Int, withMore: Boolean = false): List<Box> {
        if (count <= 0) return emptyList()
        val gap = 16f
        val area = if (withMore) content.height - MORE_HEIGHT - MORE_GAP else content.height
        val h = (area - gap * (count - 1)) / count
        return List(count) { Box(content.left, content.top + it * (h + gap), MEDS_WIDTH, h) }
    }

    /** 2 枚目のカードの下の「ほか N 件」を置く帯（[medBoxes] を `withMore = true` で作るときだけ使う）。 */
    val moreBox = Box(content.left, content.bottom - MORE_HEIGHT, MEDS_WIDTH, MORE_HEIGHT)

    /** お世話 3 項目。警告がなければ大きな 3 列、あれば右に小さく縦並び。 */
    fun careBoxes(count: Int, withReminder: Boolean): List<Box> {
        if (count <= 0) return emptyList()
        return if (withReminder) {
            val x = content.left + MEDS_WIDTH + COLUMN_GAP
            val w = content.right - x
            val h = (content.height - CARE_GAP * (count - 1)) / count
            List(count) { Box(x, content.top + it * (h + CARE_GAP), w, h) }
        } else {
            val w = (content.width - CARE_GAP * (count - 1)) / count
            List(count) { Box(content.left + it * (w + CARE_GAP), content.top, w, content.height) }
        }
    }

    // ---- 確認ダイアログ ----

    class DialogBoxes(val panel: Box, val back: Box, val done: Box, val titleCenterY: Float, val symbolBox: Box?)

    private const val DIALOG_WIDTH = 580f
    private const val DIALOG_PAD = 32f
    private const val DIALOG_BUTTON_H = 78f
    private const val DIALOG_BUTTON_GAP = 14f
    private const val DIALOG_TITLE_H = 46f
    private const val DIALOG_SYMBOL = 64f

    fun dialog(withSymbol: Boolean): DialogBoxes {
        val height = DIALOG_PAD + (if (withSymbol) DIALOG_SYMBOL + 20f else 0f) +
            DIALOG_TITLE_H + 32f + DIALOG_BUTTON_H + DIALOG_PAD
        val panel = Box((ClockMath.BASE_WIDTH - DIALOG_WIDTH) / 2f, (ClockMath.BASE_HEIGHT - height) / 2f, DIALOG_WIDTH, height)
        val buttonW = (DIALOG_WIDTH - DIALOG_PAD * 2 - DIALOG_BUTTON_GAP) / 2f
        val buttonTop = panel.bottom - DIALOG_PAD - DIALOG_BUTTON_H
        val back = Box(panel.left + DIALOG_PAD, buttonTop, buttonW, DIALOG_BUTTON_H)
        val done = Box(back.right + DIALOG_BUTTON_GAP, buttonTop, buttonW, DIALOG_BUTTON_H)
        val symbolBox = if (withSymbol) {
            Box(panel.centerX - DIALOG_SYMBOL / 2f, panel.top + DIALOG_PAD, DIALOG_SYMBOL, DIALOG_SYMBOL)
        } else {
            null
        }
        val titleTop = panel.top + DIALOG_PAD + (if (withSymbol) DIALOG_SYMBOL + 20f else 0f)
        return DialogBoxes(panel, back, done, titleTop + DIALOG_TITLE_H / 2f, symbolBox)
    }

    // ---- トースト ----

    /** 内容によらず固定の大きさ。取消後も同じ幅・高さを保つ。 */
    val toast = Box(220f, 382f, 520f, 76f)

    /** 取消ボタンの見た目（72×60）。 */
    val toastUndoVisual = Box(toast.right - 18f - 72f, toast.top + 8f, 72f, 60f)

    /** 取消の受付範囲。見た目より広く取り、親指でも押せる大きさにする。 */
    val toastUndoHit = Box(toast.right - 110f, toast.top, 110f, toast.height)

    // ---- シナリオ選択（モック用） ----

    class MenuBoxes(val panel: Box, val titleCenterY: Float, val captionCenterY: Float, val items: List<Box>)

    val menu: MenuBoxes by lazy {
        val cols = 4
        val rows = (MenuAction.all.size + cols - 1) / cols
        val pad = 32f
        val gap = 14f
        val buttonH = 84f
        val width = 820f
        val buttonW = (width - pad * 2 - gap * (cols - 1)) / cols
        val headBlock = 44f + 30f + 24f
        val height = pad + headBlock + rows * buttonH + (rows - 1) * gap + pad
        val panel = Box((ClockMath.BASE_WIDTH - width) / 2f, (ClockMath.BASE_HEIGHT - height) / 2f, width, height)
        val gridTop = panel.top + pad + headBlock
        val items = List(MenuAction.all.size) {
            Box(
                panel.left + pad + (it % cols) * (buttonW + gap),
                gridTop + (it / cols) * (buttonH + gap),
                buttonW,
                buttonH,
            )
        }
        MenuBoxes(panel, panel.top + pad + 22f, panel.top + pad + 44f + 15f, items)
    }
}

/** タッチの対象。押し始めと離した位置で同じ対象のときだけ操作として扱う。 */
sealed interface Hit {
    data object ClockButton : Hit
    data object HeaderHold : Hit
    data class Med(val occurrenceKey: String) : Hit
    data class Care(val index: Int) : Hit
    data object DialogBack : Hit
    data object DialogDone : Hit
    data object Scrim : Hit
    data object ToastUndo : Hit
    data class MenuItem(val action: MenuAction) : Hit
    data object MenuScrim : Hit

    /** 何も起こさず、背後へも通さない面（無効なボタン、ダイアログの余白、トースト本体）。 */
    data object Swallow : Hit
}

object PanelHits {
    fun at(model: PanelModel, x: Float, y: Float, nowMs: Long): Hit? {
        if (model.screen != Screen.HOME) return null
        if (model.menuOpen) return menuHit(x, y)
        val confirmation = model.confirmation
        if (confirmation != null) return dialogHit(model, x, y, nowMs)
        val toast = model.activeToast(nowMs)
        if (toast != null && PanelLayout.toast.contains(x, y)) {
            return if (toast.undo != null && PanelLayout.toastUndoHit.contains(x, y)) Hit.ToastUndo else Hit.Swallow
        }
        return homeHit(model, x, y)
    }

    private fun menuHit(x: Float, y: Float): Hit {
        val menu = PanelLayout.menu
        if (!menu.panel.contains(x, y)) return Hit.MenuScrim
        menu.items.forEachIndexed { i, box -> if (box.contains(x, y)) return Hit.MenuItem(MenuAction.all[i]) }
        return Hit.Swallow
    }

    private fun dialogHit(model: PanelModel, x: Float, y: Float, nowMs: Long): Hit {
        val d = PanelLayout.dialog(model.confirmIcon() != null)
        return when {
            d.back.contains(x, y) -> Hit.DialogBack
            d.done.contains(x, y) -> if (model.confirmEnabled(nowMs)) Hit.DialogDone else Hit.Swallow
            d.panel.contains(x, y) -> Hit.Swallow
            else -> Hit.Scrim
        }
    }

    private fun homeHit(model: PanelModel, x: Float, y: Float): Hit? {
        if (PanelLayout.clockButton.contains(x, y)) return Hit.ClockButton
        if (PanelLayout.headerHold.contains(x, y)) return Hit.HeaderHold
        val warnings = model.shownWarnings()
        PanelLayout.medBoxes(warnings.size, model.hiddenWarningCount() > 0).forEachIndexed { i, box ->
            if (box.contains(x, y)) return Hit.Med(warnings[i].occurrence.key)
        }
        PanelLayout.careBoxes(model.careCount, warnings.isNotEmpty()).forEachIndexed { i, box ->
            if (box.contains(x, y)) return Hit.Care(i)
        }
        return null
    }
}
