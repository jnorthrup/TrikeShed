package borg.trikeshed.userspace.nio.ebpf.types

import kotlin.jvm.JvmInline

/**
 * Single inline eBPF instruction — the raw 8-byte wire format encoded as a Long.
 *
 *   byte 0: opcode (6 low bits: class, 2 high: src)
 *   byte 1: dst_reg (low nibble) | src_reg (high nibble)
 *   bytes 2-3: offset (16-bit signed, LE)
 *   bytes 4-7: immediate (32-bit signed, LE)
 */
@JvmInline value class EbpfInstruction(val raw: Long) {

    fun opcode(): Int = (raw and 0xFF).toInt()
    fun dstReg(): Int = ((raw ushr 8) and 0x0F).toInt()
    fun srcReg(): Int = ((raw ushr 12) and 0x0F).toInt()
    fun offset(): Short = ((raw ushr 16) and 0xFFFF).toShort()
    fun imm(): Int = (raw ushr 32).toInt()
    fun isExit(): Boolean = opcode() == BPF_EXIT
    fun regPair() = dstReg() to srcReg()
    fun isLdImm64(): Boolean = (opcode() and BPF_CLASS_MASK) == BPF_LD &&
                               (opcode() and BPF_MODE_MASK) == BPF_IMM &&
                               (opcode() and BPF_SIZE_MASK) == BPF_DW

    companion object {
        // ── Field extractors (LE decoding from raw) ──
        fun opcode(raw: Long): Int = (raw and 0xFF).toInt()
        fun regs(raw: Long): Int = ((raw ushr 8) and 0xFF).toInt()
        fun dstReg(raw: Long): Int = regs(raw) and 0x0F
        fun srcReg(raw: Long): Int = regs(raw) ushr 4
        fun offset(raw: Long): Short = ((raw ushr 16) and 0xFFFF).toShort()
        fun imm(raw: Long): Int = (raw ushr 32).toInt()

        fun imm64(raw: Long, raw2: Long): Long =
            (raw ushr 32 and 0xFFFFFFFFL) or ((raw2 ushr 32 and 0xFFFFFFFFL) shl 32)

        fun opClass(raw: Long): OpClass = OpClass.from(opcode(raw))

        // ── Factory functions (LE encoding) ──
        private fun encode(op: Int, regByte: Int, off: Short, imm: Int): Long =
            (op.toLong() and 0xFF) or
            ((regByte.toLong() and 0xFF) shl 8) or
            ((off.toLong() and 0xFFFF) shl 16) or
            ((imm.toLong() and 0xFFFFFFFFL) shl 32)

        fun alu(opcode: Int, dst: Int, src: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, (dst and 0x0F) or ((src and 0x0F) shl 4), 0, 0))

        fun aluImm(opcode: Int, dst: Int, imm: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst and 0x0F, 0, imm))

        fun jmp8(opcode: Int, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, 0, offset.toShort(), 0))

        fun jmpCC(opcode: Int, src: Int, dst: Int, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, (dst and 0x0F) or ((src and 0x0F) shl 4), offset.toShort(), 0))

        fun jmpImmCC(opcode: Int, dst: Int, imm: Int, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst and 0x0F, offset.toShort(), imm))

        fun ldX(sizeClass: Int, dst: Int, src: Int, offset: Short): EbpfInstruction =
            EbpfInstruction(encode(BPF_LD or BPF_X or sizeClass, (dst and 0x0F) or ((src and 0x0F) shl 4), offset, 0))

        fun stX(sizeClass: Int, dst: Int, src: Int, offset: Short): EbpfInstruction =
            EbpfInstruction(encode(BPF_STX or sizeClass, (dst and 0x0F) or ((src and 0x0F) shl 4), offset, 0))

        fun call(imm: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_JMP or BPF_CALL, 0, 0, imm))

        fun exit(): EbpfInstruction =
            EbpfInstruction(encode(BPF_EXIT, 0, 0, 0))

        fun imm64(dst: Int, imm64: Long): Pair<EbpfInstruction, EbpfInstruction> {
            val lo = (imm64 and 0xFFFFFFFFL).toInt()
            val hi = ((imm64 ushr 32) and 0xFFFFFFFFL).toInt()
            val first = EbpfInstruction(encode(BPF_LD or BPF_IMM or BPF_DW, dst and 0x0F, 0, lo))
            val second = EbpfInstruction(encode(0, 0, 0, hi))
            return first to second
        }

        fun movImm(dst: Int, imm: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_MOV or BPF_X, dst and 0x0F, 0, imm))

        fun movR(dst: Int, src: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_MOV or BPF_X, (dst and 0x0F) or ((src and 0x0F) shl 4), 0, 0))

        fun neg(dst: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_NEG, dst and 0x0F, 0, 0))
    }
}

/** Opcode class — groups 256 opcodes into dispatch categories. */
enum class OpClass {
    Alu64, Alu, Ld, St, StX, Jmp, Jmp32, LdImm64, Exit, Endian, Atomic, Unknown;

    companion object {
        fun from(opcode: Int): OpClass = when {
            opcode == BPF_EXIT -> Exit
            (opcode and BPF_CLASS_MASK) == BPF_ALU64 -> when (opcode and 0xF0) {
                (BPF_NEG shl 4) -> Alu64
                else -> if ((opcode and BPF_SRC_MASK) == BPF_K) Alu else Alu64
            }
            (opcode and BPF_CLASS_MASK) == BPF_ALU -> when {
                (opcode and BPF_OP_MASK) == (BPF_END shl 4) -> Endian
                else -> if ((opcode and BPF_SRC_MASK) == BPF_K) Alu else Alu64
            }
            (opcode and BPF_CLASS_MASK) == BPF_JMP -> Alu
            (opcode and BPF_CLASS_MASK) == BPF_JMP32 -> Alu
            opcode == (BPF_LD or BPF_IMM or BPF_DW) -> LdImm64
            (opcode and BPF_CLASS_MASK) == BPF_LD -> when {
                (opcode and BPF_MODE_MASK) == BPF_X -> Alu
                else -> Ld
            }
            (opcode and BPF_CLASS_MASK) == BPF_STX -> when {
                (opcode and BPF_MODE_MASK) == BPF_ATOMIC -> Atomic
                else -> StX
            }
            (opcode and BPF_CLASS_MASK) == BPF_ST -> St
            else -> Unknown
        }
    }
}

// ── eBPF opcode constants — from linux/bpf.h ──
const val BPF_CLASS_MASK: Int = 0x07
const val BPF_ALU: Int = 0x04
const val BPF_ALU64: Int = 0x07
const val BPF_LD: Int = 0x60
const val BPF_LDX: Int = 0x10
const val BPF_ST: Int = 0x62
const val BPF_STX: Int = 0x63
const val BPF_JMP: Int = 0x05
const val BPF_JMP32: Int = 0x06
const val BPF_CALL: Int = 0x80
const val BPF_EXIT: Int = 0x95
const val BPF_IMM: Int = 0x00
const val BPF_X: Int = 0x08
const val BPF_NEG: Int = 0x08
const val BPF_MOV: Int = 0x07
const val BPF_END: Int = 0x0d
const val BPF_K: Int = 0x00
const val BPF_SRC_MASK: Int = 0x08
const val BPF_SIZE_MASK: Int = 0x18
const val BPF_DW: Int = 0x18
const val BPF_MODE_MASK: Int = 0xe0
const val BPF_OP_MASK: Int = 0xf0
const val BPF_ATOMIC: Int = 0xc0
