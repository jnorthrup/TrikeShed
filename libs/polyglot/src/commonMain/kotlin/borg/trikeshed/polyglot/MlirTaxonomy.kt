package borg.trikeshed.polyglot

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_

/* ═══════════════════════════════════════════════════════════════════════════
 *  MLIR taxonomy — the peer to [NodeKind] at the compilation level.
 *
 *  Where [NodeKind] classifies source-language AST nodes (CLASS, FUNCTION, IF),
 *  [MlirDialect] and [MlirOp] classify MLIR dialect operations
 *  (func.func, scf.for, arith.addf, linalg.matmul).
 *
 *  The two taxonomies connect via [nodeToMlir]: a NodeKind maps to zero or more
 *  MlirOps. Some source concepts (IMPORT, COMMENT) have no MLIR equivalent and
 *  are discarded during lowering. Others (FUNCTION, BLOCK, IF, LOOP) map directly
 *  to MLIR structural operations.
 *
 *  Canonical ops are defined as constants grouped by dialect, matching the
 *  standard MLIR dialect specification (mlir.llvm.org/docs/Dialects/).
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Standard MLIR dialects.
 *
 * These are the dialects relevant to multi-language lowering.
 * Each dialect is a namespace of operations and types.
 */
enum class MlirDialect(val namespace: String) {
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

    /** Fully-qualified op name: "func.func", "arith.addf", etc. */
    fun op(name: String): String = "$namespace.$name"
}

/**
 * An MLIR operation — dialect + op name + type signature.
 *
 * This is the peer to a [NodeKind] entry. Where NodeKind says "this is a FUNCTION,"
 * MlirOp says "this lowers to func.func".
 *
 * [operandTypes] and [resultTypes] use the standard MLIR type syntax
 * (f32, i64, tensor<4x8xf32>, memref<?xf64>, index, none).
 */
data class MlirOp(
    val dialect: MlirDialect,
    val name: String,
    val operandTypes: Series<String> = Join.emptySeriesOf(),
    val resultTypes: Series<String> = Join.emptySeriesOf(),
) {
    /** Fully-qualified: "arith.addf" */
    val qualifiedName: String get() = dialect.op(name)

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

/* ─── NodeKind → MlirOp mapping ──────────────────────────────────────── */

/**
 * Map a source-language AST node kind to its canonical MLIR operation(s).
 *
 * Some NodeKinds map to a single MlirOp (FUNCTION → func.func).
 * Others map to multiple (BINARY_OP → arith.addf OR arith.mulf etc., resolved by TypeEvidence).
 * Some produce no MLIR (IMPORT, COMMENT — discarded during lowering).
 *
 * Returns a series of candidate MlirOps, or empty if the node has no MLIR equivalent.
 */
fun nodeToMlir(kind: NodeKind): Series<MlirOp> = when (kind) {
    // structural — no direct MLIR equivalent (lowered to function table + alloc)
    NodeKind.MODULE          -> s_[FuncOps.func]  // module → top-level func.func
    NodeKind.NAMESPACE       -> Join.emptySeriesOf()            // lowered away
    NodeKind.CLASS           -> Join.emptySeriesOf()            // lowered to memref + vtable
    NodeKind.INTERFACE       -> Join.emptySeriesOf()            // lowered to function type set
    NodeKind.STRUCT          -> Join.emptySeriesOf()            // lowered to memref + gep
    NodeKind.TRAIT           -> Join.emptySeriesOf()            // lowered to function type constraint
    NodeKind.IMPL            -> Join.emptySeriesOf()            // lowered to function definitions
    NodeKind.ENUM            -> Join.emptySeriesOf()            // lowered to arith.constant discriminants

    // callable
    NodeKind.FUNCTION        -> s_[FuncOps.func]
    NodeKind.METHOD          -> s_[FuncOps.func]

    // variables / memory
    NodeKind.VARIABLE        -> s_[MemrefOps.alloca, MemrefOps.store]
    NodeKind.PARAMETER       -> Join.emptySeriesOf()            // becomes block argument
    NodeKind.FIELD           -> s_[MemrefOps.load, MemrefOps.store]
    NodeKind.ASSIGN          -> s_[MemrefOps.store]

    // control flow
    NodeKind.BLOCK           -> Join.emptySeriesOf()            // becomes MLIR block/region
    NodeKind.RETURN          -> s_[FuncOps.ret]
    NodeKind.IF              -> s_[ScfOps.ifOp]
    NodeKind.LOOP            -> s_[ScfOps.forLoop]
    NodeKind.WHILE           -> s_[ScfOps.whileLoop]
    NodeKind.FOR             -> s_[ScfOps.forLoop, AffineOps.forLoop]
    NodeKind.MATCH           -> s_[CfOps.switchOp, ScfOps.ifOp]  // match → switch or if-chain
    NodeKind.TRY             -> Join.emptySeriesOf()            // exception handling → later
    NodeKind.THROW           -> Join.emptySeriesOf()            // exception handling → later

    // expressions
    NodeKind.STATEMENT       -> Join.emptySeriesOf()            // sequence → block ordering
    NodeKind.EXPRESSION      -> Join.emptySeriesOf()            // resolved by evidence
    NodeKind.CALL            -> s_[FuncOps.call]
    NodeKind.LITERAL         -> s_[ArithOps.constant]
    NodeKind.BINARY_OP       -> s_[                // resolved by operator kind
        ArithOps.addf, ArithOps.addi,
        ArithOps.subf, ArithOps.subi,
        ArithOps.mulf, ArithOps.muli,
        ArithOps.divf, ArithOps.divsi,
        ArithOps.cmpf, ArithOps.cmpi,
        ArithOps.andi, ArithOps.ori, ArithOps.xori,
    ]
    NodeKind.UNARY_OP        -> s_[ArithOps.negf, MathOps.absf, MathOps.sqrt]

    // types — no runtime MLIR
    NodeKind.TYPE_ANNOTATION -> Join.emptySeriesOf()
    NodeKind.TYPE_DECL       -> Join.emptySeriesOf()

    // module-level — no MLIR equivalent
    NodeKind.IMPORT          -> Join.emptySeriesOf()
    NodeKind.EXPORT          -> Join.emptySeriesOf()
    NodeKind.COMMENT         -> Join.emptySeriesOf()
    NodeKind.UNKNOWN         -> Join.emptySeriesOf()
}

/**
 * All NodeKinds that produce MLIR operations.
 * These are the kinds that survive the lowering funnel.
 */
val mlirRelevantKinds: Series<NodeKind> =
    NodeKind.entries.filter { nodeToMlir(it).isNotEmpty() }.toSeries()
