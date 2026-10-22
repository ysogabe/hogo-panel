package io.github.ysogabe.hogo.panel

/** JSON の数値リテラル。[isInteger] は小数点も指数も無い形（`2.0`・`1e2` は false）。 */
class JsonNumber(val raw: String) {
    val isInteger: Boolean = raw.none { it == '.' || it == 'e' || it == 'E' }

    /** 整数の形で Long に収まるときだけ値。それ以外は null。 */
    val longOrNull: Long? get() = if (isInteger) raw.toLongOrNull() else null

    override fun toString(): String = "JsonNumber"
}

/**
 * RFC 8259 に厳密な JSON の読み取り。再帰しない（深い入れ子でスタックを使い切らない）。
 *
 * Android の `org.json` は同じキーを黙って上書きし、単体テスト用の Maven 版は例外を投げる。
 * コメント・単引用符・引用符なしの文字列なども通してしまう。結果が実機とテストで割れないよう、
 * 検査と読み取りをここで一括して行い、`org.json` は使わない。
 *
 * 結果の型: object は `Map<String, Any?>`、array は `List<Any?>`、string は `String`、number は [JsonNumber]、
 * boolean は `Boolean`、null は Kotlin の null。読めない・入れ子が [MAX_DEPTH] を超える・同じオブジェクトに
 * 同じキー（`"a"` と `"a"` も同じ）が 2 回出る・値のあとに文字が残る、のどれかなら [Result.Invalid]。
 */
object StrictJson {
    /** トップの `{}` / `[]` を 1 と数えて、受け入れる深さの上限。 */
    const val MAX_DEPTH = 8

    sealed interface Result {
        data class Ok(val value: Any?) : Result
        data object Invalid : Result
    }

    private class ObjectFrame(val map: LinkedHashMap<String, Any?> = LinkedHashMap(), var key: String? = null)

    fun parse(text: String): Result = try {
        Reader(text).readDocument()
    } catch (_: MalformedJson) {
        Result.Invalid
    }

    private class MalformedJson : RuntimeException(null, null, false, false)

    private class Reader(private val s: String) {
        private var i = 0

        /** 開いている入れ子。要素は [ObjectFrame] か `ArrayList<Any?>`。 */
        private val stack = ArrayList<Any>()

        fun readDocument(): Result {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (i != s.length) fail()
            return Result.Ok(value)
        }

        private fun fail(): Nothing = throw MalformedJson()

        private fun skipWhitespace() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail()

        /** 1 つの値（入れ子は明示のスタックで辿る）。 */
        private fun readValue(): Any? {
            var result: Any? = null
            var haveResult = false
            // 値を 1 つ読み始める。コンテナなら開いて、閉じるまでループで回る。
            var pending = true
            while (true) {
                if (pending) {
                    pending = false
                    skipWhitespace()
                    when (peek()) {
                        '{' -> {
                            i++
                            push(ObjectFrame())
                            skipWhitespace()
                            if (peek() == '}') {
                                i++
                                result = pop()
                                haveResult = true
                            } else {
                                readKey()
                                pending = true
                            }
                        }
                        '[' -> {
                            i++
                            push(ArrayList<Any?>())
                            skipWhitespace()
                            if (peek() == ']') {
                                i++
                                result = pop()
                                haveResult = true
                            } else {
                                pending = true
                            }
                        }
                        else -> {
                            result = readScalar()
                            haveResult = true
                        }
                    }
                    if (pending) continue
                }
                if (!haveResult) fail()
                // 読めた値を、いちばん内側のコンテナへ入れる。
                val top = stack.lastOrNull() ?: return result
                haveResult = false
                skipWhitespace()
                when (top) {
                    is ObjectFrame -> {
                        val key = top.key ?: fail()
                        if (top.map.containsKey(key)) fail()
                        top.map[key] = result
                        top.key = null
                        when (peek()) {
                            ',' -> {
                                i++
                                skipWhitespace()
                                readKey()
                                pending = true
                            }
                            '}' -> {
                                i++
                                result = pop()
                                haveResult = true
                            }
                            else -> fail()
                        }
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = top as ArrayList<Any?>
                        list.add(result)
                        when (peek()) {
                            ',' -> {
                                i++
                                pending = true
                            }
                            ']' -> {
                                i++
                                result = pop()
                                haveResult = true
                            }
                            else -> fail()
                        }
                    }
                }
            }
        }

        private fun push(frame: Any) {
            if (stack.size >= MAX_DEPTH) fail()
            stack.add(frame)
        }

        /** 閉じたコンテナを取り出して値にする。 */
        private fun pop(): Any {
            val frame = stack.removeAt(stack.size - 1)
            return if (frame is ObjectFrame) frame.map else frame
        }

        /** `"key" :` まで読んで、いちばん内側の object に覚える。 */
        private fun readKey() {
            if (peek() != '"') fail()
            val key = readString()
            skipWhitespace()
            if (peek() != ':') fail()
            i++
            (stack.last() as ObjectFrame).key = key
        }

        private fun readScalar(): Any? {
            val c = peek()
            return when {
                c == '"' -> readString()
                c == '-' || c in '0'..'9' -> readNumber()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> fail()
            }
        }

        private fun readNumber(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> while (i < s.length && s[i] in '0'..'9') i++
                else -> fail()
            }
            if (i < s.length && s[i] == '.') {
                i++
                digits()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                digits()
            }
            return JsonNumber(s.substring(start, i))
        }

        private fun digits() {
            val begin = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == begin) fail()
        }

        private fun readString(): String {
            i++ // 開き引用符
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> fail() // 生の制御文字は不可
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                var code = 0
                                repeat(4) {
                                    val d = Character.digit(s[i++], 16)
                                    // Character.digit は全角数字なども受けるので ASCII に限る
                                    if (d < 0 || s[i - 1].code > 0x7F) fail()
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar())
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
