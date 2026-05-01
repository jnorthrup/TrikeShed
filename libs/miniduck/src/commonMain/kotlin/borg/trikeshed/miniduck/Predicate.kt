package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.size

fun RowVec.getValue(key: String): Any? {
    for (index in 0 until size) {
        val cell = b(index)
        if (cell.b().a == key) return cell.a
    }
    return null
}

/**
 * Extract a named scalar from any MiniRowVec subtype without Map allocation.
 *
 * Vocabulary per subtype:
 *   DocRowVec   — any declared key name
 *   ViewRowVec  — "id"/"_id", "key", "value"
 *   JsonRowVec  — "nodeType", "rawValue"
 *   YamlRowVec  — "nodeKind", "scalarValue"
 *   BlockRowVec — null (shell, no scalars)
 *   BlobRowVec  — null (shell, no scalars)
 *   ObjectStoreRowVec — null (shell, no scalars)
 */
fun borg.trikeshed.miniduck.RowVec.getValue(key: String): Any? = when (this) {
    is DocRowVec -> this[key]
    is ViewRowVec -> when (key) {
        "_id", "id" -> id
        "key" -> this.key
        "value" -> value
        else -> null
    }
    is JsonRowVec -> when (key) {
        "nodeType" -> nodeType
        "rawValue" -> rawValue
        else -> null
    }
    is YamlRowVec -> when (key) {
        "nodeKind" -> nodeKind
        "scalarValue" -> scalarValue
        else -> null
    }
    is BlockRowVec, is BlobRowVec, is ManifoldConcept, is ObjectStoreRowVec, is LazyChildRowVec, is WrappedRowVec, is CsvRowVec -> null
}

/**
 * Cross-type comparison policy:
 *   - null < any value (nulls sort before non-nulls)
 *   - numbers compared via toDouble()
 *   - all other types compared via toString()
 */
fun compareKeys(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Number && b is Number -> run {
        val aIntegral = a is Long || a is Int || a is Short || a is Byte
        val bIntegral = b is Long || b is Int || b is Short || b is Byte
        if (aIntegral && bIntegral) {
            a.toLong().compareTo(b.toLong())
        } else {
            a.toDouble().compareTo(b.toDouble())
        }
    }
    else -> a.toString().compareTo(b.toString())
}

/** Sealed predicate tree — each node is an immutable value, evaluated via [matches]. */
sealed interface Predicate {
    fun matches(row: RowVec): Boolean
    fun matches(row: borg.trikeshed.miniduck.RowVec): Boolean = matches(row.toRowVec())
    operator fun invoke(row: RowVec): Boolean = matches(row)
}

data class Eq(val column: String, val value: Any?) : Predicate {
    override fun matches(row: RowVec) = row.getValue(column) == value
}

data class Gt(val column: String, val value: Comparable<*>) : Predicate {
    override fun matches(row: RowVec) = compareKeys(row.getValue(column), value) > 0
}

data class Lt(val column: String, val value: Comparable<*>) : Predicate {
    override fun matches(row: RowVec) = compareKeys(row.getValue(column), value) < 0
}

data class Ge(val column: String, val value: Comparable<*>) : Predicate {
    override fun matches(row: RowVec) = compareKeys(row.getValue(column), value) >= 0
}

data class Le(val column: String, val value: Comparable<*>) : Predicate {
    override fun matches(row: RowVec) = compareKeys(row.getValue(column), value) <= 0
}

data class Between(
    val column: String,
    val lower: Comparable<*>,
    val upper: Comparable<*>,
) : Predicate {
    override fun matches(row: RowVec): Boolean {
        val v = row.getValue(column) ?: return false
        return compareKeys(v, lower) >= 0 && compareKeys(v, upper) <= 0
    }
}

data class InList(val column: String, val values: List<Any?>) : Predicate {
    override fun matches(row: RowVec) = row.getValue(column) in values
}

data class And(val left: Predicate, val right: Predicate) : Predicate {
    override fun matches(row: RowVec) = left.matches(row) && right.matches(row)
}

data class Or(val left: Predicate, val right: Predicate) : Predicate {
    override fun matches(row: RowVec) = left.matches(row) || right.matches(row)
}

data class Not(val inner: Predicate) : Predicate {
    override fun matches(row: RowVec) = !inner.matches(row)
}

infix fun Predicate.and(other: Predicate): Predicate = And(this, other)
infix fun Predicate.or(other: Predicate): Predicate = Or(this, other)
operator fun Predicate.not(): Predicate = Not(this)

/** Column reference — DSL entry point: `col("age") gt 30`, `col("name") eq "vw"`. */
class ColumnRef(val name: String) {
    infix fun eq(value: Any?): Predicate = Eq(name, value)
    infix fun ne(value: Any?): Predicate = Not(Eq(name, value))
    infix fun gt(value: Comparable<*>): Predicate = Gt(name, value)
    infix fun lt(value: Comparable<*>): Predicate = Lt(name, value)
    infix fun ge(value: Comparable<*>): Predicate = Ge(name, value)
    infix fun le(value: Comparable<*>): Predicate = Le(name, value)
    infix fun between(range: Pair<Comparable<*>, Comparable<*>>): Predicate =
        Between(name, range.first, range.second)
    infix fun inList(values: List<Any?>): Predicate = InList(name, values)
}

fun col(name: String) = ColumnRef(name)
