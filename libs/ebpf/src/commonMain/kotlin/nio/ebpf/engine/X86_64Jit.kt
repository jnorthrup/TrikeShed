package nio.ebpf.engine

import nio.ebpf.types.*

/** Userspace eBPF → x86_64 JIT. Operates on EbpfInstruction raw Longs. */
object X86_64Jit {
    private const val STACK_SIZE = 512
    private val REX_W = 0x48.toByte()
    private fun xreg(i: Int): Int = when (i) {
        0 -> 0; 1 -> 7; 2 -> 6; 3 -> 2; 4 -> 1; 5 -> 0
        6 -> 3; 7 -> 5; 8 -> 6; 9 -> 7; 10 -> 5; 11 -> 4; else -> 0
    }

    fun compile(program: EbpfProgram): JitCode {
        val b = ByteBuf()
        prologue(b)
        for (i in program.instructions.indices) {
            emitOne(EbpfInstruction(program.instructions[i]), b)
        }
        epilogue(b)
        return b.data()
    }

    private fun prologue(b: ByteBuf) {
        b.push(REX_W, 0x55.toByte()); b.push(REX_W, 0x89.toByte(), 0xe5.toByte())
        b.push(REX_W, 0x81.toByte(), 0xec.toByte()); b.pushInt32(STACK_SIZE + 16)
        b.push(REX_W, 0x53.toByte()); b.push(0x41.toByte(), 0x55.toByte())
        b.push(0x41.toByte(), 0x56.toByte()); b.push(0x41.toByte(), 0x57.toByte())
    }

    private fun epilogue(b: ByteBuf) {
        b.push(0x41.toByte(), 0x5f.toByte()); b.push(0x41.toByte(), 0x5e.toByte())
        b.push(0x41.toByte(), 0x5d.toByte()); b.push(REX_W, 0x5b.toByte())
        b.push(REX_W, 0x89.toByte(), 0xec.toByte()); b.push(REX_W, 0x5d.toByte())
        b.push(0xc3.toByte())
    }

    private fun emitOne(inst: EbpfInstruction, b: ByteBuf) {
        val op = inst.opcode(); val cls = op and 0x07
        val dr = inst.dstReg(); val sr = inst.srcReg()
        val off = inst.offset(); val imm = inst.imm()

        when {
            op == 0x95.toByte() -> { b.push(0x31.toByte(), 0xc0.toByte()); b.push(0xc3.toByte()) }
            op == 0x85.toByte() -> b.push(0x31.toByte(), 0xc0.toByte())
            cls == 0x04.toByte() || cls == 0x07.toByte() -> {
                val srcIsImm = (op and 0x08) != 0x00.toByte()
                val opBase = op and 0xF0
                if (srcIsImm) {
                    b.push(REX_W, 0x81.toByte())
                    val sub = when (opBase.toInt()) {
                        0x00 -> 0; 0x10 -> 5; 0x20 -> 4; 0x30 -> 6; 0xa0 -> 6; else -> 0
                    }
                    b.push((0xc0 or sub or xreg(dr)).toByte()); b.pushInt32(imm)
                } else if (opBase == 0xb0) {
                    b.push(REX_W, 0x89.toByte()); _modrm(b, 3, 0, sr, dr)
                } else {
                    val opcode = when (opBase.toInt()) {
                        0x00 -> 0x01; 0x10 -> 0x29; 0x20 -> 0x21; 0x30 -> 0x31
                        0x40 -> 0x21; 0x50 -> 0x09; 0x60 -> 0x31; 0x70 -> 0x21
                        0x80 -> 0x31; 0x90 -> 0x31; 0xa0 -> 0x31; 0xc0 -> 0x21
                        else -> 0x01
                    }
                    b.push(REX_W, opcode.toByte()); _modrm(b, 3, 0, sr, dr)
                }
            }
            cls == 0x05.toByte() && (op and 0xF0) == 0x00.toByte() -> {
                b.push(0xe9.toByte()); b.pushInt32(0)
            }
            op == 0xb7.toByte() -> {
                b.push(REX_W, (0xb8 + (xreg(dr) and 0x07)).toByte()); b.pushInt64(imm.toLong())
            }
            op == 0x9d.toByte() -> { b.push(REX_W, 0x31.toByte()); _modrm(b, 3, 0, 0, 0) }
            (cls == 0x60.toByte() or 0x00) -> {
                if (op == (0x60 or 0x00 or 0x18).toByte()) {
                    b.push(REX_W, (0xb8 + (xreg(dr) and 0x07)).toByte())
                    b.pushInt64(imm.toLong() and 0xFFFFFFFF)
                }
            }
            else -> {
                if (op == 0x61.toByte()) {
                    b.push(REX_W, 0x8b.toByte()); modrmDisp(b, dr, sr, off.toInt())
                } else if (op == 0x73.toByte()) {
                    b.push(REX_W, 0x89.toByte()); modrmDisp(b, sr, dr, off.toInt())
                }
            }
        }
    }

    private fun _modrm(b: ByteBuf, mod: Int, opreg: Int, src: Int, dst: Int) {
        b.push((0xc0 or xreg(dst) or (xreg(src) shl 3)).toByte())
    }

    private fun modrmDisp(b: ByteBuf, dst: Int, base: Int, disp: Int) {
        val d = xreg(dst); val s = xreg(base)
        if (disp == 0 && s != 5) b.push((d or (s shl 3)).toByte())
        else { b.push((0x40 or d or (s shl 3)).toByte()); b.push(disp.toByte()) }
    }
}
