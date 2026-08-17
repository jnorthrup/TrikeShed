package borg.trikeshed.userspace.nio.ebpf.io

import borg.trikeshed.userspace.nio.ebpf.types.*

class EbpfBuilder {
    internal val instructions = mutableListOf<Long>()

    fun add(src: Reg, dst: Reg) = alu(0x0f, dst.index, src.index)
    fun sub(src: Reg, dst: Reg) = alu(0x1f, dst.index, src.index)
    fun mul(src: Reg, dst: Reg) = alu(0x2f, dst.index, src.index)
    fun div(src: Reg, dst: Reg) = alu(0x3f, dst.index, src.index)
    fun and(src: Reg, dst: Reg) = alu(0x4f, dst.index, src.index)
    fun or(src: Reg, dst: Reg) = alu(0xaf, dst.index, src.index)
    fun xor(src: Reg, dst: Reg) = alu(0x8f, dst.index, src.index)

    fun movImm(imm: Int, dst: Reg) {
        val op = 0xb7
        instructions += (op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)
    }

    fun addImm(imm: Int, dst: Reg) {
        val op = 0x07
        instructions += (op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)
    }

    fun subImm(imm: Int, dst: Reg) {
        val op = 0x17
        instructions += (op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)
    }

    fun jmpExit() {
        instructions += EbpfInstruction.exit().raw
    }

    fun jmpCall(helper: EbpfHelper) {
        instructions += EbpfInstruction.call(helper.id).raw
    }

    private fun alu(op: Int, dst: Int, src: Int) {
        instructions += (op.toLong() and 0xFF) or
            (((dst and 0x0F) or ((src and 0x0F) shl 4)).toLong() and 0xFF shl 8)
    }
}
