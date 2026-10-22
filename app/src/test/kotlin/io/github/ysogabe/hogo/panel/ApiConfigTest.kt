package io.github.ysogabe.hogo.panel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigTest {
    private val goodSecret = "abcdEFGH0123-_xyz"

    private fun json(baseUrl: String? = "http://127.0.0.1:8787", deviceId: String? = "mock-panel-1", secret: String? = goodSecret): String {
        val parts = listOfNotNull(
            baseUrl?.let { "\"baseUrl\":\"$it\"" },
            deviceId?.let { "\"deviceId\":\"$it\"" },
            secret?.let { "\"secret\":\"$it\"" },
        )
        return parts.joinToString(",", "{", "}")
    }

    private fun parse(text: String) = ApiConfig.parse(text.toByteArray(Charsets.UTF_8))

    private fun assertRule(rule: ApiConfig.Rule, text: String) =
        assertEquals(text, ApiConfig.Loaded.Invalid(rule), parse(text))

    @Test
    fun aCorrectConfigIsAccepted() {
        val r = parse(json()) as ApiConfig.Loaded.Valid
        assertEquals("http://127.0.0.1:8787", r.config.baseUrl)
        assertEquals("mock-panel-1", r.config.deviceId)
        assertEquals(goodSecret, r.config.secret)
    }

    @Test
    fun theMockApiConfigShapeIsAccepted() {
        val secret = "A".repeat(43) // secrets.token_urlsafe(32) の長さ
        assertTrue(parse("""{"baseUrl":"http://127.0.0.1:8787","deviceId":"mock-panel-1","secret":"$secret"}""") is ApiConfig.Loaded.Valid)
    }

    @Test
    fun missingKeysAreReportedByWhichRuleTheyBreak() {
        assertRule(ApiConfig.Rule.BASE_URL_MISSING, json(baseUrl = null))
        assertRule(ApiConfig.Rule.DEVICE_ID_MISSING, json(deviceId = null))
        assertRule(ApiConfig.Rule.SECRET_MISSING, json(secret = null))
        assertRule(ApiConfig.Rule.BASE_URL_MISSING, "{}")
        assertRule(ApiConfig.Rule.BASE_URL_MISSING, """{"baseUrl":null,"deviceId":"d","secret":"$goodSecret"}""")
        assertRule(ApiConfig.Rule.DEVICE_ID_MISSING, """{"baseUrl":"http://127.0.0.1:8787","deviceId":5,"secret":"$goodSecret"}""")
        assertRule(ApiConfig.Rule.SECRET_MISSING, """{"baseUrl":"http://127.0.0.1:8787","deviceId":"d","secret":["x"]}""")
    }

    @Test
    fun baseUrlIsOnlyHttpLoopbackWithAPort() {
        listOf(
            "https://127.0.0.1:8787", "http://localhost:8787", "http://127.0.0.2:8787", "http://0.0.0.0:8787",
            "http://192.168.0.2:8787", "http://example.com:8787", "http://127.0.0.1.evil.example:8787",
            "http://127.0.0.1:8787/", "http://127.0.0.1:8787/api", "http://127.0.0.1:8787?x=1", "http://127.0.0.1:8787#a",
            "http://127.0.0.1", "http://127.0.0.1:", "http://127.0.0.1:0", "http://127.0.0.1:08787", "http://127.0.0.1:65536",
            "http://127.0.0.1:123456", "http://user@127.0.0.1:8787", "http://127.0.0.1:8787@evil.example", "http://[::1]:8787",
            "HTTP://127.0.0.1:8787", " http://127.0.0.1:8787", "http://127.0.0.1:8787 ", "127.0.0.1:8787", "",
        ).forEach { assertRule(ApiConfig.Rule.BASE_URL_FORM, json(baseUrl = it)) }
        assertTrue(parse(json(baseUrl = "http://127.0.0.1:1")) is ApiConfig.Loaded.Valid)
        assertTrue(parse(json(baseUrl = "http://127.0.0.1:65535")) is ApiConfig.Loaded.Valid)
    }

    @Test
    fun deviceIdIsUpTo64LetterDigitUnderscoreHyphen() {
        assertTrue(parse(json(deviceId = "A_b-9")) is ApiConfig.Loaded.Valid)
        assertTrue(parse(json(deviceId = "d".repeat(64))) is ApiConfig.Loaded.Valid)
        listOf("", "d".repeat(65), "a b", "a/b", "a.b", "../x", "あ", "a%2Fb").forEach {
            assertRule(ApiConfig.Rule.DEVICE_ID_FORM, json(deviceId = it))
        }
    }

    @Test
    fun secretIs16To256PrintableAsciiWithoutSpaces() {
        assertTrue(parse(json(secret = "s".repeat(16))) is ApiConfig.Loaded.Valid)
        assertTrue(parse(json(secret = "s".repeat(256))) is ApiConfig.Loaded.Valid)
        assertTrue(parse(json(secret = "!#$%&'()*+,-./:;<=>?@[]^_`{|}~Aa0")) is ApiConfig.Loaded.Valid)
        listOf("s".repeat(15), "", "s".repeat(257), "abcd efgh ijkl mnop", " " + "s".repeat(16), "s".repeat(16) + " ", "あ".repeat(16), "s".repeat(15) + "\\u00e9", "s".repeat(15) + "\\n", "s".repeat(15) + "\\u007f")
            .forEach { assertRule(ApiConfig.Rule.SECRET_FORM, json(secret = it)) }
    }

    @Test
    fun brokenFilesAreInvalid() {
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_JSON), parse(""))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_JSON), parse("not json"))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_JSON), parse(json().dropLast(1)))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_JSON), parse(json() + "x"))
        // 同じキーが 2 回あるファイルは、どちらの値を使うか曖昧なので読めないものとして扱う。
        assertEquals(
            ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_JSON),
            parse("""{"baseUrl":"http://127.0.0.1:8787","baseUrl":"http://127.0.0.1:9","deviceId":"d","secret":"$goodSecret"}"""),
        )
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_OBJECT), parse("[]"))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_OBJECT), parse("\"x\""))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.NOT_UTF8), ApiConfig.parse(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun unknownKeysAreIgnored() {
        assertTrue(parse("""{"baseUrl":"http://127.0.0.1:8787","deviceId":"d","secret":"$goodSecret","note":1}""") is ApiConfig.Loaded.Valid)
    }

    @Test
    fun theFileIsAtMost4KiB() {
        val base = json()
        fun padded(bytes: Int) = (base + " ".repeat(bytes - base.length)).toByteArray()
        assertTrue(ApiConfig.parse(padded(4096)) is ApiConfig.Loaded.Valid)
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.TOO_LARGE), ApiConfig.parse(padded(4097)))
        assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.TOO_LARGE), ApiConfig.parse(ByteArray(1_000_000) { ' '.code.toByte() }))
    }

    @Test
    fun loadReturnsAbsentValidOrInvalidFromTheFilesDir() {
        val dir = Files.createTempDirectory("apiconfig").toFile()
        try {
            assertEquals(ApiConfig.Loaded.Absent, ApiConfig.load(dir))
            val file = File(dir, ApiConfig.FILE_NAME)
            file.writeText(json())
            assertTrue(ApiConfig.load(dir) is ApiConfig.Loaded.Valid)
            file.writeText(json(secret = "short"))
            assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.SECRET_FORM), ApiConfig.load(dir))
            file.writeBytes(ByteArray(5_000_000) { ' '.code.toByte() })
            assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.TOO_LARGE), ApiConfig.load(dir))
            file.delete()
            assertTrue(file.mkdir()) // 読めないもの（ディレクトリ）
            assertEquals(ApiConfig.Loaded.Invalid(ApiConfig.Rule.UNREADABLE), ApiConfig.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theSecretNeverAppearsInToStringOfTheConfigOrTheResult() {
        val r = parse(json())
        assertFalse(r.toString().contains(goodSecret))
        assertFalse((r as ApiConfig.Loaded.Valid).config.toString().contains(goodSecret))
        assertFalse(r.config.toString().contains("127.0.0.1"))
    }
}
