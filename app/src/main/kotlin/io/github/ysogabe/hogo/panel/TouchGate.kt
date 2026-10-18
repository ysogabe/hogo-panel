package io.github.ysogabe.hogo.panel

/**
 * 1 回のジェスチャー（最初の指が触れてから全ての指が離れる、またはキャンセルされるまで）を、
 * 操作として配信するか消費するかを決める。
 *
 * - 時計からの復帰や、ジェスチャー途中の切替では [consume] して、残りの全指の UP/CANCEL まで何も配信しない。
 * - 2 本目の指が触れたジェスチャーは、押し始めの対象があっても操作にしない。
 * - 消費は全ての指が離れた後の次の独立した DOWN で終わる。
 */
class TouchGate {
    var activePointers = 0
        private set
    private var consumed = false

    val gestureActive: Boolean get() = activePointers > 0

    /** 最初の指。前のジェスチャーの取りこぼしがあっても、ここで新しいジェスチャーとして数え直す。 */
    fun down() {
        activePointers = 1
        consumed = false
    }

    /** 追加の指。マルチタッチは操作として扱わない。 */
    fun pointerDown() {
        if (activePointers == 0) return
        activePointers++
        consumed = true
    }

    fun pointerUp() {
        if (activePointers > 1) activePointers--
    }

    /** このジェスチャーの残りを消費する。 */
    fun consume() {
        consumed = true
    }

    /** 最後の指が離れた。操作として配信してよいなら true。 */
    fun up(): Boolean {
        val deliver = !consumed && activePointers == 1
        reset()
        return deliver
    }

    fun cancel() = reset()

    fun reset() {
        activePointers = 0
        consumed = false
    }

    val isConsumed: Boolean get() = consumed
}
