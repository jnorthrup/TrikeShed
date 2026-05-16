@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.miniduck

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.mutable.SeriesBuffer

/** Equality predicate for Cursor.where. */
fun Eq(column: CharSequence, value: Any?): (RowVec) -> Boolean = { it.getValue(column) == value }

/** Greater-than-or-equal predicate for Cursor.where. */
fun Ge(column: CharSequence, value: Any?): (RowVec) -> Boolean = {
    compareCursorValues(it.getValue(column), value)?.let { result -> result >= 0 } ?: false
}

/** Greater-than predicate for Cursor.where. */
fun Gt(column: CharSequence, value: Any?): (RowVec) -> Boolean = {
    compareCursorValues(it.getValue(column), value)?.let { result -> result > 0 } ?: false
}

private fun compareCursorValues(left: Any?, right: Any?): Int? = when {
    left == null || right == null -> null
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is CharSequence && right is CharSequence -> left.cs.compareTo(right.cs)
    left is Boolean && right is Boolean -> left.compareTo(right)
    left is Comparable<*> && left::class == right::class -> compareComparableValues(left, right)
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun compareComparableValues(left: Comparable<*>, right: Any): Int =
    (left as Comparable<Any>).compareTo(right)

/** Filter rows by predicate. */
fun Cursor.where(predicate: (RowVec) -> Boolean): Cursor {
    var count = 0
    val bitmap = BooleanArray(size)
    for (i in 0 until size) {
        if (predicate(at(i))) { count++; bitmap[i] = true }
    }
    val indices = IntArray(count)
    var j = 0
    for (i in 0 until size) {
        if (bitmap[i]) indices[j++] = i
    }
    return count j { idx -> at(indices[idx]) }
}

/** Project named columns — family-aware so DocRowVec/ViewRowVec rows keep their child. */
fun Cursor.project(vararg columns: CharSequence): Cursor {
    val cursor = this
    return size j { y: Int ->
        val row = cursor.b(y)
        val keys = columns.map { it.toString() }
        val values = keys.map { col -> rowValue(row, col) }
        DocRowVec(keys, values, copiedChild(row))
    }
}

/** Project columns by index — family-aware so BlobRowVec rows keep their child. */
fun Cursor.columns(vararg indices: Int): Cursor {
    val cursor = this
    return size j { y: Int ->
        val row = cursor.b(y)
        val keys = indices.map { idx -> rowName(row, idx) }
        val values = indices.map { idx -> rowCell(row, idx) }
        DocRowVec(keys, values, copiedChild(row))
    }
}

/** Order by specs. */
fun Cursor.orderBy(vararg specs: OrderSpec): Cursor {
    if (size == 0) return this
    val sorted = this.view.sortedWith { r1, r2 ->
        for (spec in specs) {
            val res = compareCursorValues(r1.getValue(spec.column), r2.getValue(spec.column)) ?: 0
            if (res != 0) return@sortedWith if (spec.desc) -res else res
        }
        0
    }
    return sorted.size j { sorted[it] }
}

/** Order by a single column (ascending). */
fun Cursor.orderBy(column: CharSequence): Cursor = orderBy(OrderSpec(column.toString()))

/** Order by a single column with explicit direction. */
fun Cursor.orderBy(column: CharSequence, desc: Boolean): Cursor = orderBy(OrderSpec(column.toString(), desc = desc))

/** Chain transforms. */
infix fun <T> Cursor.then(transform: (Cursor) -> T): T = transform(this)

/** Create an empty cursor. */
fun emptyCursor(): Cursor = emptySeries()

/** Minus operator to exclude a column. */
operator fun Cursor.minus(columnName: CharSequence): Cursor {
    val meta = this.meta
    val colIdx = meta.view.indexOfFirst { it.name == columnName }
    if (colIdx < 0) return this
    val indices = (0 until meta.size).filter { it != colIdx }.toIntArray()
    return this.get(*indices)
}

/** Explicit take/drop — slice rows directly via the b accessor to avoid the IntRange column-select overload. */
fun Cursor.take(n: Int): Cursor {
    require(n >= 0) { "take count must be non-negative, got $n" }
    val end = minOf(n, size)
    return end j b
}

fun Cursor.drop(n: Int): Cursor {
    require(n >= 0) { "drop count must be non-negative, got $n" }
    val start = minOf(n, size)
    val rowFn = b
    return (size - start) j { i: Int -> rowFn(start + i) }
}

// Aggregation model
interface Aggregation {
    val targetColumn: CharSequence
    val outputColumn: CharSequence
    fun createAccumulator(): Accumulator
}

interface Accumulator {
    fun add(value: Any?)
    fun getResult(): Any?
}

object Agg {
    fun count(column: CharSequence = "*"): Aggregation = object : Aggregation {
        override val targetColumn: CharSequence = column.toString()
        override val outputColumn: CharSequence = "count"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var count = 0L
            override fun add(value: Any?) { count++ }
            override fun getResult(): Any? = count
        }
    }

    fun sum(column: CharSequence): Aggregation = object : Aggregation {
        override val targetColumn: CharSequence = column.toString()
        override val outputColumn: CharSequence = "sum_$column"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var sum = 0.0
            override fun add(value: Any?) {
                if (value is Number) sum += value.toDouble()
            }
            override fun getResult(): Any? = sum
        }
    }

    fun avg(column: CharSequence): Aggregation = object : Aggregation {
        override val targetColumn: CharSequence = column.toString()
        override val outputColumn: CharSequence = "avg_$column"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var sum = 0.0
            var count = 0L
            override fun add(value: Any?) {
                if (value is Number) {
                    sum += value.toDouble()
                    count++
                }
            }
            override fun getResult(): Any? = if (count > 0) sum / count else 0.0
        }
    }

    fun min(column: CharSequence): Aggregation = object : Aggregation {
        override val targetColumn: CharSequence = column.toString()
        override val outputColumn: CharSequence = "min_$column"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var min: Double? = null
            override fun add(value: Any?) {
                if (value is Number) {
                    val v = value.toDouble()
                    if (min == null || v < min!!) min = v
                }
            }
            override fun getResult(): Any? = min ?: 0.0
        }
    }

    fun max(column: CharSequence): Aggregation = object : Aggregation {
        override val targetColumn: CharSequence = column.toString()
        override val outputColumn: CharSequence = "max_$column"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var max: Double? = null
            override fun add(value: Any?) {
                if (value is Number) {
                    val v = value.toDouble()
                    if (max == null || v > max!!) max = v
                }
            }
            override fun getResult(): Any? = max ?: 0.0
        }
    }
}

/** Group by a column with aggregations. */
fun Cursor.groupBy(keyColumn: CharSequence, vararg aggregations: Aggregation): Cursor {
    if (size == 0) return this

    val groups: SeriesBuffer<Pair<Any?, SeriesBuffer<Accumulator>>> = SeriesBuffer()
    val groupOrder: SeriesBuffer<Any?> = SeriesBuffer()

    for (i in 0 until size) {
        val row = at(i)
        val key = row.getValue(keyColumn)
        val existingGroup = groups.view.find { it.first == key }
        val accumulators: SeriesBuffer<Accumulator>
        if (existingGroup != null) {
            accumulators = existingGroup.second
        } else {
            groupOrder.add(key)
            accumulators = SeriesBuffer()
            groups.add(key to accumulators)
        }
        if (accumulators.size == 0) aggregations.forEach { accumulators.add(it.createAccumulator()) }
        aggregations.forEachIndexed { index, agg ->
            val value = if (agg.targetColumn == "*") null else row.getValue(agg.targetColumn)
            accumulators[index].add(value)
        }
    }

    val resultRows: SeriesBuffer<RowVec> = SeriesBuffer()
    for (idx in 0 until groupOrder.size) {
        val key = groupOrder[idx]
        val accs = groups.view.find { it.first == key }!!.second
        val keys = (aggregations.size + 1) j { i: Int -> if (i == 0) keyColumn else aggregations[i - 1].outputColumn }
        val cells = (aggregations.size + 1) j { i: Int -> if (i == 0) key else accs[i - 1].getResult() }
        resultRows.add(DocRowVec(keys, cells))
    }

    return resultRows.size j { i: Int -> resultRows[i] as RowVec }
}

/** Join alias. */
fun Cursor.join(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor = hashJoin(other, leftKey, rightKey)

/**
 * Compare two nullable key values for ordering.
 * Nulls sort before non-nulls; numbers are compared across types (Int/Long/Double etc).
 */
@Suppress("UNCHECKED_CAST")
fun compareKeys(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
    a is Comparable<*> -> (a as Comparable<Any?>).compareTo(b)
    else -> a.toString().compareTo(b.toString())
}

private fun rowName(row: RowVec, index: Int)  = when (row) {
    is DocRowVec -> row.keys[index]
    is ViewRowVec -> when (index) {
        0 -> "id"
        1 -> "key"
        2 -> "value"
        else -> "col$index"
    }
    is JsonRowVec -> when (index) {
        0 -> "nodeType"
        1 -> "rawValue"
        else -> "col$index"
    }
    is YamlRowVec -> when (index) {
        0 -> "nodeKind"
        1 -> "scalarValue"
        else -> "col$index"
    }
    is BlobRowVec -> when (index) {
        0 -> "bytes"
        1 -> "mimeType"
        else -> "col$index"
    }
    else -> "col$index"
}

private fun rowCell(row: RowVec, index: Int): Any? = when (row) {
    is DocRowVec -> row.cells[index]
    is ViewRowVec -> when (index) {
        0 -> row.id
        1 -> row.key
        2 -> row.value
        else -> null
    }
    is JsonRowVec -> when (index) {
        0 -> row.nodeType
        1 -> row.rawValue
        else -> null
    }
    is YamlRowVec -> when (index) {
        0 -> row.nodeKind
        1 -> row.scalarValue
        else -> null
    }
    is BlobRowVec -> when (index) {
        0 -> row.bytes
        1 -> row.mimeType
        else -> null
    }
    else -> row[index].a
}

private fun rowValue(row: RowVec, column: CharSequence): Any? = when (row) {
    is DocRowVec -> row.getValue(column)
    is ViewRowVec -> row.getValue(column)
    is JsonRowVec -> when (column) {
        "nodeType" -> row.nodeType
        "rawValue" -> row.rawValue
        else -> null
    }
    is YamlRowVec -> when (column) {
        "nodeKind" -> row.nodeKind
        "scalarValue" -> row.scalarValue
        else -> null
    }
    else -> row.getValue(column)
}

private fun copiedChild(row: RowVec): Series<RowVec>? = when (row) {
    is DocRowVec -> row.child
    is ViewRowVec -> row.child
    is JsonRowVec -> row.child
    is YamlRowVec -> row.child
    is BlobRowVec -> row.child
    else -> null
}

internal fun appendRowData(keys: SeriesBuffer<CharSequence>, cells: SeriesBuffer<Any?>, row: RowVec) {
    when (row) {
        is DocRowVec -> {
            for (i in 0 until row.size) {
                keys.add(row.keys[i])
                cells.add(row.cells[i])
            }
        }
        else -> {
            for (i in 0 until row.size) {
                keys.add(rowName(row, i))
                cells.add(rowCell(row, i))
            }
        }
    }
}

internal fun appendJoinedRowData(keys: SeriesBuffer<CharSequence>, cells: SeriesBuffer<Any?>, row: RowVec, joinKey: CharSequence) {
    when (row) {
        is DocRowVec -> {
            val existingKeys: SeriesBuffer<String> = SeriesBuffer()
            for (i in 0 until row.keys.size) {
                val key = row.keys[i]
                val keyStr = key.toString()
                if (key == joinKey || existingKeys.view.find { it == keyStr } != null) continue
                existingKeys.add(keyStr)
                keys.add(key)
                cells.add(row.cells[i])
            }
        }
    }
}
