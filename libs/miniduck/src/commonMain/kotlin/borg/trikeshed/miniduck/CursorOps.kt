package borg.trikeshed.miniduck

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.comparisons.compareValues

/** Equality predicate for Cursor.where. */
fun Eq(column: String, value: Any?): (RowVec) -> Boolean = { it.getValue(column) == value }

/** Greater-than-or-equal predicate for Cursor.where. */
fun Ge(column: String, value: Any?): (RowVec) -> Boolean = {
    val v = it.getValue(column) as? Comparable<Any?>
    val target = value as? Comparable<Any?>
    if (v != null && target != null) v >= target else false
}

/** Greater-than predicate for Cursor.where. */
fun Gt(column: String, value: Any?): (RowVec) -> Boolean = {
    val v = it.getValue(column) as? Comparable<Any?>
    val target = value as? Comparable<Any?>
    if (v != null && target != null) v > target else false
}

/** Filter rows by predicate. */
fun Cursor.where(predicate: (RowVec) -> Boolean): Cursor {
    val filtered = this.view.filter(predicate)
    return filtered.size j { filtered[it] }
}

/** Project named columns — family-aware so DocRowVec/ViewRowVec rows keep their child. */
fun Cursor.project(vararg columns: String): Cursor {
    val cursor = this
    return size j { y: Int ->
        val row = cursor.b(y)
        when (row) {
            is JsonRowVec -> JsonRowVec(
                nodeType = row.nodeType,
                rawValue = row.rawValue,
                childFactory = row.child?.let { ch -> { ch } },
            )
            is ViewRowVec -> ViewRowVec(
                id = if (columns.contains("id")) row.id else null,
                key = if (columns.contains("key")) row.key else null,
                value = if (columns.contains("value")) row.value else null,
            )
            else -> {
                val vals = columns.joinToString(",") { col -> "${row.getValue(col)}" }
                JsonRowVec("project", vals)
            }
        }
    }
}

/** Project columns by index — family-aware so BlobRowVec rows keep their child. */
fun Cursor.columns(vararg indices: Int): Cursor {
    val cursor = this
    return size j { y: Int ->
        val row = cursor.b(y)
        when (row) {
            is JsonRowVec -> JsonRowVec(
                nodeType = row.nodeType,
                rawValue = row.rawValue,
                childFactory = row.child?.let { ch -> { ch } },
            )
            is BlobRowVec -> BlobRowVec(
                bytes = row.bytes,
                mimeType = row.mimeType,
                childFactory = row.child?.let { ch -> { _ -> ch } },
            )
            else -> indices.size j { x -> row[indices[x]] }
        }
    }
}

/** Order by specs. */
fun Cursor.orderBy(vararg specs: OrderSpec): Cursor {
    if (size == 0) return this
    val sorted = this.view.sortedWith { r1, r2 ->
        for (spec in specs) {
            val v1 = r1.getValue(spec.column) as? Comparable<Any?>
            val v2 = r2.getValue(spec.column) as? Comparable<Any?>
            val res = compareValues(v1, v2)
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

    val groups = mutableMapOf<Any?, List<Accumulator>>()

    for (i in 0 until size) {
        val row = at(i)
        val key = row.getValue(keyColumn)
        val accumulators = groups.getOrPut(key) { aggregations.map { it.createAccumulator() } }
        aggregations.forEachIndexed { index, agg ->
            val value = if (agg.targetColumn == "*") null else row.getValue(agg.targetColumn)
            accumulators[index].add(value)
        }
    }

    val groupKeys = groups.keys.toList()
    val resultRows = groupKeys.map { key ->
        val accs = groups[key]!!
        val cells = mutableListOf<Any?>(key)
        aggregations.forEachIndexed { index, agg ->
            cells.add(accs[index].getResult())
        }
        // Store group-by result as JsonRowVec with nodeType="group"
        JsonRowVec(nodeType = "group", rawValue = cells.joinToString(","))
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

    val resultRows = mutableListOf<RowVec>()
    for (i in 0 until this.size) {
        val leftRow = this.at(i)
        val key = leftRow.getValue(leftKey)
        val matches = rightIndex[key] ?: continue
        for (rightRow in matches) {
            // Merge join result into a JsonRowVec
            val leftType = (leftRow as? JsonRowVec)?.nodeType ?: "row"
            val rightType = (rightRow as? JsonRowVec)?.nodeType ?: "row"
            val merged = "left=$leftType,right=$rightType"
            resultRows.add(JsonRowVec(nodeType = "join", rawValue = merged))
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
