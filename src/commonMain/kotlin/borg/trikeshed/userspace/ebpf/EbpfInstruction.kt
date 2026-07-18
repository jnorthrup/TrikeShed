package borg.trikeshed.userspace.ebpf

import kotlin.jvm.JvmInline

/**
 * eBPF Instruction packed into a single 64-bit Long (8 bytes).
 *
 * Layout:
 * byte 0: opcode (8 bits)
 * byte 1: registers (dst_reg: 4 bits, src_reg: 4 bits)
 * bytes 2-3: offset (16 bits, signed)
 * bytes 4-7: imm (32 bits, signed)
 */
@JvmInline
value class EbpfInstruction(val raw: Long) {
    val opcode: Int get() = (raw and 0xFF).toInt()
    val dstReg: Int get() = ((raw ushr 8) and 0x0F).toInt()
    val srcReg: Int get() = ((raw ushr 12) and 0x0F).toInt()
    val offset: Short get() = ((raw ushr 16) and 0xFFFF).toShort()
    val imm: Int get() = (raw ushr 32).toInt()

    companion object {
        fun pack(opcode: Int, dstReg: Int, srcReg: Int, offset: Short, imm: Int): EbpfInstruction {
            val op = (opcode.toLong() and 0xFF)
            val dst = (dstReg.toLong() and 0x0F) shl 8
            val src = (srcReg.toLong() and 0x0F) shl 12
            val off = (offset.toLong() and 0xFFFF) shl 16
            val i = (imm.toLong() and 0xFFFFFFFF) shl 32
            return EbpfInstruction(op or dst or src or off or i)
        }
    }
}
