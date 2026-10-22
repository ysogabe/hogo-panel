package io.github.ysogabe.hogo.panel

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * API モードの接続設定（`files/api-config.json`）。`adb shell run-as` で置く。
 * [secret] を [toString] に出さない（ログや例外に混ざらないように）。
 */
class ApiConfig(val baseUrl: String, val deviceId: String, val secret: String) {
    override fun toString(): String = "ApiConfig"

    override fun equals(other: Any?): Boolean =
        other is ApiConfig && baseUrl == other.baseUrl && deviceId == other.deviceId && secret == other.secret

    override fun hashCode(): Int = 31 * (31 * baseUrl.hashCode() + deviceId.hashCode()) + secret.hashCode()

    /** どの規則に外れたか。値は持たないので、そのままログに出してよい。 */
    enum class Rule {
        UNREADABLE,
        TOO_LARGE,
        NOT_UTF8,
        NOT_JSON,
        NOT_OBJECT,
        BASE_URL_MISSING,
        BASE_URL_FORM,
        DEVICE_ID_MISSING,
        DEVICE_ID_FORM,
        SECRET_MISSING,
        SECRET_FORM,
    }

    sealed interface Loaded {
        /** 設定ファイルが無い。シナリオモード。 */
        data object Absent : Loaded

        data class Valid(val config: ApiConfig) : Loaded

        /** ファイルはあるが外れた。API モードのまま「接続設定の誤り」を出し、取得しない。 */
        data class Invalid(val rule: Rule) : Loaded
    }

    companion object {
        const val FILE_NAME = "api-config.json"
        const val MAX_BYTES = 4096

        private val BASE_URL_RULE = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})")
        private val DEVICE_ID_RULE = Regex("[A-Za-z0-9_-]{1,64}")

        /** `<filesDir>/api-config.json` を読む。 */
        fun load(filesDir: File): Loaded {
            val file = File(filesDir, FILE_NAME)
            if (!file.exists()) return Loaded.Absent
            val bytes = try {
                readUpTo(file, MAX_BYTES + 1)
            } catch (_: IOException) {
                return Loaded.Invalid(Rule.UNREADABLE)
            } catch (_: SecurityException) {
                return Loaded.Invalid(Rule.UNREADABLE)
            }
            return parse(bytes)
        }

        /** 先頭から最大 [limit] バイトを読む。大きいファイルを丸ごとメモリに載せない。 */
        private fun readUpTo(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(limit)
            var n = 0
            while (n < limit) {
                val r = input.read(buffer, n, limit - n)
                if (r < 0) break
                n += r
            }
            buffer.copyOf(n)
        }

        /** ファイルの中身を検証する。 */
        fun parse(bytes: ByteArray): Loaded {
            if (bytes.size > MAX_BYTES) return Loaded.Invalid(Rule.TOO_LARGE)
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return Loaded.Invalid(Rule.NOT_UTF8)
            }
            val parsed = StrictJson.parse(text) as? StrictJson.Result.Ok ?: return Loaded.Invalid(Rule.NOT_JSON)
            val root = parsed.value as? Map<*, *> ?: return Loaded.Invalid(Rule.NOT_OBJECT)

            val baseUrl = root["baseUrl"] as? String ?: return Loaded.Invalid(Rule.BASE_URL_MISSING)
            val port = BASE_URL_RULE.matchEntire(baseUrl)?.groupValues?.get(1)?.toInt()
            if (port == null || port > 65535) return Loaded.Invalid(Rule.BASE_URL_FORM)

            val deviceId = root["deviceId"] as? String ?: return Loaded.Invalid(Rule.DEVICE_ID_MISSING)
            if (!DEVICE_ID_RULE.matches(deviceId)) return Loaded.Invalid(Rule.DEVICE_ID_FORM)

            val secret = root["secret"] as? String ?: return Loaded.Invalid(Rule.SECRET_MISSING)
            // 表示できる ASCII（空白を除く 0x21〜0x7E）の 16〜256 文字。ヘッダーに載せるので空白は許さない。
            if (secret.length !in 16..256 || secret.any { it.code !in 0x21..0x7E }) return Loaded.Invalid(Rule.SECRET_FORM)

            return Loaded.Valid(ApiConfig(baseUrl, deviceId, secret))
        }
    }
}
