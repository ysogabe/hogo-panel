package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEllipsisTest {
    /** 1 コードポイント = 10。「…」も 10。 */
    private val measure: (String) -> Float = { it.codePointCount(0, it.length) * 10f }

    @Test
    fun textThatFitsIsUnchanged() {
        assertEquals("フィラリア", TextEllipsis.fit("フィラリア", 50f, measure))
        assertEquals("", TextEllipsis.fit("", 0f, measure))
    }

    @Test
    fun longTextIsCutAtTheEndWithAnEllipsis() {
        assertEquals("フィラ…", TextEllipsis.fit("フィラリア予防薬", 40f, measure))
        assertEquals("フィラリア予防…", TextEllipsis.fit("フィラリア予防薬です", 80f, measure))
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        val r = TextEllipsis.fit("🐱🐱🐱🐱🐱", 30f, measure)
        assertEquals("🐱🐱…", r)
    }

    @Test
    fun whenNothingFitsOnlyTheEllipsisRemains() {
        assertEquals("…", TextEllipsis.fit("あいう", 5f, measure))
        assertEquals("…", TextEllipsis.fit("あいう", 10f, measure))
    }

    @Test
    fun theResultFitsWheneverTheEllipsisAloneFits() {
        for (w in 10..200 step 10) {
            val r = TextEllipsis.fit("あ".repeat(40), w.toFloat(), measure)
            assertEquals(true, measure(r) <= w)
        }
    }
}
