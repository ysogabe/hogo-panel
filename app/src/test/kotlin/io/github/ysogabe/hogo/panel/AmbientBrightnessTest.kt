package io.github.ysogabe.hogo.panel

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class AmbientBrightnessTest {
    @Test fun anchorsAndUpperLimit() {
        val anchors = listOf(0f to .05f, 5f to .10f, 20f to .20f, 100f to .40f, 300f to .60f, 1000f to .85f)
        for ((lux, level) in anchors) assertEquals(level, AmbientBrightness.levelAt(lux)!!, .00001f)
        assertEquals(.85f, AmbientBrightness.levelAt(Float.MAX_VALUE)!!, 0f)
    }

    @Test fun linearInterpolationAcrossEverySegment() {
        for ((lux, level) in listOf(2.5f to .075f, 12.5f to .15f, 60f to .30f, 200f to .50f, 650f to .725f)) {
            assertEquals(level, AmbientBrightness.levelAt(lux)!!, .00001f)
        }
    }

    @Test fun monotonicAndBoundedIncludingDarkness() {
        var previous = 0f
        for (lux in 0..20000) {
            val level = AmbientBrightness.levelAt(lux / 10f)!!
            assertTrue(level in .05f.. .85f)
            assertTrue(level >= previous)
            previous = level
        }
    }

    @Test fun invalidLuxIsRejected() {
        for (lux in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, -Float.MIN_VALUE)) {
            assertNull(AmbientBrightness.levelAt(lux))
        }
    }

    private class Source(var available: Boolean = true) : LightReadings {
        var starts = 0
        var stops = 0
        val callbacks = mutableListOf<(FloatArray) -> Unit>()
        override fun start(accept: (FloatArray) -> Unit): Boolean {
            starts++
            callbacks += accept
            return available
        }
        override fun stop() { stops++ }
        fun emit(vararg values: Float) { callbacks.last()(values) }
    }

    private class Fixture(available: Boolean = true) {
        var local = 12 * 3_600_000L + 30_000L
        val source = Source(available)
        val applied = mutableListOf<Float?>()
        var pending: (() -> Unit)? = null
        var delay: Long? = null
        var schedules = 0
        val keeper = AmbientBrightnessKeeper(source, { local }, { applied += it }, { ms, task ->
            schedules++
            delay = ms
            pending = task
        }, { pending = null; delay = null })
        fun tick() {
            val task = checkNotNull(pending)
            pending = null
            delay = null
            task()
        }
        fun settle() {
            repeat(100) { if (pending != null) tick() else return }
            fail("transition did not converge")
        }
    }

    @Test fun homeUsesSystemUntilFirstReadingThenAllScreensUseSensor() {
        val f = Fixture()
        f.keeper.start()
        assertTrue(f.applied.isEmpty()) // null = system, so no override write needed
        assertNull(f.pending)
        f.source.emit(100f)
        assertEquals(.40f, f.applied.last()!!, .00001f)
        f.keeper.setClock(true)
        f.keeper.setClock(false) // confirmation/home share this mode
        assertEquals(1, f.applied.size)
        assertEquals(1, f.source.starts)
        assertEquals(0, f.source.stops)
    }

    @Test fun clockFallbackUsesCurrentTimeAndFollowsMinuteBoundary() {
        val f = Fixture()
        f.keeper.setClock(true)
        f.local = 17 * 3_600_000L + 59 * 60_000L + 30_000L
        f.keeper.start()
        assertEquals(ClockBrightness.DAY, f.applied.last())
        assertEquals(30_020L, f.delay)
        f.local = 18 * 3_600_000L
        f.tick()
        assertEquals(ClockBrightness.EVENING, f.applied.last())
        f.local = 22 * 3_600_000L // time correction also reevaluated
        f.tick()
        assertEquals(ClockBrightness.NIGHT, f.applied.last())
    }

    @Test fun switchingFallbackHomeReleasesAndClockReevaluatesWithoutRegistration() {
        val f = Fixture()
        f.keeper.start()
        f.keeper.setClock(true)
        assertEquals(ClockBrightness.DAY, f.applied.last())
        val old = checkNotNull(f.pending)
        f.keeper.setClock(false)
        assertNull(f.applied.last())
        assertNull(f.pending)
        old()
        assertNull(f.pending)
        assertNull(f.applied.last())
        f.local = 23 * 3_600_000L
        f.keeper.setClock(true)
        assertEquals(ClockBrightness.NIGHT, f.applied.last())
        assertEquals(1, f.source.starts)
    }

    @Test fun firstReadingReplacesClockFallbackAndCancelsItsOldTimer() {
        val f = Fixture()
        f.keeper.setClock(true)
        f.keeper.start()
        val old = checkNotNull(f.pending)
        f.source.emit(5f)
        assertEquals(.10f, f.applied.last()!!, .00001f)
        old()
        assertNull(f.pending)
        assertEquals(2, f.applied.size)
    }

    @Test fun singleOnChangeEventConvergesUpWithoutMoreEvents() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(0f)
        f.source.emit(1000f)
        assertEquals(.05f, f.applied.last()!!, .00001f)
        assertEquals(250L, f.delay)
        f.settle()
        assertEquals(.85f, f.applied.last()!!, .00001f)
        for ((a, b) in f.applied.filterNotNull().zipWithNext()) {
            assertTrue(b > a)
            assertTrue(b - a <= .02501f)
        }
        assertTrue(f.applied.size <= 34)
    }

    @Test fun singleOnChangeEventConvergesDownAndStaysVisible() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(1000f)
        f.source.emit(0f)
        f.settle()
        assertEquals(.05f, f.applied.last()!!, .00001f)
        for ((a, b) in f.applied.filterNotNull().zipWithNext()) {
            assertTrue(b < a)
            assertTrue(a - b <= .02501f)
        }
    }

    @Test fun smallNoiseDoesNotStartTransitionButAccumulatedChangeDoes() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(100f)
        for (lux in listOf(99f, 101f, 105f, 95f, 110f)) f.source.emit(lux)
        assertNull(f.pending)
        assertEquals(1, f.applied.size)
        f.source.emit(130f)
        assertNotNull(f.pending)
        f.settle()
        assertEquals(.43f, f.applied.last()!!, .00001f)
    }

    @Test fun readingsDoNotPostponePendingStepAndTargetCanReverse() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(0f)
        f.source.emit(1000f)
        val first = f.pending
        f.source.emit(300f)
        assertSame(first, f.pending)
        f.tick()
        assertEquals(.075f, f.applied.last()!!, .00001f)
        f.source.emit(0f)
        f.settle()
        assertEquals(.05f, f.applied.last()!!, .00001f)
    }

    @Test fun screenSwitchDuringTransitionPreservesTimerAndTarget() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(0f)
        f.source.emit(300f)
        f.tick()
        val timer = f.pending
        f.keeper.setClock(true)
        f.keeper.setClock(false)
        assertSame(timer, f.pending)
        f.settle()
        assertEquals(.60f, f.applied.last()!!, .00001f)
        assertEquals(1, f.source.starts)
    }

    @Test fun sameLuxLongAfterLastEventKeepsValueAndNeedsNoPolling() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(20f)
        f.local += 10 * 86_400_000L
        f.keeper.setClock(true)
        f.source.emit(20f)
        assertEquals(.20f, f.applied.last()!!, .00001f)
        assertNull(f.pending)
        assertEquals(1, f.applied.size)
    }

    @Test fun invalidAndEmptyEventsPreserveFallbackUntilValidReading() {
        val f = Fixture()
        f.keeper.setClock(true)
        f.keeper.start()
        val timer = f.pending
        f.source.emit()
        for (lux in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) f.source.emit(lux)
        assertSame(timer, f.pending)
        assertNull(f.keeper.latestLux)
        assertEquals(listOf<Float?>(ClockBrightness.DAY), f.applied)
        f.source.emit(0f)
        assertEquals(.05f, f.applied.last()!!, .00001f)
    }

    @Test fun invalidReadingsDuringTransitionDoNotChangeGoal() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(100f)
        f.source.emit(0f)
        f.source.emit(Float.NaN)
        f.source.emit()
        f.settle()
        assertEquals(.05f, f.applied.last()!!, .00001f)
        assertEquals(0f, f.keeper.latestLux!!, 0f)
    }

    @Test fun unavailableOrFailedRegistrationKeepsBothFallbacksAndIgnoresCallback() {
        val f = Fixture(available = false)
        f.keeper.start()
        f.source.emit(1000f) // callback retained by a failed source
        assertTrue(f.applied.isEmpty())
        assertNull(f.keeper.latestLux)
        f.keeper.setClock(true)
        assertEquals(ClockBrightness.DAY, f.applied.last())
        assertNotNull(f.pending)
        assertEquals(1, f.source.starts)
        assertTrue(f.source.stops >= 1)
    }

    @Test fun duplicateStartAndStopDoNotDuplicateRegistrationOrRelease() {
        val f = Fixture()
        f.keeper.stop()
        assertEquals(0, f.source.stops)
        f.keeper.start()
        f.source.emit(100f)
        f.keeper.start()
        assertEquals(1, f.source.starts)
        assertEquals(1, f.applied.size)
        f.keeper.stop()
        f.keeper.stop()
        assertEquals(1, f.source.stops)
        assertEquals(listOf(.40f, null), f.applied)
    }

    @Test fun pauseOrDetachStopsSubscriptionTimerAndOverrideIgnoringLateWork() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(0f)
        f.source.emit(1000f)
        val timer = checkNotNull(f.pending)
        f.keeper.stop()
        val count = f.applied.size
        f.source.emit(300f)
        timer()
        f.keeper.setClock(true)
        assertNull(f.pending)
        assertNull(f.applied.last())
        assertEquals(count, f.applied.size)
        assertEquals(1, f.source.stops)
        assertNull(f.keeper.latestLux)
    }

    @Test fun resumedSessionDiscardsOldLuxAndIgnoresOldEventsAndTimers() {
        val f = Fixture()
        f.keeper.start()
        f.source.emit(0f)
        f.source.emit(1000f)
        val oldEvent = f.source.callbacks.last()
        val oldTimer = checkNotNull(f.pending)
        f.keeper.stop()
        f.keeper.start()
        assertNull(f.keeper.latestLux)
        assertNull(f.applied.last())
        oldEvent(floatArrayOf(300f))
        oldTimer()
        assertNull(f.pending)
        assertNull(f.keeper.latestLux)
        f.source.emit(20f)
        assertEquals(.20f, f.applied.last()!!, .00001f)
        assertEquals(2, f.source.starts)
    }

    @Test fun clockResumeUsesNewTimeUntilNewSample() {
        val f = Fixture()
        f.keeper.setClock(true)
        f.keeper.start()
        f.source.emit(1000f)
        f.keeper.stop()
        f.local = 23 * 3_600_000L
        f.keeper.start()
        assertEquals(ClockBrightness.NIGHT, f.applied.last())
        assertNull(f.keeper.latestLux)
        f.source.emit(0f)
        assertNull(f.pending)
        assertTrue(abs(f.applied.last()!! - .05f) < .00001f)
    }
}
