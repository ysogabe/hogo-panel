package io.github.ysogabe.hogo.panel

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 投薬予定の応答（[docs/calendar-mock/api.md]）の検証と読み取り。純粋関数で、通信も保存もしない。
 * 全体の失敗は [ScheduleResult.Failed]（`INVALID`・`TOO_LARGE`）、おかしな回だけは除いて件数を
 * [ScheduleResult.Loaded.excludedCount] に数える（ログ専用）。
 */
object MedicationsJson {
    const val MAX_BYTES = 64 * 1024
    const val MAX_OCCURRENCES = 200
    const val MAX_LEAD_DAYS = 14
    const val MAX_NAME_CODE_POINTS = 40

    /** 今日からこの日数だけ過去、またはこの日数だけ未来までの回を受ける。 */
    const val PAST_DAYS = 31L
    const val FUTURE_DAYS = 400L

    private const val MAX_FETCHED_AT = (1L shl 53) - 1

    private val KEY_RULE = Regex("[A-Za-z0-9._:-]{1,128}")
    private val DATE_RULE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** `Area/City` の形（`America/Argentina/Buenos_Aires` のように段が増えてもよい）。オフセット形式・略称は通らない。 */
    private val REGION_RULE = Regex("[A-Za-z][A-Za-z0-9_+-]*(?:/[A-Za-z0-9_+-]+)+")

    /** Unicode の White_Space（api.md の一覧）。 */
    private fun isWhiteSpace(cp: Int): Boolean = when (cp) {
        in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** 応答本文のバイト列から読む。不正な UTF-8 は `INVALID`。 */
    fun parse(body: ByteArray, today: LocalDate): ScheduleResult = parse(body) { today }

    /**
     * [todayFor] は応答の `timeZone`（無ければ null）での今日。回の日付の範囲（今日の −31〜+400 日）を決めるのに使う。
     * 範囲の判定は応答の中のゾーンに依るので、ゾーンが分かってから呼ばれる。
     */
    fun parse(body: ByteArray, todayFor: (ZoneId?) -> LocalDate): ScheduleResult {
        if (body.size > MAX_BYTES) return ScheduleResult.Failed(FetchError.TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            return ScheduleResult.Failed(FetchError.INVALID)
        }
        return parseText(text, todayFor)
    }

    /** 文字列から読む。大きさは UTF-8 のバイト数で数える。 */
    fun parse(text: String, today: LocalDate): ScheduleResult = parse(text) { today }

    fun parse(text: String, todayFor: (ZoneId?) -> LocalDate): ScheduleResult {
        // UTF-16 の長さの 3 倍が UTF-8 の上限。上限に近いときだけ実際に数える。
        if (text.length > MAX_BYTES || (text.length * 3 > MAX_BYTES && text.toByteArray(Charsets.UTF_8).size > MAX_BYTES)) {
            return ScheduleResult.Failed(FetchError.TOO_LARGE)
        }
        return parseText(text, todayFor)
    }

    private fun invalid() = ScheduleResult.Failed(FetchError.INVALID)

    private fun parseText(text: String, todayFor: (ZoneId?) -> LocalDate): ScheduleResult {
        val root = (StrictJson.parse(text) as? StrictJson.Result.Ok)?.value as? Map<*, *> ?: return invalid()

        val enabled = root["enabled"] as? Boolean ?: return invalid()
        if (!enabled) return ScheduleResult.Disabled(pollSecondsOf(root))

        val status = (root["status"] as? String ?: return invalid()).let(::statusOf)
        val leadDays = integerOf(root["leadDays"], 0L, MAX_LEAD_DAYS.toLong())?.toInt() ?: return invalid()
        val zone = zoneOf(root["timeZone"]) ?: return invalid()
        val list = root["occurrences"] as? List<*> ?: return invalid()
        if (list.size > MAX_OCCURRENCES) return invalid()

        // key の重複は、回ごとの除外より前に、key が文字列の要素どうしで判定する。
        val seen = HashSet<String>()
        for (element in list) {
            val key = (element as? Map<*, *>)?.get("key") as? String ?: continue
            if (!seen.add(key)) return invalid()
        }

        val today = todayFor(zone.zone)
        val kept = ArrayList<Occurrence>(list.size)
        var excluded = 0
        for (element in list) {
            val occurrence = occurrenceOf(element, today)
            if (occurrence == null) excluded++ else kept.add(occurrence)
        }
        kept.sortWith(compareBy<Occurrence> { it.scheduled }.thenBy { it.key })

        return ScheduleResult.Loaded(
            status = status,
            leadDays = leadDays,
            zone = zone.zone,
            fetchedAt = integerOf(root["fetchedAt"], 0L, MAX_FETCHED_AT),
            pollSeconds = pollSecondsOf(root),
            occurrences = kept,
            excludedCount = excluded,
        )
    }

    private fun statusOf(raw: String): SourceStatus = when (raw) {
        "ok" -> SourceStatus.OK
        "stale" -> SourceStatus.STALE
        "reauthRequired" -> SourceStatus.REAUTH_REQUIRED
        "notConnected" -> SourceStatus.NOT_CONNECTED
        else -> SourceStatus.FETCH_FAILED // "fetchFailed" と知らない値
    }

    /** 小数・指数・文字列の数字・範囲外は null（型違い）。null（JSON の null を含む）や省略も null。 */
    private fun integerOf(value: Any?, min: Long, max: Long): Long? {
        val n = (value as? JsonNumber)?.longOrNull ?: return null
        return if (n in min..max) n else null
    }

    private fun pollSecondsOf(root: Map<*, *>): Int? =
        integerOf(root["pollSeconds"], Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    /** `timeZone` の読み取り。省略は成功（[Zone.zone] が null）、型違い・地域 ID でない・知らない地域は null（全体の失敗）。 */
    private class Zone(val zone: ZoneId?)

    private fun zoneOf(value: Any?): Zone? {
        if (value == null) return Zone(null)
        val id = value as? String ?: return null
        if (id != "UTC" && !REGION_RULE.matches(id)) return null
        return try {
            Zone(ZoneId.of(id))
        } catch (_: DateTimeException) {
            null
        }
    }

    /** 1 回分。規則に外れたら null（その回だけを除く）。 */
    private fun occurrenceOf(element: Any?, today: LocalDate): Occurrence? {
        val map = element as? Map<*, *> ?: return null
        val key = (map["key"] as? String)?.takeIf { KEY_RULE.matches(it) } ?: return null
        val seriesId = (map["seriesId"] as? String)?.takeIf { KEY_RULE.matches(it) } ?: return null
        val name = (map["name"] as? String)?.takeIf { isValidName(it) } ?: return null
        val date = dateOf(map["date"] as? String ?: return null) ?: return null
        val days = ChronoUnit.DAYS.between(today, date)
        if (days < -PAST_DAYS || days > FUTURE_DAYS) return null
        val icon = when (map["icon"]) {
            "drop" -> MedIcon.DROP
            "trash" -> MedIcon.TRASH
            else -> MedIcon.PILL
        }
        // dayOnly は省略・null が false。boolean でなければ型違いで、この回だけを除く。
        val dayOnly = when (val raw = map["dayOnly"]) {
            null -> false
            is Boolean -> raw
            else -> return null
        }
        return Occurrence(key, seriesId, name, icon, date, dayOnly)
    }

    private fun dateOf(text: String): LocalDate? {
        if (!DATE_RULE.matches(text)) return null
        return try {
            LocalDate.of(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * 薬名の規則。Unicode のコードポイントで 1〜40 個、Cc・Cf・対になっていないサロゲートを含まず、
     * 空白（White_Space）だけでない。整形（trim）はしない。
     */
    internal fun isValidName(name: String): Boolean {
        var count = 0
        var allWhite = true
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            count++
            if (count > MAX_NAME_CODE_POINTS) return false
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE -> return false
            }
            if (!isWhiteSpace(cp)) allWhite = false
        }
        return count >= 1 && !allWhite
    }
}
