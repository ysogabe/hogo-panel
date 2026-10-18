package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGateTest {
    @Test
    fun plainTapIsDelivered() {
        val g = TouchGate()
        g.down()
        assertTrue(g.gestureActive)
        assertTrue(g.up())
        assertFalse(g.gestureActive)
    }

    @Test
    fun consumedGestureIsNotDeliveredAndEndsOnTheLastUp() {
        val g = TouchGate()
        g.down()
        g.consume()
        assertFalse(g.up())
        g.down()
        assertTrue(g.up())
    }

    @Test
    fun extraFingerTurnsTheGestureIntoANonTapEvenWhenItLiftsFirst() {
        val g = TouchGate()
        g.down()
        g.pointerDown()
        assertEquals(2, g.activePointers)
        g.pointerUp()
        assertEquals(1, g.activePointers)
        assertFalse(g.up())
    }

    @Test
    fun cancelEndsEverythingAndTheNextTouchIsFresh() {
        val g = TouchGate()
        g.down()
        g.pointerDown()
        g.cancel()
        assertFalse(g.gestureActive)
        g.down()
        assertTrue(g.up())
    }

    @Test
    fun strayPointerDownWithoutAGestureIsIgnored() {
        val g = TouchGate()
        g.pointerDown()
        assertFalse(g.gestureActive)
        g.down()
        assertTrue(g.up())
    }
}
