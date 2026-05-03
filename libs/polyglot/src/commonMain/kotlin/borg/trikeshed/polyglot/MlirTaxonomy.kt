package borg.trikeshed.polyglot

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import borg.trikeshed.mlir.*

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
 *  Canonical ops are defined in [borg.trikeshed.mlir] grouped by dialect,
 *  matching the standard MLIR dialect specification (mlir.llvm.org/docs/Dialects/).
 * ═══════════════════════════════════════════════════════════════════════════ */

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
