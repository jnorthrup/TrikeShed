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

/** Project named columns. */
fun Cursor.project(vararg columns: String): Cursor = this.get(*columns)

/** Project columns by index. */
fun Cursor.columns(vararg indices: Int): Cursor = this.get(*indices)

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

/** Explicit take/drop to help compiler inference. */
fun Cursor.take(n: Int): Cursor = (this as Series<RowVec>).take(n)
fun Cursor.drop(n: Int): Cursor = (this as Series<RowVec>).drop(n)

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
        val keys = mutableListOf(keyColumn)
        val cells = mutableListOf<Any?>(key)
        aggregations.forEachIndexed { index, agg ->
            keys.add(agg.outputColumn)
            cells.add(accs[index].getResult())
        }
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

    val resultRows = mutableListOf<RowVec>()
    for (i in 0 until this.size) {
        val leftRow = this.at(i)
        val key = leftRow.getValue(leftKey)
        val matches = rightIndex[key] ?: continue
        for (rightRow in matches) {
            val keys = leftRow.keys.toList().toMutableList()
            val cells = leftRow.cells.toList().toMutableList()

            val rightKeys = rightRow.keys.toList()
            val rightCells = rightRow.cells.toList()

            rightKeys.forEachIndexed { index, k ->
                if (k != rightKey) {
                    keys.add(k)
                    cells.add(rightCells[index])
                }
            }
            resultRows.add(DocRowVec(keys, cells).toRowVec())
        }
    }

    return resultRows.size j { resultRows[it] }
}

/** Join alias. */
fun Cursor.join(other: Cursor, leftKey: String, rightKey: String): Cursor = hashJoin(other, leftKey, rightKey)
