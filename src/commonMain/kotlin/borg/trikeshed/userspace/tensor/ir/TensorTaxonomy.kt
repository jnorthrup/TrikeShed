package borg.trikeshed.userspace.tensor.ir

import borg.trikeshed.userspace.tensor.DType
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.f32
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.i
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.idx
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.memref
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.tensor
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.vector

/**
 * DialectLevel — the 9-level MLIR dialect taxonomy (bottom-up: Scalar→LLVM).
 *
 * Each dialect has a "home" level where its ops are first-class.
 * Lowering moves computation DOWN the stack toward LLVM.
 *
 *   Level  Dialect     What lives here
 *   ──────────────────────────────────────────────────────
 *    9     LLVM        Machine code generation, pointers, bitcasts
 *    8     Func        Function def/call, control flow
 *    7     Scf         Structured loops, conditionals (if/for/while)
 *    6     Math        Transcendental: exp, log, sqrt, pow, sin, cos, tanh
 *    5     Arith       Scalar int/FP: add, sub, mul, div, cmp, select, cast
 *    4     Memref     Heap/stack buffers: alloc, load, store, cast, dim
 *    3     Vector      SIMD: broadcast, fma, contract, shuffle, extract/insert
 *    2     Tensor      Runtime-shaped: extract, insert, generate, from_elements, cast, dim, reshape
 *    1     Linalg     Tiled loops: matmul, conv, fill, generic (elementwise)
 *
 * Lowering chain (bottom-up sequence):
 *   linalg  →  scf+vector  →  memref  →  arith+math  →  llvm
 */
enum class DialectLevel {
    Linalg,  // 1: tiled loop ops (matmul, conv, fill, generic)
    Tensor,  // 2: runtime-shaped tensors
    Vector,  // 3: SIMD fixed-length vectors
    Memref,  // 4: heap/stack buffers
    Arith,   // 5: scalar integer and FP ops
    Math,    // 6: transcendental functions
    Scf,     // 7: structured control flow
    Func,    // 8: function def/call
    LLVM,    // 9: LLVM IR / JIT target
    ;

    /** Canonical MLIR dialect name */
    val mlirName: String get() = when (this) {
        Linalg -> "linalg"
        Tensor -> "tensor"
        Vector -> "vector"
        Memref -> "memref"
        Arith  -> "arith"
        Math   -> "math"
        Scf    -> "scf"
        Func   -> "func"
        LLVM   -> "llvm"
    }

    /** Position in the lowering chain (0 = highest, 8 = lowest) */
    val level: Int get() = ordinal
}

/**
 * TensorTaxonomy — maps TrikeShed tensor algebra onto the MLIR dialect stack.
 *
 * User-facing API: emit tensor computations in dialect-neutral form.
 * Internally dispatches to the appropriate dialect op.
 *
 * Example usage:
 *   val tax = TensorTaxonomy()
 *   val a = tax.constant(2.0, DType.F32)
 *   val b = tax.constant(3.0, DType.F32)
 *   val c = tax.mul(a, b, DType.F32)   // → arith.mulf
 *   val d = tax.exp(c, DType.F32)       // → math.exp
 *   val e = tax.matmul(x, y, acc, 64, 64, 64, DType.F32)  // → linalg.matmul
 */
class TensorTaxonomy {
    private val ops = mutableListOf<Operation>()

    private fun add(op: Operation): SSAValue {
        ops.add(op)
        return if (op.results.isNotEmpty()) SSAValue.OpResult(op, 0) else ops.last().result()
    }

    // ─── Constants ────────────────────────────────────────────────────────

    fun constant(value: Double, dtype: DType = DType.F32): SSAValue {
        val t = dtype.toMLIR()
        return add(ConstantOp.float(value, t))
    }

    fun constant(value: Int, dtype: DType = DType.I32): SSAValue {
        val t = dtype.toMLIR()
        return add(ConstantOp.int(value, t))
    }

    // ─── Arith ────────────────────────────────────────────────────────────

    fun add(lhs: SSAValue, rhs: SSAValue, dtype: DType): SSAValue =
        add(AddFOp.create(lhs, rhs, dtype.toMLIR()))

    fun sub(lhs: SSAValue, rhs: SSAValue, dtype: DType): SSAValue =
        add(SubFOp.create(lhs, rhs, dtype.toMLIR()))

    fun mul(lhs: SSAValue, rhs: SSAValue, dtype: DType): SSAValue =
        add(MulFOp.create(lhs, rhs, dtype.toMLIR()))

    fun div(lhs: SSAValue, rhs: SSAValue, dtype: DType): SSAValue =
        add(DivFOp.create(lhs, rhs, dtype.toMLIR()))

    fun neg(x: SSAValue, dtype: DType): SSAValue =
        add(NegFOp.create(x, dtype.toMLIR()))

    fun select(cond: SSAValue, then: SSAValue, else_: SSAValue, dtype: DType): SSAValue =
        add(SelectOp.create(cond, then, else_, dtype.toMLIR()))

    fun cmp(predicate: CmpFOp.Predicate, lhs: SSAValue, rhs: SSAValue): SSAValue =
        add(CmpFOp.create(predicate, lhs, rhs))

    // ─── Type conversion ──────────────────────────────────────────────────

    fun sitofp(x: SSAValue, to: DType): SSAValue =
        add(SiToFpOp.create(x, to.toMLIR()))

    fun fptosi(x: SSAValue, to: DType): SSAValue =
        add(FpToSiOp.create(x, to.toMLIR()))

    fun extsi(x: SSAValue, to: DType): SSAValue =
        add(ExtSIOp.create(x, to.toMLIR()))

    fun extui(x: SSAValue, to: DType): SSAValue =
        add(ExtUIOp.create(x, to.toMLIR()))

    fun truncf(x: SSAValue, to: DType): SSAValue =
        add(TruncFOp.create(x, to.toMLIR()))

    // ─── Math (transcendental) ───────────────────────────────────────────

    fun exp(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathExpOp.create(x, dtype.toMLIR()))

    fun log(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathLogOp.create(x, dtype.toMLIR()))

    fun sqrt(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathSqrtOp.create(x, dtype.toMLIR()))

    fun pow(lhs: SSAValue, rhs: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathPowOp.create(lhs, rhs, dtype.toMLIR()))

    fun sin(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathSinOp.create(x, dtype.toMLIR()))

    fun cos(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathCosOp.create(x, dtype.toMLIR()))

    fun tanh(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathTanhOp.create(x, dtype.toMLIR()))

    fun rsqrt(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathRsqrtOp.create(x, dtype.toMLIR()))

    fun abs(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathAbsOp.create(x, dtype.toMLIR()))

    fun ceil(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathCeilOp.create(x, dtype.toMLIR()))

    fun floor(x: SSAValue, dtype: DType = DType.F32): SSAValue =
        add(MathFloorOp.create(x, dtype.toMLIR()))

    // ─── Memref ───────────────────────────────────────────────────────────

    fun alloc(shape: List<Int>, dtype: DType): SSAValue =
        add(AllocOp.create(dtype.toMLIR(), shape))

    fun alloca(shape: List<Int>, dtype: DType): SSAValue =
        add(AllocaOp.create(dtype.toMLIR(), shape))

    fun dealloc(memref: SSAValue) {
        ops.add(DeallocOp.create(memref))
    }

    fun load(memref: SSAValue, indices: List<SSAValue> = emptyList()): SSAValue =
        add(LoadOp.create(memref, indices))

    fun store(value: SSAValue, memref: SSAValue, indices: List<SSAValue> = emptyList()) {
        ops.add(StoreOp.create(value, memref, indices))
    }

    fun dim(memref: SSAValue, index: SSAValue): SSAValue =
        add(DimOp.create(memref, index))

    fun cast(memref: SSAValue, resultType: IrType): SSAValue =
        add(CastOp.create(memref, resultType))

    // ─── Tensor dialect ─────────────────────────────────────────────────

    fun tensorEmpty(shape: List<Int>, dtype: DType): SSAValue =
        add(EmptyOp.create(shape, dtype.toMLIR()))

    fun tensorExtract(tensor: SSAValue, indices: List<SSAValue>, dtype: DType): SSAValue =
        add(ExtractOp.create(tensor, indices, dtype.toMLIR()))

    fun tensorInsert(scalar: SSAValue, tensor: SSAValue, indices: List<SSAValue>, dtype: DType): SSAValue {
        val shape = (tensor as? SSAValue.OpResult)?.op?.results?.firstOrNull()?.let { t ->
            (t as? IrType.Tensor)?.shape
        } ?: emptyList()
        return add(InsertOp.create(scalar, tensor, indices, tensor(f32(), shape)))
    }

    fun tensorDim(tensor: SSAValue, index: Int): SSAValue {
        val idxVal = constant(index, DType.I64)
        return add(DimOp2.create(tensor, idxVal))
    }

    fun tensorReshape(tensor: SSAValue, newShape: List<Int>, dtype: DType): SSAValue =
        add(ReshapeOp.create(tensor, newShape, tensor(dtype.toMLIR(), newShape)))

    fun tensorCast(tensor: SSAValue, newShape: List<Int>, dtype: DType): SSAValue =
        add(TensorCastOp.create(tensor, tensor(dtype.toMLIR(), newShape)))

    // ─── Vector dialect ─────────────────────────────────────────────────

    fun vectorBroadcast(source: SSAValue, shape: List<Int>, dtype: DType): SSAValue =
        add(BroadcastOp.create(source, vector(dtype.toMLIR(), shape)))

    fun vectorFMA(a: SSAValue, b: SSAValue, c: SSAValue, dtype: DType): SSAValue {
        val shape = (a as? SSAValue.OpResult)?.op?.results?.firstOrNull()?.let { t ->
            (t as? IrType.Vector)?.shape
        } ?: emptyList()
        return add(FMAOp.create(a, b, c, vector(dtype.toMLIR(), shape)))
    }

    // ─── Linalg dialect ─────────────────────────────────────────────────

    fun matmul(lhs: SSAValue, rhs: SSAValue, acc: SSAValue, m: Int, k: Int, n: Int, dtype: DType): SSAValue =
        add(MatMulOp.create(lhs, rhs, acc, m, k, n, dtype.toMLIR()))

    fun fill(value: SSAValue, output: SSAValue) {
        ops.add(FillOp.create(value, output))
    }

    fun genericLinalg(
        inputs: List<SSAValue>,
        outputs: List<SSAValue>,
        region: Region.() -> Unit,
        indexingMaps: List<List<Int>>,
        iteratorTypes: List<String>
    ): SSAValue = add(GenericOp.create(inputs, outputs, region, indexingMaps, iteratorTypes))

    fun conv2d(lhs: SSAValue, rhs: SSAValue, output: SSAValue, strides: List<Int>, padding: List<List<Int>>) {
        ops.add(Conv2dOp.create(lhs, rhs, output, strides, padding))
    }

    // ─── Scf control flow ────────────────────────────────────────────────

    fun if_(cond: SSAValue, thenBranch: Region.() -> Unit, elseBranch: (Region.() -> Unit)? = null): SSAValue {
        val op = IfOp.create(cond, thenBranch, elseBranch)
        ops.add(op)
        return if (op.results.isNotEmpty()) SSAValue.OpResult(op, 0) else ops.last().result()
    }

    fun for_(lower: SSAValue, upper: SSAValue, step: SSAValue, body: Region.() -> Unit): SSAValue {
        val op = ForOp.create(lower, upper, step, body)
        ops.add(op)
        return if (op.results.isNotEmpty()) SSAValue.OpResult(op, 0) else ops.last().result()
    }

    fun executeRegion(body: Region.() -> Unit): SSAValue {
        val op = ExecuteRegionOp.create(body)
        ops.add(op)
        return if (op.results.isNotEmpty()) SSAValue.OpResult(op, 0) else ops.last().result()
    }

    fun yield(values: List<SSAValue> = emptyList()) {
        ops.add(YieldOp.create(values))
    }

    // ─── Build ──────────────────────────────────────────────────────────

    fun build(): List<Operation> = ops.toList()

    /** Emit a complete function with the standard pipeline applied */
    fun emitFunction(
        name: String,
        inputTypes: List<IrType>,
        outputTypes: List<IrType>,
        bodyBuilder: (List<SSAValue>) -> SSAValue
    ): Operation {
        val entryBlock = Block()
        inputTypes.forEachIndexed { ix, t -> entryBlock.arguments[ix] = t }
        val args = inputTypes.indices.map { entryBlock.arg(it) }

        val localTax = TensorTaxonomy()
        val result = bodyBuilder(args)

        val ret = ReturnOp.create(listOf(result))
        localTax.ops.add(ret)
        entryBlock.operations.addAll(localTax.ops)
        entryBlock.terminator = ret

        val region = Region()
        region.blocks.add(entryBlock)

        return FuncOp.create(name, inputTypes, outputTypes)
    }
}
