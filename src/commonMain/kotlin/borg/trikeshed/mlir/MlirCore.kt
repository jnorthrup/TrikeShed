package borg.trikeshed.mlir

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view

/**
 * Standard MLIR dialects.
 *
 * These are the dialects relevant to multi-language lowering.
 * Each dialect is a namespace of operations and types.
 */
enum class MlirDialect(val namespace: CharSequence) {
    BUILTIN("builtin"),     // module, unrealized_conversion_cast
    FUNC("func"),           // func.func, func.call, func.return
    ARITH("arith"),         // arith.addi, arith.addf, arith.constant, arith.cmpi
    MATH("math"),           // math.absf, math.sqrt, math.exp, math.log
    SCF("scf"),             // scf.for, scf.while, scf.if, scf.yield
    CF("cf"),               // cf.br, cf.cond_br, cf.switch
    MEMREF("memref"),       // memref.alloc, memref.load, memref.store, memref.dealloc
    TENSOR("tensor"),       // tensor.empty, tensor.extract, tensor.insert
    LINALG("linalg"),       // linalg.matmul, linalg.fill, linalg.generic, linalg.conv_2d
    AFFINE("affine"),       // affine.for, affine.load, affine.store
    LLVM("llvm"),           // llvm.func, llvm.call, llvm.mlir.constant
    GPU("gpu"),             // gpu.launch, gpu.func
    VECTOR("vector"),       // vector.load, vector.store, vector.transfer_read
    SPIRV("spirv"),         // spirv.func, spirv.module
    STABLEHLO("stablehlo"), // stablehlo.add, stablehlo.softmax, stablehlo.convolution
    ;

    /** Fully qualified op name: "func.func", "arith.addf", etc. */
    fun op(name: CharSequence): CharSequence = "$namespace.$name"
}

/**
 * An MLIR operation — dialect + op name + type signature.
 *
 * [operandTypes] and [resultTypes] use the standard MLIR type syntax
 * (f32, i64, tensor<4x8xf32>, memref<?xf64>, index, none).
 */
data class MlirOp(
    val dialect: MlirDialect,
    val name: CharSequence,
    val operandTypes: Series<CharSequence> = Join.emptySeriesOf(),
    val resultTypes: Series<CharSequence> = Join.emptySeriesOf(),
) {
    /** Fully-qualified: "arith.addf" */
    val qualifiedName: CharSequence get() = dialect.op(name)

    override fun toString(): String =
        "\"$qualifiedName\"(${operandTypes.view.joinToString(", ")}) -> (${resultTypes.view.joinToString(", ")})"
}

/* ─── Canonical MLIR ops ─────────────────────────────────────────────── */

/** func dialect ops. */
object FuncOps {
    val func       = MlirOp(MlirDialect.FUNC, "func")
    val call       = MlirOp(MlirDialect.FUNC, "call")
    val ret        = MlirOp(MlirDialect.FUNC, "return")
    val constant   = MlirOp(MlirDialect.FUNC, "constant")
}

/** arith dialect ops — integer and floating-point arithmetic. */
object ArithOps {
    val constant   = MlirOp(MlirDialect.ARITH, "constant")
    val addi       = MlirOp(MlirDialect.ARITH, "addi")
    val addf       = MlirOp(MlirDialect.ARITH, "addf")
    val subi       = MlirOp(MlirDialect.ARITH, "subi")
    val subf       = MlirOp(MlirDialect.ARITH, "subf")
    val muli       = MlirOp(MlirDialect.ARITH, "muli")
    val mulf       = MlirOp(MlirDialect.ARITH, "mulf")
    val divsi      = MlirOp(MlirDialect.ARITH, "divsi")
    val divf       = MlirOp(MlirDialect.ARITH, "divf")
    val remsi      = MlirOp(MlirDialect.ARITH, "remsi")
    val cmpi       = MlirOp(MlirDialect.ARITH, "cmpi")
    val cmpf       = MlirOp(MlirDialect.ARITH, "cmpf")
    val select     = MlirOp(MlirDialect.ARITH, "select")
    val andi       = MlirOp(MlirDialect.ARITH, "andi")
    val ori        = MlirOp(MlirDialect.ARITH, "ori")
    val xori       = MlirOp(MlirDialect.ARITH, "xori")
    val shli       = MlirOp(MlirDialect.ARITH, "shli")
    val shrsi      = MlirOp(MlirDialect.ARITH, "shrsi")
    val negf       = MlirOp(MlirDialect.ARITH, "negf")
    val extsi      = MlirOp(MlirDialect.ARITH, "extsi")  // sign-extend integer
    val trunci     = MlirOp(MlirDialect.ARITH, "trunci") // truncate integer
    val sitofp     = MlirOp(MlirDialect.ARITH, "sitofp")
    val fptosi     = MlirOp(MlirDialect.ARITH, "fptosi")
}

/** math dialect ops — higher-level math functions. */
object MathOps {
    val absf       = MlirOp(MlirDialect.MATH, "absf")
    val sqrt       = MlirOp(MlirDialect.MATH, "sqrt")
    val exp        = MlirOp(MlirDialect.MATH, "exp")
    val exp2       = MlirOp(MlirDialect.MATH, "exp2")
    val log        = MlirOp(MlirDialect.MATH, "log")
    val log2       = MlirOp(MlirDialect.MATH, "log2")
    val powf       = MlirOp(MlirDialect.MATH, "powf")
    val sin        = MlirOp(MlirDialect.MATH, "sin")
    val cos        = MlirOp(MlirDialect.MATH, "cos")
    val tanh       = MlirOp(MlirDialect.MATH, "tanh")
    val relu       = MlirOp(MlirDialect.MATH, "relu")
    val sigmoid    = MlirOp(MlirDialect.MATH, "sigmoid")
}

/** scf dialect ops — structured control flow. */
object ScfOps {
    val forLoop    = MlirOp(MlirDialect.SCF, "for")
    val whileLoop  = MlirOp(MlirDialect.SCF, "while")
    val ifOp       = MlirOp(MlirDialect.SCF, "if")
    val yield      = MlirOp(MlirDialect.SCF, "yield")
    val executeRegion = MlirOp(MlirDialect.SCF, "execute_region")
}

/** cf dialect ops — low-level control flow. */
object CfOps {
    val br         = MlirOp(MlirDialect.CF, "br")
    val condBr     = MlirOp(MlirDialect.CF, "cond_br")
    val switchOp   = MlirOp(MlirDialect.CF, "switch")
}

/** memref dialect ops — memory allocation and access. */
object MemrefOps {
    val alloc      = MlirOp(MlirDialect.MEMREF, "alloc")
    val dealloc    = MlirOp(MlirDialect.MEMREF, "dealloc")
    val load       = MlirOp(MlirDialect.MEMREF, "load")
    val store      = MlirOp(MlirDialect.MEMREF, "store")
    val cast       = MlirOp(MlirDialect.MEMREF, "cast")
    val alloca     = MlirOp(MlirDialect.MEMREF, "alloca")
    val subview    = MlirOp(MlirDialect.MEMREF, "subview")
}

/** tensor dialect ops — immutable multi-dimensional arrays. */
object TensorOps {
    val empty      = MlirOp(MlirDialect.TENSOR, "empty")
    val extract    = MlirOp(MlirDialect.TENSOR, "extract")
    val insert     = MlirOp(MlirDialect.TENSOR, "insert")
    val pad        = MlirOp(MlirDialect.TENSOR, "pad")
    val reshape    = MlirOp(MlirDialect.TENSOR, "reshape")
    val expandShape = MlirOp(MlirDialect.TENSOR, "expand_shape")
    val collapseShape = MlirOp(MlirDialect.TENSOR, "collapse_shape")
}

/** linalg dialect ops — linear algebra on tensors. */
object LinalgOps {
    val matmul     = MlirOp(MlirDialect.LINALG, "matmul")
    val matvec     = MlirOp(MlirDialect.LINALG, "matvec")
    val fill       = MlirOp(MlirDialect.LINALG, "fill")
    val generic    = MlirOp(MlirDialect.LINALG, "generic")
    val conv2d     = MlirOp(MlirDialect.LINALG, "conv_2d")
    val conv2dNchwFchw = MlirOp(MlirDialect.LINALG, "conv_2d_nchw_fchw")
    val batchMatmul = MlirOp(MlirDialect.LINALG, "batch_matmul")
    val reduce     = MlirOp(MlirDialect.LINALG, "reduce")
    val map        = MlirOp(MlirDialect.LINALG, "map")
    val transpose  = MlirOp(MlirDialect.LINALG, "transpose")
    val dot        = MlirOp(MlirDialect.LINALG, "dot")
}

/** affine dialect ops — polyhedral loop optimization. */
object AffineOps {
    val forLoop    = MlirOp(MlirDialect.AFFINE, "for")
    val load       = MlirOp(MlirDialect.AFFINE, "load")
    val store      = MlirOp(MlirDialect.AFFINE, "store")
    val yield      = MlirOp(MlirDialect.AFFINE, "yield")
    val apply      = MlirOp(MlirDialect.AFFINE, "apply")
}
