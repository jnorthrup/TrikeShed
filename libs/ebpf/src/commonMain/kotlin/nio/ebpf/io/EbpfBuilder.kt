package nio.ebpf.io

import nio.ebpf.types.*

class EbpfBuilder {
    internal val instructions = mutableListOf<Long>()

    fun add(src: Reg, dst: Reg) =
        alu(0x0f, dst.index, src.index)
    fun sub(src: Reg, dst: Reg) =
        alu(0x1f, dst.index, src.index)
    fun mul(src: Reg, dst: Reg) =
        alu(0x2f, dst.index, src.index)
    fun div(src: Reg, dst: Reg) =
        alu(0x3f, dst.index, src.index)
    fun and(src: Reg, dst: Reg) =
        alu(0x4f, dst.index, src.index)
    fun or(src: Reg, dst: Reg) =
        alu(0xaf, dst.index, src.index)
    fun xor(src: Reg, dst: Reg) =
        alu(0x8f, dst.index, src.index)
    fun movImm(imm: Int, dst: Reg) =
        movImmImm(imm, dst)
    fun addImm(imm: Int, dst: Reg) =
        addImmOp(imm, dst)
    fun subImm(imm: Int, dst: Reg) =
        subImmImm(imm, dst)
    fun jmpExit() =
        instructions += EbpfInstruction.exit().raw
    fun jmpCall(helper: EbpfHelper) =
        instructions += EbpfInstruction.call(helper.id).raw

    private fun addImmOp(imm: Int, dst: Reg) {
        val op: Byte = 0x07
        instructions += EbpfInstruction((op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)).raw
    }

    private fun subImmImm(imm: Int, dst: Reg) {
        val op: Byte = 0x17
        instructions += EbpfInstruction((op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)).raw
    }

    private fun movImmImm(imm: Int, dst: Reg) {
        val op: Byte = 0xb7
        instructions += EbpfInstruction((op.toLong() and 0xFF) or
            ((dst.index.toLong() and 0xFF) shl 8) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)).raw
    }

    private fun alu(op: Int, dst: Int, src: Int) {
        val opcode = op.toByte()
        instructions += EbpfInstruction(
            (opcode.toLong() and 0xFF) or
            ((dst.toLong() and 0xFF).toInt() or ((src.toLong() and 0xFF).toInt() shl 4)).toLong() shl 8
        ).raw
    }
}
