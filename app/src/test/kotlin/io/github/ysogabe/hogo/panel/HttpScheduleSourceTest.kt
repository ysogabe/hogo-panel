package io.github.ysogabe.hogo.panel

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 127.0.0.1・空きポートの小さな HTTP サーバー（[ServerSocket]）に対して、実際の [java.net.HttpURLConnection] で確かめる。
 * `com.sun.net.httpserver` は Android のビルドが使う android.jar に無く、テストをコンパイルできないので使わない。
 */
class HttpScheduleSourceTest {
    private val secret = "test-secret-0123456789abcdef"
    private val deviceId = "mock-panel-1"
    private val today = LocalDate.of(2026, 10, 22)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val servers = mutableListOf<ServerSocket>()

    /** 受けた要求（リクエスト行とヘッダー。ヘッダー名は小文字）。 */
    private class Request(val line: String, val headers: Map<String, String>)

    private val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/api/medications/$name.json")!!.use { it.readBytes() }

    /** 接続ごとに要求を読み、[handler] に生の出力を渡す。閉じるのは [handler] の後。 */
    private fun serve(handler: (OutputStream) -> Unit): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers += server
        pool.execute {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@execute
                }
                pool.execute {
                    socket.use { s ->
                        try {
                            requests += readRequest(s)
                            handler(s.getOutputStream())
                            s.getOutputStream().flush()
                        } catch (_: Exception) {
                            // 相手（パネル側）が切った。
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    private fun readRequest(socket: Socket): Request {
        val text = readHeaderBlock(socket.getInputStream())
        val lines = text.split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Request(lines.first(), headers)
    }

    private fun readHeaderBlock(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (!buf.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name()).trimEnd()
    }

    private fun OutputStream.response(
        status: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = "application/json; charset=utf-8",
        extra: List<String> = emptyList(),
        contentLength: Long? = body.size.toLong(),
    ) {
        val head = StringBuilder("HTTP/1.1 $status\r\nConnection: close\r\n")
        contentType?.let { head.append("Content-Type: $it\r\n") }
        contentLength?.let { head.append("Content-Length: $it\r\n") }
        extra.forEach { head.append(it).append("\r\n") }
        head.append("\r\n")
        write(head.toString().toByteArray(Charsets.ISO_8859_1))
        write(body)
    }

    private fun source(port: Int, readTimeoutMs: Int = 5_000) =
        HttpScheduleSource(ApiConfig("http://127.0.0.1:$port", deviceId, secret), connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs) { today }

    private fun failed(error: FetchError) = ScheduleResult.Failed(error)

    // ---- 成功 ----

    @Test
    fun a200JsonResponseIsParsedAndOnlyTheRightPathAndSecretAreSent() {
        val port = serve { it.response("200 OK", sample("ok")) }
        val result = source(port).fetch() as ScheduleResult.Loaded
        assertEquals(SourceStatus.OK, result.status)
        assertEquals(2, result.leadDays)
        assertTrue(result.occurrences.isNotEmpty())
        val request = requests.single()
        assertEquals("GET /api/agent/$deviceId/medications HTTP/1.1", request.line)
        assertEquals(secret, request.headers["x-mock-device-secret"])
    }

    @Test
    fun aDisabledResponseIsReadToo() {
        val port = serve { it.response("200 OK", sample("disabled")) }
        assertEquals(ScheduleResult.Disabled(60), source(port).fetch())
    }

    @Test
    fun theContentTypeIsComparedByTypeIgnoringCaseAndParameters() {
        for (type in listOf("application/json", "Application/JSON", "application/json;charset=UTF-8", "  application/json ; charset=utf-8")) {
            val port = serve { it.response("200 OK", sample("ok"), contentType = type) }
            val result = source(port).fetch()
            assertTrue("$type → $result", result is ScheduleResult.Loaded)
        }
    }

    @Test
    fun aContentTypeThatIsNotJsonIsNotJson() {
        for (type in listOf("text/html", "application/jsonp", "application/json-seq", "text/json", null)) {
            val port = serve { it.response("200 OK", sample("ok"), contentType = type) }
            assertEquals("$type", failed(FetchError.NOT_JSON), source(port).fetch())
        }
    }

    // ---- ステータス ----

    @Test
    fun a401IsHttp401AndA404IsHttp404() {
        assertEquals(failed(FetchError.HTTP_401), source(serve { it.response("401 Unauthorized", sample("error-401")) }).fetch())
        assertEquals(failed(FetchError.HTTP_404), source(serve { it.response("404 Not Found", sample("error-404")) }).fetch())
    }

    @Test
    fun otherStatusesAreHttpOtherEvenWhenTheBodyIsAValidResponse() {
        for (status in listOf("202 Accepted", "203 Non-Authoritative Information", "206 Partial Content", "400 Bad Request", "403 Forbidden", "421 Misdirected Request", "500 Internal Server Error", "503 Service Unavailable")) {
            val port = serve { it.response(status, sample("ok")) }
            assertEquals(status, failed(FetchError.HTTP_OTHER), source(port).fetch())
        }
        assertEquals(failed(FetchError.HTTP_OTHER), source(serve { it.response("204 No Content", contentType = null, contentLength = null) }).fetch())
    }

    @Test
    fun a3xxIsARedirectAndTheLocationIsNeverFollowed() {
        val followed = AtomicInteger()
        val target = serve {
            followed.incrementAndGet()
            it.response("200 OK", sample("ok"))
        }
        for (status in listOf("301 Moved Permanently", "302 Found", "303 See Other", "307 Temporary Redirect", "308 Permanent Redirect")) {
            val port = serve { it.response(status, contentType = null, extra = listOf("Location: http://127.0.0.1:$target/api/agent/$deviceId/medications")) }
            assertEquals(status, failed(FetchError.REDIRECT), source(port).fetch())
        }
        assertEquals(0, followed.get())
        assertTrue(requests.none { "elsewhere" in it.line })
    }

    // ---- 大きさ ----

    @Test
    fun aBodyOverSixtyFourKiBIsTooLargeEvenWithoutAContentLength() {
        val port = serve { out ->
            out.response("200 OK", contentLength = null) // 閉じるまでが本文
            repeat(70) { out.write(ByteArray(1024) { ' '.code.toByte() }) }
        }
        assertEquals(failed(FetchError.TOO_LARGE), source(port).fetch())
    }

    @Test
    fun theLimitIsExactlySixtyFourKiB() {
        val ok = sample("ok")
        val exact = ok + ByteArray(MedicationsJson.MAX_BYTES - ok.size) { ' '.code.toByte() }
        assertEquals(MedicationsJson.MAX_BYTES, exact.size)
        assertTrue(source(serve { it.response("200 OK", exact) }).fetch() is ScheduleResult.Loaded)
        val over = exact + byteArrayOf(' '.code.toByte())
        assertEquals(failed(FetchError.TOO_LARGE), source(serve { it.response("200 OK", over) }).fetch())
    }

    @Test
    fun anInvalidBodyIsInvalid() {
        val port = serve { it.response("200 OK", "{not json".toByteArray()) }
        assertEquals(failed(FetchError.INVALID), source(port).fetch())
    }

    // ---- 通信の失敗・遅延・切断 ----

    @Test
    fun aPortNobodyListensOnIsANetworkFailure() {
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = dead.localPort
        dead.close()
        assertEquals(failed(FetchError.NETWORK), source(port).fetch())
    }

    @Test
    fun aConnectionClosedWithoutAResponseIsANetworkFailure() {
        val port = serve { /* 何も返さず閉じる */ }
        assertEquals(failed(FetchError.NETWORK), source(port).fetch())
    }

    @Test
    fun aSlowResponseEndsWithATimeout() {
        val release = CountDownLatch(1)
        val port = serve {
            release.await(5, TimeUnit.SECONDS)
            it.response("200 OK", sample("ok"))
        }
        val started = System.nanoTime()
        val result = source(port, readTimeoutMs = 300).fetch()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        release.countDown()
        assertEquals(failed(FetchError.TIMEOUT), result)
        assertTrue("took $tookMs ms", tookMs < 3_000)
    }

    @Test
    fun cancelFromAnotherThreadEndsAHangingFetchAtOnce() {
        val hanging = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = serve {
            hanging.countDown()
            release.await(10, TimeUnit.SECONDS)
            it.response("200 OK", sample("ok"))
        }
        val src = source(port, readTimeoutMs = 8_000)
        var result: ScheduleResult? = null
        val worker = Thread { result = src.fetch() }.apply { isDaemon = true; start() }
        assertTrue(hanging.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        src.cancel()
        worker.join(3_000)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        release.countDown()
        assertFalse("worker still running", worker.isAlive)
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertTrue("$result", result is ScheduleResult.Failed)
    }

    @Test
    fun cancelWithoutAFetchIsHarmless() {
        val src = source(1)
        src.cancel()
        assertEquals(failed(FetchError.NETWORK), src.fetch()) // 取り消した後も使える
    }
}
