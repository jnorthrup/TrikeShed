package borg.trikeshed.lib

// import the IoMemento enum
import borg.trikeshed.isam.ColMeta
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento.*
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass

/**
 * Cursors are a columnar abstraction composed of Series of Joined value+meta pairs (RecordMeta)
 *
 */
typealias Cursor = Series<Series<Join<*, () -> RecordMeta>>>

///**
// * overload unary minus operator for Cursor to strip out the meta and return a series of values-only
// *
// * apparently this duplicates the unaryMinus() function above it, but it's not clear how to get the compiler to use that one
// */
//operator fun Cursor.unaryMinus(): Series<Series<*>> = this α { it α Join<*, () -> RecordMeta>::a }

/**
 * Operator Cursor '/' Class<A>
 *
 * returns Series<Series<A?>>> where the meta is stripped out and the values are cast using
 *
 * it "as?" A return only A values and null for non-A values
 */
  inline operator fun <  A:Any,IR:Any?,SrInnr:Series<Join<  A,*>>,SrOutr:Series< SrInnr >,RC:KClass<A?>> SrOutr.div(c: KClass<out A>): Series<Series<A?>> =         this α { it α Join<A, *>::a } α { it α { it as? A } } α { it α { it as? A } }

/**
 *
 */
infix fun Cursor.row(y: Int): Series<Join<*, () -> RecordMeta>> {
    require(y < size) { "index $y out of bounds for cursor of size $size" }
    return b(y)
}

/**
 * Cursor get by Int vararg -- return a Cursor with the columns specified by the vararg
 */
operator fun Cursor.get(vararg i: Int): Cursor =
    size j { y ->
        i.size j { x ->
            row(y)[i[x]]
        }
    }

/**
 * cursor get by IntRange -- return a Cursor with the columns specified by the IntRange
 */
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
val Cursor.meta: Series<out ColMeta>
    get() = row(0) α {
        it.b()
    }

/**
create an Intarray of cursor meta by Strings of column names
 */
fun Cursor.meta(vararg s: String): Series<Int> {
    val meta = meta
    return s.size j { i ->
        meta.`▶`.indexOfFirst { it -> it.name == s[i] }
    }
}

/**
 * cursor get by String vararg -- return a Cursor with the columns specified by the vararg
 */
fun Cursor.get(vararg s: String): Cursor = this[meta(*s)]

/**
 * ColumnExclusion value class
 *
 * used to exclude columns from a cursor by name
 *
 * @param s the name of the column to exclude
 *
 */
@JvmInline
value class ColumnExclusion(public val name: String) {
    override fun toString(): String = "ColumnExclusion($name)"
}

/**
 * create operator unary minus for ColumnExclusion on string
 */
operator fun String.unaryMinus(): ColumnExclusion = ColumnExclusion(this)

/**
 * Return cursor with columns excluded by indexes
 */
operator fun Cursor.minus(killbag: Series<Int>) {
    val toSet = (0 until meta.size).toSet()
    val ints = (toSet - killbag.toSet()).toIntArray()
    this[ints]
}

/**
 * cursor get by ColumnExclusion vararg -- return a Cursor with the columns excluded by the vararg
 */
fun Cursor.get(s: Series<ColumnExclusion>): Cursor {

    val exclusionBag = mutableSetOf<Int>()
    s.`▶`.forEachIndexed { i: Int, it: ColumnExclusion ->
        exclusionBag.add(meta.`▶`.indexOfFirst { it.name == it.name })
    }
    val retained = ((0 until meta.size).toSet() - exclusionBag).toIntArray()
    return this[retained]
}


