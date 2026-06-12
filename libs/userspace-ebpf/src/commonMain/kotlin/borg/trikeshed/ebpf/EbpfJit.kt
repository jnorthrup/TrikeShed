package borg.trikeshed.ebpf

class ByteArrayBuilder {
    private var buffer = ByteArray(64)
    private var size = 0

    val currentSize: Int get() = size

    fun append(b: Byte) {
        if (size == buffer.size) {
            val newBuffer = ByteArray(buffer.size * 2)
            buffer.copyInto(newBuffer)
            buffer = newBuffer
        }
        buffer[size++] = b
    }

    fun append(vararg bytes: Int) {
        for (b in bytes) {
            append(b.toByte())
        }
    }

    fun appendInt32(value: Int) {
        append((value and 0xFF).toByte())
        append(((value ushr 8) and 0xFF).toByte())
        append(((value ushr 16) and 0xFF).toByte())
        append(((value ushr 24) and 0xFF).toByte())
    }

    fun setInt32(offset: Int, value: Int) {
        if (offset + 3 < buffer.size) {
            buffer[offset] = (value and 0xFF).toByte()
            buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/**
 * eBPF to x86-64 JIT compiler.
 * Emits raw machine code bytes corresponding to the eBPF instructions.
 * Requires multi-pass to properly resolve jump offsets.
 */
class EbpfJit {

    // Register mapping: eBPF reg -> x86-64 reg encoding
    private val regMap = intArrayOf(
        0, // R0 = RAX
        7, // R1 = RDI
        6, // R2 = RSI
        2, // R3 = RDX
        1, // R4 = RCX
        8, // R5 = R8
        3, // R6 = RBX
        13, // R7 = R13
        14, // R8 = R14
        15, // R9 = R15
        5  // R10 = RBP
    )

    fun compile(program: EbpfProgram): ByteArray {
        val out = ByteArrayBuilder()

        // Pass 1: Record instruction offsets in the output buffer
        val x86Offsets = IntArray(program.instructions.size)
        // Store locations of jumps that need patching
        val jumpPatches = mutableListOf<JumpPatch>()

        // Prologue
        // push rbp
        out.append(0x55)
        // mov rbp, rsp
        out.append(0x48, 0x89, 0xE5)

        var i = 0
        while (i < program.instructions.size) {
            x86Offsets[i] = out.currentSize
            val inst = EbpfInstruction(program.instructions[i])
            val opcode = inst.opcode
            val classOp = opcode and EbpfOpcode.BPF_CLASS_MASK

            when (classOp) {
                EbpfOpcode.BPF_ALU64 -> compileAlu64(opcode, inst, out)
                EbpfOpcode.BPF_ALU -> compileAlu32(opcode, inst, out)
                EbpfOpcode.BPF_JMP -> compileJmp(opcode, inst, out, i, jumpPatches)
                EbpfOpcode.BPF_LD -> {
                    val consumed = compileLd(opcode, inst, program, i, out)
                    i += consumed
                    continue
                }
                EbpfOpcode.BPF_LDX -> compileLdx(opcode, inst, out)
                EbpfOpcode.BPF_STX -> compileStx(opcode, inst, out)
            }
            i++
        }

        // Pass 2: Patch jump offsets
        for (patch in jumpPatches) {
            val targetEbpfIndex = patch.sourceEbpfIndex + 1 + patch.ebpfOffset
            val targetX86Offset = if (targetEbpfIndex < x86Offsets.size) {
                x86Offsets[targetEbpfIndex]
            } else {
                out.currentSize // Jump to end (after last instruction)
            }

            // The jump offset is relative to the *end* of the jump instruction
            val relativeOffset = targetX86Offset - (patch.patchOffset + 4)
            out.setInt32(patch.patchOffset, relativeOffset)
        }

        return out.toByteArray()
    }

    private class JumpPatch(val sourceEbpfIndex: Int, val ebpfOffset: Short, val patchOffset: Int)

    private fun getRex(w: Int, r: Int, x: Int, b: Int): Int {
        return 0x40 or (w shl 3) or (r shl 2) or (x shl 1) or b
    }

    private fun getModRm(mod: Int, reg: Int, rm: Int): Int {
        return (mod shl 6) or (reg shl 3) or rm
    }

    private fun compileAlu64(opcode: Int, inst: EbpfInstruction, out: ByteArrayBuilder) {
        val dst = regMap[inst.dstReg]
        val src = regMap[inst.srcReg]
        val isK = (opcode and EbpfOpcode.BPF_X) == 0
        val aluOp = opcode and 0xF0

        val dstB = dst and 7
        val srcB = src and 7
        val dstRex = if (dst > 7) 1 else 0
        val srcRex = if (src > 7) 1 else 0

        when (aluOp) {
            EbpfOpcode.BPF_MOV -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    out.append(0xC7)
                    out.append(getModRm(3, 0, dstB))
                    out.appendInt32(inst.imm)
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x89)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_ADD -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 0, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 0, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x01)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_SUB -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 5, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 5, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x29)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_MUL -> {
                if (isK) {
                    out.append(getRex(1, dstRex, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x6B)
                        out.append(getModRm(3, dstB, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x69)
                        out.append(getModRm(3, dstB, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, dstRex, 0, srcRex))
                    out.append(0x0F, 0xAF)
                    out.append(getModRm(3, dstB, srcB))
                }
            }
            EbpfOpcode.BPF_DIV, EbpfOpcode.BPF_MOD -> {
                // Division/modulo uses RAX/RDX, so we need to save/restore
                // For simplicity, we emit a call to a helper or use the slow path
                // Here we implement the slow path inline for MOD/DIV
                // RAX = dividend, RDX = 0 (for unsigned), then DIV src
                if (isK) {
                    // For immediate, mov to scratch register then divide
                    val scratchReg = 10 // R10 (our mapped R10 is RBP, use R11=regMap[10]... wait R10 is index 10)
                    // Actually simpler: use a helper function
                    // For now emit placeholder - real impl would be more complex
                    out.append(getRex(1, 0, 0, dstRex))
                    out.append(0xC7) // mov reg, imm
                    out.append(getModRm(3, 0, dstB))
                    out.appendInt32(inst.imm)
                    // TODO: actual DIV implementation
                } else {
                    // Register-register DIV
                    // mov rax, dst; xor rdx, rdx; div src
                    out.append(getRex(1, dstRex, 0, 0)) // mov rax, dst
                    out.append(0x89)
                    out.append(getModRm(3, dstB, 0))
                    out.append(getRex(1, 0, 0, 2)) // xor rdx, rdx
                    out.append(0x31)
                    out.append(getModRm(3, 2, 2))
                    out.append(getRex(1, srcRex, 0, 0)) // div src
                    out.append(0xF7)
                    out.append(getModRm(3, 6, srcB)) // div r/m64 (mod=3, reg=6)
                    // Result: RAX = quotient, RDX = remainder
                    if (aluOp == EbpfOpcode.BPF_DIV) {
                        // mov dst, rax
                        out.append(getRex(1, 0, 0, dstRex))
                        out.append(0x89)
                        out.append(getModRm(3, 0, dstB))
                    } else {
                        // mov dst, rdx
                        out.append(getRex(1, 0, 0, dstRex))
                        out.append(0x89)
                        out.append(getModRm(3, 2, dstB))
                    }
                }
            }
            EbpfOpcode.BPF_OR -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 1, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 1, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x09)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_AND -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 4, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 4, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x21)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_LSH -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3) // shl reg, 1
                        out.append(getModRm(3, 4, dstB))
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 4, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        // Large shift - use cl register
                        out.append(getRex(1, 0, 0, 1)) // mov cl, imm
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        out.append(getRex(1, 1, 0, dstRex)) // shl reg, cl
                        out.append(0xD3)
                        out.append(getModRm(3, 4, dstB))
                    }
                } else {
                    // Variable shift - src must be in RCX
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) { // RCX is 1
                        out.append(getRex(1, srcRex, 0, 1)) // mov rcx, src
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    out.append(getRex(1, 1, 0, dstRex)) // shl reg, cl
                    out.append(0xD3)
                    out.append(getModRm(3, 4, dstB))
                }
            }
            EbpfOpcode.BPF_RSH -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3) // shr reg, 1 (same opcode, different modrm)
                        out.append(getModRm(3, 5, dstB))
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 5, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(getRex(1, 0, 0, 1)) // mov cl, imm
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        out.append(getRex(1, 1, 0, dstRex)) // shr reg, cl
                        out.append(0xD3)
                        out.append(getModRm(3, 5, dstB))
                    }
                } else {
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) {
                        out.append(getRex(1, srcRex, 0, 1)) // mov rcx, src
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    out.append(getRex(1, 1, 0, dstRex)) // shr reg, cl
                    out.append(0xD3)
                    out.append(getModRm(3, 5, dstB))
                }
            }
            EbpfOpcode.BPF_NEG -> {
                out.append(getRex(1, 0, 0, dstRex))
                out.append(0xF7)
                out.append(getModRm(3, 3, dstB)) // neg reg
            }
            EbpfOpcode.BPF_XOR -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 6, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 6, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x31)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_ARSH -> {
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3)
                        out.append(getModRm(3, 7, dstB)) // sar reg, 1
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 7, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(getRex(1, 0, 0, 1)) // mov cl, imm
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        out.append(getRex(1, 1, 0, dstRex)) // sar reg, cl
                        out.append(0xD3)
                        out.append(getModRm(3, 7, dstB))
                    }
                } else {
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) {
                        out.append(getRex(1, srcRex, 0, 1)) // mov rcx, src
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    out.append(getRex(1, 1, 0, dstRex)) // sar reg, cl
                    out.append(0xD3)
                    out.append(getModRm(3, 7, dstB))
                }
            }
        }
    }

    private fun compileAlu32(opcode: Int, inst: EbpfInstruction, out: ByteArrayBuilder) {
        val dst = regMap[inst.dstReg]
        val src = regMap[inst.srcReg]
        val isK = (opcode and EbpfOpcode.BPF_X) == 0
        val aluOp = opcode and 0xF0

        val dstB = dst and 7
        val srcB = src and 7
        val dstRex = if (dst > 7) 1 else 0
        val srcRex = if (src > 7) 1 else 0

        val rexw = 0 // 32-bit ops

        when (aluOp) {
            EbpfOpcode.BPF_MOV -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    out.append(0xC7)
                    out.append(getModRm(3, 0, dstB))
                    out.appendInt32(inst.imm)
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x89)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_ADD -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 0, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 0, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x01)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_SUB -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 5, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 5, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x29)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_MUL -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, dstRex, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x6B)
                        out.append(getModRm(3, dstB, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x69)
                        out.append(getModRm(3, dstB, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, dstRex, 0, srcRex))
                    out.append(0x0F, 0xAF)
                    out.append(getModRm(3, dstB, srcB))
                }
            }
            EbpfOpcode.BPF_DIV, EbpfOpcode.BPF_MOD -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    out.append(0xC7)
                    out.append(getModRm(3, 0, dstB))
                    out.appendInt32(inst.imm)
                    // TODO: actual DIV implementation for 32-bit
                } else {
                    // 32-bit DIV uses EAX/EDX
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, dstRex, 0, 0))
                    out.append(0x89) // mov eax, dst
                    out.append(getModRm(3, dstB, 0))
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, 0, 0, 2))
                    out.append(0x31) // xor edx, edx
                    out.append(getModRm(3, 2, 2))
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, 0))
                    out.append(0xF7) // div src (32-bit)
                    out.append(getModRm(3, 6, srcB))
                    // Result: EAX = quotient, EDX = remainder
                    if (aluOp == EbpfOpcode.BPF_DIV) {
                        if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                        out.append(0x89)
                        out.append(getModRm(3, 0, dstB))
                    } else {
                        if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                        out.append(0x89)
                        out.append(getModRm(3, 2, dstB))
                    }
                }
            }
            EbpfOpcode.BPF_OR -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 1, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 1, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x09)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_AND -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 4, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 4, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x21)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_LSH -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3)
                        out.append(getModRm(3, 4, dstB))
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 4, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        if (dstRex == 1) out.append(getRex(rexw, 0, 0, 1))
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                        out.append(0xD3)
                        out.append(getModRm(3, 4, dstB))
                    }
                } else {
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) {
                        if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, 1))
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                    out.append(0xD3)
                    out.append(getModRm(3, 4, dstB))
                }
            }
            EbpfOpcode.BPF_RSH -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3)
                        out.append(getModRm(3, 5, dstB))
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 5, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        if (dstRex == 1) out.append(getRex(rexw, 0, 0, 1))
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                        out.append(0xD3)
                        out.append(getModRm(3, 5, dstB))
                    }
                } else {
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) {
                        if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, 1))
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                    out.append(0xD3)
                    out.append(getModRm(3, 5, dstB))
                }
            }
            EbpfOpcode.BPF_NEG -> {
                if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                out.append(0xF7)
                out.append(getModRm(3, 3, dstB)) // neg reg32
            }
            EbpfOpcode.BPF_XOR -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm in -128..127) {
                        out.append(0x83)
                        out.append(getModRm(3, 6, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        out.append(0x81)
                        out.append(getModRm(3, 6, dstB))
                        out.appendInt32(inst.imm)
                    }
                } else {
                    if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, dstRex))
                    out.append(0x31)
                    out.append(getModRm(3, srcB, dstB))
                }
            }
            EbpfOpcode.BPF_ARSH -> {
                if (isK) {
                    if (dstRex == 1) out.append(getRex(rexw, 0, 0, dstRex))
                    if (inst.imm == 1) {
                        out.append(0xD3)
                        out.append(getModRm(3, 7, dstB)) // sar reg, 1
                    } else if (inst.imm in -128..127) {
                        out.append(0xC1)
                        out.append(getModRm(3, 7, dstB))
                        out.append(inst.imm.toByte())
                    } else {
                        if (dstRex == 1) out.append(getRex(rexw, 0, 0, 1))
                        out.append(0xC7)
                        out.append(getModRm(3, 1, 1))
                        out.appendInt32(inst.imm)
                        if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                        out.append(0xD3)
                        out.append(getModRm(3, 7, dstB))
                    }
                } else {
                    val srcMapped = regMap[inst.srcReg]
                    if (srcMapped != 1) {
                        if (dstRex == 1 || srcRex == 1) out.append(getRex(rexw, srcRex, 0, 1))
                        out.append(0x89)
                        out.append(getModRm(3, srcB, 1))
                    }
                    if (dstRex == 1) out.append(getRex(rexw, 1, 0, dstRex))
                    out.append(0xD3)
                    out.append(getModRm(3, 7, dstB))
                }
            }
        }
    }

    private fun compileJmp(opcode: Int, inst: EbpfInstruction, out: ByteArrayBuilder, index: Int, patches: MutableList<JumpPatch>) {
        val jmpOp = opcode and 0xF0
        val isK = (opcode and EbpfOpcode.BPF_X) == 0

        if (jmpOp == EbpfOpcode.BPF_EXIT) {
            out.append(0x5D) // pop rbp
            out.append(0xC3) // ret
        } else if (jmpOp == EbpfOpcode.BPF_CALL) {
            // For x86-64 calling convention:
            // R1(RDI), R2(RSI), R3(RDX), R4(RCX), R5(R8)
            // Helpers are assumed to be raw function pointers or handled via an external dispatcher.
            // In a fully integrated JIT, inst.imm is the helper ID, which we map to a function pointer.
            // For now, we emit a call to a mock placeholder (offset 0) to be patched.
            out.append(0xE8)
            patches.add(JumpPatch(index, 0, out.currentSize)) // 0 offset, would point to helper stub
            out.appendInt32(0)
        } else if (jmpOp == EbpfOpcode.BPF_JA) {
            // Unconditional jump: jmp rel32
            out.append(0xE9)
            patches.add(JumpPatch(index, inst.offset, out.currentSize))
            out.appendInt32(0)
        } else {
            val dst = regMap[inst.dstReg]
            val dstB = dst and 7
            val dstRex = if (dst > 7) 1 else 0

            var srcB: Int = 0
            var srcRex: Int = 0
            if (!isK) {
                val src = regMap[inst.srcReg]
                srcB = src and 7
                srcRex = if (src > 7) 1 else 0
            }

            val jmpCode = when (jmpOp) {
                EbpfOpcode.BPF_JEQ -> 0x84
                EbpfOpcode.BPF_JGT -> 0x87
                EbpfOpcode.BPF_JGE -> 0x83
                EbpfOpcode.BPF_JNE -> 0x85
                EbpfOpcode.BPF_JSGT -> 0x8F
                EbpfOpcode.BPF_JSGE -> 0x8D
                EbpfOpcode.BPF_JLT -> 0x82
                EbpfOpcode.BPF_JLE -> 0x86
                EbpfOpcode.BPF_JSLT -> 0x8C
                EbpfOpcode.BPF_JSLE -> 0x8E
                EbpfOpcode.BPF_JSET -> 0x0F // JSET uses TEST, different encoding (0x84/0x85 after TEST)
                else -> 0x84
            }

            if (jmpOp == EbpfOpcode.BPF_JSET) {
                // JSET: test dst, imm/src; jnz target
                // The CMP above already set flags, but JSET needs TEST
                // So we redo the TEST instruction
                // Approximate: just test without removing CMP
                if (isK) {
                    out.append(getRex(1, 0, 0, dstRex))
                    out.append(0xF7) // TEST reg, imm32
                    out.append(getModRm(3, 0, dstB))
                    out.appendInt32(inst.imm)
                } else {
                    out.append(getRex(1, srcRex, 0, dstRex))
                    out.append(0x85) // TEST reg, reg
                    out.append(getModRm(3, srcB, dstB))
                }
                // JNZ (jump if not zero) = 0x85
                out.append(0x0F)
                out.append(0x85)
                patches.add(JumpPatch(index, inst.offset, out.currentSize))
                out.appendInt32(0)
            } else {
                // Standard conditional jumps
                out.append(0x0F)
                out.append(jmpCode)
                patches.add(JumpPatch(index, inst.offset, out.currentSize))
                out.appendInt32(0)
            }
        }
    }

    private fun compileLdx(opcode: Int, inst: EbpfInstruction, out: ByteArrayBuilder) {
        val dst = regMap[inst.dstReg]
        val src = regMap[inst.srcReg]
        val dstB = dst and 7
        val srcB = src and 7
        val dstRex = if (dst > 7) 1 else 0
        val srcRex = if (src > 7) 1 else 0

        val size = opcode and 0x18
        when (size) {
            EbpfOpcode.BPF_B -> {
                // movzx dst, byte ptr [src + offset]
                out.append(getRex(1, dstRex, 0, srcRex))
                out.append(0x0F, 0xB6)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, dstB, srcB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, dstB, srcB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, dstB, srcB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_H -> {
                // movzx dst, word ptr [src + offset]
                out.append(getRex(1, dstRex, 0, srcRex))
                out.append(0x0F, 0xB7)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, dstB, srcB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, dstB, srcB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, dstB, srcB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_W -> {
                // mov dst, dword ptr [src + offset]
                out.append(getRex(0, dstRex, 0, srcRex))
                out.append(0x8B)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, dstB, srcB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, dstB, srcB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, dstB, srcB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_DW -> {
                // mov dst, qword ptr [src + offset]
                out.append(getRex(1, dstRex, 0, srcRex))
                out.append(0x8B)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, dstB, srcB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, dstB, srcB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, dstB, srcB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
        }
    }

    private fun compileStx(opcode: Int, inst: EbpfInstruction, out: ByteArrayBuilder) {
        val dst = regMap[inst.dstReg]
        val src = regMap[inst.srcReg]
        val dstB = dst and 7
        val srcB = src and 7
        val dstRex = if (dst > 7) 1 else 0
        val srcRex = if (src > 7) 1 else 0

        val size = opcode and 0x18
        when (size) {
            EbpfOpcode.BPF_B -> {
                // mov byte ptr [dst + offset], src
                out.append(getRex(0, srcRex, 0, dstRex))
                out.append(0x88)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, srcB, dstB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, srcB, dstB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, srcB, dstB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_H -> {
                // mov word ptr [dst + offset], src
                out.append(getRex(0, srcRex, 0, dstRex))
                out.append(0x66, 0x89)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, srcB, dstB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, srcB, dstB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, srcB, dstB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_W -> {
                // mov dword ptr [dst + offset], src
                out.append(getRex(0, srcRex, 0, dstRex))
                out.append(0x89)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, srcB, dstB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, srcB, dstB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, srcB, dstB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
            EbpfOpcode.BPF_DW -> {
                // mov qword ptr [dst + offset], src
                out.append(getRex(1, srcRex, 0, dstRex))
                out.append(0x89)
                if (inst.offset == 0.toShort()) {
                    out.append(getModRm(0, srcB, dstB))
                } else if (inst.offset in -128..127) {
                    out.append(getModRm(1, srcB, dstB))
                    out.append(inst.offset.toByte())
                } else {
                    out.append(getModRm(2, srcB, dstB))
                    out.appendInt32(inst.offset.toInt())
                }
            }
        }
    }

    /**
     * Compiles LD (load) instructions.
     * Returns the number of additional instructions consumed (e.g., 1 for LD_DW_IMM which uses next instruction).
     */
    private fun compileLd(opcode: Int, inst: EbpfInstruction, program: EbpfProgram, index: Int, out: ByteArrayBuilder): Int {
        val size = opcode and 0x18
        val mode = opcode and 0xE0

        if (size == EbpfOpcode.BPF_DW && mode == EbpfOpcode.BPF_IMM) {
            // 64-bit immediate load: LD_DW_IMM
            // The first instruction has lower 32 bits in imm, upper 32 bits in next instruction's imm
            val dst = regMap[inst.dstReg]
            val dstB = dst and 7
            val dstRex = if (dst > 7) 1 else 0

            val lower32 = inst.imm.toLong() and 0xFFFFFFFFL
            var upper32 = 0L
            if (index + 1 < program.instructions.size) {
                val nextInst = EbpfInstruction(program.instructions[index + 1])
                upper32 = nextInst.imm.toLong() and 0xFFFFFFFFL
            }
            val fullValue = lower32 or (upper32 shl 32)

            // mov reg, imm64
            out.append(getRex(1, 0, 0, dstRex))
            out.append(0xC7) // mov r64, imm32 (sign-extended to 64-bit)
            out.append(getModRm(3, 0, dstB))
            out.appendInt32(fullValue.toInt()) // Note: truncates to 32-bit for encoding
            // For true 64-bit, we'd need MOVABS (REX.B + B8+rd io), but 0xC7 works for sign-extended

            return 1 // Consume next instruction
        }

        // Other LD modes (ABS, IND, LEN, MSH) not yet supported in JIT
        return 0
    }
}
