package borg.trikeshed.cutedsl

/**
 * Hardware atom abstractions for GPU tensor cores and memory operations.
 *
 * These model the key hardware primitives exposed by modern GPUs:
 * - MMA: Matrix Multiply Accumulate (Tensor Cores)
 * - TMA: Tensor Memory Accelerator (async global <-> shared copy)
 * - CPAsync: Copy Async (older async copy)
 * - Pipeline: Multi-stage pipelining with synchronization
 * - Cluster: Thread block clusters for distributed execution
 */

enum class DataType(
    val sizeBytes: Int,
    val typeName: String
) {
    F16(2, "f16"),
    BF16(2, "bf16"),
    TF32(4, "tf32"),
    F32(4, "f32"),
    F64(8, "f64"),
    INT8(1, "i8"),
    INT4(1, "i4"), // Packed
    FP8(1, "fp8")
}

enum class MmaLayout { ROW_MAJOR, COL_MAJOR }

/**
 * MMA (Matrix Multiply Accumulate) atom descriptor.
 * Describes a single tensor core operation: D = A * B + C
 *
 * Shape = [M, N, K] where:
 *   A: [M, K], B: [K, N], C/D: [M, N]
 */
class MmaAtom(
    val shape: IntArray,      // [M, N, K]
    val dtype: DataType,
    val layoutA: MmaLayout,
    val layoutB: MmaLayout,
    val layoutC: MmaLayout = MmaLayout.ROW_MAJOR
) {
    init {
        require(shape.size == 3) { "MMA shape must be [M, N, K]" }
        require(shape.all { it > 0 }) { "MMA dimensions must be positive" }
    }

    val m: Int get() = shape[0]
    val n: Int get() = shape[1]
    val k: Int get() = shape[2]

    /** Number of tensor core operations for a full GEMM of size [M, N, K] */
    fun numOps(m: Int, n: Int, k: Int): Int {
        return (m / this.m) * (n / this.n) * (k / this.k)
    }

    /** Shared memory required for A tile */
    val smemA: Int get() = m * k * dtype.sizeBytes

    /** Shared memory required for B tile */
    val smemB: Int get() = k * n * dtype.sizeBytes

    /** Shared memory required for C tile */
    val smemC: Int get() = m * n * DataType.F32.sizeBytes // Accumulate in FP32

    override fun toString(): String {
        return "MmaAtom(${m}x${n}x${k}, ${dtype.typeName}, ${layoutA}/${layoutB})"
    }
}

/**
 * Predefined MMA atoms for common architectures.
 */
object MmaAtoms {
    /** Hopper/H100: 16x16x16 FP16/BF16/TF32 */
    val MMA_16_16_16_F16 = MmaAtom(intArrayOf(16, 16, 16), DataType.F16, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)
    val MMA_16_16_16_BF16 = MmaAtom(intArrayOf(16, 16, 16), DataType.BF16, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)
    val MMA_16_16_16_TF32 = MmaAtom(intArrayOf(16, 16, 16), DataType.TF32, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)

    /** Hopper: 16x8x16 FP16/BF16 */
    val MMA_16_8_16_F16 = MmaAtom(intArrayOf(16, 8, 16), DataType.F16, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)
    val MMA_16_8_16_BF16 = MmaAtom(intArrayOf(16, 8, 16), DataType.BF16, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)

    /** Ada/RTX 40: 16x8x16 FP8 */
    val MMA_16_8_16_FP8 = MmaAtom(intArrayOf(16, 8, 16), DataType.FP8, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)

    /** Blackwell/B200: 16x8x16 FP4/INT4 */
    val MMA_16_8_16_INT4 = MmaAtom(intArrayOf(16, 8, 16), DataType.INT4, MmaLayout.ROW_MAJOR, MmaLayout.COL_MAJOR)
}

/**
 * TMA (Tensor Memory Accelerator) descriptor for async copy.
 * Available on Hopper (H100) and later.
 */
class TmaDescriptor(
    val tensorShape: IntArray,       // Full tensor shape in global memory
    val elementType: DataType,
    val sharedMemLayout: Layout,     // Layout in shared memory
    val globalMemLayout: Layout,     // Layout in global memory
    val swizzle: Int = 0             // Shared memory swizzle pattern (0-7)
) {
    init {
        require(tensorShape.size == sharedMemLayout.rank) { "Shape rank must match shared layout rank" }
        require(tensorShape.size == globalMemLayout.rank) { "Shape rank must match global layout rank" }
    }

    /** Total bytes transferred */
    val bytes: Long get() = tensorShape.fold(1L) { a, b -> a * b } * elementType.sizeBytes

    /**
     * Launch an async TMA copy from global to shared memory.
     * Returns a TmaCopyOp that can be waited on.
     */
    fun copyAsync(
        globalBase: Long,   // Global memory base address
        sharedBase: Long,   // Shared memory base address
        coords: IntArray    // Tile coordinates in the tensor
    ): TmaCopyOp {
        return TmaCopyOp(this, globalBase, sharedBase, coords)
    }

    /** 2D convenience */
    fun copyAsync2D(
        globalBase: Long,
        sharedBase: Long,
        row: Int,
        col: Int
    ): TmaCopyOp {
        return copyAsync(globalBase, sharedBase, intArrayOf(row, col))
    }
}

/** TMA copy operation handle */
class TmaCopyOp internal constructor(
    val descriptor: TmaDescriptor,
    val globalBase: Long,
    val sharedBase: Long,
    val coords: IntArray
) {
    /** Wait for the async copy to complete */
    fun wait() {
        // In real implementation: tma_wait or cluster wait
        // For now: no-op placeholder
    }

    /** Check if copy is complete without blocking */
    fun isComplete(): Boolean = true // Placeholder
}

/**
 * CPAsync (Copy Async) for pre-Hopper or smaller copies.
 */
class CpAsync(
    val srcLayout: Layout,
    val dstLayout: Layout,
    val elementType: DataType,
    val stages: Int = 4  // Number of async stages
) {
    init {
        require(srcLayout.rank == dstLayout.rank) { "Src and dst layout rank must match" }
    }

    fun commit(
        srcBase: Long,
        dstBase: Long,
        coords: IntArray
    ): CpAsyncOp {
        return CpAsyncOp(this, srcBase, dstBase, coords)
    }

    fun waitAll() {
        // Wait for all pending commits
    }
}

class CpAsyncOp internal constructor(
    val cpAsync: CpAsync,
    val srcBase: Long,
    val dstBase: Long,
    val coords: IntArray
) {
    fun wait() { /* placeholder */ }
}

/**
 * Pipeline for multi-stage overlapped execution.
 *
 * Classic pattern:
 * Stage 0: TMA load A0, B0
 * Stage 1: MMA on A0,B0 | TMA load A1,B1
 * Stage 2: MMA on A1,B1 | TMA load A2,B2
 * ...
 */
class Pipeline(
    val stages: Int,
    val stageWork: (Int) -> Unit
) {
    /**
     * Execute the pipeline synchronously (for testing/CPU).
     * Real GPU implementation uses async stages with barriers.
     */
    fun execute() {
        for (i in 0 until stages) {
            stageWork(i)
        }
    }

    /**
     * Producer-consumer pipeline factory.
     * producer: loads data for next stage
     * consumer: computes on current stage
     */
    companion object {
        fun producerConsumer(
            stages: Int,
            producer: (Int) -> Unit,
            consumer: (Int) -> Unit
        ): Pipeline {
            return Pipeline(stages) { stage ->
                if (stage < stages) producer(stage)
                if (stage > 0) consumer(stage - 1)
            }
        }

        /**
         * K-stage GEMM pipeline.
         * Each stage: TMA load A/B -> MMA compute -> (next stage loads next tiles)
         */
        fun kStageGemm(
            kStages: Int,
            loadA: (Int) -> Unit,
            loadB: (Int) -> Unit,
            compute: (Int) -> Unit,
            storeC: (Int) -> Unit
        ): Pipeline {
            return Pipeline(kStages * 3) { stage ->
                val phase = stage % 3
                val iter = stage / 3
                when (phase) {
                    0 -> loadA(iter)
                    1 -> {
                        loadB(iter)
                        if (iter > 0) compute(iter - 1)
                    }
                    2 -> {
                        compute(iter)
                        if (iter > 0) storeC(iter - 1)
                    }
                }
            }
        }
    }
}

/**
 * Cluster launch control for thread block clusters (Hopper+).
 */
class ClusterLaunch(
    val clusterShape: IntArray,
    val blockShape: IntArray,
    val gridShape: IntArray
) {
    init {
        require(clusterShape.size == 3 && blockShape.size == 3 && gridShape.size == 3) {
            "All shapes must be 3D"
        }
    }

    val blocksPerCluster: Int get() = clusterShape.fold(1) { a, b -> a * b }
    val totalBlocks: Int get() = gridShape.fold(1) { a, b -> a * b }
    val totalThreads: Int get() = totalBlocks * blockShape.fold(1) { a, b -> a * b }

    /** Launch a kernel with cluster configuration */
    fun launchKernel(
        kernelName: String,
        sharedMemBytes: Int = 0,
        args: Array<Any> = emptyArray()
    ) {
        // Real implementation: cudaLaunchKernelEx with cluster attrs
        // Placeholder for now
    }
}

/**
 * Pipeline barrier for synchronization across stages.
 */
class PipelineBarrier(
    val threadsPerBlock: Int,
    val phases: Int = 1
) {
    private val phaseCounters = IntArray(phases)

    /** Arrive at barrier for current phase */
    fun arrive(phase: Int = 0) {
        require(phase < phases) { "Phase out of bounds" }
        phaseCounters[phase]++
    }

    /** Wait for all threads to arrive at phase */
    fun wait(phase: Int = 0) {
        require(phase < phases) { "Phase out of bounds" }
        // Spin wait - real impl uses __syncthreads or cluster barrier
        while (phaseCounters[phase] < threadsPerBlock) {
            Thread.onSpinWait()
        }
    }

    /** Arrive and wait in one call */
    fun arriveAndWait(phase: Int = 0) {
        arrive(phase)
        wait(phase)
    }

    /** Reset barrier for reuse */
    fun reset() {
        for (i in phaseCounters.indices) {
            phaseCounters[i] = 0
        }
    }
}