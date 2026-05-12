package borg.trikeshed.userspace.tensor.ir

import borg.trikeshed.userspace.tensor.DType

// ─── SSA Values ─────────────────────────────────────────────────────────────

/** SSA values: either an operation result or a block argument. */
sealed class SSAValue {
    abstract fun name(): CharSequence

    data class OpResult(val op: Operation, val index: Int) : SSAValue() {
        override fun name(): CharSequence = "%v${op.uid}.$index"
    }

    data class BlockArgument(val block: Block, val index: Int) : SSAValue() {
        override fun name(): CharSequence = "%arg${block.uid}.$index"
    }
}

// ─── Block ──────────────────────────────────────────────────────────────────

private var _blockCounter = 0L

/** Single-entry sequence of ops ending in a terminator. */
class Block {
    val uid = _blockCounter++
    val operations = mutableListOf<Operation>()
    val arguments = mutableMapOf<Int, IrType>()  // index → type
    var terminator: Operation? = null

    fun addOp(op: Operation) { operations.add(op) }
    fun arg(i: Int): SSAValue = SSAValue.BlockArgument(this, i)

    fun lastOp(): Operation? = operations.lastOrNull()
}

// ─── Region ─────────────────────────────────────────────────────────────────

private var _regionCounter = 0L

/** Tree of blocks. Structural ops (func, scf.if, scf.for) contain regions. */
class Region {
    val uid = _regionCounter++
    val blocks = mutableListOf<Block>()

    fun block(args: Map<Int, IrType> = emptyMap()): Block {
        val b = Block()
        b.arguments.putAll(args)
        blocks.add(b)
        return b
    }

    fun lastBlock(): Block = blocks.last()
}

// ─── Operation ─────────────────────────────────────────────────────────────

private var _opCounter = 0L

/** Core unit of MLIR IR: name + SSA results + regions + attributes. */
class Operation(
    val name: CharSequence,
    val results: List<IrType> = emptyList(),
    val regions: List<Region> = emptyList(),
    val attributes: Map<CharSequence, IrAttribute> = emptyMap()
) {
    val uid = _opCounter++

    fun result(i: Int): SSAValue = SSAValue.OpResult(this, i)
    fun result(): SSAValue = result(0)

    fun toString(full: Boolean = false): CharSequence = buildString {
        if (results.isNotEmpty()) {
            append(results.indices.joinToString(", ") { "${result(it)}" })
            append(" = ")
        }
        append("\"$name\"")
        if (attributes.isNotEmpty()) {
            append(" { ")
            append(attributes.entries.joinToString(", ") { (k, v) -> "$k = $v" })
            append(" }")
        }
        if (regions.isNotEmpty()) {
            regions.forEach { r ->
                append(" {\n")
                r.blocks.forEach { b ->
                    append("  ^bb${b.uid}")
                    if (b.arguments.isNotEmpty()) {
                        append("(")
                        append(b.arguments.entries.joinToString(", ") { (i, t) -> "%arg${b.uid}.$i: $t" })
                        append(")")
                    }
                    append(":\n")
                    b.operations.forEach { op ->
                        op.toString(full).lineSequence().forEach { append("    $it\n") }
                    }
                    b.terminator?.let { term ->
                        term.toString(full).lineSequence().forEach { append("    $it\n") }
                    }
                }
                append("  }")
            }
        }
        if (!full) append(")")
    }
}

// ─── IrType ────────────────────────────────────────────────────────────────

/** MLIR type system — all dialect types. */
sealed class IrType {
    // Named aliases for use in Ops.kt and TensorTaxonomy.kt
    companion object {
        fun f32(): IrType = F32()
        fun f64(): IrType = F64()
        fun i(width: Int, signed: Boolean = true): IrType = Integer(width, signed)
        fun idx(): IrType = Index()
        fun memref(elem: IrType, shape: List<Int>): IrType = MemRef(elem, shape)
        fun tensor(elem: IrType, shape: List<Int>): IrType = IrType.Tensor(elem, shape)
        fun vector(elem: IrType, shape: List<Int>): IrType = Vector(elem, shape)
        fun func(inputs: List<IrType>, outputs: List<IrType>): IrType = Function(inputs, outputs)
    }

    class F32(val width: Int = 32) : IrType() {
        override fun toString(): String = "f$width"
    }

    class F64(val width: Int = 64) : IrType() {
        override fun toString(): String = "f$width"
    }

    class Integer(val width: Int, val signed: Boolean = true) : IrType() {
        override fun toString(): String = if (signed) "i$width" else "i$width"
    }

    class Index : IrType() {
        override fun toString(): String = "index"
    }

    class MemRef(val elementType: IrType, val shape: List<Int>) : IrType() {
        override fun toString(): String = "memref<${shape.joinToString("x")}x$elementType>"
    }

    class Tensor(val elementType: IrType, val shape: List<Int>) : IrType() {
        override fun toString(): String = when (shape) {
            emptyList<Int>() -> elementType.toString()
            else -> "tensor<${shape.joinToString("x")}x$elementType>"
        }
    }

    class Vector(val elementType: IrType, val shape: List<Int>) : IrType() {
        override fun toString(): String = "vector<${shape.joinToString("x")}x$elementType>"
    }

    class Function(val inputs: List<IrType>, val outputs: List<IrType>) : IrType() {
        override fun toString(): String = "(() -> ())"
    }
}

/** DType → IrType conversion */
fun DType.toMLIR(): IrType = when (this) {
    DType.F32  -> IrType.f32()
    DType.F64  -> IrType.f64()
    DType.I32  -> IrType.i(32, signed = true)
    DType.I64  -> IrType.i(64, signed = true)
    DType.U8   -> IrType.i(8, signed = false)
    DType.U32  -> IrType.i(32, signed = false)
    DType.U64  -> IrType.i(64, signed = false)
    DType.Bool -> IrType.i(1, signed = false)
}

// ─── IrAttribute ───────────────────────────────────────────────────────────

/** Compile-time constant values. */
sealed class IrAttribute {
    class IntAttr(val value: Int, val type: IrType) : IrAttribute() {
        override fun toString(): String = "$value : $type"
    }

    class FloatAttr(val value: Double, val type: IrType) : IrAttribute() {
        override fun toString(): String = "$value : $type"
    }

    class StringAttr(val value: CharSequence) : IrAttribute() {
        override fun toString(): String = "\"$value\""
    }

    class DenseAttr(val values: List<Number>, val type: IrType) : IrAttribute() {
        override fun toString(): String = "dense<${values.joinToString(",")}> : $type"
    }
}

// Backwards-compatible aliases expected by other modules
/** Short alias for IR types */
typealias Type = IrType

/** Short alias for IR attributes */
typealias Attribute = IrAttribute
