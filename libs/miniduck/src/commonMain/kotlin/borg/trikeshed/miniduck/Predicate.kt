package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.getValue

typealias Predicate = (RowVec) -> Boolean

data class ColumnRef(val name: String)

fun col(name: String): ColumnRef = ColumnRef(name)

infix fun ColumnRef.eq(value: Any?): Predicate = { row -> row.getValue(name) == value }

infix fun ColumnRef.gt(value: Any?): Predicate = { row -> compareRowValue(row.getValue(name), value) > 0 }

infix fun ColumnRef.lt(value: Any?): Predicate = { row -> compareRowValue(row.getValue(name), value) < 0 }

infix fun ColumnRef.ge(value: Any?): Predicate = { row -> compareRowValue(row.getValue(name), value) >= 0 }

infix fun ColumnRef.le(value: Any?): Predicate = { row -> compareRowValue(row.getValue(name), value) <= 0 }

infix fun ColumnRef.inList(values: Iterable<Any?>): Predicate = { row -> row.getValue(name) in values }

infix fun ColumnRef.between(bounds: Pair<Any?, Any?>): Predicate = { row ->
    val value = row.getValue(name)
    compareRowValue(value, bounds.first) >= 0 && compareRowValue(value, bounds.second) <= 0
}

infix fun Predicate.and(other: Predicate): Predicate = { row -> this(row) && other(row) }

infix fun Predicate.or(other: Predicate): Predicate = { row -> this(row) || other(row) }

operator fun Predicate.not(): Predicate = { row -> !this(row) }

@Suppress("UNCHECKED_CAST")
private fun compareRowValue(left: Any?, right: Any?): Int = when {
    left == null && right == null -> 0
    left == null -> -1
    right == null -> 1
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is Comparable<*> -> (left as Comparable<Any?>).compareTo(right)
    else -> left.toString().compareTo(right.toString())
}
