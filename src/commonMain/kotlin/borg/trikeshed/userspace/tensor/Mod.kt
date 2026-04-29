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
 *   IR.kt              — Region, Block, Operation, SSAValue, IrType, IrAttribute
 *   Ops.kt             — All dialect ops: func, arith, scf, memref, vector, tensor, linalg, math, llvm
 *   Pipeline.kt        — Bottom-up lowering pipeline: tensor→linalg→scf+vector→memref→arith→LLVM
 *   TensorTaxonomy.kt  — User-facing tensor algebra mapped to dialect ops
 */
package borg.trikeshed.userspace.tensor

// Core tensor algebra (Tensor, TensorShape, DType) is defined in Core.kt and
// is already accessible as borg.trikeshed.userspace.tensor.{Tensor,TensorShape,DType}

// MLIR IR hierarchy (ir/)
public typealias Region = borg.trikeshed.userspace.tensor.ir.Region
public typealias Block = borg.trikeshed.userspace.tensor.ir.Block
public typealias Operation = borg.trikeshed.userspace.tensor.ir.Operation
public typealias SSAValue = borg.trikeshed.userspace.tensor.ir.SSAValue
public typealias IrType = borg.trikeshed.userspace.tensor.ir.IrType
public typealias IrAttribute = borg.trikeshed.userspace.tensor.ir.IrAttribute
public typealias DialectLevel = borg.trikeshed.userspace.tensor.ir.DialectLevel
public typealias TensorTaxonomy = borg.trikeshed.userspace.tensor.ir.TensorTaxonomy
public typealias Pipeline = borg.trikeshed.userspace.tensor.ir.Pipeline
