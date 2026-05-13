package nio.ebpf.types

/** eBPF register — zero overhead wrapper around index. */
@JvmInline
value class Reg(val index: Int) {
    init { require(index in 0..15) { "eBPF register index must be 0..15, got $index" } }
    companion object {
        val R0 = Reg(0); val R1 = Reg(1); val R2 = Reg(2); val R3 = Reg(3); val R4 = Reg(4)
        val R5 = Reg(5); val R6 = Reg(6); val R7 = Reg(7); val R8 = Reg(8); val R9 = Reg(9)
        val R10 = Reg(10); val FP = Reg(10); val SP = Reg(11)
        val ALL_CALLEE_SAVED = listOf(R6, R7, R8, R9)
        val ALL_ARGS = listOf(R1, R2, R3, R4, R5)
    }
}

/** Memory access size. */
enum class BitWidth(val bytes: Int) { B8(1), B16(2), B32(4), B64(8) }

/** eBPF helper functions. */
enum class EbpfHelper(val id: Int) {
    Unspec(0), GetSmpProcId(1), KtimeGetNs(5), TracePrintk(6),
    GetPrandomU32(7), GetCgroupClassid(8), SkbVlanPush(9), SkbVlanPop(10),
    GetSocketCookie(11), MapLookupElem(100),
}

/** Program category. */
enum class EbpfProgramType(val rawId: Int) {
    Unspec(0), SocketFilter(1), Kprobe(6), SchedAct(11),
    Tracepoint(12), PerfEvent(15), RawTracepoint(24), Xdp(27)
}

/**
 * SoA-backed eBPF program — single LongArray replaces List<dataclass>.
 *
 * Memory layout:
 *   program[]: LongArray — direct wire format, no indirection
 *   name/license: metadata strings (program-level, not per-instruction)
 *
 * Supports lazy offheap: swap LongArray for memory-mapped ByteBuffer at the boundary.
 * Network-endian conversion applied on network send / receive.
 */
class EbpfProgram(
    val name: String,
    val instructions: LongArray,
    val programType: EbpfProgramType = EbpfProgramType.Unspec,
    val license: String = "GPL",
) {
    constructor(name: String, instructions: List<EbpfInstruction>,
        programType: EbpfProgramType = EbpfProgramType.Unspec, license: String = "GPL")
        : this(name, instructions.toLongArray(), programType, license)

    inline fun forEach(block: (Int, EbpfInstruction) -> Unit) {
        for (i in instructions.indices) {
            if (i < instructions.size) block(i, EbpfInstruction(instructions[i]))
        }
    }

    fun size(): Int = instructions.size

    operator fun get(i: Int): EbpfInstruction {
        require(i in instructions.indices) { "index $i out of bounds [0..${instructions.size})" }
        return EbpfInstruction(instructions[i])
    }
}

/** Network-endian expected value. */
expect val BEBPF_ORDER: Boolean

/** Error types. */
sealed class EbpfError {
    data class VerificationFailed(val reason: String) : EbpfError()
    data class JitCompilationFailed(val reason: String) : EbpfError()
    data class RuntimeError(val reason: String) : EbpfError()
    data class InvalidInstruction(val pc: Int, val message: String) : EbpfError()
}

sealed class EbpfResult {
    sealed class Sealed {
        data class Success(val program: EbpfProgram) : Sealed()
        data class Error(val error: EbpfError) : Sealed()
    }
}
