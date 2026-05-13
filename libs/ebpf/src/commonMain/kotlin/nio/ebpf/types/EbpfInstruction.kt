package nio.ebpf.types

import nio.ebpf.raw.BEBPF_ORDER

/**
 * Single inline eBPF instruction — the raw 8-byte wire format encoded as a Long.
 *
 * Eliminates 43 data class allocations per program. Each instruction IS the payload.
 * No object header, no nullable refs — just the 8-byte eBPF encoding from the spec:
 *   byte 0: opcode (6 low bits: class, 2 high: src)
 *   byte 1: dst_reg (low nibble) | src_reg (high nibble)
 *   bytes 2-3: offset (16-bit signed, LE)
 *   bytes 4-7: immediate (32-bit signed, LE)
 *
 * PRELOAD.md dominance: this is THE canonical instruction representation in TrikeShed.
 * All algebraic operations (encode, decode, JIT, verify) operate on this raw form.
 *
 * Network-endian: the RowVec backing store uses LE (native on x86/amd64, ARM64).
 * JVM translation layer handles ByteBuffer order switching at the last mile.
 */
@JvmInline
value class EbpfInstruction(val raw: Long) {

    companion object {
        // ── Field extractors (LE decoding from raw) ──
        fun opcode(raw: Long): Byte = (raw and 0xFF).toByte()
        fun regs(raw: Long): Byte    = ((raw ushr 8) and 0xFF).toByte()
        fun dstReg(raw: Long): Int   = (regs(raw) and 0x0F).toInt()
        fun srcReg(raw: Long): Int   = (regs(raw) ushr 4).toInt()
        fun offset(raw: Long): Short = ((raw ushr 16) and 0xFFFF).toShort()
        fun imm(raw: Long): Int      = (raw ushr 32).toInt()

        // ── LdImm64 helpers (needs second word) ──
        fun imm64(raw: Long, raw2: Long): Long =
            (raw ushr 32 and 0xFFFFFFFFL) or ((raw2 ushr 32 and 0xFFFFFFFFL) shl 32)

        // ── Opclass — maps opcode byte to dispatch group ──
        fun opClass(raw: Long): OpClass = OpClass.from(opcode(raw))

        // ── Factory functions (LE encoding) ──
        fun alu(opcode: Byte, dst: Int, src: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst.toByte(), src.toByte(), 0, 0))

        fun aluImm(opcode: Byte, dst: Int, imm: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst.toByte(), 0, 0, imm))

        fun jmp8(opcode: Byte, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, 0, 0, offset.toShort(), 0))

        fun jmpCC(opcode: Byte, src: Int, dst: Int, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst.toByte(), src.toByte(), offset.toShort(), 0))

        fun jmpImmCC(opcode: Byte, dst: Int, imm: Int, offset: Int): EbpfInstruction =
            EbpfInstruction(encode(opcode, dst.toByte(), 0, offset.toShort(), imm))

        fun ldX(sizeClass: Byte, dst: Int, src: Int, offset: Short): EbpfInstruction =
            EbpfInstruction(encode(BPF_LD or BPF_X or sizeClass, dst.toByte(), src.toByte(), offset, 0))

        fun stX(sizeClass: Byte, dst: Int, src: Int, offset: Short): EbpfInstruction =
            EbpfInstruction(encode(BPF_STX or sizeClass, dst.toByte(), src.toByte(), offset, 0))

        fun store(sizeClass: Byte, dst: Int, src: Int, offset: Short, imm: Int = 0): EbpfInstruction =
            EbpfInstruction(encode(BPF_ST or sizeClass, dst.toByte(), src.toByte(), offset, imm))

        fun call(imm: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_JMP or BPF_CALL, 0, 0, 0, imm))

        fun exit(): EbpfInstruction =
            EbpfInstruction(encode(BPF_EXIT, 0, 0, 0, 0))

        fun imm64(dst: Int, imm64: Long): Pair<EbpfInstruction, EbpfInstruction> {
            val lo = (imm64 and 0xFFFFFFFFL).toInt()
            val hi = ((imm64 ushr 32) and 0xFFFFFFFFL).toInt()
            val first = EbpfInstruction(encode(BPF_LD or BPF_IMM or BPF_DW, dst.toByte(), 0, 0, lo))
            val second = EbpfInstruction(encode(0, 0, 0, 0, hi))
            return first to second
        }

        fun movImm(dst: Int, imm: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_MOV or BPF_X, dst.toByte(), 0, 0, imm))

        fun movR(dst: Int, src: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_MOV or BPF_X, dst.toByte(), src.toByte(), 0, 0))

        fun neg(dst: Int): EbpfInstruction =
            EbpfInstruction(encode(BPF_ALU64 or BPF_NEG, dst.toByte(), 0, 0, 0))

        fun endian(toBig: Boolean, size: Byte, dst: Int): EbpfInstruction =
            EbpfInstruction(encode(
                (if (toBig) BPF_END_TOBE else BPF_END_TOLE) or BPF_END or BPF_ALU,
                dst.toByte(), size, 0, 0
            ))

        fun atomicXAdd(dst: Int, src: Int, offset: Short, size: Byte): EbpfInstruction =
            EbpfInstruction(encode(BPF_STX or BPF_ATOMIC or size, dst.toByte(), src.toByte(), offset, 0))

        private fun encode(op: Byte, regByte: Byte, junk: Byte, off: Short, imm: Int): Long {
            return (op.toLong() and 0xFF) or
                   ((regByte.toLong() and 0xFF) shl 8) or
                   ((off.toLong() and 0xFFFF) shl 16) or
                   ((imm.toLong() and 0xFFFFFFFFL) shl 32)
        }
    }

    /** Is this the first half of a LdImm64 double-word? */
    fun isLdImm64(): Boolean = (opcode() and BPF_CLASS_MASK) == BPF_LD &&
                               (opcode() and BPF_MODE_MASK) == BPF_IMM &&
                               (opcode() and BPF_SIZE_MASK) == BPF_DW

    // ── Convenience accessors ──
    fun opcode(): Byte = (raw and 0xFF).toByte()
    fun dstReg(): Int = ((raw ushr 8) and 0x0F).toInt()
    fun srcReg(): Int = ((raw ushr 12) and 0x0F).toInt()
    fun offset(): Short = ((raw ushr 16) and 0xFFFF).toShort()
    fun imm(): Int = (raw ushr 32).toInt()
    fun isExit(): Boolean = opcode() == BPF_EXIT
    fun regPair() = dstReg() to srcReg()
}

/** Opcode class — groups 256 opcodes into 7 dispatch categories. */
enum class OpClass {
    Alu64, Alu, Ld, St, StX, Jmp, Jmp32, LdImm64, Exit, Endian, Atomic, Unknown;

    companion object {
        fun from(opcode: Byte): OpClass = when {
            opcode == BPF_EXIT -> Exit
            (opcode and BPF_CLASS_MASK) == BPF_ALU64 -> when (opcode and 0xF0) {
                (BPF_NEG shl 4).toByte() -> Alu64
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
                (opcode and BPF_MODE_MASK) == BPF_ATOMIC.toInt() -> Atomic
                else -> StX
            }
            (opcode and BPF_CLASS_MASK) == BPF_ST -> St
            else -> Unknown
        }
    }
}

/** eBPF opcode constants — from linux/bpf.h */
private const val BPF_CLASS_MASK: Byte = 0x07
private const val BPF_ALU: Byte = 0x04
private const val BPF_ALU64: Byte = 0x07
private const val BPF_LD: Byte = 0x60.toByte()
private const val BPF_LDX: Byte = 0x10.toByte()
private const val BPF_ST: Byte = 0x62.toByte()
private const val BPF_STX: Byte = 0x63.toByte()
private const val BPF_JMP: Byte = 0x05
private const val BPF_JMP32: Byte = 0x06
private const val BPF_CALL: Int = 0x80
private const val BPF_EXIT: Byte = 0x95.toByte()
private const val BPF_IMM: Int = 0x00
private const val BPF_X: Int = 0x08
private const val BPF_NEG: Int = 0x08
private const val BPF_MOV: Int = 0x07
private const val BPF_END: Int = 0x0d
private const val BPF_XOR: Int = 0x0a
private const val BPF_OR: Int = 0x04
private const val BPF_AND: Int = 0x05
private const val BPF_END_TOBE: Int = 0x08
private const val BPF_END_TOLE: Int = 0x00
private const val BPF_ATOMIC: Byte = 0xc1.toByte()
private const val BPF_K: Byte = 0x00
private const val BPF_SRC_MASK: Byte = 0x08
private const val BPF_SIZE_MASK: Int = 0x18
private const val BPF_DW: Byte = 0x18.toByte()
private const val BPF_MODE_MASK: Int = 0xe0
private const val BPF_OP_MASK: Int = 0xf0

/** Network-endian conversion marker. */
expect val BEBPF_ORDER: Boolean
