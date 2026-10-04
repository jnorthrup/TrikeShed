package borg.trikeshed.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Growable primitive columns: a table kept as one primitive array per field, filled by appending and read by
 * index, never a row object per entry. [n] is the filled length; [a] is the backing array, whose filled prefix a
 * loop may read directly. Appends double the array, so a fill is amortized O(1) per element; [reset] empties a
 * column without releasing its storage.
 */
class IntColumn(capacity: Int = 64) {
    var a = IntArray(capacity)
    var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(maxOf(8, n shl 1)); a[n++] = v }
    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun reset() { n = 0 }
    /** The filled prefix, copied out. */
    fun toIntArray(): IntArray = a.copyOf(n)
    /** The filled prefix as a view; later appends past it do not show through. */
    val series: Series<Int> get() { val s = a; return n j { i: Int -> s[i] } }
}

/** A growable `Double` column; see [IntColumn]. */
class DoubleColumn(capacity: Int = 64) {
    var a = DoubleArray(capacity)
    var n = 0
    fun add(v: Double) { if (n == a.size) a = a.copyOf(maxOf(8, n shl 1)); a[n++] = v }
    operator fun get(i: Int): Double = a[i]
    operator fun set(i: Int, v: Double) { a[i] = v }
    fun reset() { n = 0 }
    fun toDoubleArray(): DoubleArray = a.copyOf(n)
    val series: Series<Double> get() { val s = a; return n j { i: Int -> s[i] } }
}

/** A growable `Long` column; see [IntColumn]. */
class LongColumn(capacity: Int = 64) {
    var a = LongArray(capacity)
    var n = 0
    fun add(v: Long) { if (n == a.size) a = a.copyOf(maxOf(8, n shl 1)); a[n++] = v }
    operator fun get(i: Int): Long = a[i]
    operator fun set(i: Int, v: Long) { a[i] = v }
    fun reset() { n = 0 }
    fun toLongArray(): LongArray = a.copyOf(n)
    val series: Series<Long> get() { val s = a; return n j { i: Int -> s[i] } }
}
