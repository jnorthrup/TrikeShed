package borg.trikeshed.miniduck

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
/** Equality predicate for Cursor.where. */
fun Eq(column: String, value: Any?): (RowVec) -> Boolean = { it.getValue(column) == value }

/** Greater-than-or-equal predicate for Cursor.where. */
fun Ge(column: String, value: Any?): (RowVec) -> Boolean = {
    compareCursorValues(it.getValue(column), value)?.let { result -> result >= 0 } ?: false
}

/** Greater-than predicate for Cursor.where. */
fun Gt(column: String, value: Any?): (RowVec) -> Boolean = {
    compareCursorValues(it.getValue(column), value)?.let { result -> result > 0 } ?: false
}

private fun compareCursorValues(left: Any?, right: Any?): Int? = when {
    left == null || right == null -> null
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is String && right is String -> left.compareTo(right)
    left is Boolean && right is Boolean -> left.compareTo(right)
    left is Comparable<*> && left::class == right::class -> compareComparableValues(left, right)
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun compareComparableValues(left: Comparable<*>, right: Any): Int =
    (left as Comparable<Any>).compareTo(right)

/** Filter rows by predicate. */
fun Cursor.where(predicate: (RowVec) -> Boolean): Cursor {
    // Two-pass: count matches, then compact into IntArray for O(1) random access.
    // This avoids List<RowVec> allocation (only stores int indices, not references + object headers).
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
fun Cursor.project(vararg columns: String): Cursor {
    val cursor = this
    return size j { y: Int ->
        val row = cursor.b(y)
        val keys = columns.toList()
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
fun Cursor.orderBy(column: String): Cursor = orderBy(OrderSpec(column))

/** Order by a single column with explicit direction. */
fun Cursor.orderBy(column: String, desc: Boolean): Cursor = orderBy(OrderSpec(column, desc = desc))

/** Chain transforms. */
infix fun <T> Cursor.then(transform: (Cursor) -> T): T = transform(this)

/** Create an empty cursor. */
fun emptyCursor(): Cursor = emptySeries()

/** Minus operator to exclude a column. */
operator fun Cursor.minus(columnName: String): Cursor {
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
    val targetColumn: String
    val outputColumn: String
    fun createAccumulator(): Accumulator
}

interface Accumulator {
    fun add(value: Any?)
    fun getResult(): Any?
}

object Agg {
    fun count(column: String = "*"): Aggregation = object : Aggregation {
        override val targetColumn: String = column
        override val outputColumn: String = "count"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var count = 0L
            override fun add(value: Any?) { count++ }
            override fun getResult(): Any? = count
        }
    }

    fun sum(column: String): Aggregation = object : Aggregation {
        override val targetColumn: String = column
        override val outputColumn: String = "sum_$column"
        override fun createAccumulator(): Accumulator = object : Accumulator {
            var sum = 0.0
            override fun add(value: Any?) {
                if (value is Number) sum += value.toDouble()
            }
            override fun getResult(): Any? = sum
        }
    }

    fun avg(column: String): Aggregation = object : Aggregation {
        override val targetColumn: String = column
        override val outputColumn: String = "avg_$column"
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

    fun min(column: String): Aggregation = object : Aggregation {
        override val targetColumn: String = column
        override val outputColumn: String = "min_$column"
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

    fun max(column: String): Aggregation = object : Aggregation {
        override val targetColumn: String = column
        override val outputColumn: String = "max_$column"
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
fun Cursor.groupBy(keyColumn: String, vararg aggregations: Aggregation): Cursor {
    if (size == 0) return this

    val groups = mutableMapOf<Any?, MutableList<Accumulator>>()
    val groupOrder = mutableListOf<Any?>()

    for (i in 0 until size) {
        val row = at(i)
        val key = row.getValue(keyColumn)
        if (key !in groups) groupOrder.add(key)
        val accumulators = groups.getOrPut(key) { aggregations.map { it.createAccumulator() }.toMutableList() }
        aggregations.forEachIndexed { index, agg ->
            val value = if (agg.targetColumn == "*") null else row.getValue(agg.targetColumn)
            accumulators[index].add(value)
        }
    }

    val resultRows = groupOrder.map { key ->
        val accs = groups[key]!!
        val keys = (aggregations.size + 1) j { i: Int -> if (i == 0) keyColumn else aggregations[i - 1].outputColumn }
        val cells = (aggregations.size + 1) j { i: Int -> if (i == 0) key else accs[i - 1].getResult() }
        DocRowVec(keys, cells)
    }

    return resultRows.size j { resultRows[it].toRowVec() }
}

/** Hash join with another cursor. */
fun Cursor.hashJoin(other: Cursor, leftKey: String, rightKey: String): Cursor {
    if (this.size == 0 || other.size == 0) return emptyCursor()

    val rightIndex = mutableMapOf<Any?, MutableList<RowVec>>()
    for (i in 0 until other.size) {
        val row = other.at(i)
        val key = row.getValue(rightKey)
        if (key != null) {
            rightIndex.getOrPut(key) { mutableListOf() }.add(row)
        }
    }

    // Pre-allocate result list to avoid resizing
    val resultRows = ArrayList<RowVec>(this.size * 2)
    // HashSet for O(1) join-key dedup across left columns
    for (i in 0 until this.size) {
        val leftRow = this.at(i)
        val key = leftRow.getValue(leftKey)
        val matches = rightIndex[key] ?: continue
        for (rightRow in matches) {
            val keys = mutableListOf<String>()
            val cells = mutableListOf<Any?>()
            appendRowData(keys, cells, leftRow)
            appendJoinedRowData(keys, cells, rightRow, rightKey)
            resultRows.add(DocRowVec(keys, cells))
        }
    }

    return resultRows.size j { resultRows[it] }
}

/** Join alias. */
fun Cursor.join(other: Cursor, leftKey: String, rightKey: String): Cursor = hashJoin(other, leftKey, rightKey)

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

private fun rowName(row: RowVec, index: Int): String = when (row) {
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

private fun rowValue(row: RowVec, column: String): Any? = when (row) {
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

private fun appendRowData(keys: MutableList<String>, cells: MutableList<Any?>, row: RowVec) {
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

private fun appendJoinedRowData(keys: MutableList<String>, cells: MutableList<Any?>, row: RowVec, joinKey: String) {
    when (row) {
        is DocRowVec -> {
            // Build a HashSet of already-emitted key names for O(1) dedup
            val existingKeys = keys.toHashSet()
            for (i in 0 until row.keys.size) {
                val key = row.keys[i]
                if (key == joinKey || key in existingKeys) continue
                existingKeys.add(key)
                keys.add(key)
                cells.add(row.cells[i])
            }
        }
        else -> appendRowData(keys, cells, row)
    }
}
