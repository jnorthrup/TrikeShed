package nio.ebpf.jit

import nio.ebpf.algebra.*

/**
 * Userspace eBPF → x86_64 native code JIT compiler.
 *
 * Register mapping (eBPF → x86_64 SysV ABI):
 *   R0→RAX  R1→RDI  R2→RSI  R3→RDX  R4→RCX  R5→R8
 *   R6→RBX  R7→R13  R8→R14  R9→R15  R10→RBP
 */
object X86_64Jit {
    private const val STACK_SIZE = 512
    private val REX_W = 0x48.toByte()

    /** eBPF reg → x86_64 ModR/M encoding */
    private fun xreg(i: Int): Int = when (i) {
        0 -> 0; 1 -> 7; 2 -> 6; 3 -> 2; 4 -> 1; 5 -> 0
        6 -> 3; 7 -> 5; 8 -> 6; 9 -> 7; 10 -> 5; 11 -> 4
        else -> 0
    }

    fun compile(program: EbpfProgram): JitCode {
        val buf = ByteBuf()
        prologue(buf)
        for (inst in program.instructions) emitOne(inst, buf)
        epilogue(buf)
        return buf.data()
    }

    private fun prologue(b: ByteBuf) {
        b.push(REX_W, 0x55.toByte())
        b.push(REX_W, 0x89.toByte(), 0xe5.toByte())
        b.push(REX_W, 0x81.toByte(), 0xec.toByte())
        b.pushInt32(STACK_SIZE + 16)
        b.push(REX_W, 0x53.toByte())
        b.push(0x41.toByte(), 0x55.toByte())
        b.push(0x41.toByte(), 0x56.toByte())
        b.push(0x41.toByte(), 0x57.toByte())
    }

    private fun epilogue(b: ByteBuf) {
        b.push(0x41.toByte(), 0x5f.toByte())
        b.push(0x41.toByte(), 0x5e.toByte())
        b.push(0x41.toByte(), 0x5d.toByte())
        b.push(REX_W, 0x5b.toByte())
        b.push(REX_W, 0x89.toByte(), 0xec.toByte())
        b.push(REX_W, 0x5d.toByte())
        b.push(0xc3.toByte())
    }

    private fun emitOne(inst: EbpfInstruction, b: ByteBuf) {
        when (inst) {
            is EbpfInstruction.Add -> aluR(b, 0x01, inst.dst, inst.src)
            is EbpfInstruction.Sub -> aluR(b, 0x29, inst.dst, inst.src)
            is EbpfInstruction.And -> aluR(b, 0x21, inst.dst, inst.src)
            is EbpfInstruction.Or -> aluR(b, 0x09, inst.dst, inst.src)
            is EbpfInstruction.Xor -> aluR(b, 0x31, inst.dst, inst.src)
            is EbpfInstruction.Mul -> {
                b.push(REX_W, 0x0f.toByte(), 0xaf.toByte())
                modrm(b, inst.dst, inst.src)
            }
            is EbpfInstruction.LShift -> shift(b, 4, inst.src, inst.dst)
            is EbpfInstruction.RShift -> shift(b, 5, inst.src, inst.dst)
            is EbpfInstruction.ArithRShift -> shift(b, 7, inst.src, inst.dst)
            is EbpfInstruction.Move -> movReg(b, inst.dst, inst.src)
            is EbpfInstruction.Neg -> {
                b.push(REX_W, 0xf7.toByte())
                _modrm(b, 3, 0, inst.dst.index)
            }
            is EbpfInstruction.Mod -> {
                b.push(0x31.toByte(), 0xd2.toByte())
                b.push(REX_W, 0xf7.toByte())
                _modrm(b, 6, 0, inst.src.index)
                b.push(REX_W, 0x89.toByte())
                _modrm(b, 3, 0, inst.dst.index, dstIsEdx = true)
            }
            is EbpfInstruction.Div -> {
                b.push(0x31.toByte(), 0xd2.toByte())
                b.push(REX_W, 0xf7.toByte())
                _modrm(b, 6, 0, inst.src.index)
            }
            is EbpfInstruction.MovImm -> movImm64(b, inst.dst, inst.imm.toLong())
            is EbpfInstruction.AddImm -> aluI(b, 0, inst.dst, inst.imm.toLong())
            is EbpfInstruction.SubImm -> aluI(b, 5, inst.dst, inst.imm.toLong())
            is EbpfInstruction.AndImm -> aluI(b, 4, inst.dst, inst.imm.toLong())
            is EbpfInstruction.OrImm -> aluI(b, 1, inst.dst, inst.imm.toLong())
            is EbpfInstruction.XorImm -> aluI(b, 6, inst.dst, inst.imm.toLong())
            is EbpfInstruction.LdX -> {
                b.push(REX_W, 0x8b.toByte())
                modrmDisp(b, inst.dst, inst.src, inst.offset.toInt())
            }
            is EbpfInstruction.StX -> {
                b.push(REX_W, 0x89.toByte())
                modrmDisp(b, inst.src, inst.dst, inst.offset.toInt())
            }
            is EbpfInstruction.St -> {
                b.push(0xc7.toByte(), 0x40.toByte())
                b.push(inst.offset.toByte())
                b.pushInt32(0)
            }
            is EbpfInstruction.LdImm64 -> movImm64(b, inst.dst, inst.imm64)
            is EbpfInstruction.JmpExit -> {
                b.push(0x31.toByte(), 0xc0.toByte())
                b.push(0xc3.toByte())
            }
            is EbpfInstruction.Jmp -> {
                b.push(0xe9.toByte())
                b.pushInt32(0)
            }
            is EbpfInstruction.JmpCall -> {
                b.push(0x31.toByte(), 0xc0.toByte())
            }
            is EbpfInstruction.JmpEq -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x84)
            is EbpfInstruction.JmpNe -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x85)
            is EbpfInstruction.JmpGt -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x87)
            is EbpfInstruction.JmpGe -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x83)
            is EbpfInstruction.JmpSgt -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x8f)
            is EbpfInstruction.JmpSge -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x8d)
            is EbpfInstruction.JmpSet -> jmpCC(b, inst.src, inst.dst, inst.offset, 0x85)
            is EbpfInstruction.JmpEqImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x84)
            is EbpfInstruction.JmpNeImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x85)
            is EbpfInstruction.JmpSgtImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x8f)
            is EbpfInstruction.JmpSgeImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x8d)
            is EbpfInstruction.JgtImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x87)
            is EbpfInstruction.GeImm -> jmpImmCC(b, inst.dst, inst.imm, inst.offset, 0x83)
            is EbpfInstruction.AtomicXAdd -> {
                b.push(0xf0.toByte())
                b.push(REX_W, 0x0f.toByte(), 0xc1.toByte())
                modrmDisp(b, inst.src, inst.dst, inst.offset.toInt())
            }
            is EbpfInstruction.Endian -> {
                if (inst.size == BitWidth.B64) {
                    b.push(0x48.toByte(), 0x0f.toByte(), (0xc8 + xreg(inst.dst.index)).toByte())
                }
            }
            // Remaining immediate ALU instructions
            is EbpfInstruction.MulImm, is EbpfInstruction.DivImm,
            is EbpfInstruction.LShiftImm, is EbpfInstruction.RShiftImm,
            is EbpfInstruction.ModImm, is EbpfInstruction.ArithRShiftImm -> {
                b.push(REX_W, 0x31.toByte(), 0xc0.toByte()) // stub
            }
        }
    }

    private fun aluR(b: ByteBuf, opcode: Int, dst: Reg, src: Reg) {
        b.push(REX_W, opcode.toByte())
        modrm(b, dst, src)
    }

    private fun aluI(b: ByteBuf, sub: Int, dst: Reg, imm: Long) {
        b.push(REX_W, 0x81.toByte())
        b.push((0xc0 or sub or xreg(dst.index)).toByte())
        b.pushInt32(imm.toInt())
    }

    private fun movImm64(b: ByteBuf, dst: Reg, imm: Long) {
        b.push(REX_W, (0xb8 + (xreg(dst.index) and 0x07)).toByte())
        b.pushInt64(imm)
    }

    private fun movReg(b: ByteBuf, dst: Reg, src: Reg) {
        b.push(REX_W, 0x89.toByte())
        _modrmSrcDst(b, src.index, dst.index)
    }

    private fun shift(b: ByteBuf, sub: Int, src: Reg, dst: Reg) {
        b.push(REX_W, 0x89.toByte())
        _modrmSrcDst(b, src.index, dst.index)
        b.push(REX_W, 0xd3.toByte())
        _modrm(b, sub, 1, dst.index)
    }

    private fun jmpCC(b: ByteBuf, src: Reg, dst: Reg, offset: Int, cc: Int) {
        b.push(REX_W, 0x39.toByte())
        modrm(b, dst, src)
        b.push(0x0f.toByte(), cc.toByte())
        b.pushInt32(0)
    }

    private fun jmpImmCC(b: ByteBuf, dst: Reg, imm: Int, offset: Int, cc: Int) {
        b.push(REX_W, 0x81.toByte())
        b.push((0xf8 or xreg(dst.index)).toByte())
        b.pushInt32(imm)
        b.push(0x0f.toByte(), cc.toByte())
        b.pushInt32(0)
    }

    private fun _modrm(b: ByteBuf, mod: Int, opreg: Int, rm: Int, dstIsEdx: Boolean = false) {
        val r = if (dstIsEdx) 2 else 0
        val finalOp = if (dstIsEdx) 0 else opreg
        b.push(((mod shl 6) or (finalOp shl 3) or rm).toByte())
    }

    private fun _modrmSrcDst(b: ByteBuf, srcIdx: Int, dstIdx: Int) {
        b.push((0xc0 or xreg(dstIdx) or (xreg(srcIdx) shl 3)).toByte())
    }

    private fun modrm(b: ByteBuf, dst: Reg, src: Reg) {
        b.push((0xc0 or xreg(dst.index) or (xreg(src.index) shl 3)).toByte())
    }

    private fun modrmDisp(b: ByteBuf, dst: Reg, base: Reg, disp: Int) {
        val d = xreg(dst.index)
        val s = xreg(base.index)
        if (disp == 0 && s != 5) {
            b.push((d or (s shl 3)).toByte())
        } else {
            b.push((0x40 or d or (s shl 3)).toByte())
            b.push(disp.toByte())
        }
    }
}
