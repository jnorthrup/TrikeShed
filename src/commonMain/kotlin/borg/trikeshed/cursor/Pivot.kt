package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/**
 * Ordered — sort cursor by axis columns.
 * Ported from columnar cursors.
 * Comparison is done via CharSeries contentEquals for CharSequence values,
 * numeric ordering for numeric values — no String, no java.util.TreeMap.
 */
fun Cursor.ordered(axis: IntArray, cmp: Comparator<Series<Any?>> = Comparator { a, b -> cmpRow(a, b) }): Cursor {
    if (size == 0) return this
    val pairs = ArrayList<Join<Series<Any?>, Int>>(size)
    for (iy in 0 until size) {
        val key: Series<Any?> = axis.size j { x -> row(iy)[axis[x]].a }
        pairs.add(Join(key, iy))
    }
    val sorted = pairs.sortedWith { x, y -> cmp.compare(x.a, y.a) }
    return sorted.size j { iy -> row(sorted[iy].b) }
}

private fun cmpRow(a: Series<Any?>, b: Series<Any?>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = cmpAny(a[i], b[i])
        if (c != 0) return c
    }
    return a.size - b.size
}

private fun cmpAny(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Comparable<*> && b is Comparable<*> -> @Suppress("UNCHECKED_CAST") (a as Comparable<Any>).compareTo(b)
    a is CharSequence && b is CharSequence -> compareCharSequences(a, b)
    else -> 0
}

private fun compareCharSequences(a: CharSequence, b: CharSequence): Int {
    val len = minOf(a.length, b.length)
    for (i in 0 until len) {
        val d = a[i] - b[i]
        if (d != 0) return d
    }
    return a.length - b.length
}
