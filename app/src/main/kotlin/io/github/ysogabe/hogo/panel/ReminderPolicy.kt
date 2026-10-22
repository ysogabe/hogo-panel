package io.github.ysogabe.hogo.panel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class MedIcon { PILL, DROP, TRASH }

/** 薬の予定の 1 回分。[key] がその回の識別子（occurrenceKey）で、実施はこの単位で記録する。 */
data class Occurrence(
    val key: String,
    val seriesId: String,
    val name: String,
    val icon: MedIcon,
    val scheduled: LocalDate,
    /** true の回は予定日の当日だけ警告する（ゴミ収集など。そろそろ・未確認は出さない）。 */
    val dayOnly: Boolean = false,
)

enum class Phase { NORMAL, SOON, TODAY, LATE, DONE }

data class Warning(val occurrence: Occurrence, val phase: Phase)

/** 予定日の [WARN_DAYS_BEFORE] 暦日前（API モードでは応答の `leadDays`）から警告する判定。時刻・48 時間ではなく、予定地域の LocalDate だけで決める。 */
object ReminderPolicy {
    const val WARN_DAYS_BEFORE = 2L

    fun warnStart(scheduled: LocalDate, leadDays: Long = WARN_DAYS_BEFORE): LocalDate = scheduled.minusDays(leadDays)

    /** 回ごとの警告開始日。[Occurrence.dayOnly] の回は `leadDays` に関係なく予定日の当日。 */
    private fun warnStart(occurrence: Occurrence, leadDays: Long): LocalDate =
        warnStart(occurrence.scheduled, if (occurrence.dayOnly) 0L else leadDays)

    /** 予定地域のタイムゾーンでの「今日」。端末のゾーンではなく予定側のゾーンを渡す。 */
    fun localDate(instant: Instant, regionZone: ZoneId): LocalDate = instant.atZone(regionZone).toLocalDate()

    /** [dayOnly] の回は、当日だけ [Phase.TODAY]（実施済みなら [Phase.DONE]）で、前日以前も翌日以降も [Phase.NORMAL]（対象外）。 */
    fun phase(
        scheduled: LocalDate,
        today: LocalDate,
        done: Boolean,
        leadDays: Long = WARN_DAYS_BEFORE,
        dayOnly: Boolean = false,
    ): Phase {
        if (done) return Phase.DONE
        val days = ChronoUnit.DAYS.between(today, scheduled)
        if (dayOnly) return if (days == 0L) Phase.TODAY else Phase.NORMAL
        return when {
            days > leadDays -> Phase.NORMAL
            days > 0 -> Phase.SOON
            days == 0L -> Phase.TODAY
            else -> Phase.LATE
        }
    }

    /**
     * 画面に出す警告。系列ごとに、警告開始日が到来した回のうち予定が最新の 1 回だけを、完了状態を問わず選ぶ。
     * 新しい回を実施しても、古い未実施の回を同じ枠へ繰り上げない。実施済みの回と通常期間は含めない。
     * 並びは [occurrences] の系列の初出順。[leadDays] は警告を始める日数で、API モードでは応答の値を渡す。
     * [Occurrence.dayOnly] の回は当日だけ警告する（警告開始日が予定日）。前日以前の予告も、翌日以降の持ち越しもしない。
     */
    fun warnings(
        occurrences: List<Occurrence>,
        doneKeys: Set<String>,
        today: LocalDate,
        leadDays: Long = WARN_DAYS_BEFORE,
    ): List<Warning> =
        occurrences
            .groupBy { it.seriesId }
            .values
            .mapNotNull { series ->
                val current = series
                    .filter { !warnStart(it, leadDays).isAfter(today) }
                    .maxWithOrNull(compareBy<Occurrence> { it.scheduled }.thenBy { it.key })
                    ?: return@mapNotNull null
                val phase = phase(current.scheduled, today, current.key in doneKeys, leadDays, current.dayOnly)
                if (phase == Phase.NORMAL || phase == Phase.DONE) null else Warning(current, phase)
            }
}
