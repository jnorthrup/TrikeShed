package nio.ebpf.streaming

import nio.ebpf.algebra.EbpfError
import nio.ebpf.algebra.EbpfInstruction
import nio.ebpf.algebra.EbpfProgram
import nio.ebpf.algebra.EbpfProgramType
import nio.ebpf.algebra.Reg
import nio.ebpf.jit.JitCode
import nio.ebpf.jit.JitFunction
import nio.ebpf.jit.X86_64Jit
import nio.ebpf.runNative
import nio.ebpf.verifier.VerifierResult
import nio.ebpf.verifier.verifyProgram

/**
 * Streaming JIT engine — incrementally build eBPF programs, verify, compile,
 * and execute them in userspace.
 *
 * Design: the engine owns the program under construction. Each instruction
 * append mutates the builder. Once `compile()` is called, the program is
 * sealed and the JIT emits native code.
 *
 * This provides:
 * - Incremental program construction (append instructions one-by-one)
 * - Streaming verification (check at append time or verify-at-end)
 * - Hot-swappable JIT backends (x86_64, ARM64)
 * - Kernel bypass via io_uring (linuxMain)
 */
class EbpfJitEngine(
    val name: String,
    val programType: EbpfProgramType = EbpfProgramType.Unspec,
    val license: String = "GPL",
) {
    private val instructions = mutableListOf<EbpfInstruction>()
    private var compiled: JitCode? = null
    private var verified: VerifierResult? = null

    /** Append an instruction to the program. Returns this for chaining. */
    fun emit(inst: EbpfInstruction): EbpfJitEngine {
        instructions += inst
        compiled = null // invalidate compilation
        return this
    }

    /** Build program from a lambda builder DSL. */
    fun build(block: EbpfBuilder.() -> Unit): EbpfJitEngine {
        val builder = EbpfBuilder()
        builder.block()
        instructions += builder.instructions
        return this
    }

    /** Verify the current program. Throws on failure. */
    fun verify(): VerifierResult {
        val program = toEbpfProgram()
        val result = verifyProgram(program)
        this.verified = result
        return result
    }

    /** Compile to native code. Must be verified first. */
    fun compile(): JitCode {
        val v = verified ?: verify()
        if (v is VerifierResult.Failure) {
            throw IllegalStateException("compile failed: ${v.reason}")
        }
        compiled = X86_64Jit.compile(toEbpfProgram())
        return compiled!!
    }

    /**
     * Execute the program with given arguments.
     * The args correspond to R1-R5 (arg1-arg5).
     * Returns the value in R0 (return value).
     */
    fun execute(vararg args: Long): Long {
        val code = compiled ?: compile()
        return executeNative(code, args)
    }

    /** Convert to a sealed EbpfProgram. */
    fun toEbpfProgram(): EbpfProgram {
        return EbpfProgram(name, instructions.toList(), programType, license)
    }

    private fun executeNative(code: JitCode, args: LongArray): Long =
        runNative(code, args)
}
