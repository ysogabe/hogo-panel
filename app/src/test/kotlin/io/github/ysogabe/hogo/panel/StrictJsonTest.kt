package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    private fun ok(text: String): Any? = (StrictJson.parse(text) as StrictJson.Result.Ok).value

    private fun invalid(text: String) = assertEquals("expected invalid: $text", StrictJson.Result.Invalid, StrictJson.parse(text))

    @Test
    fun readsEveryValueType() {
        val root = ok("""{"a":[1,"x",true,false,null,{"b":{}}],"c":"\u3042\n\"\/"}""") as Map<*, *>
        val a = root["a"] as List<*>
        assertEquals(6, a.size)
        assertEquals("1", (a[0] as JsonNumber).raw)
        assertEquals("x", a[1])
        assertEquals(true, a[2])
        assertEquals(false, a[3])
        assertNull(a[4])
        assertEquals(emptyMap<String, Any?>(), (a[5] as Map<*, *>)["b"])
        assertEquals("あ\n\"/", root["c"])
    }

    @Test
    fun numbersKeepTheirLiteralForm() {
        fun n(text: String) = ok(text) as JsonNumber
        assertTrue(n("2").isInteger)
        assertEquals(2L, n("2").longOrNull)
        assertEquals(0L, n("-0").longOrNull)
        assertFalse(n("2.0").isInteger)
        assertNull(n("2.0").longOrNull)
        assertFalse(n("1e2").isInteger)
        assertFalse(n("1E+2").isInteger)
        assertFalse(n("-1.5e-3").isInteger)
        assertNull(n("12345678901234567890").longOrNull)
        assertEquals(9007199254740991L, n("9007199254740991").longOrNull)
    }

    @Test
    fun rejectsWhatLenientParsersAccept() {
        listOf(
            "", " ", "{", "{\"a\":1", "{\"a\":1,}", "[1,]", "[,1]", "{,}", "{\"a\" 1}", "{\"a\"=1}", "{\"a\":1;\"b\":2}",
            "{'a':1}", "{a:1}", "{\"a\":'x'}", "{\"a\":x}", "{\"a\":0x10}", "{\"a\":01}", "{\"a\":.5}", "{\"a\":1.}",
            "{\"a\":+1}", "{\"a\":-}", "{\"a\":1e}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":tru}", "{\"a\":truex}",
            "/* c */{}", "{} // c", "# c\n{}", "{\"a\":1} x", "{}{}", "\uFEFF{}", "{\"a\":\"\u0001\"}", "{\"a\":\"\t\"}",
            "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12G4\"}", "{\"a\":\"abc", "{\"a\":\"\\",
            "[1 2]", "[1}", "{\"a\":1]",
        ).forEach(::invalid)
    }

    @Test
    fun acceptsWhitespaceAndEmptyContainers() {
        assertEquals(emptyMap<String, Any?>(), ok(" \t\r\n{ }\n"))
        assertEquals(emptyList<Any?>(), ok("[ ]"))
    }

    @Test
    fun depthCountsTheTopContainerAsOneAndAllowsEight() {
        assertTrue(StrictJson.parse("[".repeat(8) + "]".repeat(8)) is StrictJson.Result.Ok)
        invalid("[".repeat(9) + "]".repeat(9))
        assertTrue(StrictJson.parse("{\"a\":".repeat(7) + "[]" + "}".repeat(7)) is StrictJson.Result.Ok)
        invalid("{\"a\":".repeat(8) + "[]" + "}".repeat(8))
    }

    @Test
    fun bracketsAndEscapesInsideStringsAreNotCounted() {
        assertTrue(StrictJson.parse("""{"a":"[[[[[[[[[[[[{{{{{{{{{{ \" [[[[ \\ [[[["}""") is StrictJson.Result.Ok)
        assertTrue(StrictJson.parse("""{"[[[[[[[[[[[[":1}""") is StrictJson.Result.Ok)
    }

    @Test
    fun veryDeepInputFailsWithoutOverflowingTheStack() {
        invalid("[".repeat(60_000))
        invalid("{\"a\":".repeat(10_000))
    }

    @Test
    fun duplicateKeysAreInvalidAtAnyLevel() {
        invalid("""{"a":1,"a":2}""")
        invalid("""{"a":1,"a":1}""")
        invalid("""{"x":{"a":1,"b":{"c":1,"c":2}}}""")
        invalid("""{"x":[{"a":1},{"a":1,"a":2}]}""")
        invalid("""{"a":1,"\u0061":2}""")
        invalid("""{"a":null,"a":null}""")
        // 別のオブジェクトの同名は重複ではない。
        assertTrue(StrictJson.parse("""{"a":{"a":1},"b":[{"a":1},{"a":2}]}""") is StrictJson.Result.Ok)
    }

    @Test
    fun unpairedSurrogateEscapesAreKeptAsTheyAre() {
        assertEquals("\ud800", (ok("""{"a":"\ud800"}""") as Map<*, *>)["a"])
    }
}
