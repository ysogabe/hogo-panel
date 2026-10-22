package io.github.ysogabe.hogo.panel

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * `GET {baseUrl}/api/agent/{deviceId}/medications` で投薬予定を取る。必ず作業用スレッドから呼ぶ（待つ）。
 * リダイレクトは追わず、読むのは 200 の本文だけ、本文は実際に読んだバイト数で [MedicationsJson.MAX_BYTES] を数える。
 * [Failed][ScheduleResult.Failed] には決まった種類だけを入れ、URL・ヘッダー・秘密・例外のメッセージはどこにも出さない。
 */
class HttpScheduleSource(
    private val config: ApiConfig,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    /** 応答の `timeZone`（無ければ null）での今日。回の日付の範囲の判定に使う。 */
    private val todayFor: (ZoneId?) -> LocalDate = { zone -> LocalDate.now(zone ?: ZoneId.systemDefault()) },
) : ScheduleSource {
    @Volatile
    private var active: HttpURLConnection? = null

    override fun fetch(): ScheduleResult = try {
        doFetch()
    } catch (_: SocketTimeoutException) {
        ScheduleResult.Failed(FetchError.TIMEOUT)
    } catch (_: Throwable) {
        ScheduleResult.Failed(FetchError.NETWORK)
    }

    /**
     * 接続を切って、待っている [fetch] を例外で終わらせる。メインスレッドから呼ばれるので、ソケットを閉じる処理
     * （StrictMode のネットワーク操作の検出に当たりうる）は別のスレッドで行い、すぐ戻る。
     */
    override fun cancel() {
        val conn = active ?: return
        Thread {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
                // 切れなくても、結果は呼び出し側が捨てる。
            }
        }.apply { isDaemon = true }.start()
    }

    private fun doFetch(): ScheduleResult {
        val url = URL("${config.baseUrl}/api/agent/${URLEncoder.encode(config.deviceId, "UTF-8")}/medications")
        // システムのプロキシに秘密を渡さない。
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        active = conn
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(SECRET_HEADER, config.secret)
            val code = conn.responseCode
            when {
                code in 300..399 -> return ScheduleResult.Failed(FetchError.REDIRECT)
                code == 401 -> return ScheduleResult.Failed(FetchError.HTTP_401)
                code == 404 -> return ScheduleResult.Failed(FetchError.HTTP_404)
                code != 200 -> return ScheduleResult.Failed(FetchError.HTTP_OTHER) // 200 以外の 2xx も本文は読まない
            }
            if (mediaType(conn.contentType) != "application/json") return ScheduleResult.Failed(FetchError.NOT_JSON)
            val body = readLimited(conn) ?: return ScheduleResult.Failed(FetchError.TOO_LARGE)
            return MedicationsJson.parse(body, todayFor)
        } finally {
            // 見張りで切られた古い取得が、次の取得の接続の参照を消さないよう、自分の接続のときだけ外す。
            if (active === conn) active = null
            conn.disconnect()
        }
    }

    /** `Content-Type` の種類（パラメータを除き、小文字にしたもの）。無ければ null。 */
    private fun mediaType(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

    /** 本文を読む。実際に読んだバイト数が上限を超えたら null（`Content-Length` は信じない）。 */
    private fun readLimited(conn: HttpURLConnection): ByteArray? = conn.inputStream.use { input ->
        val buffer = ByteArray(MedicationsJson.MAX_BYTES + 1)
        var n = 0
        while (n < buffer.size) {
            val r = input.read(buffer, n, buffer.size - n)
            if (r < 0) break
            n += r
        }
        if (n > MedicationsJson.MAX_BYTES) null else buffer.copyOf(n)
    }

    companion object {
        const val SECRET_HEADER = "X-Mock-Device-Secret"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
