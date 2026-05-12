package borg.trikeshed.userspace.tensor.ir

import borg.trikeshed.userspace.tensor.ir.IrType.Companion.f32
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.i
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.idx
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.memref
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.tensor
import borg.trikeshed.userspace.tensor.ir.IrType.Companion.vector
import borg.trikeshed.userspace.tensor.ir.IrAttribute.IntAttr
import borg.trikeshed.userspace.tensor.ir.IrAttribute.StringAttr
import borg.trikeshed.userspace.tensor.ir.IrAttribute.FloatAttr
import borg.trikeshed.userspace.tensor.ir.IrAttribute.DenseAttr

// ─── func dialect ─────────────────────────────────────────────────────────────

object FuncOp {
    fun create(name: CharSequence, inputs: List<IrType>, outputs: List<IrType>): Operation {
        val entry = Region().block()
        inputs.forEachIndexed { ix, t -> entry.arguments[ix] = t }
        return Operation(
            name = "func.func",
            regions = listOf(Region().apply { blocks.add(entry) }),
            attributes = mapOf("sym_name" to StringAttr(name))
        )
    }
}

object ReturnOp {
    fun create(operands: List<SSAValue> = emptyList()): Operation =
        Operation(name = "func.return")
}

object CallOp {
    fun create(callee: CharSequence, args: List<SSAValue>, resultTypes: List<IrType>): Operation =
        Operation(
            name = "func.call",
            results = resultTypes,
            attributes = mapOf("callee" to StringAttr(callee))
        )
}

object BranchOp {
    fun create(dest: SSAValue.BlockArgument): Operation =
        Operation(name = "cf.br", attributes = mapOf("dest" to IntAttr(dest.index, idx())))
}

object CondBranchOp {
    fun create(cond: SSAValue, trueDest: SSAValue.BlockArgument, falseDest: SSAValue.BlockArgument): Operation =
        Operation(
            name = "cf.cond_br",
            attributes = mapOf(
                "condition" to IntAttr(0, idx()),
                "true_dest" to IntAttr(trueDest.index, idx()),
                "false_dest" to IntAttr(falseDest.index, idx())
            )
        )
}

// ─── arith dialect ────────────────────────────────────────────────────────────

object AddFOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "arith.addf", results = listOf(resultType))
}

object AddIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.addi", results = listOf(resultType))
}

object SubFOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "arith.subf", results = listOf(resultType))
}

object SubIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.subi", results = listOf(resultType))
}

object MulFOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "arith.mulf", results = listOf(resultType))
}

object MulIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.muli", results = listOf(resultType))
}

object DivFOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "arith.divf", results = listOf(resultType))
}

object DivSIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.divsi", results = listOf(resultType))
}

object RemSIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.remsi", results = listOf(resultType))
}

object NegFOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "arith.negf", results = listOf(resultType))
}

object XOrIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.xori", results = listOf(resultType))
}

object AndIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.andi", results = listOf(resultType))
}

object OrIOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(32)): Operation =
        Operation(name = "arith.ori", results = listOf(resultType))
}

object SelectOp {
    fun create(cond: SSAValue, then: SSAValue, else_: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.select", results = listOf(resultType))
}

object ConstantOp {
    fun float(value: Double, type: IrType = f32()): Operation =
        Operation(name = "arith.constant", results = listOf(type), attributes = mapOf("value" to FloatAttr(value, type)))

    fun int(value: Int, type: IrType = i(32)): Operation =
        Operation(name = "arith.constant", results = listOf(type), attributes = mapOf("value" to IntAttr(value, type)))
}

object IndexConstantOp {
    fun create(value: Int): Operation =
        Operation(name = "arith.constant", results = listOf(idx()), attributes = mapOf("value" to IntAttr(value, idx())))
}

object CmpFOp {
    enum class Predicate { oeq, one, olt, ole, ogt, oge, ord, uno }

    fun create(predicate: Predicate, lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(1)): Operation =
        Operation(
            name = "arith.cmpf",
            results = listOf(resultType),
            attributes = mapOf("predicate" to StringAttr(predicate.name))
        )
}

object CmpIOp {
    enum class Predicate { eq, ne, slt, sle, sgt, sge, ult, ule, ugt, uge }

    fun create(predicate: Predicate, lhs: SSAValue, rhs: SSAValue, resultType: IrType = i(1)): Operation =
        Operation(
            name = "arith.cmpfi",
            results = listOf(resultType),
            attributes = mapOf("predicate" to StringAttr(predicate.name))
        )
}

object ExtSIOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.extsi", results = listOf(resultType))
}

object ExtUIOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.extui", results = listOf(resultType))
}

object TruncFOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.truncf", results = listOf(resultType))
}

object TruncIOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.trunci", results = listOf(resultType))
}

object FpToSiOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.fptosi", results = listOf(resultType))
}

object SiToFpOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.sitofp", results = listOf(resultType))
}

object ZeroExtendIOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "arith.extui", results = listOf(resultType))
}

// ─── scf dialect ──────────────────────────────────────────────────────────────

object IfOp {
    fun create(cond: SSAValue, thenBody: Region.() -> Unit, elseBody: (Region.() -> Unit)? = null): Operation {
        val thenRegion = Region().apply(thenBody)
        val elseRegion = elseBody?.let { Region().apply(it) }
        return Operation(
            name = "scf.if",
            regions = buildList {
                add(thenRegion)
                elseRegion?.let { add(it) }
            }
        )
    }
}

object ForOp {
    fun create(lower: SSAValue, upper: SSAValue, step: SSAValue, body: Region.() -> Unit): Operation {
        val bodyRegion = Region().apply {
            val entry = block(mapOf(0 to idx())) // induction variable at index 0
            body()
        }
        return Operation(name = "scf.for", regions = listOf(bodyRegion))
    }
}

object ExecuteRegionOp {
    fun create(body: Region.() -> Unit): Operation =
        Operation(name = "scf.execute_region", regions = listOf(Region().apply(body)))
}

object YieldOp {
    fun create(values: List<SSAValue> = emptyList()): Operation =
        Operation(name = "scf.yield")
}

object WhileOp {
    fun create(body: Region.() -> Unit): Operation =
        Operation(name = "scf.while", regions = listOf(Region().apply(body)))
}

object ReduceOp {
    fun create(initial: SSAValue, body: Region.() -> Unit): Operation =
        Operation(name = "scf.reduce", regions = listOf(Region().apply(body)))
}

object ReduceReturnOp {
    fun create(values: List<SSAValue> = emptyList()): Operation =
        Operation(name = "scf.reduce_return")
}

// ─── memref dialect ───────────────────────────────────────────────────────────

object AllocaOp {
    fun create(elementType: IrType, shape: List<Int>): Operation =
        Operation(name = "memref.alloca", results = listOf(memref(elementType, shape)))
}

object AllocOp {
    fun create(elementType: IrType, shape: List<Int>): Operation =
        Operation(name = "memref.alloc", results = listOf(memref(elementType, shape)))
}

object DeallocOp {
    fun create(memref: SSAValue): Operation =
        Operation(name = "memref.dealloc")
}

object LoadOp {
    fun create(memref: SSAValue, indices: List<SSAValue> = emptyList()): Operation =
        Operation(name = "memref.load")
}

object StoreOp {
    fun create(value: SSAValue, memref: SSAValue, indices: List<SSAValue> = emptyList()): Operation =
        Operation(name = "memref.store")
}

object CastOp {
    fun create(memref: SSAValue, resultType: IrType): Operation =
        Operation(name = "memref.cast", results = listOf(resultType))
}

object DimOp {
    fun create(memref: SSAValue, index: SSAValue, resultType: IrType = idx()): Operation =
        Operation(name = "memref.dim", results = listOf(resultType))
}

object SubViewOp {
    fun create(memref: SSAValue, offsets: List<Int>, sizes: List<Int>, strides: List<Int>): Operation =
        Operation(name = "memref.subview", results = listOf(memref(IrType.f32(), sizes)))
}

// ─── vector dialect ──────────────────────────────────────────────────────────

object BroadcastOp {
    fun create(source: SSAValue, resultType: IrType): Operation =
        Operation(name = "vector.broadcast", results = listOf(resultType))
}

object VectorLoadOp {
    fun create(memref: SSAValue, indices: List<SSAValue>, resultType: IrType): Operation =
        Operation(name = "vector.load", results = listOf(resultType))
}

object VectorStoreOp {
    fun create(value: SSAValue, memref: SSAValue, indices: List<SSAValue> = emptyList()): Operation =
        Operation(name = "vector.store")
}

object FMAOp {
    fun create(a: SSAValue, b: SSAValue, c: SSAValue, resultType: IrType): Operation =
        Operation(name = "vector.fma", results = listOf(resultType))
}

object ContractOp {
    fun create(lhs: SSAValue, rhs: SSAValue, acc: SSAValue, resultType: IrType): Operation =
        Operation(name = "vector.contract", results = listOf(resultType))
}

object ExtractElementOp {
    fun create(vector: SSAValue, index: SSAValue, resultType: IrType): Operation =
        Operation(name = "vector.extractelement", results = listOf(resultType))
}

object InsertElementOp {
    fun create(vector: SSAValue, value: SSAValue, index: SSAValue, resultType: IrType): Operation =
        Operation(name = "vector.insertelement", results = listOf(resultType))
}

object ShuffleOp {
    fun create(v1: SSAValue, v2: SSAValue, mask: List<Int>, resultType: IrType): Operation =
        Operation(
            name = "vector.shuffle",
            results = listOf(resultType),
            attributes = mapOf("mask" to DenseAttr(mask, idx()))
        )
}

object VectorCreateOp {
    fun create(resultType: IrType): Operation =
        Operation(name = "vector.create_mask", results = listOf(resultType))
}

// ─── tensor dialect ──────────────────────────────────────────────────────────

object ExtractOp {
    fun create(tensor: SSAValue, indices: List<SSAValue>, resultType: IrType): Operation =
        Operation(name = "tensor.extract", results = listOf(resultType))
}

object InsertOp {
    fun create(scalar: SSAValue, tensor: SSAValue, indices: List<SSAValue>, resultType: IrType): Operation =
        Operation(name = "tensor.insert", results = listOf(resultType))
}

object GenerateOp {
    fun create(shape: List<Int>, elementType: IrType, body: Region.() -> Unit): Operation =
        Operation(
            name = "tensor.generate",
            results = listOf(tensor(elementType, shape)),
            regions = listOf(Region().apply(body))
        )
}

object FromElementsOp {
    fun create(elements: List<SSAValue>, resultType: IrType): Operation =
        Operation(name = "tensor.from_elements", results = listOf(resultType))
}

object TensorCastOp {
    fun create(tensor: SSAValue, resultType: IrType): Operation =
        Operation(name = "tensor.cast", results = listOf(resultType))
}

object DimOp2 {
    fun create(tensor: SSAValue, index: SSAValue, resultType: IrType = idx()): Operation =
        Operation(name = "tensor.dim", results = listOf(resultType))
}

object EmptyOp {
    fun create(shape: List<Int>, elementType: IrType): Operation =
        Operation(name = "tensor.empty", results = listOf(tensor(elementType, shape)))
}

object ReshapeOp {
    fun create(tensor: SSAValue, newShape: List<Int>, resultType: IrType): Operation =
        Operation(name = "tensor.reshape", results = listOf(resultType))
}

// ─── linalg dialect ──────────────────────────────────────────────────────────

object MatMulOp {
    fun create(
        lhs: SSAValue, rhs: SSAValue, acc: SSAValue,
        m: Int, k: Int, n: Int, elementType: IrType
    ): Operation = Operation(
        name = "linalg.matmul",
        results = listOf(memref(tensor(elementType, listOf(m, n)), listOf(m, n))),
        attributes = mapOf(
            "M" to IntAttr(m, idx()),
            "K" to IntAttr(k, idx()),
            "N" to IntAttr(n, idx())
        )
    )
}

object FillOp {
    fun create(value: SSAValue, output: SSAValue): Operation =
        Operation(name = "linalg.fill")
}

object GenericOp {
    fun create(
        inputs: List<SSAValue>,
        outputs: List<SSAValue>,
        region: Region.() -> Unit,
        indexingMaps: List<List<Int>>,
        iteratorTypes: List<CharSequence>
    ): Operation = Operation(
        name = "linalg.generic",
        regions = listOf(Region().apply(region)),
        attributes = mapOf(
            "indexing_maps" to StringAttr(indexingMaps.toString()),
            "iterator_types" to StringAttr(iteratorTypes.joinToString(","))
        )
    )
}

object Conv2dOp {
    fun create(
        lhs: SSAValue, rhs: SSAValue, output: SSAValue,
        strides: List<Int>, padding: List<List<Int>>
    ): Operation = Operation(
        name = "linalg.conv_2d",
        attributes = mapOf(
            "strides" to StringAttr(strides.joinToString(",")),
            "padding" to StringAttr(padding.joinToString(","))
        )
    )
}

// ─── math dialect ────────────────────────────────────────────────────────────

object MathExpOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.exp", results = listOf(resultType))
}

object MathLogOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.log", results = listOf(resultType))
}

object MathSqrtOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.sqrt", results = listOf(resultType))
}

object MathPowOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.pow", results = listOf(resultType))
}

object MathSinOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.sin", results = listOf(resultType))
}

object MathCosOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.cos", results = listOf(resultType))
}

object MathTanhOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.tanh", results = listOf(resultType))
}

object MathRsqrtOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.rsqrt", results = listOf(resultType))
}

object MathAbsOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.abs", results = listOf(resultType))
}

object MathCeilOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.ceil", results = listOf(resultType))
}

object MathFloorOp {
    fun create(operand: SSAValue, resultType: IrType = f32()): Operation =
        Operation(name = "math.floor", results = listOf(resultType))
}

// ─── LLVM dialect ─────────────────────────────────────────────────────────────

object LLVMFuncOp {
    fun create(name: CharSequence, inputs: List<IrType>, outputs: List<IrType>): Operation =
        Operation(name = "llvm.func", results = outputs, attributes = mapOf("sym_name" to StringAttr(name)))
}

object LLVMCallOp {
    fun create(callee: CharSequence, args: List<SSAValue>, resultTypes: List<IrType>): Operation =
        Operation(name = "llvm.call", results = resultTypes, attributes = mapOf("callee" to StringAttr(callee)))
}

object LLVMBrOp {
    fun create(dest: SSAValue.BlockArgument): Operation =
        Operation(name = "llvm.br", attributes = mapOf("dest" to IntAttr(dest.index, idx())))
}

object LLVMRetOp {
    fun create(values: List<SSAValue> = emptyList()): Operation =
        Operation(name = "llvm.ret")
}

object LLVMBitcastOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.bitcast", results = listOf(resultType))
}

object LLVMSextOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.sext", results = listOf(resultType))
}

object LLVMTruncOp {
    fun create(operand: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.trunc", results = listOf(resultType))
}

object LLVMMulOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.mul", results = listOf(resultType))
}

object LLVMSubOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.sub", results = listOf(resultType))
}

object LLVMAddOp {
    fun create(lhs: SSAValue, rhs: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.add", results = listOf(resultType))
}

object LLVMCmpOp {
    fun create(predicate: CharSequence, lhs: SSAValue, rhs: SSAValue, resultType: IrType): Operation =
        Operation(name = "llvm.icmp", results = listOf(resultType), attributes = mapOf("predicate" to StringAttr(predicate)))
}
