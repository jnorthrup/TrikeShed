package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.tilting.lz4.loadLibrary
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * The column kernels of `trikeshed_kernels.c` through FFM critical downcalls: each array is passed in
 * place as a heap [MemorySegment], nothing is copied. Library: `-Dtrikeshed.libkernels=<path>`, else
 * `libtrikeshed_kernels.so` on the loader path (`buildJvmKernels` writes it to `build/native/jvm`).
 * Answers are bit-identical to [KotlinColumnOperations]: the C is built with `-ffp-contract=off` and
 * keeps the Kotlin reduction order.
 *
 * Handles are unbound (the function address is the first argument), so native-image can initialize
 * [ColumnDowncall] at build time, as `Lz4Downcall` does. A host without the library has no provider;
 * a library missing a symbol is a visible failure.
 */
class JvmColumnOperations private constructor(private val lookup: SymbolLookup) : ColumnOperations {
    override val identity: String = "ffm:libtrikeshed_kernels"

    private fun find(name: String): MemorySegment = lookup.find(name).orElseThrow { UnsatisfiedLinkError(name) }
    private val whereF64 = find("ts_where_f64")
    private val refineF64 = find("ts_refine_f64")
    private val whereI64 = find("ts_where_i64")
    private val refineI64 = find("ts_refine_i64")
    private val sumF64 = find("ts_sum_f64_sel")
    private val sumI64 = find("ts_sum_i64_sel")
    private val mulF64 = find("ts_mul_f64")
    private val affineF64 = find("ts_affine_f64")
    private val groupIds = find("ts_group_ids_i64")
    private val sumByGroupF64 = find("ts_sum_by_group_f64")
    private val sumByGroupI64 = find("ts_sum_by_group_i64")
    private val countByGroup = find("ts_count_by_group")
    private val cosineF32 = find("ts_cosine_f32")
    private val dotF32 = find("ts_dot_f32")
    private val l2SquaredF32 = find("ts_l2sq_f32")

    private fun seg(a: IntArray) = MemorySegment.ofArray(a)
    private fun seg(a: LongArray) = MemorySegment.ofArray(a)
    private fun seg(a: DoubleArray) = MemorySegment.ofArray(a)
    private fun seg(a: FloatArray) = MemorySegment.ofArray(a)

    override fun whereF64(values: DoubleArray, n: Int, op: Int, operand: Double, out: IntArray): Int =
        ColumnDowncall.WHERE_F64.invokeExact(whereF64, seg(values), n, op, operand, seg(out)) as Int

    override fun refineF64(values: DoubleArray, sel: IntArray, m: Int, op: Int, operand: Double, out: IntArray): Int =
        ColumnDowncall.REFINE_F64.invokeExact(refineF64, seg(values), seg(sel), m, op, operand, seg(out)) as Int

    override fun whereI64(values: LongArray, n: Int, op: Int, operand: Long, out: IntArray): Int =
        ColumnDowncall.WHERE_I64.invokeExact(whereI64, seg(values), n, op, operand, seg(out)) as Int

    override fun refineI64(values: LongArray, sel: IntArray, m: Int, op: Int, operand: Long, out: IntArray): Int =
        ColumnDowncall.REFINE_I64.invokeExact(refineI64, seg(values), seg(sel), m, op, operand, seg(out)) as Int

    override fun sumF64(values: DoubleArray, sel: IntArray, m: Int): Double =
        ColumnDowncall.SUM_F64.invokeExact(sumF64, seg(values), seg(sel), m) as Double

    override fun sumI64(values: LongArray, sel: IntArray, m: Int): Long =
        ColumnDowncall.SUM_I64.invokeExact(sumI64, seg(values), seg(sel), m) as Long

    override fun mulF64(a: DoubleArray, b: DoubleArray, sel: IntArray, m: Int, out: DoubleArray) {
        ColumnDowncall.MUL_F64.invokeExact(mulF64, seg(a), seg(b), seg(sel), m, seg(out))
    }

    override fun affineF64(a: DoubleArray, scale: Double, shift: Double, sel: IntArray, m: Int, out: DoubleArray) {
        ColumnDowncall.AFFINE_F64.invokeExact(affineF64, seg(a), scale, shift, seg(sel), m, seg(out))
    }

    override fun groupIdsI64(keys: LongArray, sel: IntArray, m: Int, ids: IntArray, firstRow: IntArray, table: IntArray, tableKeys: LongArray): Int =
        ColumnDowncall.GROUP_IDS.invokeExact(groupIds, seg(keys), seg(sel), m, seg(ids), seg(firstRow), seg(table), seg(tableKeys), table.size) as Int

    override fun sumByGroupF64(values: DoubleArray, sel: IntArray, ids: IntArray, m: Int, sums: DoubleArray) {
        ColumnDowncall.SUM_BY_GROUP.invokeExact(sumByGroupF64, seg(values), seg(sel), seg(ids), m, seg(sums))
    }

    override fun sumByGroupI64(values: LongArray, sel: IntArray, ids: IntArray, m: Int, sums: LongArray) {
        ColumnDowncall.SUM_BY_GROUP.invokeExact(sumByGroupI64, seg(values), seg(sel), seg(ids), m, seg(sums))
    }

    override fun countByGroup(ids: IntArray, m: Int, counts: LongArray) {
        ColumnDowncall.COUNT_BY_GROUP.invokeExact(countByGroup, seg(ids), m, seg(counts))
    }

    override fun cosineF32(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
        ColumnDowncall.COSINE.invokeExact(cosineF32, seg(flat), n, dims, seg(probe), seg(out))
    }

    override fun dotF32(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
        ColumnDowncall.COSINE.invokeExact(dotF32, seg(flat), n, dims, seg(probe), seg(out))
    }

    override fun l2SquaredF32(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
        ColumnDowncall.COSINE.invokeExact(l2SquaredF32, seg(flat), n, dims, seg(probe), seg(out))
    }

    companion object {
        /** Null when no `libtrikeshed_kernels` loads. */
        fun available(): JvmColumnOperations? =
            loadLibrary("trikeshed.libkernels", "libtrikeshed_kernels.so")?.let(::JvmColumnOperations)
    }
}

/** The downcall shapes of the kernel library, unbound: the function address is the first argument. */
object ColumnDowncall {
    private val linker = Linker.nativeLinker()
    private fun handle(descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(descriptor, Linker.Option.critical(true))

    @JvmField val WHERE_F64: MethodHandle = handle(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_DOUBLE, ADDRESS))
    @JvmField val REFINE_F64: MethodHandle = handle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_DOUBLE, ADDRESS))
    @JvmField val WHERE_I64: MethodHandle = handle(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS))
    @JvmField val REFINE_I64: MethodHandle = handle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS))
    @JvmField val SUM_F64: MethodHandle = handle(FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS, JAVA_INT))
    @JvmField val SUM_I64: MethodHandle = handle(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT))
    @JvmField val MUL_F64: MethodHandle = handle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
    @JvmField val AFFINE_F64: MethodHandle = handle(FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS, JAVA_INT, ADDRESS))
    @JvmField val GROUP_IDS: MethodHandle = handle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    @JvmField val SUM_BY_GROUP: MethodHandle = handle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
    @JvmField val COUNT_BY_GROUP: MethodHandle = handle(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS))
    @JvmField val COSINE: MethodHandle = handle(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
}
