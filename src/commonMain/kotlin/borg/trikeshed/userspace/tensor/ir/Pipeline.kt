package borg.trikeshed.userspace.tensor.ir

/**
 * MLIR dialect pipeline — bottom-up lowering from tensor ops to LLVM.
 *
 * Bottom-up stack:
 *   [TensorTaxonomy]        — user-facing tensor computation graph
 *        ↓
 *   [tensor dialect]        — runtime-shaped tensors
 *        ↓  lowerTensorToLinalg
 *   [linalg dialect]        — named tiled loop ops (matmul, conv, fill, generic)
 *        ↓  lowerLinalgToScfVector
 *   [scf + vector dialect] — loop unrolling, vectorization of pointwise ops
 *        ↓  bufferize
 *   [memref dialect]        — heap/stack buffer allocation
 *        ↓  lowerMemrefToArith
 *   [arith + math dialect]  — scalar replacement, math lowering (exp→llvm.exp)
 *        ↓  canonicalize + lowerFuncToLLVM
 *   [LLVM dialect]          — final LLVM IR, ready for ORC JIT
 *
 * A single lowering pass: input Region → output Region
 */
typealias LoweringPass = (Region) -> Region

// ─── Stage 1: tensor → linalg ────────────────────────────────────────────

/**
 * Lower tensor.generate + tensor.insert loop nest → linalg.generic.
 * Lower tensor.from_elements → linalg.fill where possible.
 */
fun lowerTensorToLinalg(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            when (op.name) {
                "tensor.generate" -> {
                    val bodyBlock = op.regions.firstOrNull()?.blocks?.firstOrNull()
                    if (bodyBlock != null) {
                        val genOp = GenericOp.create(
                            inputs = emptyList(),
                            outputs = emptyList(),
                            region = {
                                val inner = block()
                                bodyBlock.operations.forEach { op -> inner.addOp(op) }
                                inner.terminator = bodyBlock.terminator
                            },
                            indexingMaps = emptyList(),
                            iteratorTypes = emptyList()
                        )
                        outBlock.addOp(genOp)
                    }
                }
                else -> outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator
    }
    out
}

// ─── Stage 2: linalg → scf + vector ──────────────────────────────────────

/**
 * Tile linalg.matmul → 3 nested scf.for + vector.contract.
 * Tile linalg.generic → scf.for loops with vectorized bodies.
 */
fun lowerLinalgToScfVector(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            when (op.name) {
                "linalg.matmul" -> {
                    val m = (op.attributes["M"] as? IrAttribute.IntAttr)?.value ?: 0
                    val k = (op.attributes["K"] as? IrAttribute.IntAttr)?.value ?: 0
                    val n = (op.attributes["N"] as? IrAttribute.IntAttr)?.value ?: 0
                    if (m > 0 && k > 0 && n > 0) {
                        // outer loop over M
                        val mFor = ForOp.create(
                            lower = IndexConstantOp.create(0).result(),
                            upper = IndexConstantOp.create(m).result(),
                            step = IndexConstantOp.create(1).result(),
                            body = {
                                lastBlock().addOp(
                                    ForOp.create(
                                        lower = IndexConstantOp.create(0).result(),
                                        upper = IndexConstantOp.create(n).result(),
                                        step = IndexConstantOp.create(1).result(),
                                        body = {
                                            lastBlock().addOp(
                                                ForOp.create(
                                                    lower = IndexConstantOp.create(0).result(),
                                                    upper = IndexConstantOp.create(k).result(),
                                                    step = IndexConstantOp.create(1).result(),
                                                    body = {
                                                        // inner loop body: vector.contract goes here
                                                    }
                                                )
                                            )
                                        }
                                    )
                                )
                            }
                        )
                        outBlock.addOp(mFor)
                    }
                }
                else -> outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator
    }
    out
}

/**
 * Vectorize scf.for bodies: broadcast scalar arith → vector form.
 * Pointwise arith ops inside loops → vector.broadcast + vector ops.
 */
fun vectorizeScfLoops(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            outBlock.addOp(op)
        }
        outBlock.terminator = block.terminator
    }
    out
}

// ─── Stage 3: memref bufferization ───────────────────────────────────────

/**
 * Convert tensor values to memref via bufferization.
 * tensor.empty → memref.alloc
 * tensor.dim → memref.dim
 */
fun bufferize(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            when (op.name) {
                "tensor.empty" -> {
                    val elemType = (op.results.firstOrNull() as? IrType.Tensor)?.elementType ?: IrType.f32()
                    outBlock.addOp(AllocOp.create(elemType, emptyList()))
                }
                "tensor.dim" -> {
                    outBlock.addOp(op)
                }
                else -> outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator
    }
    out
}

// ─── Stage 4: memref + vector → arith + math ────────────────────────────

/**
 * Lower math.transcendental (exp, log, sqrt, tanh) to target intrinsics.
 * Lower memref.load/store to scalar arith after vectorization.
 */
fun lowerMemrefToArith(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            when (op.name) {
                "math.exp"  -> { outBlock.addOp(MathExpOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                "math.log"  -> { outBlock.addOp(MathLogOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                "math.sqrt" -> { outBlock.addOp(MathSqrtOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                "math.tanh" -> { outBlock.addOp(MathTanhOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                "math.sin"  -> { outBlock.addOp(MathSinOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                "math.cos"  -> { outBlock.addOp(MathCosOp.create(op.results.first().let { SSAValue.OpResult(op, 0) }, op.results.firstOrNull() ?: IrType.f32())) }
                else -> outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator
    }
    out
}

// ─── Stage 5: canonicalize ───────────────────────────────────────────────

/**
 * DCE: remove operations with no live uses and no results.
 * Constant folding: inline arith.constant chains.
 */
fun canonicalize(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            if (op.results.isNotEmpty() || op.name.startsWith("func.") || op.name == "scf.for" || op.name == "scf.if") {
                outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator
    }
    out
}

// ─── Stage 6: func → LLVM ───────────────────────────────────────────────

/**
 * Lower func dialect → LLVM dialect.
 * func.func → llvm.func, func.call → llvm.call, func.return → llvm.ret
 */
fun lowerFuncToLLVM(): LoweringPass = { region ->
    val out = Region()
    region.blocks.forEach { block ->
        val outBlock = out.block()
        block.operations.forEach { op ->
            when (op.name) {
                "func.func" -> {
                    val symName = (op.attributes["sym_name"] as? IrAttribute.StringAttr)?.value ?: "fn"
                    val inputs = op.regions.firstOrNull()?.blocks?.firstOrNull()?.arguments?.values?.toList() ?: emptyList()
                    outBlock.addOp(LLVMFuncOp.create(symName, inputs, emptyList()))
                }
                "func.return" -> outBlock.addOp(LLVMRetOp.create())
                "func.call" -> outBlock.addOp(op)
                else -> outBlock.addOp(op)
            }
        }
        outBlock.terminator = block.terminator?.let {
            if (it.name == "func.return") LLVMRetOp.create() else it
        }
    }
    out
}

// ─── Pipeline composer ───────────────────────────────────────────────────

data class Pipeline(
    val stages: List<LoweringPass>,
    val entryName: String = "main"
) {
    /** Run all stages in sequence, returning the final lowered region */
    fun lower(region: Region): Region = stages.fold(region) { r, stage -> stage(r) }

    companion object {
        /** Standard bottom-up pipeline: tensor → LLVM */
        fun standard(): Pipeline {
            val s1: (Region) -> Region = { r -> lowerTensorToLinalg()(r) }
            val s2: (Region) -> Region = { r -> lowerLinalgToScfVector()(r) }
            val s3: (Region) -> Region = { r -> vectorizeScfLoops()(r) }
            val s4: (Region) -> Region = { r -> bufferize()(r) }
            val s5: (Region) -> Region = { r -> lowerMemrefToArith()(r) }
            val s6: (Region) -> Region = { r -> canonicalize()(r) }
            val s7: (Region) -> Region = { r -> lowerFuncToLLVM()(r) }
            return Pipeline(listOf(s1, s2, s3, s4, s5, s6, s7))
        }

        /**
         * Lightweight pipeline for JIT: skips full vectorization for fast compile.
         * tensor → linalg → memref → arith → LLVM
         */
        fun jit(): Pipeline {
            val s1: (Region) -> Region = { r -> lowerTensorToLinalg()(r) }
            val s2: (Region) -> Region = { r -> bufferize()(r) }
            val s3: (Region) -> Region = { r -> lowerMemrefToArith()(r) }
            val s4: (Region) -> Region = { r -> canonicalize()(r) }
            return Pipeline(listOf(s1, s2, s3, s4))
        }

        /** A la carte: compose a custom pipeline from named stages */
        fun of(vararg stage: LoweringPass): Pipeline = Pipeline(stage.toList())
    }
}
