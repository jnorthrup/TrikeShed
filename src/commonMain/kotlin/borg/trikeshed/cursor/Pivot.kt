package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import java.util.*

/**
 * Ordered — sort cursor by axis columns.
 * Ported from columnar cursors.
 */
fun Cursor.ordered(axis: IntArray, cmp: Comparator<List<Any?>> = Comparator { a, b -> cmpAny(a, b) }): Cursor {
    if (size == 0) return this
    val sorted = TreeMap<List<Any?>, MutableList<Int>>(cmp)
    for (iy in 0 until size) {
        val key = axis.map { row(iy)[it].a }
        sorted.getOrPut(key) { mutableListOf() }.add(iy)
    }
    val orderedIndices = sorted.values.flatMap { it }.toIntArray()
    return orderedIndices.size j { iy ->
        row(orderedIndices[iy])
    }
}

private fun cmpAny(o1: List<Any?>, o2: List<Any?>): Int =
    o1.joinToString("\u0000").compareTo(o2.joinToString("\u0000"))