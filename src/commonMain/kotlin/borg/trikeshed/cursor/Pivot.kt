package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/**
 * Sparse widening by distinct axis keys, in first-occurrence order. Rows are not aggregated:
 * compose [groupBy] to reduce them. Unmatched cells are null; values stay lazy.
 */
fun Cursor.pivot(lhs: IntArray, axis: IntArray, fanOut: IntArray): Cursor {
    requireColumns(lhs)
    requireColumns(axis)
    requireColumns(fanOut)
    if (size == 0 || fanOut.isEmpty()) return select(*lhs)
    val retained = lhs.copyOf()
    val columns = fanOut.copyOf()
    val keys = linkedMapOf<CursorKey, Int>()
    val ordinals = IntArray(size) { row ->
        val key = this[row].key(axis)
        keys.getOrPut(key) { keys.size }
    }
    val schema = meta
    val prefixes = Array(keys.size) { "" }
    for ((key, ordinal) in keys) {
        prefixes[ordinal] = axis.indices.joinToString(",", "[", "]") { i -> "${schema[axis[i]].name}=${key[i]}" }
    }
    require(keys.size <= (Int.MAX_VALUE - retained.size) / columns.size) { "Pivot width exceeds Int capacity" }
    val count = retained.size + keys.size * columns.size
    return size j { row ->
        val source = this[row]
        count j { column ->
            if (column < retained.size) source[retained[column]]
            else {
                val offset = column - retained.size
                val keyIndex = offset / columns.size
                val sourceColumn = columns[offset % columns.size]
                val value = if (ordinals[row] == keyIndex) source.values[sourceColumn] else null
                value j {
                    val original = source[sourceColumn].b()
                    ColumnMeta("${prefixes[keyIndex]}:${original.name}", original.type, original.child)
                }
            }
        }
    }
}

/** Reverse the column axis, preserving the value/metadata pairs. */
fun Cursor.mirror(): Cursor = size j { row ->
    val source = this[row]
    source.size j { column -> source[source.size - 1 - column] }
}

/**
 * Append missing domain keys as null-filled rows, retaining existing row order and duplicates.
 * The explicit domain replaces Columnar's JVM-only implicit LocalDate interval.
 */
fun Cursor.resample(indexColumn: Int, domain: Series<Any?>): Cursor {
    requireColumns(intArrayOf(indexColumn))
    require(size > 0) { "Resampling needs an exemplar row for metadata" }
    val existing = mutableSetOf<Any?>()
    for (row in 0 until size) existing.add(this[row].values[indexColumn])
    val missing = mutableListOf<Any?>()
    for (index in 0 until domain.size) if (existing.add(domain[index])) missing.add(domain[index])
    val exemplar = this[0]
    return (size + missing.size) j { row ->
        if (row < size) this[row]
        else exemplar.size j { column ->
            (if (column == indexColumn) missing[row - size] else null) j exemplar[column].b
        }
    }
}
