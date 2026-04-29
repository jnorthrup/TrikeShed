package borg.trikeshed.userspace.tensor.ir

import borg.trikeshed.userspace.tensor.DType

/**
 * SSA values are the result of operations or block arguments.
 * This is the fundamental value type in MLIR's SSA form.
 */
sealed class SSAValue {
    /** A value defined by a operation result */
    data class OpResult(val op: Operation, val index: Int) : SSAValue() {
        override fun toString(): String = "%v${op.uid}.$index"
    }

    /** A value that is a block parameter (function arg or block arg) */
    data class BlockArgument(val block: Block, val index: Int) : SSAValue() {
        override fun toString(): String = "%arg${block.uid}.$index"
    }
}

/** Convenience: get the SSA value name string */
fun SSAValue.name(): String = when (this) {
    is SSAValue.OpResult -> "%v${op.uid}.$index"
    is SSAValue.BlockArgument -> "%arg${block.uid}.$index"
}

/**
 * Operation — the core unit of MLIR IR.
 * Each op has a name, optional SSA results, optional regions, and optional attributes.
 */
class Operation(
    val name: String,
    val results: List<Type> = emptyList(),
    val regions: List<Region> = emptyList(),
    val attributes: Map<String, Attribute> = emptyMap()
) {
    companion object {
        var counter = 0L
    }

    val uid = counter++

    fun result(index: Int): SSAValue = SSAValue.OpResult(this, index)
    fun result(): SSAValue = result(0)

    fun toString(full: Boolean = false): String = buildString {
        if (results.isNotEmpty()) {
            val names = results.indices.joinToString(", ") { "${result(it)}" }
            append("$names = ")
        }
        append("\"$name\"")
        if (attributes.isNotEmpty()) {
            append(" {")
            append(attributes.entries.joinToString(", ") { (k, v) -> "$k = $v" })
            append("}")
        }
        if (regions.isNotEmpty()) {
            regions.forEach { region ->
                append(" {\n")
                region.blocks.forEach { block ->
                    append("  ^bb${block.uid}")
                    if (block.arguments.isNotEmpty()) {
                        append("(")
                        append(block.arguments.entries.joinToString(", ") { (i, t) -> "%arg${block.uid}.$i: $t" })
                        append(")")
                    }
                    append(":\n")
                    block.operations.forEach { op ->
                        op.toString(full).lines().forEach { append("    $it\n") }
                    }
                    block.terminator?.let { term ->
                        term.toString(full).lines().forEach { append("    $it\n") }
                    }
                }
                append("  }")
            }
        }
        if (!full) append(")")
    }
}

/**
 * Block — a sequence of operations ending with a terminator.
 * A region contains one or more blocks forming a CFG.
 */
class Block {
    val uid = nextUid()
    val operations = mutableListOf<Operation>()
    val arguments = mutableMapOf<Int, Type>() // index -> type
    var terminator: Operation? = null

    fun addOp(op: Operation) { operations.add(op) }
    fun arg(index: Int): SSAValue = SSAValue.BlockArgument(this, index)

    companion object {
        private var _counter = 0L
        fun nextUid() = _counter++
    }
}

/**
 * Region — a tree of blocks (single entry, zero or more exit blocks).
 * Structural operations (func, if, while, scf) contain regions.
 */
class Region {
    val blocks = mutableListOf<Block>()

    fun block(arguments: Map<Int, Type> = emptyMap()): Block {
        val b = Block()
        b.arguments.putAll(arguments)
        blocks.add(b)
        return b
    }

    fun lastBlock(): Block = blocks.last()
}

/** Type system — MLIR has a closed set of dialect types. */
sealed class Type {
    data class F32(val width: Int = 32) : Type() {
        override fun toString(): String = "f$width"
    }

    data class F64(val width: Int = 64) : Type() {
        override fun toString(): String = "f$width"
    }

    data class Integer(val width: Int, val signed: Boolean = true) : Type() {
        override fun toString(): String = if (signed) "i$width" else "i$width"
    }

    data class Index(val width: Int = 64) : Type() {
        override fun toString(): String = "index"
    }

    data class MemRef(val elementType: Type, val shape: List<Int>) : Type() {
        override fun toString(): String = "memref<${shape.joinToString("x")}x$elementType>"
    }

    data class Tensor(val elementType: Type, val shape: List<Int>) : Type() {
        override fun toString(): String = when (shape) {
            emptyList<Any>() -> elementType.toString()
            else -> "tensor<${shape.joinToString("x")}x$elementType>"
        }
    }

    data class Vector(val elementType: Type, val shape: List<Int>) : Type() {
        override fun toString(): String = "vector<${shape.joinToString("x")}x$elementType>"
    }

    data class Function(val inputs: List<Type>, val outputs: List<Type>) : Type() {
        override fun toString(): String = "(() -> ())"
    }
}

/** Attributes — constant values known at compile time. */
sealed class Attribute {
    data class Int(val value: Int, val type: Type) : Attribute() {
        override fun toString(): String = "$value : $type"
    }

    data class Float(val value: Double, val type: Type) : Attribute() {
        override fun toString(): String = "$value : $type"
    }

    data class String(val value: kotlin.String) : Attribute() {
        override fun toString(): String = "\"$value\""
    }

    data class Dense(val values: List<Number>, val type: Type) : Attribute() {
        override fun toString(): String = "dense<${values.joinToString(",")}> : $type"
    }
}

/** Translate TrikeShed DType to MLIR Type */
fun DType.toMLIR(): Type = when (this) {
    DType.F32 -> Type.F32()
    DType.F64 -> Type.F64()
    DType.I32 -> Type.Integer(32, signed = true)
    DType.I64 -> Type.Integer(64, signed = true)
    DType.U8 -> Type.Integer(8, signed = false)
    DType.U32 -> Type.Integer(32, signed = false)
    DType.U64 -> Type.Integer(64, signed = false)
    DType.Bool -> Type.Integer(1, signed = false)
}
