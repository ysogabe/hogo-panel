package io.github.ysogabe.hogo.panel

import java.time.LocalDate

/** 試用用の架空データ。実際の薬名・予定・記録ではない。 */
object MockData {
    /** 固定の架空の予定日。壁時計とは無関係。 */
    val SCHEDULED: LocalDate = LocalDate.of(2026, 10, 20)

    val occurrences = listOf(
        Occurrence("filaria-20261020", "filaria", "フィラリア", MedIcon.PILL, SCHEDULED),
        Occurrence("frontline-20261020", "frontline", "フロントライン", MedIcon.DROP, SCHEDULED),
    )

    /** お世話の名前と、最後に実施してからの架空の経過分。 */
    val cares = listOf(
        CareSeed("ごはん", 120L),
        CareSeed("トイレ", 50L),
        CareSeed("目薬", 120L),
    )
}

class CareSeed(val name: String, val agoMinutes: Long)

/** メニューで選べる試用シナリオ。日付は固定で壁時計から分離している。 */
enum class Scenario(val label: String, val date: LocalDate, val allDone: Boolean = false, val showsClock: Boolean = false) {
    NORMAL("普段", LocalDate.of(2026, 10, 17)),
    SOON("2日前", LocalDate.of(2026, 10, 18)),
    TODAY("当日", LocalDate.of(2026, 10, 20)),
    LATE("過ぎたら", LocalDate.of(2026, 10, 21)),
    DONE("実施後", LocalDate.of(2026, 10, 20), allDone = true),
    CLOCK("時計", LocalDate.of(2026, 10, 18), showsClock = true),
}

/** シナリオ選択の項目。 */
sealed interface MenuAction {
    data class Pick(val scenario: Scenario) : MenuAction
    data object Reset : MenuAction
    data object Close : MenuAction

    companion object {
        /** 画面に並べる順。 */
        val all: List<MenuAction> = Scenario.entries.map { Pick(it) } + Reset + Close
    }
}

enum class Screen { HOME, CLOCK }

sealed interface Target {
    data class Med(val occurrenceKey: String) : Target
    data class Care(val index: Int) : Target
}

enum class ConfirmFocus { BACK, DONE }

/** 開いた時点の対象と時刻。この対象にだけ記録する。 */
data class Confirmation(val target: Target, val openedAtMs: Long)

sealed interface Undo {
    data class Med(val occurrenceKey: String) : Undo
    data class Care(val index: Int, val previousAtMs: Long) : Undo
}

data class Toast(val text: String, val undo: Undo?, val expiresAtMs: Long)

enum class ConfirmResult { NOT_OPEN, TOO_EARLY, RECORDED, ALREADY_RECORDED }

/**
 * 画面の状態。メモリ内だけで、通信も保存もしない。時刻は呼び出し側が渡す単調増加のミリ秒で、
 * 壁時計や日付とは関係ない（日付は [today]、シナリオが決める固定のサンプル日）。
 */
class PanelModel(
    nowMs: Long,
    private val occurrences: List<Occurrence> = MockData.occurrences,
    private val careSeeds: List<CareSeed> = MockData.cares,
    val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    var today: LocalDate = Scenario.NORMAL.date
        private set
    var scenario: Scenario? = Scenario.NORMAL
        private set
    var screen: Screen = Screen.HOME
        private set
    var confirmation: Confirmation? = null
        private set
    var confirmFocus: ConfirmFocus = ConfirmFocus.BACK
        private set
    var menuOpen: Boolean = false
        private set
    var toast: Toast? = null
        private set
    var lastInteractionMs: Long = nowMs
        private set

    private val doneKeys = mutableSetOf<String>()
    private val careLastAtMs = LongArray(careSeeds.size)

    /** テスト用。実施済みの回を直接足して、二重記録の経路を作る。 */
    internal fun markDoneForTest(key: String) {
        doneKeys.add(key)
    }

    fun isDone(occurrenceKey: String): Boolean = occurrenceKey in doneKeys

    init {
        applyScenario(Scenario.NORMAL, nowMs)
    }

    // ---- 表示用の導出 ----

    fun warnings(): List<Warning> = ReminderPolicy.warnings(occurrences, doneKeys, today)

    /** 警告対象があるときだけ左に薬・右に小さなお世話の配置にする。 */
    fun hasReminder(): Boolean = warnings().isNotEmpty()

    /** 時計の札に出す薬名。実施済みの回は含まない。 */
    fun clockBadges(): List<String> = warnings().map { it.occurrence.name }

    val careCount: Int get() = careSeeds.size

    fun careName(index: Int): String = careSeeds[index].name

    fun careElapsedText(index: Int, nowMs: Long): String =
        ElapsedText.format(((nowMs - careLastAtMs[index]) / 60_000L).coerceAtLeast(0L))

    fun confirmTitle(): String? = when (val t = confirmation?.target) {
        is Target.Med -> occurrences.firstOrNull { it.key == t.occurrenceKey }?.name
        is Target.Care -> careName(t.index)
        null -> null
    }

    fun confirmIcon(): MedIcon? = (confirmation?.target as? Target.Med)?.let { t ->
        occurrences.firstOrNull { it.key == t.occurrenceKey }?.icon
    }

    /** 開いてから [CONFIRM_LOCK_MS] 経つまで「やった」は無効。 */
    fun confirmEnabled(nowMs: Long): Boolean {
        val c = confirmation ?: return false
        return nowMs - c.openedAtMs >= CONFIRM_LOCK_MS
    }

    fun confirmUnlockRemainingMs(nowMs: Long): Long? {
        val c = confirmation ?: return null
        val remaining = CONFIRM_LOCK_MS - (nowMs - c.openedAtMs)
        return if (remaining > 0) remaining else null
    }

    /** 期限内のトースト。期限切れで [tick] が遅れていても出さない。 */
    fun activeToast(nowMs: Long): Toast? = toast?.takeIf { nowMs < it.expiresAtMs }

    fun toastRemainingMs(nowMs: Long): Long? = activeToast(nowMs)?.let { it.expiresAtMs - nowMs }

    // ---- 確認 ----

    fun openMed(occurrenceKey: String, nowMs: Long): Boolean {
        if (screen != Screen.HOME || menuOpen) return false
        if (warnings().none { it.occurrence.key == occurrenceKey }) return false
        open(Target.Med(occurrenceKey), nowMs)
        return true
    }

    fun openCare(index: Int, nowMs: Long): Boolean {
        if (screen != Screen.HOME || menuOpen || index !in 0 until careSeeds.size) return false
        open(Target.Care(index), nowMs)
        return true
    }

    private fun open(target: Target, nowMs: Long) {
        toast = null
        confirmation = Confirmation(target, nowMs)
        confirmFocus = ConfirmFocus.BACK
    }

    /** 戻る・外側・Android Back。何も記録しない。 */
    fun closeConfirmation() {
        confirmation = null
        confirmFocus = ConfirmFocus.BACK
    }

    fun moveConfirmFocus() {
        if (confirmation == null) return
        confirmFocus = if (confirmFocus == ConfirmFocus.BACK) ConfirmFocus.DONE else ConfirmFocus.BACK
    }

    /** 開いた時点の対象だけに記録する。時間ガードはここにも置く（ボタンの無効表示に頼らない）。 */
    fun confirm(nowMs: Long): ConfirmResult {
        val c = confirmation ?: return ConfirmResult.NOT_OPEN
        if (nowMs - c.openedAtMs < CONFIRM_LOCK_MS) return ConfirmResult.TOO_EARLY
        confirmation = null
        confirmFocus = ConfirmFocus.BACK
        return when (val t = c.target) {
            is Target.Med -> recordMed(t.occurrenceKey, nowMs)
            is Target.Care -> recordCare(t.index, nowMs)
        }
    }

    private fun recordMed(key: String, nowMs: Long): ConfirmResult {
        val occurrence = occurrences.firstOrNull { it.key == key } ?: return ConfirmResult.NOT_OPEN
        if (!doneKeys.add(key)) {
            toast = Toast("${occurrence.name} は記録済み", null, nowMs + TOAST_MS)
            return ConfirmResult.ALREADY_RECORDED
        }
        toast = Toast("${occurrence.name} 記録しました", Undo.Med(key), nowMs + TOAST_MS)
        return ConfirmResult.RECORDED
    }

    private fun recordCare(index: Int, nowMs: Long): ConfirmResult {
        val previous = careLastAtMs[index]
        careLastAtMs[index] = nowMs
        toast = Toast("${careSeeds[index].name} 記録しました", Undo.Care(index, previous), nowMs + TOAST_MS)
        return ConfirmResult.RECORDED
    }

    /** 8 秒以内なら、そのトーストの対象だけを元に戻す。 */
    fun undo(nowMs: Long): Boolean {
        val t = activeToast(nowMs) ?: return false
        val action = t.undo ?: return false
        when (action) {
            is Undo.Med -> doneKeys.remove(action.occurrenceKey)
            is Undo.Care -> careLastAtMs[action.index] = action.previousAtMs
        }
        // 幅・高さは画面側の固定サイズなので、取消後も同じ期限で残して下へのタップを通さない。
        toast = Toast("取り消しました", null, t.expiresAtMs)
        return true
    }

    fun tick(nowMs: Long) {
        if (toast != null && activeToast(nowMs) == null) toast = null
    }

    // ---- 時計 ----

    fun idleDue(nowMs: Long): Boolean = screen == Screen.HOME && nowMs - lastInteractionMs >= idleTimeoutMs

    fun idleRemainingMs(nowMs: Long): Long = (idleTimeoutMs - (nowMs - lastInteractionMs)).coerceAtLeast(0L)

    fun markInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /** 確認・メニュー・取消の受付を終えて時計へ。 */
    fun enterClock() {
        confirmation = null
        confirmFocus = ConfirmFocus.BACK
        menuOpen = false
        toast = null
        screen = Screen.CLOCK
    }

    fun wake(nowMs: Long) {
        screen = Screen.HOME
        lastInteractionMs = nowMs
    }

    /** 前面復帰。ホームから待機時間を数え直す。記録は残し、確認・メニュー・取消は閉じる。 */
    fun resume(nowMs: Long) {
        enterClock() // 一時的な表示を全て閉じる
        wake(nowMs)
    }

    // ---- シナリオ ----

    fun openMenu() {
        if (screen != Screen.HOME) return
        confirmation = null
        confirmFocus = ConfirmFocus.BACK
        toast = null
        menuOpen = true
    }

    fun closeMenu() {
        menuOpen = false
    }

    fun perform(action: MenuAction, nowMs: Long) {
        when (action) {
            MenuAction.Close -> closeMenu()
            MenuAction.Reset -> applyScenario(Scenario.NORMAL, nowMs)
            is MenuAction.Pick -> {
                applyScenario(action.scenario, nowMs)
                if (action.scenario.showsClock) enterClock()
            }
        }
    }

    /** 日付だけを差し替える（データ更新）。開いている確認の対象は変えない。 */
    fun refreshDate(date: LocalDate) {
        today = date
    }

    private fun applyScenario(s: Scenario, nowMs: Long) {
        confirmation = null
        confirmFocus = ConfirmFocus.BACK
        menuOpen = false
        toast = null
        screen = Screen.HOME
        lastInteractionMs = nowMs
        scenario = s
        today = s.date
        doneKeys.clear()
        if (s.allDone) occurrences.forEach { doneKeys.add(it.key) }
        careSeeds.forEachIndexed { i, seed -> careLastAtMs[i] = nowMs - seed.agoMinutes * 60_000L }
    }

    companion object {
        const val CONFIRM_LOCK_MS = 450L
        const val TOAST_MS = 8_000L
        const val IDLE_TIMEOUT_MS = 3 * 60_000L
    }
}

object ElapsedText {
    /** お世話の経過時間。「たった今」「N分前」「N時間前」「N日前」。 */
    fun format(minutes: Long): String = when {
        minutes < 1L -> "たった今"
        minutes < 60L -> "${minutes}分前"
        minutes < 24L * 60L -> "${minutes / 60L}時間前"
        else -> "${minutes / (24L * 60L)}日前"
    }
}
