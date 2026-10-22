package io.github.ysogabe.hogo.panel

import java.time.ZoneId

/** 取得に失敗した理由。決まった種類だけで、例外のメッセージ・URL・ヘッダーは持たない。 */
enum class FetchError { NETWORK, TIMEOUT, HTTP_401, HTTP_404, HTTP_OTHER, REDIRECT, NOT_JSON, TOO_LARGE, INVALID }

/** 応答の `status`。知らない値は [FETCH_FAILED] として読む。 */
enum class SourceStatus { OK, STALE, FETCH_FAILED, REAUTH_REQUIRED, NOT_CONNECTED }

/** 1 回の取得の結果。 */
sealed interface ScheduleResult {
    /**
     * `enabled:true` の応答。[zone] は応答に `timeZone` が無いとき null（呼び出し側が端末のゾーンを使う）。
     * [occurrences] は日付、同じ日は key の順。[excludedCount] は回ごとの除外で外した件数で、ログ専用（画面には出さない）。
     */
    data class Loaded(
        val status: SourceStatus,
        val leadDays: Int,
        val zone: ZoneId?,
        val fetchedAt: Long?,
        val pollSeconds: Int?,
        val occurrences: List<Occurrence>,
        val excludedCount: Int = 0,
    ) : ScheduleResult

    /** `enabled:false` の応答。 */
    data class Disabled(val pollSeconds: Int?) : ScheduleResult

    data class Failed(val error: FetchError) : ScheduleResult
}

/** 予定の取得元。呼び出し元のスレッドで待つので、メインスレッドから呼ばない。例外は投げず [ScheduleResult.Failed] にする。 */
fun interface ScheduleSource {
    fun fetch(): ScheduleResult
}
