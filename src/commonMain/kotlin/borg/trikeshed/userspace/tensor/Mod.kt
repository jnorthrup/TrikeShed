/**
 * TrikeShed Platform — Userspace Tensor
 *
 * Ported from literbike `src/userspace/tensor/`. Tensor computation with MLIR
 * integration and JIT compilation.
 *
 * Module map:
 *   Core.kt       — DType, TensorShape, Tensor (core algebra)
 *   Mlir.kt       — MLIRContext, MLIRTensor, MLIROpBuilder
 *   MlirJit.kt    — OrcJit, JitSession, TensorGraph, MlirOrcBuilder, JitExecutor
 *   MlirSys.kt    — MlirContext (FFI placeholder for MLIR C API)
 *   TensorModule.kt — module regroup (this file)
 *
 * MLIR IR taxonomy (ir/):
 *   IR.kt              — Region, Block, Operation, SSAValue, Type, Attribute
 *   Ops.kt             — All dialect ops: func, arith, scf, memref, vector, tensor, linalg, math, llvm
 *   Pipeline.kt        — Bottom-up lowering pipeline: tensor→linalg→scf+vector→memref→arith→LLVM
 *   TensorTaxonomy.kt  — User-facing tensor algebra mapped to dialect ops
 */
package borg.trikeshed.userspace.tensor

// ─── Core tensor algebra ──────────────────────────────────────────────────
// Core types are declared in Core.kt and are available in this package.

// ─── MLIR IR hierarchy (ir/) ────────────────────────────────────────────
public typealias Region = borg.trikeshed.userspace.tensor.ir.Region
public typealias Block = borg.trikeshed.userspace.tensor.ir.Block
public typealias Operation = borg.trikeshed.userspace.tensor.ir.Operation
public typealias SSAValue = borg.trikeshed.userspace.tensor.ir.SSAValue
public typealias Type = borg.trikeshed.userspace.tensor.ir.Type
public typealias Attribute = borg.trikeshed.userspace.tensor.ir.Attribute

// ─── Dialect ops ─────────────────────────────────────────────────────────
public typealias Ops = borg.trikeshed.userspace.tensor.ir.Ops

// ─── Pipeline and taxonomy ──────────────────────────────────────────────
public typealias Pipeline = borg.trikeshed.userspace.tensor.ir.Pipeline
public typealias DialectLevel = borg.trikeshed.userspace.tensor.ir.DialectLevel
public typealias TensorTaxonomy = borg.trikeshed.userspace.tensor.ir.TensorTaxonomy

// Re-export all ir types directly for convenience
public val borg.trikeshed.userspace.tensor.ir.Pipeline.Companion.standard: Pipeline
    get() = borg.trikeshed.userspace.tensor.ir.Pipeline.standard()

public val borg.trikeshed.userspace.tensor.ir.Pipeline.Companion.jit: Pipeline
    get() = borg.trikeshed.userspace.tensor.ir.Pipeline.jit()
