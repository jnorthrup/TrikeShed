package nio.ebpf.io

import nio.ebpf.types.*
import nio.ebpf.engine.JitCode
import nio.ebpf.engine.X86_64Jit
import nio.ebpf.verifier.VerifierResult
import nio.ebpf.verifier.verifyProgram
import nio.ebpf.runNative

class EbpfJitEngine(
    val name: String,
    val programType: EbpfProgramType = EbpfProgramType.Unspec,
    val license: String = "GPL",
) {
    private val instructions = mutableListOf<Long>()
    private var compiled: JitCode? = null
    private var verified: VerifierResult? = null

    fun emit(raw: Long): EbpfJitEngine { instructions += raw; compiled = null; return this }
    fun emit(inst: EbpfInstruction): EbpfJitEngine { instructions += inst.raw; compiled = null; return this }
    fun build(block: EbpfBuilder.() -> Unit): EbpfJitEngine {
        val b = EbpfBuilder(); b.block(); instructions += b.instructions; return this
    }

    fun verify(): VerifierResult {
        val program = EbpfProgram(name, instructions.toLongArray(), programType, license)
        val r = verifyProgram(program); this.verified = r; return r
    }

    fun compile(): JitCode {
        val v = verified ?: verify()
        if (v is VerifierResult.Failure) throw IllegalStateException("compile failed: ${v.reason}")
        val program = EbpfProgram(name, instructions.toLongArray(), programType, license)
        compiled = X86_64Jit.compile(program); return compiled!!
    }

    fun execute(vararg args: Long): Long {
        val code = compiled ?: compile()
        return runNative(code, args)
    }
}
