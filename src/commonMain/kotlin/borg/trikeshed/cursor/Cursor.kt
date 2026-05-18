@file:Suppress("UNCHECKED_CAST", "FunctionName", "NonAsciiCharacters")

package borg.trikeshed.cursor

// import the IoMemento enum
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.*
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmOverloads
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.reflect.KClass

typealias RowVec = Series2<Any?, `ColumnMeta↻`>
//val RowVec.left get() =  this α Join<*, () -> RecordMeta>::a

/** Cursors are a columnar abstraction composed of Series of Joined value+meta pairs (RecordMeta) */
typealias Cursor = Series<RowVec>

/** Pair row values with column metadata providers into a RowVec. */
infix fun Series<Any?>.j(meta: Series<`ColumnMeta↻`>): RowVec =
    this.zip(meta).debug { if (a != meta.a) logDebug("RowVec size mismatch detected".leftIdentity) }

/**
 * Joins a Series of scalar values with a Series of column metadata providers into a RowVec.
 * This is a convenience alias used across the codebase where the name 'joins' is preferred.
 */
infix fun Series<Any?>.joins(meta: Series<`ColumnMeta↻`>): RowVec = this j meta

/** Cursors combine: combine a series of RowVec into a Cursor. */
val Series<RowVec>.cursor: Cursor get() = this

/** Explicit helper to combine two cursors (row concatenation). */
fun combine(a: Cursor, b: Cursor): Cursor = borg.trikeshed.lib.combine(a, b)

/** Project the scalar values from a RowVec, discarding column metadata.
 *  Short-circuits to leftSeries for ReifiedSplitSeries2 — zero Join allocation. */
val RowVec.values: Series<Any?>
    get() = (this as? ReifiedSplitSeries2<*, *>)?.leftSeries as? Series<Any?>
        ?: this.α { it.a }

/** α-conversion over a Cursor — apply a transform to each RowVec. */
inline infix fun <C> Cursor.α(crossinline xform: (RowVec) -> C): Series<C> = size j { xform(row(it)) }

 /**
  * overload unary minus operator for Cursor to strip out the meta and return a series of values-only
  */
operator fun Cursor.unaryMinus(): Series<Series<Any?>> =this α { it: RowVec -> it.values }

/** Operator Cursor '/' Class<A>
 *
 * returns Series<Series<A?>>> where the meta is stripped out and the values are cast using
 *
 * it "as?" A return only A values and null for non-A values */
operator fun <A : Any, SrInnr : Series<Join<A, *>>, SrOutr : Series<SrInnr>> SrOutr.div(
    c: KClass<A>,
): Series<Series<A?>> =
    this α { it: SrInnr -> it α Join<A, *>::a } α { it: Series<A> -> it α { it: A -> it } } α { it: Series<A> -> it α { it: A -> it } }


/** cursor get by IntRange -- return a Cursor with the columns specified by the IntRange */
operator fun Cursor.get(i: IntRange): Cursor {
    require(i.first >= 0) { "index ${i.first} out of bounds for cursor of size $size" }
    require(i.last < size) { "index ${i.last} out of bounds for cursor of size $size" }
    return size j { y ->
        // get the size of range
        val rangeSize = i.last - i.first + 1
        rangeSize j { x ->
            row(y)[i.first + x]
        }
    }
}

/** get meta for a cursor from row 0 */
val Cursor.meta: Series<ColumnMeta>
    get() {
        val first = row(0)
        return first.size j { index ->
            val cell = first[index]
            when (val raw = cell.b as Any?) {
                is RecordMeta -> raw as ColumnMeta
                is Function0<*> -> raw.invoke() as ColumnMeta
                is Join<*, *> -> raw as ColumnMeta
                else -> error("Unsupported column meta for row $index")
            }
        }
    }

/** create an Intarray of cursor meta by Strings of column names */
fun Cursor.meta(vararg s: String): Series<Int> {
    val meta: Series<ColumnMeta> = meta
    return s.size j { i ->
        meta.view.indexOfFirst { columnMeta: ColumnMeta -> columnMeta.name == s[i] }
    }
}

/** cursor get by String vararg — select columns by name, bypassing Series<Int> row-selection. */
operator fun Cursor.get(vararg s: String): Cursor {
    val indices = meta(*s)
    return selectColumns(IntArray(indices.size) { indices[it] })
}

/** ColumnExclusion value class
 *
 * used to exclude columns from a cursor by name
 *
 * @param name the name of the column to exclude */
@JvmInline
value class ColumnExclusion(val name: String) {
    override fun toString(): String = "ColumnExclusion($name)"
}

/** create operator unary minus for ColumnExclusion on string */
operator fun String.unaryMinus(): ColumnExclusion = ColumnExclusion(this)

/** cursor get by ColumnExclusion vararg -- return a Cursor with the columns excluded by the vararg */
operator fun Cursor.get(s: Series<ColumnExclusion>): Cursor {

    val exclusionBag = mutableSetOf<Int>()
    s.view.forEach { exclusion ->
        exclusionBag.add(meta.view.indexOfFirst { it.name == exclusion.name })
    }
    val retained: IntArray = ((0 until meta.size).toSet() - exclusionBag).toIntArray()
    return this.get(*retained)
}

//in columnar project this is meta.right
val Series<ColumnMeta>.names: Series<String> get() = this α ColumnMeta::name

/** head default 5 rows
 * just like unix head - print default 5 lines from cursor contents to stdout */
@JvmOverloads
fun Cursor.head(last: Int = 5): Unit = show(0 until (max(0, min(last, size))))

/** run head starting at random index */
fun Cursor.showRandom(n: Int = 5) {
    head(0); repeat(n) {
        if (size > 0) showValues(Random.nextInt(0, size).let { it..it })
    }
}

/** simple printout macro*/
fun Cursor.show(range: IntRange = 0 until size) {
    val meta: Series<ColumnMeta> = meta
    println("rows:$size" to meta.names.toList())
    showValues(range)
}

fun Cursor.showValues(range: IntRange) = try {
    range.forEach { x: Int ->
        val row: RowVec = row(x)

        val show: Series<Any?> = row α { (c: Any?, d: `ColumnMeta↻`) ->
            val meta: ColumnMeta = d()
            meta.name to c
            when (meta.type) {
                IoCharSeries -> meta.name to (c as Series<Char>).asString()
                else -> c
            }
        }
        println(show.toList())
    }
} catch (e: NoSuchElementException) {
    println("cannot fully access range $range")
}


/** gets the RowVec at y or if y is negative then -y from last */
infix fun Cursor.at(y: Int): RowVec = b(if (y < 0) size + y else y)
infix fun Cursor.row(y: Int): RowVec = at(y)

/** Cursor get by Int vararg — select columns by index, zero per-cell Join allocation. */
operator fun Cursor.get(vararg i: Int): Cursor = selectColumns(i)

private fun Cursor.selectColumns(indices: IntArray): Cursor {
    val selectedMeta = meta
    val selectedNames = indices.size j { i: Int -> selectedMeta[indices[i]].name }
    return size j { y: Int ->
        val row = row(y)
        val selectedValues = indices.size j { x: Int -> row[indices[x]].a }
        cellsToRowVec(selectedValues, selectedNames)
    }
}


/** IsNumerical
 * iterate the meta enum types and check if all are numerical
 *
 * IoByte,IoShort,IoInt,IoDouble,IoLong   qualify as numerical
 *
 * kotlin enumset is not available in JS
 *
 */
val Cursor.isNumerical: Boolean
    get() = meta.view.all { (_: String, b: TypeMemento): ColumnMeta ->
        when (b) {
            IoByte, IoShort, IoInt, IoFloat, IoDouble, IoLong -> true
            else -> false
        }
    }

val Cursor.isHomoMorphic: Boolean get() = !meta.view.any { it.type != meta[0].type }

/** Filter rows by predicate. Preserves row count semantics. */
fun Cursor.where(predicate: (RowVec) -> Boolean): Cursor {
    if (size == 0) return this
    val cursor = this
    val filtered = (0 until size).filter { predicate(cursor.row(it)) }
    return if (filtered.isEmpty()) emptySeries()
    else Series(filtered.size) { cursor.row(filtered[it]) }
}

/** Order by a single column ascending (default) or descending. */
fun Cursor.orderBy(column: String, desc: Boolean = false): Cursor {
    if (size == 0) return this
    val cursor = this
    val colIdx = meta.view.indexOfFirst { it.name == column }.takeIf { it >= 0 }
        ?: return this
    val sorted = (0 until size).sortedWith { a, b ->
        val va = (cursor.row(a) as ReifiedSplitSeries2<*, *>).valueAt(colIdx)
        val vb = (cursor.row(b) as ReifiedSplitSeries2<*, *>).valueAt(colIdx)
        val cmp = compareCursorValues(va, vb)
        if (desc) -cmp else cmp
    }
    return Series(sorted.size) { cursor.row(sorted[it]) }
}

/** Take first n rows. */
fun Cursor.take(n: Int): Cursor {
    require(n >= 0) { "take count must be non-negative" }
    if (n == 0) return emptySeries()
    val cursor = this
    val end = minOf(n, size)
    return Series(end) { cursor.row(it) }
}

/** Drop first n rows. */
fun Cursor.drop(n: Int): Cursor {
    require(n >= 0) { "drop count must be non-negative" }
    if (n == 0) return this
    val cursor = this
    val start = minOf(n, size)
    val remaining = size - start
    if (remaining <= 0) return emptySeries()
    return Series(remaining) { cursor.row(start + it) }
}

/** Project named columns — returns new cursor with only those columns. */
fun Cursor.project(vararg columns: String): Cursor {
    if (size == 0) return this
    val cursor = this
    val indices = columns.mapNotNull { col ->
        cursor.meta.view.indexOfFirst { it.name == col }.takeIf { it >= 0 }
    }
    if (indices.isEmpty()) {
        return Series(size) { cellsToRowVec(emptySeries(), emptySeries()) }
    }
    return Series(size) { y ->
        val row = cursor.row(y)
        val selectedValues = indices.map { (row as ReifiedSplitSeries2<*, *>).valueAt(it) }
        val selectedNames = indices.map { cursor.meta[it].name }
        cellsToRowVec(selectedValues.toSeries(), selectedNames.toSeries())
    }
}

// Compare two nullable values for ordering: nulls first, then numeric, then comparable, then string
@Suppress("UNCHECKED_CAST")
internal fun compareCursorValues(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Number && b is Number -> a.toDouble().compareTo(b.toDouble()).toInt()
    a is Comparable<*> -> (a as Comparable<Any?>).compareTo(b)
    else -> a.toString().compareTo(b.toString())
}
