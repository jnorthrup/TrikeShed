package nio.ebpf.algebra

/**
 * Pure algebra for eBPF instruction encoding. All instructions are immutable value types.
 *
 * PRELOAD.md dominance: this file IS the source of truth for eBPF in TrikeShed.
 * Any extension must preserve the sealed hierarchy.
 */

sealed class EbpfInstruction {
    abstract val pc: Int
    abstract val src: Reg?
    abstract val dst: Reg?
    abstract val immVal: Int?
    abstract val offsetVal: Int?

    data class Add(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Sub(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Mul(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Div(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Or(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class And(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class LShift(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class RShift(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Neg(override val pc: Int, override val dst: Reg,
        override val src: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Mod(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Xor(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class Move(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class ArithRShift(override val pc: Int, override val src: Reg, override val dst: Reg,
        override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()

    data class AddImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class SubImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class MulImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class DivImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class OrImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class AndImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class LShiftImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class RShiftImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class ModImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class XorImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class MovImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class ArithRShiftImm(override val pc: Int, val imm: Int, override val dst: Reg,
        override val src: Reg? = null, override val offsetVal: Int? = null) : EbpfInstruction() { override val immVal: Int get() = imm }
    data class Endian(override val pc: Int, val toBigEndian: Boolean, val size: BitWidth, override val dst: Reg,
        override val src: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()

    data class LdImm64(override val pc: Int, val imm64: Long, override val dst: Reg,
        override val src: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class LdX(override val pc: Int, override val src: Reg, override val dst: Reg,
        val offset: Short, val size: BitWidth, override val immVal: Int? = null, override val offsetVal: Int = offset.toInt()) : EbpfInstruction()
    data class St(override val pc: Int, override val src: Reg, val offset: Short, val size: BitWidth,
        override val dst: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int = offset.toInt()) : EbpfInstruction()
    data class StX(override val pc: Int, override val dst: Reg, override val src: Reg,
        val offset: Short, val size: BitWidth, override val immVal: Int? = null, override val offsetVal: Int = offset.toInt()) : EbpfInstruction()
    data class AtomicXAdd(override val pc: Int, override val dst: Reg, override val src: Reg,
        val offset: Short, val size: BitWidth, override val immVal: Int? = null, override val offsetVal: Int = offset.toInt()) : EbpfInstruction()

    data class Jmp(override val pc: Int, val offset: Int,
        override val src: Reg? = null, override val dst: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpEq(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpGt(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpGe(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpSet(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpNe(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpSgt(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpSge(override val pc: Int, override val src: Reg, override val dst: Reg, val offset: Int,
        override val immVal: Int? = null, override val offsetVal: Int = offset) : EbpfInstruction()
    data class JmpCall(override val pc: Int, val helper: EbpfHelper,
        override val src: Reg? = null, override val dst: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()
    data class JmpExit(override val pc: Int,
        override val src: Reg? = null, override val dst: Reg? = null, override val immVal: Int? = null, override val offsetVal: Int? = null) : EbpfInstruction()

    data class JmpEqImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }
    data class JmpNeImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }
    data class JmpSgtImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }
    data class JmpSgeImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }
    data class JgtImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }
    data class GeImm(override val pc: Int, val imm: Int, override val dst: Reg, val offset: Int,
        override val src: Reg? = null) : EbpfInstruction() { override val immVal: Int get() = imm; override val offsetVal: Int get() = offset }

    fun regs(): List<Reg> = listOfNotNull(src, dst).distinct()
}

enum class BitWidth(val bytes: Int) { B8(1), B16(2), B32(4), B64(8) }

data class Reg(val index: Int) {
    init { require(index in 0..15) { "eBPF register index must be 0..15, got $index" } }
    companion object {
        val R0 = Reg(0); val R1 = Reg(1); val R2 = Reg(2); val R3 = Reg(3); val R4 = Reg(4)
        val R5 = Reg(5); val R6 = Reg(6); val R7 = Reg(7); val R8 = Reg(8); val R9 = Reg(9)
        val R10 = Reg(10); val FP = Reg(10); val SP = Reg(11)
        val ALL_CALLEE_SAVED = listOf(R6, R7, R8, R9)
        val ALL_ARGS = listOf(R1, R2, R3, R4, R5)
    }
}

data class EbpfProgram(
    val name: String,
    val instructions: List<EbpfInstruction>,
    val programType: EbpfProgramType = EbpfProgramType.Unspec,
    val license: String = "GPL",
) { fun size(): Int = instructions.size }

enum class EbpfProgramType(val rawId: Int) {
    Unspec(0), SocketFilter(1), Kprobe(6), SchedAct(11),
    Tracepoint(12), PerfEvent(15), RawTracepoint(24), Xdp(27)
}

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
