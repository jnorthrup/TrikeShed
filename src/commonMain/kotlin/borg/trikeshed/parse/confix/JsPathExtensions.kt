package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*

/** Convert a List of path segments (String or Int) into a JsPath Series.
 *  Accepts Int/Number or String; other values are stringified.
 */
fun List<Any?>.toJsPath(): JsPath {
    val n = this.size
    if (n == 0) return 0 j { _: Int -> Either.left("") }
    return n j { i: Int ->
        val seg = this@toJsPath[i]
        when (seg) {
            is Int -> Either.right(seg)
            is Number -> Either.right(seg.toInt())
            else -> Either.left(seg?.toString() ?: "")
        }
    }
}
