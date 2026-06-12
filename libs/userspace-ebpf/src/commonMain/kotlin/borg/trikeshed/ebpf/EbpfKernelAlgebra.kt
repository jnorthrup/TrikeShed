package borg.trikeshed.ebpf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.send
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
        return this.instructions.size j { index -> this.instructions[index] }
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
     * Uses the public interpreter API.
     */
    fun EbpfProgram.traceExecute(
        context: ByteArray,
        registry: EbpfHelperRegistry = EbpfHelperRegistry()
    ): ExecutionTrace {
        val interpreter = EbpfInterpreter(this, registry)
        val traces = mutableListOf<LongArray>()

        // We need to trace by stepping through manually
        var pc = 0
        val registers = LongArray(11) { 0L }

        while (pc < program.instructions.size) {
            traces.add(registers.copyOf())
            val inst = EbpfInstruction(program.instructions[pc])
            val opcode = inst.opcode
            val classOp = opcode and EbpfOpcode.BPF_CLASS_MASK
            pc++

            when (classOp) {
                EbpfOpcode.BPF_ALU64 -> {
                    val dst = inst.dstReg
                    val src = inst.srcReg
                    val isK = (opcode and EbpfOpcode.BPF_X) == 0
                    val aluOp = opcode and 0xF0
                    val operand = if (isK) inst.imm.toLong() else registers[src]

                    when (aluOp) {
                        EbpfOpcode.BPF_ADD -> registers[dst] += operand
                        EbpfOpcode.BPF_SUB -> registers[dst] -= operand
                        EbpfOpcode.BPF_MUL -> registers[dst] *= operand
                        EbpfOpcode.BPF_DIV -> if (operand != 0L) registers[dst] /= operand else registers[dst] = 0
                        EbpfOpcode.BPF_OR -> registers[dst] = registers[dst] or operand
                        EbpfOpcode.BPF_AND -> registers[dst] = registers[dst] and operand
                        EbpfOpcode.BPF_LSH -> registers[dst] = registers[dst] shl operand.toInt()
                        EbpfOpcode.BPF_RSH -> registers[dst] = registers[dst] ushr operand.toInt()
                        EbpfOpcode.BPF_NEG -> registers[dst] = -registers[dst]
                        EbpfOpcode.BPF_MOD -> if (operand != 0L) registers[dst] %= operand else registers[dst] = registers[dst]
                        EbpfOpcode.BPF_XOR -> registers[dst] = registers[dst] xor operand
                        EbpfOpcode.BPF_MOV -> registers[dst] = operand
                        EbpfOpcode.BPF_ARSH -> registers[dst] = registers[dst] shr operand.toInt()
                    }
                }
                EbpfOpcode.BPF_ALU -> {
                    val dst = inst.dstReg
                    val src = inst.srcReg
                    val isK = (opcode and EbpfOpcode.BPF_X) == 0
                    val aluOp = opcode and 0xF0
                    val operand = if (isK) inst.imm else registers[src].toInt()
                    var dstVal = registers[dst].toInt()

                    when (aluOp) {
                        EbpfOpcode.BPF_ADD -> dstVal += operand
                        EbpfOpcode.BPF_SUB -> dstVal -= operand
                        EbpfOpcode.BPF_MUL -> dstVal *= operand
                        EbpfOpcode.BPF_DIV -> if (operand != 0) dstVal /= operand else dstVal = 0
                        EbpfOpcode.BPF_OR -> dstVal = dstVal or operand
                        EbpfOpcode.BPF_AND -> dstVal = dstVal and operand
                        EbpfOpcode.BPF_LSH -> dstVal = dstVal shl operand
                        EbpfOpcode.BPF_RSH -> dstVal = dstVal ushr operand
                        EbpfOpcode.BPF_NEG -> dstVal = -dstVal
                        EbpfOpcode.BPF_MOD -> if (operand != 0) dstVal %= operand else dstVal = dstVal
                        EbpfOpcode.BPF_XOR -> dstVal = dstVal xor operand
                        EbpfOpcode.BPF_MOV -> dstVal = operand
                        EbpfOpcode.BPF_ARSH -> dstVal = dstVal shr operand
                    }
                    registers[dst] = dstVal.toLong() and 0xFFFFFFFFL
                }
                EbpfOpcode.BPF_JMP -> {
                    val jmpOp = opcode and 0xF0
                    val isK = (opcode and EbpfOpcode.BPF_X) == 0
                    val dst = inst.dstReg
                    val src = inst.srcReg
                    val operand = if (isK) inst.imm.toLong() else registers[src]
                    val dstVal = registers[dst]

                    val jump = when (jmpOp) {
                        EbpfOpcode.BPF_JA -> true
                        EbpfOpcode.BPF_JEQ -> dstVal == operand
                        EbpfOpcode.BPF_JGT -> dstVal.toULong() > operand.toULong()
                        EbpfOpcode.BPF_JGE -> dstVal.toULong() >= operand.toULong()
                        EbpfOpcode.BPF_JSET -> (dstVal and operand) != 0L
                        EbpfOpcode.BPF_JNE -> dstVal != operand
                        EbpfOpcode.BPF_JSGT -> dstVal > operand
                        EbpfOpcode.BPF_JSGE -> dstVal >= operand
                        EbpfOpcode.BPF_CALL -> {
                            registers[0] = registry.callHelper(inst.imm, registers, context)
                            false
                        }
                        EbpfOpcode.BPF_EXIT -> false
                        EbpfOpcode.BPF_JLT -> dstVal.toULong() < operand.toULong()
                        EbpfOpcode.BPF_JLE -> dstVal.toULong() <= operand.toULong()
                        EbpfOpcode.BPF_JSLT -> dstVal < operand
                        EbpfOpcode.BPF_JSLE -> dstVal <= operand
                        else -> false
                    }

                    if (jump) pc += inst.offset.toInt()
                }
                EbpfOpcode.BPF_LD -> {
                    val sizeLd = opcode and 0x18
                    val mode = opcode and 0xE0

                    if (sizeLd == EbpfOpcode.BPF_DW && mode == EbpfOpcode.BPF_IMM) {
                        // 64-bit immediate load. The second half of the immediate is in the next instruction's imm field.
                        if (pc < program.instructions.size) {
                            val nextInst = EbpfInstruction(program.instructions[pc])
                            val lower32 = inst.imm.toLong() and 0xFFFFFFFFL
                            val upper32 = nextInst.imm.toLong() and 0xFFFFFFFFL
                            registers[inst.dstReg] = lower32 or (upper32 shl 32)
                            pc++
                        }
                    }
                }
                EbpfOpcode.BPF_LDX -> {
                    val dst = inst.dstReg
                    val src = inst.srcReg
                    val sizeLdx = opcode and 0x18
                    val addr = registers[src] + inst.offset

                    if (addr >= 0) {
                        when (sizeLdx) {
                            EbpfOpcode.BPF_B -> if (addr < context.size) registers[dst] = (context[addr.toInt()].toLong() and 0xFF)
                            EbpfOpcode.BPF_H -> if (addr + 1 < context.size) registers[dst] = (
                                (context[addr.toInt()].toLong() and 0xFF) or
                                ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8)
                            )
                            EbpfOpcode.BPF_W -> if (addr + 3 < context.size) registers[dst] = (
                                (context[addr.toInt()].toLong() and 0xFF) or
                                ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8) or
                                ((context[(addr + 2).toInt()].toLong() and 0xFF) shl 16) or
                                ((context[(addr + 3).toInt()].toLong() and 0xFF) shl 24)
                            )
                            EbpfOpcode.BPF_DW -> if (addr + 7 < context.size) registers[dst] = (
                                (context[addr.toInt()].toLong() and 0xFF) or
                                ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8) or
                                ((context[(addr + 2).toInt()].toLong() and 0xFF) shl 16) or
                                ((context[(addr + 3).toInt()].toLong() and 0xFF) shl 24) or
                                ((context[(addr + 4).toInt()].toLong() and 0xFF) shl 32) or
                                ((context[(addr + 5).toInt()].toLong() and 0xFF) shl 40) or
                                ((context[(addr + 6).toInt()].toLong() and 0xFF) shl 48) or
                                ((context[(addr + 7).toInt()].toLong() and 0xFF) shl 56)
                            )
                        }
                    }
                }
                EbpfOpcode.BPF_STX -> {
                    val dst = inst.dstReg
                    val src = inst.srcReg
                    val sizeStx = opcode and 0x18
                    val addr = registers[dst] + inst.offset

                    if (addr >= 0) {
                        val v = registers[src]
                        when (sizeStx) {
                            EbpfOpcode.BPF_B -> if (addr < context.size) {
                                context[addr.toInt()] = (v and 0xFF).toByte()
                            }
                            EbpfOpcode.BPF_H -> if (addr + 1 < context.size) {
                                context[addr.toInt()] = (v and 0xFF).toByte()
                                context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                            }
                            EbpfOpcode.BPF_W -> if (addr + 3 < context.size) {
                                context[addr.toInt()] = (v and 0xFF).toByte()
                                context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                                context[(addr + 2).toInt()] = ((v ushr 16) and 0xFF).toByte()
                                context[(addr + 3).toInt()] = ((v ushr 24) and 0xFF).toByte()
                            }
                            EbpfOpcode.BPF_DW -> if (addr + 7 < context.size) {
                                context[addr.toInt()] = (v and 0xFF).toByte()
                                context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                                context[(addr + 2).toInt()] = ((v ushr 16) and 0xFF).toByte()
                                context[(addr + 3).toInt()] = ((v ushr 24) and 0xFF).toByte()
                                context[(addr + 4).toInt()] = ((v ushr 32) and 0xFF).toByte()
                                context[(addr + 5).toInt()] = ((v ushr 40) and 0xFF).toByte()
                                context[(addr + 6).toInt()] = ((v ushr 48) and 0xFF).toByte()
                                context[(addr + 7).toInt()] = ((v ushr 56) and 0xFF).toByte()
                            }
                        }
                    }
                }
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
    suspend fun <E> executeChannel(
        contexts: ReceiveChannel<ByteArray>
    ): ReceiveChannel<Long> = CoroutineScope(Job()).produce {
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
    val size: Int get() = results.size
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
        for (program in this.programs) {
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