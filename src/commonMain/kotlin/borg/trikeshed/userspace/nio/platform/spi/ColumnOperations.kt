package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.intp.Comparison
import kotlin.coroutines.CoroutineContext

/**
 * The column kernels the query executor runs, behind the platform seam: a native provider
 * (FFM on the JVM, cinterop on Kotlin/Native) runs the same loops compiled with the C
 * compiler's autovectorizer, which Kotlin does not match. Every provider returns exactly what
 * [KotlinColumnOperations] returns; the executor never names a platform.
 *
 * `op` is [Comparison.ordinal]. Selection vectors are ascending row ordinals.
 */
interface ColumnOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ColumnOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    /** Which implementation runs: `kotlin`, or the native library's identity. */
    val identity: String

    fun whereF64(values: DoubleArray, n: Int, op: Int, operand: Double, out: IntArray): Int
    fun refineF64(values: DoubleArray, sel: IntArray, m: Int, op: Int, operand: Double, out: IntArray): Int
    fun whereI64(values: LongArray, n: Int, op: Int, operand: Long, out: IntArray): Int
    fun refineI64(values: LongArray, sel: IntArray, m: Int, op: Int, operand: Long, out: IntArray): Int
    fun sumF64(values: DoubleArray, sel: IntArray, m: Int): Double
    fun sumI64(values: LongArray, sel: IntArray, m: Int): Long
    fun mulF64(a: DoubleArray, b: DoubleArray, sel: IntArray, m: Int, out: DoubleArray)
    fun affineF64(a: DoubleArray, scale: Double, shift: Double, sel: IntArray, m: Int, out: DoubleArray)
    fun groupIdsI64(keys: LongArray, sel: IntArray, m: Int, ids: IntArray, firstRow: IntArray, table: IntArray, tableKeys: LongArray): Int
    fun sumByGroupF64(values: DoubleArray, sel: IntArray, ids: IntArray, m: Int, sums: DoubleArray)
    fun sumByGroupI64(values: LongArray, sel: IntArray, ids: IntArray, m: Int, sums: LongArray)
    fun countByGroup(ids: IntArray, m: Int, counts: LongArray)
    fun cosineF32(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray)

    /** Releases native resources; a no-op for the Kotlin provider. */
    fun close() {}
}

/** The pure-Kotlin kernels: the default provider and the answer every native provider must match. */
object KotlinColumnOperations : ColumnOperations {
    override val identity: String = "kotlin"
    private val ops = Comparison.entries

    override fun whereF64(values: DoubleArray, n: Int, op: Int, operand: Double, out: IntArray) = borg.trikeshed.lib.doublep.where(values, n, ops[op], operand, out)
    override fun refineF64(values: DoubleArray, sel: IntArray, m: Int, op: Int, operand: Double, out: IntArray) = borg.trikeshed.lib.doublep.refine(values, sel, m, ops[op], operand, out)
    override fun whereI64(values: LongArray, n: Int, op: Int, operand: Long, out: IntArray) = borg.trikeshed.lib.longp.where(values, n, ops[op], operand, out)
    override fun refineI64(values: LongArray, sel: IntArray, m: Int, op: Int, operand: Long, out: IntArray) = borg.trikeshed.lib.longp.refine(values, sel, m, ops[op], operand, out)
    override fun sumF64(values: DoubleArray, sel: IntArray, m: Int) = borg.trikeshed.lib.doublep.sum(values, sel, m)
    override fun sumI64(values: LongArray, sel: IntArray, m: Int) = borg.trikeshed.lib.longp.sum(values, sel, m)
    override fun mulF64(a: DoubleArray, b: DoubleArray, sel: IntArray, m: Int, out: DoubleArray) = borg.trikeshed.lib.doublep.mul(a, b, sel, m, out)
    override fun affineF64(a: DoubleArray, scale: Double, shift: Double, sel: IntArray, m: Int, out: DoubleArray) = borg.trikeshed.lib.doublep.affine(a, scale, shift, sel, m, out)
    override fun groupIdsI64(keys: LongArray, sel: IntArray, m: Int, ids: IntArray, firstRow: IntArray, table: IntArray, tableKeys: LongArray) =
        borg.trikeshed.lib.longp.groupIds(keys, sel, m, ids, firstRow, table, tableKeys)
    override fun sumByGroupF64(values: DoubleArray, sel: IntArray, ids: IntArray, m: Int, sums: DoubleArray) = borg.trikeshed.lib.doublep.sumByGroup(values, sel, ids, m, sums)
    override fun sumByGroupI64(values: LongArray, sel: IntArray, ids: IntArray, m: Int, sums: LongArray) = borg.trikeshed.lib.longp.sumByGroup(values, sel, ids, m, sums)
    override fun countByGroup(ids: IntArray, m: Int, counts: LongArray) = borg.trikeshed.lib.longp.countByGroup(ids, m, counts)
    override fun cosineF32(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) =
        borg.trikeshed.lib.floatp.cosine(flat, n, dims, probe, out)
}
