package borg.trikeshed.ebpf

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

/**
 * Integration of userspace eBPF with TrikeShed kernel algebra.
 * Provides Series/Join representations for programs, execution results, and streams.
 */
object EbpfKernelAlgebra {

    /**
     * Represents an eBPF program as a kernel algebra Series.
     * The index is the instruction offset (pc), the value is the raw instruction.
     */
    fun EbpfProgram.asInstructionSeries(): Series<Long> {
        val size = program.instructions.size
        return size j { index -> program.instructions[index] }
    }

    /**
     * Represents a program's execution trace as a Series of register states.
     * Each element is a snapshot of all 11 registers at a given PC.
     */
    data class ExecutionTrace(
        val program: EbpfProgram,
        val context: ByteArray,
        val traces: List<LongArray> // List of register arrays at each PC
    ) {
        /** Converts trace to a Series of Join<PC, RegisterState> */
        fun asTraceSeries(): Series<Join<Int, LongArray>> {
            val size = traces.size
            return size j { pc -> pc j traces[pc] }
        }

        /** Gets the final register state (R0-R10) */
        fun finalRegisters(): LongArray = traces.last()
    }

    /**
     * Executes a program and captures the full execution trace.
     */
    fun EbpfProgram.traceExecute(
        context: ByteArray,
        registry: EbpfHelperRegistry = EbpfHelperRegistry()
    ): ExecutionTrace {
        val interpreter = EbpfInterpreter(this, registry)
        val traces = mutableListOf<LongArray>()
        var pc = 0
        val registers = LongArray(11)
        registers.fill(0)

        while (pc < program.instructions.size) {
            traces.add(registers.copyOf())
            val inst = EbpfInstruction(program.instructions[pc])
            val opcode = inst.opcode
            val classOp = opcode and EbpfOpcode.BPF_CLASS_MASK
            pc++

            when (classOp) {
                EbpfOpcode.BPF_ALU64 -> interpreter.executeAlu64(opcode, inst)
                EbpfOpcode.BPF_ALU -> interpreter.executeAlu32(opcode, inst)
                EbpfOpcode.BPF_JMP -> {
                    val jmpOffset = interpreter.executeJmp(opcode, inst, context)
                    pc += jmpOffset
                }
                EbpfOpcode.BPF_LD -> pc += interpreter.executeLd(opcode, inst, this, pc)
                EbpfOpcode.BPF_LDX -> interpreter.executeLdx(opcode, inst, context)
                EbpfOpcode.BPF_STX -> interpreter.executeStx(opcode, inst, context)
            }

            if (classOp == EbpfOpcode.BPF_JMP && (opcode and 0xF0) == EbpfOpcode.BPF_EXIT) {
                traces.add(registers.copyOf())
                break
            }
        }

        return ExecutionTrace(this, context, traces)
    }
}

/**
 * Streaming eBPF programs as a kernel algebra Series.
 * Each element is a LoadedEbpfProgram from the manager.
 */
class EbpfProgramSeries(
    private val manager: EbpfProgramManager
) {
    /** Gets the current programs as a Series */
    val programs: Series<LoadedEbpfProgram>
        get() {
            val list = manager.listPrograms()
            return list.size j { index -> list[index] }
        }

    /** Filters programs by type */
    fun filterByType(progType: Int): Series<LoadedEbpfProgram> {
        val filtered = manager.listPrograms().filter { it.progType == progType }
        return filtered.size j { index -> filtered[index] }
    }

    /** Maps programs to their JIT-compiled bytecode (if available) */
    val jitCodeSeries: Series<ByteArray?>
        get() {
            val list = manager.listPrograms()
            return list.size j { index ->
                if (list[index].jitCompiled) {
                    EbpfJit().compile(list[index].program)
                } else null
            }
        }
}

/**
 * Streaming execution results as a Flow/Series integration.
 */
class EbpfStreamResults(
    private val executor: EbpfStreamExecutor
) {
    /** Executes on a Flow of contexts, producing a Flow of results */
    fun executeFlow(contexts: Flow<ByteArray>): Flow<Long> = executor.executeFlow(contexts)

    /** Executes on a ReceiveChannel of contexts */
    suspend fun executeChannel(
        contexts: ReceiveChannel<ByteArray>
    ): ReceiveChannel<Long> = contexts.produce {
        for (context in contexts) {
            send(executor.executeStream(sequenceOf(context)).first())
        }
    }
}

/**
 * Represents a sequence of eBPF execution results as a kernel Series.
 */
data class EbpfResultSeries(
    val results: List<Long>
) {
    override val size: Int get() = results.size
    operator fun get(index: Int): Long = results[index]

    /** Converts to kernel algebra Series */
    fun asSeries(): Series<Long> = size j { i -> results[i] }

    /** Maps results through a transformation (α projection) */
    fun <R> map(xform: (Long) -> R): Series<R> = asSeries().α(xform)

    /** Aggregates results (sum, avg, etc.) */
    fun sum(): Long = results.sum()
    fun average(): Double = if (results.isEmpty()) 0.0 else results.average()
}

/**
 * Program composition using kernel algebra joins.
 * Allows combining multiple programs into a pipeline.
 */
class EbpfPipeline private constructor(
    private val programs: List<EbpfProgram>
) {
    companion object {
        fun of(vararg programs: EbpfProgram): EbpfPipeline = EbpfPipeline(programs.toList())
        fun fromSeries(series: Series<EbpfProgram>): EbpfPipeline = EbpfPipeline(series.view.toList())
    }

    /** Executes pipeline sequentially, passing context through */
    fun execute(context: ByteArray, registry: EbpfHelperRegistry = EbpfHelperRegistry()): Long {
        var currentContext = context.copyOf()
        var lastResult = 0L
        for (program in programs) {
            val interpreter = EbpfInterpreter(program, registry)
            lastResult = interpreter.execute(currentContext)
        }
        return lastResult
    }

    /** Adds a program to the pipeline (returns new pipeline) */
    fun plus(program: EbpfProgram): EbpfPipeline = EbpfPipeline(programs + program)

    /** Number of programs in pipeline */
    val size: Int get() = programs.size
}

/**
 * Kernel algebra integration for eBPF maps.
 * Represents map contents as a Series for querying.
 */
class EbpfMapSeries<K, V>(
    private val map: EbpfMap,
    private val keySerializer: (K) -> ByteArray,
    private val valueDeserializer: (ByteArray) -> V
) {
    /** Looks up a value (extending Join for key->value) */
    operator fun get(key: K): V? {
        val bytes = map.lookup(keySerializer(key))
        return bytes?.let { valueDeserializer(it) }
    }

    /** Gets all entries as a Series of Join<K, V> (requires iteration support) */
    // Note: EbpfMap doesn't support iteration, so this is limited
    // For full support, would need to extend EbpfMap with entry iteration
}

/**
 * Extension to represent eBPF program loading as kernel algebra operations.
 */
class EbpfProgramCursor(
    private val manager: EbpfProgramManager
) {
    /** Gets all loaded programs as a cursor-like Series */
    val programs: Series<LoadedEbpfProgram>
        get() {
            val list = manager.listPrograms().sortedBy { it.id }
            return list.size j { i -> list[i] }
        }

    /** Projects to just program IDs (α projection) */
    val ids: Series<Int> = programs.α { it.id }

    /** Projects to program names */
    val names: Series<String> = programs.α { it.name }

    /** Filters verified programs */
    val verified: Series<LoadedEbpfProgram> = programs.view.filter { it.verified }.toSeries()

    /** Filters JIT-compiled programs */
    val jitCompiled: Series<LoadedEbpfProgram> = programs.view.filter { it.jitCompiled }.toSeries()
}