package borg.trikeshed.ebpf

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Streaming eBPF program loader that can load programs from various stream sources.
 * Supports loading raw eBPF bytecode, ELF sections, and incremental streaming.
 */
class EbpfProgramLoader {

    companion object {
        /** Magic number for eBPF ELF section identification */
        const val EBPF_ELF_MAGIC = 0x12345678 // Placeholder - real ELF magic
    }

    /**
     * Loads a single eBPF program from a byte array containing raw instructions.
     * Each instruction is 8 bytes (little-endian).
     */
    fun loadFromBytes(bytes: ByteArray): EbpfProgram {
        require(bytes.size % 8 == 0) { "Byte array size must be multiple of 8 (instruction size)" }
        val count = bytes.size / 8
        val instructions = LongArray(count)
        for (i in 0 until count) {
            var inst = 0L
            for (j in 0 until 8) {
                val b = bytes[i * 8 + j].toLong() and 0xFF
                inst = inst or (b shl (j * 8))
            }
            instructions[i] = inst
        }
        return EbpfProgram(instructions)
    }

    /**
     * Loads an eBPF program from a sequence of 64-bit integers (instruction raw values).
     */
    fun loadFromSequence(sequence: Sequence<Long>): EbpfProgram {
        val list = sequence.toList()
        return EbpfProgram(list.toLongArray())
    }

    /**
     * Loads eBPF programs from a Flow of byte chunks (streaming).
     * Accumulates chunks until we have complete instructions.
     */
    suspend fun loadFromByteFlow(
        flow: Flow<ByteArray>,
        chunkHandler: (ByteArray) -> Unit = { }
    ): List<EbpfProgram> {
        val buffer = ByteArrayOutputStream()
        val programs = mutableListOf<EbpfProgram>()

        flow.collect { chunk ->
            chunkHandler(chunk)
            buffer.write(chunk)

            // Process complete 8-byte instructions
            while (buffer.size() >= 8) {
                val instBytes = ByteArray(8)
                buffer.read(instBytes, 0, 8)
                val program = loadFromBytes(instBytes)
                programs.add(program)
            }
        }

        require(buffer.size() == 0) { "Incomplete instruction at end of stream (${buffer.size()} bytes remaining)" }
        return programs
    }

    /**
     * Loads eBPF programs from a ReceiveChannel of byte chunks.
     * Each chunk can be any size; instructions are 8 bytes each.
     */
    suspend fun loadFromByteChannel(
        channel: ReceiveChannel<ByteArray>,
        chunkHandler: (ByteArray) -> Unit = { }
    ): List<EbpfProgram> {
        val buffer = ByteArrayOutputStream()
        val programs = mutableListOf<EbpfProgram>()

        channel.consumeEach { chunk ->
            chunkHandler(chunk)
            buffer.write(chunk)

            while (buffer.size() >= 8) {
                val instBytes = ByteArray(8)
                buffer.read(instBytes, 0, 8)
                val program = loadFromBytes(instBytes)
                programs.add(program)
            }
        }

        require(buffer.size() == 0) { "Incomplete instruction at end of stream (${buffer.size()} bytes remaining)" }
        return programs
    }

    /**
     * Parses a simple text-based eBPF assembly format and returns the program.
     * Format: one instruction per line as "opcode dst src offset imm"
     * Example: "0xb7 0 0 0 10"  (MOV64_IMM R0, 10)
     */
    fun loadFromAssembly(text: String): EbpfProgram {
        val instructions = mutableListOf<Long>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return@forEach

            val parts = trimmed.split("\\s+".toRegex())
            require(parts.size == 5) { "Assembly line must have 5 parts: opcode dst src offset imm" }

            val opcode = parseHexOrDec(parts[0]).toInt()
            val dstReg = parseHexOrDec(parts[1]).toInt()
            val srcReg = parseHexOrDec(parts[2]).toInt()
            val offset = parseHexOrDec(parts[3]).toShort()
            val imm = parseHexOrDec(parts[4]).toInt()

            val inst = EbpfInstruction.pack(opcode, dstReg, srcReg, offset, imm)
            instructions.add(inst.raw)
        }
        return EbpfProgram(instructions.toLongArray())
    }

    private fun parseHexOrDec(str: String): Long {
        return if (str.startsWith("0x") || str.startsWith("0X")) {
            str.substring(2).toLong(16)
        } else {
            str.toLong()
        }
    }
}

/** Simple byte array output stream for buffering. */
class ByteArrayOutputStream {
    private var buffer = ByteArray(64)
    private var size = 0

    fun size(): Int = size

    fun write(data: ByteArray) {
        if (size + data.size > buffer.size) {
            val newBuffer = ByteArray(maxOf(buffer.size * 2, size + data.size))
            buffer.copyInto(newBuffer, 0, size)
            buffer = newBuffer
        }
        data.copyInto(buffer, size)
        size += data.size
    }

    fun read(dest: ByteArray, offset: Int, length: Int) {
        buffer.copyInto(dest, offset, 0, length)
        // Shift remaining data
        if (length < size) {
            buffer.copyInto(buffer, 0, length, size)
        }
        size -= length
    }
}

/**
 * Streaming executor that can execute eBPF programs continuously
 * with a stream of context updates.
 */
class EbpfStreamExecutor(
    private val program: EbpfProgram,
    private val registry: EbpfHelperRegistry = EbpfHelperRegistry()
) {

    private val interpreter = EbpfInterpreter(program, registry)

    /**
     * Executes the program on a stream of contexts, producing a stream of results.
     * Each context is a ByteArray representing the input data.
     */
    fun executeStream(contexts: Sequence<ByteArray>): Sequence<Long> {
        return contexts.map { context ->
            interpreter.execute(context)
        }
    }

    /**
     * Executes the program on a Flow of contexts, producing a Flow of results.
     */
    fun executeFlow(contexts: Flow<ByteArray>): Flow<Long> = contexts.map { context ->
        interpreter.execute(context)
    }
}

/**
 * Represents a loaded eBPF program with metadata for dynamic management.
 */
data class LoadedEbpfProgram(
    val id: Int,
    val program: EbpfProgram,
    val name: String,
    val progType: Int,
    val license: String,
    val jitCompiled: Boolean = false,
    val verified: Boolean = false
)

/**
 * Program manager for dynamic loading, unloading, and hot-replacement of eBPF programs.
 * Provides thread-safe program lifecycle management.
 */
class EbpfProgramManager {
    private val programs = mutableMapOf<Int, LoadedEbpfProgram>()
    private var nextId = 1
    private val lock = Any()

    /** Executes action under lock. */
    @Suppress("UNUSED_PARAMETER")
    private inline fun <T> locked(action: () -> T): T {
        synchronized(lock) {
            return action()
        }
    }

    /** Loads a new program and returns its assigned ID. */
    fun loadProgram(
        program: EbpfProgram,
        name: String = "prog_$nextId",
        progType: Int = BpfProgType.BPF_PROG_TYPE_SOCKET_FILTER,
        license: String = "GPL",
        verify: Boolean = true,
        jitCompile: Boolean = false
    ): Int {
        return locked {
            val id = nextId++
            var verified = false
            if (verify) {
                try {
                    EbpfVerifier(program).verify()
                    verified = true
                } catch (e: EbpfVerifierError) {
                    throw IllegalArgumentException("Program verification failed: ${e.message}", e)
                }
            }
            val loaded = LoadedEbpfProgram(
                id = id,
                program = program,
                name = name,
                progType = progType,
                license = license,
                jitCompiled = jitCompile,
                verified = verified
            )
            programs[id] = loaded
            id
        }
    }

    /** Loads a program from bytes (streaming-friendly). */
    fun loadProgramFromBytes(
        bytes: ByteArray,
        name: String,
        progType: Int = BpfProgType.BPF_PROG_TYPE_SOCKET_FILTER,
        license: String = "GPL",
        verify: Boolean = true,
        jitCompile: Boolean = false
    ): Int {
        val loader = EbpfProgramLoader()
        val program = loader.loadFromBytes(bytes)
        return loadProgram(program, name, progType, license, verify, jitCompile)
    }

    /** Unloads a program by ID. */
    fun unloadProgram(id: Int): Boolean {
        return locked {
            programs.remove(id) != null
        }
    }

    /** Gets a loaded program by ID. */
    fun getProgram(id: Int): LoadedEbpfProgram? {
        return locked {
            programs[id]
        }
    }

    /** Replaces a program atomically (hot reload). */
    fun replaceProgram(
        id: Int,
        newProgram: EbpfProgram,
        verify: Boolean = true,
        jitCompile: Boolean = false
    ): Boolean {
        return locked {
            val existing = programs[id] ?: return@locked false
            var verified = false
            if (verify) {
                try {
                    EbpfVerifier(newProgram).verify()
                    verified = true
                } catch (e: EbpfVerifierError) {
                    throw IllegalArgumentException("New program verification failed: ${e.message}", e)
                }
            }
            programs[id] = existing.copy(
                program = newProgram,
                verified = verified,
                jitCompiled = jitCompile
            )
            true
        }
    }

    /** Lists all loaded program IDs. */
    fun listPrograms(): List<LoadedEbpfProgram> {
        return locked {
            programs.values.toList()
        }
    }

    /** Gets the number of loaded programs. */
    fun size(): Int {
        return locked {
            programs.size
        }
    }

    /** Clears all loaded programs. */
    fun clear() {
        locked {
            programs.clear()
        }
    }
}

/**
 * Program array for eBPF tail calls (BPF_PROG_ARRAY map equivalent).
 * Allows dynamic dispatch to other programs by index.
 */
class EbpfProgramArray private constructor(
    private val programs: MutableMap<Int, EbpfProgram>,
    private val manager: EbpfProgramManager
) {
    companion object {
        fun create(manager: EbpfProgramManager): EbpfProgramArray {
            return EbpfProgramArray(mutableMapOf(), manager)
        }
    }

    /** Sets a program at the given index. */
    fun set(index: Int, programId: Int): Boolean {
        return manager.getProgram(programId)?.let { loaded ->
            programs[index] = loaded.program
            true
        } ?: false
    }

    /** Gets a program at the given index. */
    fun get(index: Int): EbpfProgram? {
        return programs[index]
    }

    /** Removes a program at the given index. */
    fun remove(index: Int): Boolean {
        return programs.remove(index) != null
    }

    /** Gets all indices with programs. */
    fun indices(): Set<Int> = programs.keys
}

/**
 * Helper to register program array operations in the helper registry.
 */
object EbpfProgramArrayHelpers {
    const val BPF_FUNC_tail_call = 12 // Standard BPF helper ID for tail calls

    fun register(registry: EbpfHelperRegistry, programArray: EbpfProgramArray) {
        registry.registerHelper(BPF_FUNC_tail_call) { regs, context ->
            // R1 = program array map pointer (we use 0 as our array ID)
            // R2 = index
            // R3 = context pointer (ignored, we pass the same context)
            val arrayId = regs[1]
            val index = regs[2].toInt()

            if (arrayId == 0L) {
                val targetProgram = programArray.get(index)
                if (targetProgram != null) {
                    // In a real implementation, this would tail-call into the target program
                    // For our interpreter, we execute it and return the result
                    val interpreter = EbpfInterpreter(targetProgram)
                    val result = interpreter.execute(context)
                    regs[0] = result // Return value in R0
                    return@registerHelper 0L // Success
                }
            }
            -1L // Error: program not found
        }
    }
}