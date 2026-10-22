package io.github.ysogabe.hogo.panel

import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 偽の Handler 相当（仮想の時間）・偽の作業用スレッド・偽の取得元で、予約・同時 1 件・世代番号を確かめる。 */
class ScheduleFetcherTest {
    /** 仮想の時間で動く [MainPoster]。[advance] で時間を進め、期限の来た順に実行する。 */
    private class FakePoster : MainPoster {
        private class Entry(val due: Long, val seq: Int, val task: Runnable)

        private val queue = mutableListOf<Entry>()
        private var seq = 0
        var time = 0L
            private set

        @Synchronized
        override fun post(task: Runnable) {
            queue += Entry(time, seq++, task)
        }

        @Synchronized
        override fun postDelayed(task: Runnable, delayMs: Long) {
            queue += Entry(time + delayMs, seq++, task)
        }

        @Synchronized
        override fun removeCallbacks(task: Runnable) {
            queue.removeAll { it.task === task }
        }

        /** 残っている予約の、今からの待ち時間（昇順）。 */
        @Synchronized
        fun pendingDelays(): List<Long> = queue.map { it.due - time }.sorted()

        val isIdle: Boolean get() = synchronized(this) { queue.isEmpty() }

        fun runDue() = advance(0)

        fun advance(ms: Long) {
            val target = time + ms
            while (true) {
                val e = synchronized(this) {
                    queue.filter { it.due <= target }.minWithOrNull(compareBy<Entry>({ it.due }, { it.seq }))
                        ?.also { queue.remove(it) }
                } ?: break
                time = maxOf(time, e.due)
                e.task.run()
            }
            time = target
        }
    }

    /** 作業用スレッドの代わり。[runNext] で最も古い 1 件を呼び出し元のスレッドで実行する。 */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()
    }

    private class FakeSource(vararg results: ScheduleResult) : ScheduleSource {
        val script = ArrayDeque(results.toList())
        var fetches = 0
        var cancels = 0
        var failWith: Throwable? = null
        override fun fetch(): ScheduleResult {
            fetches++
            failWith?.let { throw it }
            return script.removeFirstOrNull() ?: loaded(null)
        }

        override fun cancel() {
            cancels++
        }
    }

    private val poster = FakePoster()
    private val executor = ManualExecutor()
    private val delivered = mutableListOf<ScheduleResult>()

    private fun fetcher(source: ScheduleSource, onResult: (ScheduleResult) -> Unit = {}) =
        ScheduleFetcher(source, poster, executor) {
            delivered += it
            onResult(it)
        }

    /** 作業用スレッドで取得し、結果をメインスレッドで受けるところまで進める。 */
    private fun completeFetch() {
        executor.runNext()
        poster.runDue()
    }

    companion object {
        fun loaded(pollSeconds: Int?, status: SourceStatus = SourceStatus.OK) =
            ScheduleResult.Loaded(status, 2, null, null, pollSeconds, emptyList())

        val failed = ScheduleResult.Failed(FetchError.NETWORK)
    }

    // ---- 同時 1 件・作業用スレッド ----

    @Test
    fun resumeStartsOneFetchOnTheWorkerAndNotOnTheCallingThread() {
        val source = FakeSource(loaded(20))
        val f = fetcher(source)
        f.resume()
        assertEquals(0, source.fetches) // resume を呼んだスレッドでは待たない
        assertEquals(1, executor.tasks.size)
        f.resume() // もう一度呼んでも増えない
        assertEquals(1, executor.tasks.size)
        poster.advance(14_999) // 前の取得が終わるまで、次の取得は始まらない
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(1, source.fetches)
        assertTrue(delivered.isEmpty()) // 結果はメインスレッドの予約を通ってから
        poster.runDue()
        assertEquals(1, delivered.size)
    }

    @Test
    fun theNextFetchIsReservedOnlyAfterTheCurrentOneEnds() {
        val f = fetcher(FakeSource(loaded(20), loaded(20)))
        f.resume()
        assertEquals(listOf(15_000L), poster.pendingDelays()) // 見張りだけ
        completeFetch()
        assertEquals(listOf(20_000L), poster.pendingDelays()) // 見張りは消え、次の 1 件だけ
        poster.advance(19_999)
        assertTrue(executor.tasks.isEmpty())
        poster.advance(1)
        assertEquals(1, executor.tasks.size)
        assertEquals(listOf(15_000L), poster.pendingDelays())
    }

    @Test
    fun aRealWorkerThreadFetchesAndTheResultComesBackOnTheCallingThread() {
        val fetchedOn = arrayOfNulls<Thread>(1)
        val done = CountDownLatch(1)
        val source = ScheduleSource {
            fetchedOn[0] = Thread.currentThread()
            done.countDown()
            loaded(20)
        }
        val resultThread = arrayOfNulls<Thread>(1)
        val f = ScheduleFetcher(source, poster, { task -> Thread(task).apply { isDaemon = true }.start() }) {
            resultThread[0] = Thread.currentThread()
            delivered += it
        }
        f.resume()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // 結果は poster に積まれる。テストのスレッドが取り出して実行する（メインスレッド相当）。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (delivered.isEmpty() && System.nanoTime() < deadline) {
            poster.runDue()
            Thread.sleep(5)
        }
        assertEquals(1, delivered.size)
        assertNotSame(Thread.currentThread(), fetchedOn[0])
        assertEquals(Thread.currentThread(), resultThread[0])
    }

    @Test
    fun aSourceThatThrowsBecomesANetworkFailureAndTheLoopContinues() {
        val source = FakeSource().also { it.failWith = IllegalStateException("x") }
        val f = fetcher(source)
        f.resume()
        completeFetch()
        assertEquals(listOf<ScheduleResult>(ScheduleResult.Failed(FetchError.NETWORK)), delivered)
        assertEquals(listOf(30_000L), poster.pendingDelays())
    }

    // ---- 見張り ----

    @Test
    fun theWatchdogEndsAHungFetchWithATimeoutAndDropsItsLateResult() {
        val source = FakeSource(loaded(20))
        val f = fetcher(source)
        f.resume()
        poster.advance(14_999)
        assertTrue(delivered.isEmpty())
        poster.advance(1) // 15 秒
        assertEquals(listOf<ScheduleResult>(ScheduleResult.Failed(FetchError.TIMEOUT)), delivered)
        assertEquals(1, source.cancels) // 接続を切る
        assertEquals(listOf(30_000L), poster.pendingDelays()) // 失敗の既定
        // 止まっていた作業が後で終わっても、その結果は捨てる。
        completeFetch()
        assertEquals(1, delivered.size)
        assertEquals(listOf(30_000L), poster.pendingDelays())
        // 次の取得は普通に動く。
        poster.advance(30_000)
        completeFetch()
        assertEquals(2, delivered.size)
        assertTrue(delivered[1] is ScheduleResult.Loaded)
    }

    @Test
    fun aFetchThatFinishesInTimeCancelsTheWatchdog() {
        val f = fetcher(FakeSource(loaded(3600)))
        f.resume()
        completeFetch()
        poster.advance(20_000)
        assertEquals(1, delivered.size) // 15 秒を過ぎても TIMEOUT は出ない
    }

    // ---- 止める ----

    @Test
    fun pauseDropsAResultThatArrivesAfterwardsAndStopsReserving() {
        val source = FakeSource(loaded(20))
        val f = fetcher(source)
        f.resume()
        executor.runNext() // 取得は終わったが、結果はまだメインスレッドに届いていない
        f.pause()
        assertEquals(1, source.cancels)
        poster.runDue() // 止めた後に届く
        assertTrue(delivered.isEmpty())
        assertTrue(poster.isIdle) // 止めている間は何も予約しない
        poster.advance(10 * 3600_000L)
        assertTrue(delivered.isEmpty())
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun pauseWhileWaitingForTheNextFetchRemovesTheReservation() {
        val f = fetcher(FakeSource(loaded(20), loaded(20)))
        f.resume()
        completeFetch()
        assertEquals(listOf(20_000L), poster.pendingDelays())
        f.pause()
        assertTrue(poster.isIdle)
        poster.advance(60_000)
        assertTrue(executor.tasks.isEmpty())
    }

    /** 作業用スレッドに渡され、まだ終わっていない作業の数（実際の通信が同時に動いている数）。 */
    private fun assertSerial() = assertTrue("同時に ${executor.tasks.size} 件", executor.tasks.size <= 1)

    @Test
    fun pauseWhileTheWorkerIsStillRunningThenResumeWaitsForItAndIgnoresItsResult() {
        val source = FakeSource(loaded(20), ScheduleResult.Disabled(60))
        val f = fetcher(source)
        f.resume()
        f.pause()
        f.resume() // 新しい取得の時刻だが、古い作業はまだ終わっていない
        assertEquals(1, executor.tasks.size) // 2 件目は渡さない
        assertEquals(2, source.cancels) // 止めたときと、待つときに接続を切る
        executor.runNext() // 古い作業（Loaded）が終わる
        assertEquals(1, source.fetches)
        poster.runDue()
        assertTrue(delivered.isEmpty()) // 世代が違うので捨てる
        assertEquals(1, executor.tasks.size) // 古い作業が終わったので、待っていた取得がすぐ始まる
        assertEquals(listOf(15_000L), poster.pendingDelays()) // 見張りは待ちに入った時刻から
        executor.runNext() // 新しい作業
        poster.runDue()
        assertEquals(listOf<ScheduleResult>(ScheduleResult.Disabled(60)), delivered)
        assertEquals(listOf(60_000L), poster.pendingDelays())
    }

    @Test
    fun repeatedPauseAndResumeNeverRunsTwoWorkersAtOnce() {
        val source = FakeSource(loaded(20), loaded(21), loaded(22))
        val f = fetcher(source)
        f.resume()
        assertSerial()
        repeat(6) {
            f.pause()
            assertSerial()
            f.resume()
            assertSerial()
        }
        assertEquals(1, executor.tasks.size)
        assertEquals(0, source.fetches) // どの作業もまだ終わっていない（通信は 1 件だけ止まっている）
        executor.runNext()
        poster.runDue()
        assertTrue(delivered.isEmpty()) // 最初の作業の結果は捨てる
        assertSerial()
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        poster.runDue()
        assertEquals(listOf(loaded(21)), delivered) // 採用されるのは今の世代の作業だけ
        assertEquals(2, source.fetches)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun pauseAndResumeAfterTheWorkerEndedStartsAtOnce() {
        val f = fetcher(FakeSource(loaded(20), loaded(21)))
        f.resume()
        completeFetch()
        f.pause()
        f.resume()
        assertEquals(1, executor.tasks.size)
        assertSerial()
    }

    @Test
    fun theRetryAfterTheWatchdogWaitsForTheStuckWorker() {
        val source = FakeSource(loaded(20), loaded(30))
        val f = fetcher(source)
        f.resume()
        poster.advance(15_000) // 見張りが切る
        assertEquals(listOf<ScheduleResult>(ScheduleResult.Failed(FetchError.TIMEOUT)), delivered)
        assertSerial()
        poster.advance(30_000) // 再試行の時刻。ただし作業は止まったまま
        assertSerial()
        assertEquals(1, executor.tasks.size)
        assertEquals(1, delivered.size)
        assertEquals(2, source.cancels) // 見張りと、待つとき
        poster.advance(15_000) // 待っている間も見張りは動く
        assertEquals(2, delivered.size)
        assertEquals(ScheduleResult.Failed(FetchError.TIMEOUT), delivered[1])
        poster.advance(30_000)
        assertSerial()
        assertEquals(2, delivered.size)
        assertEquals(0, source.fetches)
        executor.runNext() // 止まっていた作業がやっと終わる
        poster.runDue()
        assertEquals(2, delivered.size) // 古い結果は捨てる
        assertSerial()
        assertEquals(1, executor.tasks.size) // 待っていた取得が始まる
        executor.runNext()
        poster.runDue()
        assertEquals(loaded(30), delivered.last())
        assertEquals(3, delivered.size)
        assertEquals(listOf(30_000L), poster.pendingDelays())
    }

    @Test
    fun aStuckWorkerFinishingBeforeTheRetryLetsTheRetryStartAtItsOwnTime() {
        val f = fetcher(FakeSource(loaded(20), loaded(30)))
        f.resume()
        poster.advance(15_000)
        executor.runNext() // 切られた作業が終わる
        poster.runDue()
        assertEquals(1, delivered.size) // 結果は捨てる
        assertTrue(executor.tasks.isEmpty())
        poster.advance(29_999)
        assertTrue(executor.tasks.isEmpty())
        poster.advance(1)
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun aWorkerThatCouldNotBeStartedBecomesANetworkFailureAndFreesTheSlot() {
        var attempts = 0
        val f = ScheduleFetcher(FakeSource(), poster, { attempts++; throw java.util.concurrent.RejectedExecutionException() }) {
            delivered += it
        }
        f.resume()
        poster.runDue()
        assertEquals(listOf<ScheduleResult>(ScheduleResult.Failed(FetchError.NETWORK)), delivered)
        poster.advance(30_000) // 枠は空いているので、次の時刻にまた試す
        poster.runDue()
        assertEquals(2, attempts)
        assertEquals(2, delivered.size)
    }

    @Test
    fun pauseWithoutResumeDoesNothing() {
        val source = FakeSource()
        fetcher(source).pause()
        assertEquals(0, source.cancels)
        assertTrue(poster.isIdle)
    }

    // ---- 間隔 ----

    /** 最初の取得の結果が [first] のとき、次の取得までの待ち時間（毎回、新しい予約先・作業用スレッドで）。 */
    private fun delayAfter(first: ScheduleResult): Long {
        val ownPoster = FakePoster()
        val ownExecutor = ManualExecutor()
        ScheduleFetcher(FakeSource(first), ownPoster, ownExecutor) {}.resume()
        ownExecutor.runNext()
        ownPoster.runDue()
        return ownPoster.pendingDelays().single()
    }

    @Test
    fun successUsesTheAnsweredIntervalOrFiveMinutes() {
        assertEquals(20_000L, delayAfter(loaded(20)))
        assertEquals(300_000L, delayAfter(loaded(null)))
        assertEquals(45_000L, delayAfter(ScheduleResult.Disabled(45)))
        assertEquals(300_000L, delayAfter(ScheduleResult.Disabled(null)))
    }

    @Test
    fun pollSecondsIsClampedToTenThroughThirtySixHundred() {
        assertEquals(10_000L, delayAfter(loaded(1)))
        assertEquals(10_000L, delayAfter(loaded(0)))
        assertEquals(10_000L, delayAfter(loaded(Int.MIN_VALUE)))
        assertEquals(10_000L, delayAfter(loaded(10)))
        assertEquals(3_600_000L, delayAfter(loaded(3600)))
        assertEquals(3_600_000L, delayAfter(loaded(3601)))
        assertEquals(3_600_000L, delayAfter(ScheduleResult.Disabled(Int.MAX_VALUE)))
        assertEquals(10, ScheduleFetcher.clampPoll(-5))
        assertEquals(1234, ScheduleFetcher.clampPoll(1234))
    }

    @Test
    fun aFailureWithNothingKnownRetriesInThirtySeconds() {
        assertEquals(30_000L, delayAfter(failed))
        assertEquals(30_000L, delayAfter(ScheduleResult.Failed(FetchError.INVALID)))
    }

    @Test
    fun aFailureAfterASuccessRetriesAtTheLastKnownInterval() {
        val f = fetcher(FakeSource(loaded(90), failed, failed))
        f.resume()
        completeFetch()
        poster.advance(90_000)
        completeFetch() // 失敗
        assertEquals(listOf(90_000L), poster.pendingDelays())
        poster.advance(90_000)
        completeFetch() // 続けて失敗
        assertEquals(listOf(90_000L), poster.pendingDelays())
    }

    @Test
    fun theLastKnownIntervalIsKeptWhenALaterSuccessOmitsIt() {
        val f = fetcher(FakeSource(loaded(60), loaded(null), failed))
        f.resume()
        completeFetch()
        poster.advance(60_000)
        completeFetch() // pollSeconds の無い成功は 300 秒
        assertEquals(listOf(300_000L), poster.pendingDelays())
        poster.advance(300_000)
        completeFetch() // 失敗は最後に分かった 60 秒
        assertEquals(listOf(60_000L), poster.pendingDelays())
    }

    @Test
    fun theLastKnownIntervalIsClampedToo() {
        val f = fetcher(FakeSource(loaded(5000), failed))
        f.resume()
        completeFetch()
        poster.advance(3_600_000)
        completeFetch()
        assertEquals(listOf(3_600_000L), poster.pendingDelays())
    }

    // ---- 失敗の後に成功すれば状態の表示が消える（AC7） ----

    @Test
    fun aSuccessAfterAFailureClearsTheStatusLine() {
        val model = PanelModel(0).also { it.enterApiMode(LocalDate.of(2026, 10, 22)) }
        val seen = mutableListOf<String?>()
        val f = fetcher(FakeSource(failed, loaded(15))) { result ->
            model.applySchedule(result, LocalDate.of(2026, 10, 22))
            seen += model.statusText()
        }
        f.resume()
        completeFetch()
        assertEquals(listOf<String?>(PanelModel.STATUS_FETCH_FAILED), seen)
        assertEquals(listOf(30_000L), poster.pendingDelays()) // 次の間隔で取り直す
        poster.advance(30_000)
        completeFetch()
        assertEquals(listOf(PanelModel.STATUS_FETCH_FAILED, null), seen)
        assertNull(model.statusText())
        assertFalse(model.lastFetchFailed)
        assertEquals(listOf(15_000L), poster.pendingDelays())
    }
}
