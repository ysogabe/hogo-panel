package io.github.ysogabe.hogo.panel

import java.util.concurrent.Executor

/** メインスレッドで実行する予約の窓口。Android では [android.os.Handler] の薄い包み。 */
interface MainPoster {
    fun post(task: Runnable)

    fun postDelayed(task: Runnable, delayMs: Long)

    fun removeCallbacks(task: Runnable)
}

/**
 * 予定の取得の予約。取得は [executor]（作業用スレッド）で行い、結果は [poster]（メインスレッド）で [onResult] に渡す。
 * メインスレッドの外から触ってよいのは [ScheduleSource.fetch] だけで、状態はすべてメインスレッドで扱う。
 *
 * - 取得は同時に 1 件。前の取得が終わって（または見張りが切って）から次を予約する。
 * - 取得を始めるたびに [WATCHDOG_MS] の見張りを予約し、時間が来たら世代番号を進め、接続を切り、`Failed(TIMEOUT)` を渡す。
 * - [pause] で世代番号を進めて予約を消す。止めた後に届いた結果は捨て、止めている間は予約しない。
 * - 次までの間隔は、成功（`Loaded`・`Disabled`）なら応答の `pollSeconds`（無ければ [SUCCESS_DEFAULT_SECONDS]）、
 *   失敗なら最後に分かった `pollSeconds`（無ければ [FAILURE_DEFAULT_SECONDS]）。どちらも 10〜3600 秒に収める。
 */
class ScheduleFetcher(
    private val source: ScheduleSource,
    private val poster: MainPoster,
    private val executor: Executor,
    private val onResult: (ScheduleResult) -> Unit,
) {
    private var running = false
    private var inFlight = false
    private var generation = 0

    /** 最後に分かった `pollSeconds`（収めた後の値）。成功の応答に付いていたときだけ更新する。 */
    private var lastPollSeconds: Int? = null

    private val next = Runnable { startFetch() }
    private val watchdog = Runnable { onWatchdog() }

    /** 即時に 1 回取得し、以後は完了のたびに次を予約する。すでに動いていれば何もしない。 */
    fun resume() {
        if (running) return
        running = true
        startFetch()
    }

    /** 予約・見張りを消し、作業中の取得は接続を切って結果を捨てる。 */
    fun pause() {
        if (!running && !inFlight) return
        running = false
        generation++
        inFlight = false
        poster.removeCallbacks(next)
        poster.removeCallbacks(watchdog)
        source.cancel()
    }

    private fun startFetch() {
        if (!running || inFlight) return
        inFlight = true
        val gen = generation
        poster.postDelayed(watchdog, WATCHDOG_MS)
        executor.execute {
            val result = try {
                source.fetch()
            } catch (_: Throwable) {
                ScheduleResult.Failed(FetchError.NETWORK)
            }
            poster.post { complete(gen, result) }
        }
    }

    private fun complete(gen: Int, result: ScheduleResult) {
        if (gen != generation || !running) return // 止めた後・見張りで切った後の結果は捨てる
        inFlight = false
        poster.removeCallbacks(watchdog)
        deliver(result)
    }

    private fun onWatchdog() {
        if (!running || !inFlight) return
        generation++ // 作業中の取得の結果を捨てる
        inFlight = false
        source.cancel()
        deliver(ScheduleResult.Failed(FetchError.TIMEOUT))
    }

    private fun deliver(result: ScheduleResult) {
        val answered = when (result) {
            is ScheduleResult.Loaded -> result.pollSeconds
            is ScheduleResult.Disabled -> result.pollSeconds
            is ScheduleResult.Failed -> null
        }
        if (answered != null) lastPollSeconds = clampPoll(answered)
        val delaySeconds = when (result) {
            is ScheduleResult.Failed -> lastPollSeconds ?: FAILURE_DEFAULT_SECONDS
            else -> answered?.let(::clampPoll) ?: SUCCESS_DEFAULT_SECONDS
        }
        poster.postDelayed(next, delaySeconds * 1000L)
        onResult(result)
    }

    companion object {
        const val WATCHDOG_MS = 15_000L
        const val MIN_POLL_SECONDS = 10
        const val MAX_POLL_SECONDS = 3600
        const val SUCCESS_DEFAULT_SECONDS = 300
        const val FAILURE_DEFAULT_SECONDS = 30

        fun clampPoll(seconds: Int): Int = seconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
    }
}
