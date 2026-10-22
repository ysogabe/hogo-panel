package io.github.ysogabe.hogo.panel

/** 幅に収まらない文字列の末尾を「…」で切る。測り方は呼び出し側が渡す（Paint に依らず単体テストできる）。 */
object TextEllipsis {
    const val MARK = "…"

    /** [text] が [maxWidth] に収まればそのまま。収まらなければ、収まる最も長い先頭 + 「…」。サロゲートペアは割らない。 */
    fun fit(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        val cps = text.codePointCount(0, text.length)
        // 先頭 n コードポイント + 「…」が収まる最大の n を二分探索（幅は n に対して単調と見なす）。
        var lo = 0
        var hi = cps - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(prefix(text, mid) + MARK) <= maxWidth) lo = mid else hi = mid - 1
        }
        return prefix(text, lo) + MARK
    }

    private fun prefix(text: String, codePoints: Int): String =
        text.substring(0, text.offsetByCodePoints(0, codePoints))
}
