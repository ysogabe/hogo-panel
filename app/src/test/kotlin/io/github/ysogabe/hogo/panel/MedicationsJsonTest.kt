package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationsJsonTest {
    private val today = LocalDate.of(2026, 10, 22)

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/api/medications/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parse(text: String, day: LocalDate = today) = MedicationsJson.parse(text, day)

    private fun loaded(text: String): ScheduleResult.Loaded = parse(text) as ScheduleResult.Loaded

    private fun failed(text: String): FetchError = (parse(text) as ScheduleResult.Failed).error

    private fun assertInvalid(text: String) = assertEquals("expected INVALID: $text", FetchError.INVALID, failed(text))

    /** 回 1 件の JSON。 */
    private fun occ(
        key: String = "k1",
        series: String = "s1",
        name: String = "薬",
        date: String = "2026-10-22",
        extra: String = "",
    ) = """{"key":"$key","seriesId":"$series","name":"$name","date":"$date"$extra}"""

    private fun doc(
        occurrences: String = "[]",
        status: String = "\"ok\"",
        lead: String = "2",
        zone: String = "\"Asia/Tokyo\"",
        extra: String = "",
    ) = """{"enabled":true,"status":$status,"leadDays":$lead,"timeZone":$zone,$extra"occurrences":$occurrences}"""

    private fun list(vararg items: String) = items.joinToString(",", "[", "]")

    // ---- 見本 ----

    @Test
    fun okSampleIsReadSortedWithEveryField() {
        val r = loaded(sample("ok"))
        assertEquals(SourceStatus.OK, r.status)
        assertEquals(2, r.leadDays)
        assertEquals(ZoneId.of("Asia/Tokyo"), r.zone)
        assertEquals(1792629000L, r.fetchedAt)
        assertEquals(15, r.pollSeconds)
        assertEquals(0, r.excludedCount)
        assertEquals(7, r.occurrences.size)
        assertEquals(r.occurrences.sortedWith(compareBy({ it.scheduled }, { it.key })), r.occurrences)
        val l = r.occurrences.first { it.key == "occ-cal1-L-20261022" }
        assertEquals("フロントライン", l.name)
        assertEquals(MedIcon.DROP, l.icon)
        assertEquals("ser-cal1-L", l.seriesId)
        assertEquals(LocalDate.of(2026, 10, 22), l.scheduled)
        val once = r.occurrences.first { it.key == "occ-cal1-once-20261030" }
        assertEquals(once.key, once.seriesId)
        assertEquals(MedIcon.PILL, r.occurrences.first { it.key == "occ-cal1-F-20261024" }.icon)
    }

    @Test
    fun okSampleGivesLateTodaySoonWarnings() {
        val r = loaded(sample("ok"))
        val w = ReminderPolicy.warnings(r.occurrences, emptySet(), today, r.leadDays.toLong())
        assertEquals(
            setOf("occ-cal1-M-20261021" to Phase.LATE, "occ-cal1-L-20261022" to Phase.TODAY, "occ-cal1-F-20261024" to Phase.SOON),
            w.map { it.occurrence.key to it.phase }.toSet(),
        )
    }

    @Test
    fun statusSamplesMapToTheirStatus() {
        assertEquals(SourceStatus.STALE, loaded(sample("stale")).status)
        assertEquals(SourceStatus.FETCH_FAILED, loaded(sample("fetchFailed")).status)
        assertEquals(SourceStatus.REAUTH_REQUIRED, loaded(sample("reauthRequired")).status)
        listOf("stale", "fetchFailed", "reauthRequired").forEach {
            val r = loaded(sample(it))
            assertTrue(it, r.occurrences.isNotEmpty())
            assertEquals(it, ZoneId.of("Asia/Tokyo"), r.zone)
            assertEquals(it, 0, r.excludedCount)
        }
        assertEquals(60, loaded(sample("stale")).pollSeconds)
        assertEquals(30, loaded(sample("fetchFailed")).pollSeconds)
        assertEquals(300, loaded(sample("reauthRequired")).pollSeconds)
    }

    @Test
    fun notConnectedSampleHasNoZoneOrFetchedAt() {
        val r = loaded(sample("notConnected"))
        assertEquals(SourceStatus.NOT_CONNECTED, r.status)
        assertTrue(r.occurrences.isEmpty())
        assertNull(r.zone)
        assertNull(r.fetchedAt)
        assertEquals(60, r.pollSeconds)
    }

    @Test
    fun disabledSampleReadsOnlyPollSeconds() {
        assertEquals(ScheduleResult.Disabled(60), parse(sample("disabled")))
    }

    @Test
    fun errorBodiesAreNotSuccessfulResponses() {
        listOf("error-401", "error-404", "error-500").forEach { assertInvalid(sample(it)) }
    }

    @Test
    fun bytesAndTextGiveTheSameResult() {
        val text = sample("ok")
        assertEquals(parse(text), MedicationsJson.parse(text.toByteArray(Charsets.UTF_8), today))
    }

    // ---- leadDays ----

    @Test
    fun leadDaysThreeWarnsThreeDaysBefore() {
        val r = loaded(doc(list(occ(date = "2026-10-25")), lead = "3"))
        assertEquals(3, r.leadDays)
        val w = ReminderPolicy.warnings(r.occurrences, emptySet(), today, r.leadDays.toLong())
        assertEquals(listOf(Phase.SOON), w.map { it.phase })
        val twoDays = loaded(doc(list(occ(date = "2026-10-25")), lead = "2"))
        assertTrue(ReminderPolicy.warnings(twoDays.occurrences, emptySet(), today, twoDays.leadDays.toLong()).isEmpty())
    }

    @Test
    fun leadDaysRangeIsZeroToFourteen() {
        assertEquals(0, loaded(doc(lead = "0")).leadDays)
        assertEquals(14, loaded(doc(lead = "14")).leadDays)
        listOf("-1", "15", "2.0", "1e2", "1E2", "\"2\"", "true", "2147483648", "99999999999999999999", "[2]", "{}").forEach {
            assertInvalid(doc(lead = it))
        }
    }

    // ---- 全体の失敗 ----

    @Test
    fun missingOrWrongEnabledIsInvalid() {
        assertInvalid("{}")
        assertInvalid("""{"status":"ok","leadDays":2,"occurrences":[]}""")
        assertInvalid("""{"enabled":"true","status":"ok","leadDays":2,"occurrences":[]}""")
        assertInvalid("""{"enabled":1,"status":"ok","leadDays":2,"occurrences":[]}""")
        assertInvalid("""{"enabled":null,"pollSeconds":10}""")
    }

    @Test
    fun enabledWithMissingOrWrongRequiredKeysIsInvalid() {
        assertInvalid("""{"enabled":true,"leadDays":2,"occurrences":[]}""")
        assertInvalid("""{"enabled":true,"status":"ok","occurrences":[]}""")
        assertInvalid("""{"enabled":true,"status":"ok","leadDays":2}""")
        assertInvalid("""{"enabled":true,"status":null,"leadDays":2,"occurrences":[]}""")
        assertInvalid(doc(status = "5"))
        assertInvalid(doc(occurrences = "{}"))
        assertInvalid(doc(occurrences = "\"[]\""))
        assertInvalid(doc(occurrences = "null"))
    }

    @Test
    fun topLevelThatIsNotAnObjectIsInvalid() {
        assertInvalid("[]")
        assertInvalid("5")
        assertInvalid("\"x\"")
        assertInvalid("null")
    }

    @Test
    fun brokenJsonIsInvalid() {
        assertInvalid("")
        assertInvalid("not json")
        assertInvalid("""{"enabled":true,""")
        assertInvalid("<html>login</html>")
        assertInvalid(doc() + " extra")
    }

    @Test
    fun invalidUtf8BytesAreInvalid() {
        val bytes = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), 0x28, '"'.code.toByte(), '}'.code.toByte())
        assertEquals(ScheduleResult.Failed(FetchError.INVALID), MedicationsJson.parse(bytes, today))
    }

    @Test
    fun timeZoneMustBeARegionIdOrUtc() {
        assertEquals(ZoneId.of("UTC"), loaded(doc(zone = "\"UTC\"")).zone)
        assertEquals(ZoneId.of("America/Argentina/Buenos_Aires"), loaded(doc(zone = "\"America/Argentina/Buenos_Aires\"")).zone)
        assertEquals(ZoneId.of("Etc/GMT+9"), loaded(doc(zone = "\"Etc/GMT+9\"")).zone)
        listOf(
            "\"+09:00\"", "\"UTC+9\"", "\"UTC+09:00\"", "\"Z\"", "\"JST\"", "\"GMT\"", "\"GMT+9\"", "\"\"", "\"Asia/Nowhere\"",
            "\"asia/tokyo\"", "\"Asia/\"", "\"/Tokyo\"", "\"Asia//Tokyo\"", "\"Asia/Tokyo \"", "5", "true", "[]", "{}",
        ).forEach { assertInvalid(doc(zone = it)) }
    }

    @Test
    fun missingOrNullTimeZoneMeansTheDeviceZone() {
        assertNull(loaded("""{"enabled":true,"status":"ok","leadDays":2,"occurrences":[]}""").zone)
        assertNull(loaded(doc(zone = "null")).zone)
    }

    @Test
    fun theTodayFunctionReceivesTheResponseZone() {
        val seen = mutableListOf<ZoneId?>()
        MedicationsJson.parse(doc(), { z -> seen.add(z); today })
        MedicationsJson.parse("""{"enabled":true,"status":"ok","leadDays":2,"occurrences":[]}""", { z -> seen.add(z); today })
        assertEquals(listOf<ZoneId?>(ZoneId.of("Asia/Tokyo"), null), seen)
    }

    @Test
    fun occurrencesMustBeAtMostTwoHundred() {
        fun many(n: Int) = list(*Array(n) { occ(key = "k$it", series = "s$it") })
        assertEquals(200, loaded(doc(many(200))).occurrences.size)
        assertInvalid(doc(many(201)))
    }

    @Test
    fun duplicateKeysAreInvalidAtAnyLevel() {
        assertInvalid("""{"enabled":true,"enabled":true,"status":"ok","leadDays":2,"occurrences":[]}""")
        assertInvalid("""{"enabled":false,"enabled":false}""")
        assertInvalid(doc(list(occ().dropLast(1) + ""","key":"x"}""")))
        assertInvalid(doc(extra = """"x":{"a":1,"a":2},"""))
        assertInvalid(doc(extra = """"x":[{"a":1,"a":2}],"""))
    }

    @Test
    fun nestingDeeperThanEightIsInvalidEvenWhenDisabled() {
        fun nested(depth: Int) = """{"enabled":false,"x":""" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}"
        assertEquals(ScheduleResult.Disabled(null), parse(nested(8)))
        assertInvalid(nested(9))
        assertInvalid(doc(extra = """"x":""" + "[".repeat(8) + "]".repeat(8) + ","))
        assertInvalid("[".repeat(60_000))
    }

    @Test
    fun bracketsInsideStringsAreNotCounted() {
        val text = doc(list(occ(name = "[[[[[[[[[[[[{{{{{{{{{{ \\\" [[[[ \\\\ [[[[")))
        assertEquals(1, loaded(text).occurrences.size)
        assertEquals(0, loaded(doc(extra = """"note":"[[[[[[[[[[[[[[[[",""")).occurrences.size)
    }

    // ---- 大きさ ----

    private fun padded(bytes: Int): String {
        val base = doc()
        return base + " ".repeat(bytes - base.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun exactly64KiBIsAcceptedAndOneMoreByteIsTooLarge() {
        assertEquals(65536, padded(65536).toByteArray().size)
        loaded(padded(65536))
        assertEquals(FetchError.TOO_LARGE, failed(padded(65537)))
        assertEquals(ScheduleResult.Failed(FetchError.TOO_LARGE), MedicationsJson.parse(padded(65537).toByteArray(), today))
    }

    @Test
    fun sizeIsCountedInUtf8BytesNotCharacters() {
        // 全角 1 文字は 3 バイト。文字数では 64Ki 未満でもバイト数で超える。
        val base = doc(list(occ()))
        val pad = "あ".repeat((65536 - base.toByteArray().size) / 3 + 1)
        val text = doc(list(occ()), extra = """"note":"$pad",""")
        assertTrue(text.length < 65536)
        assertTrue(text.toByteArray().size > 65536)
        assertEquals(FetchError.TOO_LARGE, failed(text))
    }

    // ---- 回ごとの除外 ----

    @Test
    fun oneBadOccurrenceIsDroppedAndTheRestAreKept() {
        val text = doc(
            list(
                occ(key = "good1"),
                """"not an object"""",
                "5",
                "null",
                """{"seriesId":"s","name":"薬","date":"2026-10-22"}""",
                """{"key":"nokey","name":"薬","date":"2026-10-22"}""",
                """{"key":"noname","seriesId":"s","date":"2026-10-22"}""",
                """{"key":"nodate","seriesId":"s","name":"薬"}""",
                occ(key = "bad date", date = "2026-10-22"),
                occ(key = "good2", date = "2026-10-23"),
            ),
        )
        val r = loaded(text)
        assertEquals(listOf("good1", "good2"), r.occurrences.map { it.key })
        assertEquals(8, r.excludedCount)
    }

    @Test
    fun keyAndSeriesIdFollowTheCharacterRule() {
        assertEquals(1, loaded(doc(list(occ(key = "Az09._:-", series = "Az09._:-")))).occurrences.size)
        assertEquals(1, loaded(doc(list(occ(key = "k".repeat(128), series = "s".repeat(128))))).occurrences.size)
        listOf("", "a b", "a/b", "あ", "k".repeat(129), "a,b", "a\\u0000").forEach {
            assertEquals(it, 0, loaded(doc(list(occ(key = it)))).occurrences.size)
            assertEquals(it, 0, loaded(doc(list(occ(series = it)))).occurrences.size)
        }
        assertEquals(0, loaded(doc(list("""{"key":5,"seriesId":"s","name":"薬","date":"2026-10-22"}"""))).occurrences.size)
    }

    @Test
    fun dateMustMatchTheFormAndBeARealDate() {
        assertEquals(1, loaded(doc(list(occ(date = "2026-10-28")))).occurrences.size)
        listOf(
            "2026-1-1", "2026-10-2", "26-10-22", "2026/10/22", "20261022", "2026-10-22T00:00", "2026-10-22 ", " 2026-10-22",
            "2026-02-30", "2026-13-01", "2026-00-10", "2026-10-00", "2026-10-32", "2027-02-29", "２０２６-１０-２２", "", "2026-10-22\\n",
        ).forEach { assertEquals(it, 0, loaded(doc(list(occ(date = it)))).occurrences.size) }
        assertEquals(0, loaded(doc(list("""{"key":"k","seriesId":"s","name":"薬","date":20261022}"""))).occurrences.size)
        val leap = MedicationsJson.parse(doc(list(occ(date = "2028-02-29"))), LocalDate.of(2028, 2, 20)) as ScheduleResult.Loaded
        assertEquals(1, leap.occurrences.size)
    }

    @Test
    fun dateRangeIsMinus31ToPlus400DaysFromToday() {
        fun count(d: LocalDate) = loaded(doc(list(occ(date = d.toString())))).occurrences.size
        assertEquals(1, count(today.minusDays(31)))
        assertEquals(0, count(today.minusDays(32)))
        assertEquals(1, count(today.plusDays(400)))
        assertEquals(0, count(today.plusDays(401)))
    }

    @Test
    fun nameCountsUnicodeCodePointsNotUtf16Units() {
        val emoji40 = "🐱".repeat(40)
        assertEquals(80, emoji40.length)
        assertEquals(emoji40, loaded(doc(list(occ(name = emoji40)))).occurrences.single().name)
        val r = loaded(doc(list(occ(key = "a", name = "🐱".repeat(41)), occ(key = "b", name = "あ".repeat(40)), occ(key = "c", name = "あ".repeat(41)))))
        assertEquals(listOf("b"), r.occurrences.map { it.key })
        assertEquals(2, r.excludedCount)
    }

    @Test
    fun nameRejectsControlFormatLoneSurrogateAndBlankNames() {
        fun names(vararg escapedNames: String) =
            loaded(doc(list(*escapedNames.mapIndexed { i, n -> occ(key = "k$i", name = n) }.toTypedArray()))).occurrences.size
        assertEquals(0, names("a\\u0007b", "a\\tb", "a\\nb", "\\u0085", "\\u007f"))
        assertEquals(0, names("a\\u200bb", "\\u200e", "\\ufeff薬", "a\\u00adb"))
        assertEquals(0, names("\\ud800", "a\\udc00", "\\ud83d", "\\udc31\\ud83d"))
        assertEquals(0, names("", " ", "   ", "\\u00a0", "\\u3000 \\u2003", "\\u202f\\u205f\\u1680\\u2028\\u2029", "\\u2000\\u200a"))
        // 対になったサロゲートは 1 コードポイント。
        assertEquals(1, names("\\ud83d\\udc31"))
    }

    @Test
    fun nameIsNotTrimmed() {
        assertEquals(" 薬 ", loaded(doc(list(occ(name = " 薬 ")))).occurrences.single().name)
        assertEquals("　薬", loaded(doc(list(occ(name = "\\u3000薬")))).occurrences.single().name)
        assertEquals("a b", loaded(doc(list(occ(name = "a b")))).occurrences.single().name)
    }

    @Test
    fun nameThatIsNotAStringDropsTheOccurrence() {
        assertEquals(0, loaded(doc(list("""{"key":"k","seriesId":"s","name":5,"date":"2026-10-22"}"""))).occurrences.size)
        assertEquals(0, loaded(doc(list("""{"key":"k","seriesId":"s","name":null,"date":"2026-10-22"}"""))).occurrences.size)
    }

    // ---- key の重複 ----

    @Test
    fun duplicateKeyAmongOccurrencesIsAnInvalidResponse() {
        assertInvalid(doc(list(occ(key = "dup"), occ(key = "dup", date = "2026-10-23"))))
        // あとで除かれる回どうしの重複も全体の失敗。
        assertInvalid(doc(list(occ(key = "dup", date = "2000-01-01"), occ(key = "dup", date = "2000-01-02"))))
        assertInvalid(doc(list(occ(key = "dup", name = ""), occ(key = "dup", name = ""))))
        assertInvalid(doc(list(occ(key = "bad key"), occ(key = "bad key"))))
        assertInvalid(doc(list(occ(key = "dup"), occ(key = "dup", date = "2026-02-30"))))
    }

    @Test
    fun keysThatAreNotStringsDoNotCountAsDuplicates() {
        val bad = """{"key":1,"seriesId":"s","name":"薬","date":"2026-10-22"}"""
        val r = loaded(doc(list(bad, bad, "[]", "[]", occ())))
        assertEquals(1, r.occurrences.size)
        assertEquals(4, r.excludedCount)
    }

    // ---- icon ・ 知らないキー・値 ----

    @Test
    fun iconDefaultsToPillForMissingUnknownOrWrongTypes() {
        fun icon(extra: String) = loaded(doc(list(occ(extra = extra)))).occurrences.single().icon
        assertEquals(MedIcon.DROP, icon(""","icon":"drop""""))
        assertEquals(MedIcon.PILL, icon(""","icon":"pill""""))
        assertEquals(MedIcon.PILL, icon(""))
        assertEquals(MedIcon.PILL, icon(""","icon":"syringe""""))
        assertEquals(MedIcon.PILL, icon(""","icon":"DROP""""))
        assertEquals(MedIcon.PILL, icon(""","icon":null"""))
        assertEquals(MedIcon.PILL, icon(""","icon":5"""))
        assertEquals(MedIcon.PILL, icon(""","icon":["drop"]"""))
        assertEquals(MedIcon.PILL, icon(""","icon":{"a":1}"""))
    }

    @Test
    fun unknownKeysAreIgnoredAtTopAndInOccurrences() {
        val r = loaded(doc(list(occ(extra = ""","color":"red","extra":{"a":[1,2]}""")), extra = """"futureKey":[1,{"a":null}],"x":1.5,"""))
        assertEquals(1, r.occurrences.size)
        assertEquals(0, r.excludedCount)
    }

    @Test
    fun unknownStatusIsTreatedAsFetchFailed() {
        assertEquals(SourceStatus.FETCH_FAILED, loaded(doc(status = "\"somethingNew\"")).status)
        assertEquals(SourceStatus.FETCH_FAILED, loaded(doc(status = "\"\"")).status)
        assertEquals(SourceStatus.FETCH_FAILED, loaded(doc(status = "\"OK\"")).status)
        assertEquals(SourceStatus.OK, loaded(doc(status = "\"ok\"")).status)
    }

    @Test
    fun nullIsTheSameAsOmitted() {
        val r = loaded(
            """{"enabled":true,"status":"ok","leadDays":2,"timeZone":null,"fetchedAt":null,"pollSeconds":null,"occurrences":[""" +
                """{"key":"k","seriesId":"s","name":"薬","date":"2026-10-22","icon":null}]}""",
        )
        assertNull(r.zone)
        assertNull(r.fetchedAt)
        assertNull(r.pollSeconds)
        assertEquals(MedIcon.PILL, r.occurrences.single().icon)
        assertInvalid("""{"enabled":true,"status":"ok","leadDays":null,"occurrences":[]}""")
    }

    // ---- pollSeconds ・ fetchedAt ----

    @Test
    fun pollSecondsOfTheWrongTypeOrRangeIsTreatedAsOmitted() {
        fun poll(v: String) = loaded(doc(extra = """"pollSeconds":$v,""")).pollSeconds
        assertEquals(15, poll("15"))
        assertEquals(5, poll("5")) // 10〜3600 に収めるのは取得側。ここでは値のまま。
        assertEquals(99999, poll("99999"))
        assertEquals(Int.MAX_VALUE, poll("2147483647"))
        assertEquals(Int.MIN_VALUE, poll("-2147483648"))
        listOf("2.0", "1e2", "1E2", "\"15\"", "true", "2147483648", "-2147483649", "99999999999999999999", "[15]", "null").forEach {
            assertNull(it, poll(it))
        }
    }

    @Test
    fun fetchedAtMustBeAnIntegerInZeroToTwoToThe53Minus1() {
        fun at(v: String) = loaded(doc(extra = """"fetchedAt":$v,""")).fetchedAt
        assertEquals(0L, at("0"))
        assertEquals(9007199254740991L, at("9007199254740991"))
        listOf("9007199254740992", "-1", "1.5", "1e9", "1792629000.0", "\"1792629000\"", "true", "99999999999999999999").forEach {
            assertNull(it, at(it))
        }
    }

    @Test
    fun wrongOptionalTypesDoNotFailTheWholeResponse() {
        val r = loaded(doc(list(occ()), extra = """"fetchedAt":"x","pollSeconds":1.5,"""))
        assertNull(r.fetchedAt)
        assertNull(r.pollSeconds)
        assertEquals(1, r.occurrences.size)
    }

    // ---- enabled:false ----

    @Test
    fun disabledReadsOnlyPollSecondsAndIgnoresTheRest() {
        assertEquals(ScheduleResult.Disabled(null), parse("""{"enabled":false}"""))
        assertEquals(ScheduleResult.Disabled(30), parse("""{"enabled":false,"pollSeconds":30}"""))
        assertEquals(
            ScheduleResult.Disabled(30),
            parse("""{"enabled":false,"status":5,"leadDays":"x","timeZone":9,"fetchedAt":"x","occurrences":{"a":1},"pollSeconds":30}"""),
        )
        assertEquals(ScheduleResult.Disabled(null), parse("""{"enabled":false,"pollSeconds":2.5}"""))
        assertEquals(ScheduleResult.Disabled(null), parse("""{"enabled":false,"pollSeconds":"30"}"""))
    }

    @Test
    fun disabledStillChecksJsonDepthAndDuplicates() {
        assertInvalid("""{"enabled":false,"pollSeconds":30,"pollSeconds":30}""")
        assertInvalid("""{"enabled":false,"x":{"a":1,"a":2}}""")
        assertInvalid("""{"enabled":false""")
        assertInvalid("""{"pollSeconds":30}""")
    }

    // ---- 並べ直し ----

    @Test
    fun occurrencesAreSortedByDateThenKeyNoMatterTheInputOrder() {
        val r = loaded(
            doc(
                list(
                    occ(key = "c", date = "2026-10-25"),
                    occ(key = "b", date = "2026-10-23"),
                    occ(key = "z", date = "2026-10-23"),
                    occ(key = "a", date = "2026-10-24"),
                    occ(key = "A", date = "2026-10-23"),
                ),
            ),
        )
        assertEquals(listOf("A", "b", "z", "a", "c"), r.occurrences.map { it.key })
    }

    @Test
    fun aNotConnectedResponseKeepsWhatItWasGivenAndTheModelEmptiesIt() {
        val r = loaded(doc(list(occ()), status = "\"notConnected\""))
        assertEquals(SourceStatus.NOT_CONNECTED, r.status)
        assertEquals(1, r.occurrences.size)
    }
}
