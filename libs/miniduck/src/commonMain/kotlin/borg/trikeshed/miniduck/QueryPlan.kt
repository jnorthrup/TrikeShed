package borg.trikeshed.miniduck

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

private fun cursorRows(cursor: MiniCursor): List<borg.trikeshed.cursor.RowVec> =
    (0 until cursor.size).map { i -> cursor.b(i) }

fun emptyMiniCursor(): MiniCursor = 0 j { _: Int -> throw IndexOutOfBoundsException("empty MiniCursor") }

fun MiniCursor.at(index: Int): borg.trikeshed.cursor.RowVec = this.b(index)

fun MiniCursor.where(predicate: Predicate): MiniCursor {
    val rows = cursorRows(this).filter { predicate(it) }
    return listCursor(rows)
}

fun MiniCursor.take(n: Int): MiniCursor {
    require(n >= 0) { "take count must be non-negative" }
    val rows = cursorRows(this).take(n)
    return listCursor(rows)
}

fun MiniCursor.drop(n: Int): MiniCursor {
    require(n >= 0) { "drop count must be non-negative" }
    val rows = cursorRows(this).drop(n)
    return listCursor(rows)
}

fun compareKeys(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
    a is Comparable<*> && a::class == b::class -> (a as Comparable<Any?>).compareTo(b)
    else -> a.toString().compareTo(b.toString())
}

data class OrderSpec(val column: String, val desc: Boolean = false)

fun MiniCursor.orderBy(column: String, desc: Boolean = false): MiniCursor = orderBy(OrderSpec(column, desc))

fun MiniCursor.orderBy(vararg specs: OrderSpec): MiniCursor = orderBy(specs.toList())

fun MiniCursor.orderBy(specs: List<OrderSpec>): MiniCursor {
    val rows = cursorRows(this).sortedWith { left, right ->
        var cmp = 0
        for (spec in specs) {
            cmp = compareKeys(left.getValue(spec.column), right.getValue(spec.column))
            if (cmp != 0) return@sortedWith if (spec.desc) -cmp else cmp
        }
        cmp
    }
    return listCursor(rows)
}

fun MiniCursor.project(vararg columns: String): MiniCursor {
    val projected = cursorRows(this).map { row ->
        val child = (row as? MiniRowVec)?.child
        val values = columns.map { row.getValue(it) }
        docFromValues(columns.toList(), values, child)
    }
    return listCursor(projected)
}

fun MiniCursor.columns(vararg ordinals: Int): MiniCursor {
    val projected = cursorRows(this).map { row ->
        val keys = row.materializedKeys()
        val values = row.materializedValues()
        val child = (row as? MiniRowVec)?.child
        val selectedKeys = ordinals.map { keys.getOrElse(it) { "c$it" } }
        val selectedValues = ordinals.map { values.getOrNull(it) }
        docFromValues(selectedKeys, selectedValues, child)
    }
    return listCursor(projected)
}

fun interface Predicate {
    operator fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean
}

class ColumnPredicate(private val name: String) {
    fun eq(value: Any?): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = row.getValue(name) == value }
    infix fun gt(value: Any?): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = compareKeys(row.getValue(name), value) > 0 }
    infix fun lt(value: Any?): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = compareKeys(row.getValue(name), value) < 0 }
    infix fun between(bounds: Pair<Any?, Any?>): Predicate = object : Predicate {
        override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean {
            val v = row.getValue(name)
            return compareKeys(v, bounds.first) >= 0 && compareKeys(v, bounds.second) <= 0
        }
    }
    infix fun inList(values: List<Any?>): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = row.getValue(name) in values }
}

fun col(name: String): ColumnPredicate = ColumnPredicate(name)

infix fun Predicate.and(other: Predicate): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = this@and(row) && other(row) }
infix fun Predicate.or(other: Predicate): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = this@or(row) || other(row) }
operator fun Predicate.not(): Predicate = object : Predicate { override fun invoke(row: borg.trikeshed.cursor.RowVec): Boolean = !this@not(row) }

fun MiniCursor.hashJoin(right: MiniCursor, leftKey: String, rightKey: String): MiniCursor {
    val rightRows = cursorRows(right)
    val index = rightRows.groupBy { it.getValue(rightKey) }
    val out = mutableListOf<borg.trikeshed.cursor.RowVec>()
    for (leftRow in cursorRows(this)) {
        val matches = index[leftRow.getValue(leftKey)].orEmpty()
        for (rightRow in matches) {
            val lKeys = leftRow.materializedKeys()
            val lVals = leftRow.materializedValues()
            val rKeys = rightRow.materializedKeys()
            val rVals = rightRow.materializedValues()
            val addKeys = mutableListOf<String>()
            val addVals = mutableListOf<Any?>()
            for (i in rKeys.indices) {
                if (rKeys[i] !in lKeys) {
                    addKeys.add(rKeys[i])
                    addVals.add(rVals.getOrNull(i))
                }
            }
            out.add(docFromValues(lKeys + addKeys, lVals + addVals))
        }
    }
    return listCursor(out)
}

data class Agg(val column: String?, val outputName: String, val kind: Kind) {
    enum class Kind { COUNT, SUM, AVG, MIN, MAX }
    companion object {
        fun count(column: String = "*") = Agg(column, "count", Kind.COUNT)
        fun sum(column: String) = Agg(column, "sum_$column", Kind.SUM)
        fun avg(column: String) = Agg(column, "avg_$column", Kind.AVG)
        fun min(column: String) = Agg(column, "min_$column", Kind.MIN)
        fun max(column: String) = Agg(column, "max_$column", Kind.MAX)
    }
}

fun MiniCursor.groupBy(groupColumn: String, vararg aggs: Agg): MiniCursor {
    val groups = cursorRows(this).groupBy { it.getValue(groupColumn) }
    val out = groups.map { (key, rows) ->
        val keys = mutableListOf(groupColumn)
        val values = mutableListOf<Any?>(key)
        for (agg in aggs) {
            keys.add(agg.outputName)
            val nums = rows.mapNotNull { agg.column?.let { col -> it.getValue(col) as? Number }?.toDouble() }
            values.add(
                when (agg.kind) {
                    Agg.Kind.COUNT -> rows.size.toLong()
                    Agg.Kind.SUM -> nums.sum()
                    Agg.Kind.AVG -> if (nums.isEmpty()) null else nums.sum() / nums.size
                    Agg.Kind.MIN -> nums.minOrNull()
                    Agg.Kind.MAX -> nums.maxOrNull()
                },
            )
        }
        docFromValues(keys, values)
    }
    return listCursor(out)
}

data class RelationRef(val database: String, val name: String, val kind: RelationKind)
enum class RelationKind { DOCS, ALL_DOCS, VIEW, INDEX, SEGMENT }

sealed interface QueryPlan { val source: RelationRef }
data class ScanPlan(override val source: RelationRef) : QueryPlan
data class FilterPlan(val upstream: QueryPlan, val predicate: Predicate) : QueryPlan { override val source: RelationRef get() = upstream.source }
data class ProjectPlan(val upstream: QueryPlan, val columns: List<String>) : QueryPlan { override val source: RelationRef get() = upstream.source }
data class OrderPlan(val upstream: QueryPlan, val specs: List<OrderSpec>) : QueryPlan { override val source: RelationRef get() = upstream.source }
data class LimitPlan(val upstream: QueryPlan, val limit: Int, val offset: Int = 0) : QueryPlan { override val source: RelationRef get() = upstream.source }

infix fun QueryPlan.filter(pred: Predicate): QueryPlan = FilterPlan(this, pred)
infix fun QueryPlan.project(columns: List<String>): QueryPlan = ProjectPlan(this, columns)
infix fun QueryPlan.orderBy(specs: List<OrderSpec>): QueryPlan = OrderPlan(this, specs)
infix fun QueryPlan.limit(n: Int): QueryPlan = LimitPlan(this, n)
infix fun QueryPlan.offset(n: Int): QueryPlan = (this as? LimitPlan)?.copy(offset = n) ?: LimitPlan(this, Int.MAX_VALUE, n)

fun execute(plan: QueryPlan, base: MiniCursor): MiniCursor = when (plan) {
    is ScanPlan -> base
    is FilterPlan -> execute(plan.upstream, base).where(plan.predicate)
    is ProjectPlan -> execute(plan.upstream, base).project(*plan.columns.toTypedArray())
    is OrderPlan -> execute(plan.upstream, base).orderBy(plan.specs)
    is LimitPlan -> execute(plan.upstream, base).drop(plan.offset).take(plan.limit)
}
