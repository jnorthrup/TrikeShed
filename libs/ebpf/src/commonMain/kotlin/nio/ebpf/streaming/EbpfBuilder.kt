package nio.ebpf.streaming

import nio.ebpf.algebra.EbpfInstruction
import nio.ebpf.algebra.EbpfHelper
import nio.ebpf.algebra.Reg

/** DSL builder for eBPF instructions. */
class EbpfBuilder {
    internal val instructions = mutableListOf<EbpfInstruction>()
    internal var nextPc = 0

    fun add(src: Reg, dst: Reg) = emit(EbpfInstruction.Add(nextPc++, src, dst))
    fun sub(src: Reg, dst: Reg) = emit(EbpfInstruction.Sub(nextPc++, src, dst))
    fun mul(src: Reg, dst: Reg) = emit(EbpfInstruction.Mul(nextPc++, src, dst))
    fun div(src: Reg, dst: Reg) = emit(EbpfInstruction.Div(nextPc++, src, dst))
    fun and(src: Reg, dst: Reg) = emit(EbpfInstruction.And(nextPc++, src, dst))
    fun or(src: Reg, dst: Reg) = emit(EbpfInstruction.Or(nextPc++, src, dst))
    fun xor(src: Reg, dst: Reg) = emit(EbpfInstruction.Xor(nextPc++, src, dst))
    fun lshift(src: Reg, dst: Reg) = emit(EbpfInstruction.LShift(nextPc++, src, dst))
    fun rshift(src: Reg, dst: Reg) = emit(EbpfInstruction.RShift(nextPc++, src, dst))
    fun movImm(imm: Int, dst: Reg) = emit(EbpfInstruction.MovImm(nextPc++, imm, dst))
    fun addImm(imm: Int, dst: Reg) = emit(EbpfInstruction.AddImm(nextPc++, imm, dst))
    fun subImm(imm: Int, dst: Reg) = emit(EbpfInstruction.SubImm(nextPc++, imm, dst))
    fun ldx(src: Reg, dst: Reg, offset: Short, size: nio.ebpf.algebra.BitWidth = nio.ebpf.algebra.BitWidth.B64) =
        emit(EbpfInstruction.LdX(nextPc++, src, dst, offset, size))
    fun stx(dst: Reg, src: Reg, offset: Short, size: nio.ebpf.algebra.BitWidth = nio.ebpf.algebra.BitWidth.B64) =
        emit(EbpfInstruction.StX(nextPc++, dst, src, offset, size))
    fun jmpExit() = emit(EbpfInstruction.JmpExit(nextPc++))
    fun jmpCall(helper: EbpfHelper) = emit(EbpfInstruction.JmpCall(nextPc++, helper))

    private fun emit(inst: EbpfInstruction): EbpfBuilder {
        instructions += inst
        return this
    }
}
