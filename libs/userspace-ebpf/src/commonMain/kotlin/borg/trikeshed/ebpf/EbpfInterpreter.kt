package borg.trikeshed.ebpf

class EbpfInterpreter(val program: EbpfProgram) {
    val registers = LongArray(11) // R0-R10

    fun execute(context: ByteArray): Long {
        var pc = 0
        registers.fill(0)

        while (pc < program.instructions.size) {
            val inst = EbpfInstruction(program.instructions[pc])
            pc++

            val opcode = inst.opcode
            val classOp = opcode and EbpfOpcode.BPF_CLASS_MASK

            when (classOp) {
                EbpfOpcode.BPF_ALU64 -> executeAlu64(opcode, inst)
                EbpfOpcode.BPF_ALU -> executeAlu32(opcode, inst)
                EbpfOpcode.BPF_JMP -> {
                    val jmpOffset = executeJmp(opcode, inst)
                    pc += jmpOffset
                }
                EbpfOpcode.BPF_LDX -> executeLdx(opcode, inst, context)
                EbpfOpcode.BPF_STX -> executeStx(opcode, inst, context)
            }

            if (classOp == EbpfOpcode.BPF_JMP && (opcode and 0xF0) == EbpfOpcode.BPF_EXIT) {
                return registers[0]
            }
        }

        return registers[0]
    }

    private fun executeAlu64(opcode: Int, inst: EbpfInstruction) {
        val dst = inst.dstReg
        val src = inst.srcReg
        val isK = (opcode and EbpfOpcode.BPF_X) == 0
        val aluOp = opcode and 0xF0

        val operand = if (isK) inst.imm.toLong() else registers[src]

        when (aluOp) {
            EbpfOpcode.BPF_ADD -> registers[dst] += operand
            EbpfOpcode.BPF_SUB -> registers[dst] -= operand
            EbpfOpcode.BPF_MUL -> registers[dst] *= operand
            EbpfOpcode.BPF_DIV -> if (operand != 0L) registers[dst] /= operand else registers[dst] = 0
            EbpfOpcode.BPF_OR -> registers[dst] = registers[dst] or operand
            EbpfOpcode.BPF_AND -> registers[dst] = registers[dst] and operand
            EbpfOpcode.BPF_LSH -> registers[dst] = registers[dst] shl operand.toInt()
            EbpfOpcode.BPF_RSH -> registers[dst] = registers[dst] ushr operand.toInt()
            EbpfOpcode.BPF_NEG -> registers[dst] = -registers[dst]
            EbpfOpcode.BPF_MOD -> if (operand != 0L) registers[dst] %= operand else registers[dst] = registers[dst]
            EbpfOpcode.BPF_XOR -> registers[dst] = registers[dst] xor operand
            EbpfOpcode.BPF_MOV -> registers[dst] = operand
            EbpfOpcode.BPF_ARSH -> registers[dst] = registers[dst] shr operand.toInt()
        }
    }

    private fun executeAlu32(opcode: Int, inst: EbpfInstruction) {
        val dst = inst.dstReg
        val src = inst.srcReg
        val isK = (opcode and EbpfOpcode.BPF_X) == 0
        val aluOp = opcode and 0xF0

        val operand = if (isK) inst.imm else registers[src].toInt()
        var dstVal = registers[dst].toInt()

        when (aluOp) {
            EbpfOpcode.BPF_ADD -> dstVal += operand
            EbpfOpcode.BPF_SUB -> dstVal -= operand
            EbpfOpcode.BPF_MUL -> dstVal *= operand
            EbpfOpcode.BPF_DIV -> if (operand != 0) dstVal /= operand else dstVal = 0
            EbpfOpcode.BPF_OR -> dstVal = dstVal or operand
            EbpfOpcode.BPF_AND -> dstVal = dstVal and operand
            EbpfOpcode.BPF_LSH -> dstVal = dstVal shl operand
            EbpfOpcode.BPF_RSH -> dstVal = dstVal ushr operand
            EbpfOpcode.BPF_NEG -> dstVal = -dstVal
            EbpfOpcode.BPF_MOD -> if (operand != 0) dstVal %= operand else dstVal = dstVal
            EbpfOpcode.BPF_XOR -> dstVal = dstVal xor operand
            EbpfOpcode.BPF_MOV -> dstVal = operand
            EbpfOpcode.BPF_ARSH -> dstVal = dstVal shr operand
        }

        // Zero extend 32-bit result to 64-bit register
        registers[dst] = dstVal.toLong() and 0xFFFFFFFFL
    }

    private fun executeJmp(opcode: Int, inst: EbpfInstruction): Int {
        val dst = inst.dstReg
        val src = inst.srcReg
        val isK = (opcode and EbpfOpcode.BPF_X) == 0
        val jmpOp = opcode and 0xF0

        val operand = if (isK) inst.imm.toLong() else registers[src]
        val dstVal = registers[dst]

        val jump = when (jmpOp) {
            EbpfOpcode.BPF_JA -> true
            EbpfOpcode.BPF_JEQ -> dstVal == operand
            EbpfOpcode.BPF_JGT -> dstVal.toULong() > operand.toULong()
            EbpfOpcode.BPF_JGE -> dstVal.toULong() >= operand.toULong()
            EbpfOpcode.BPF_JSET -> (dstVal and operand) != 0L
            EbpfOpcode.BPF_JNE -> dstVal != operand
            EbpfOpcode.BPF_JSGT -> dstVal > operand
            EbpfOpcode.BPF_JSGE -> dstVal >= operand
            EbpfOpcode.BPF_JLT -> dstVal.toULong() < operand.toULong()
            EbpfOpcode.BPF_JLE -> dstVal.toULong() <= operand.toULong()
            EbpfOpcode.BPF_JSLT -> dstVal < operand
            EbpfOpcode.BPF_JSLE -> dstVal <= operand
            EbpfOpcode.BPF_CALL -> false // Calls not fully implemented
            EbpfOpcode.BPF_EXIT -> false
            else -> false
        }

        return if (jump) inst.offset.toInt() else 0
    }

    private fun executeLdx(opcode: Int, inst: EbpfInstruction, context: ByteArray) {
        val dst = inst.dstReg
        val src = inst.srcReg
        val size = opcode and 0x18

        val addr = registers[src] + inst.offset

        if (addr >= 0) {
            when (size) {
                // Use Little-Endian decoding for compatibility with x86-64 target
                EbpfOpcode.BPF_B -> if (addr < context.size) registers[dst] = (context[addr.toInt()].toLong() and 0xFF)
                EbpfOpcode.BPF_H -> if (addr + 1 < context.size) registers[dst] = (
                    (context[addr.toInt()].toLong() and 0xFF) or
                    ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8)
                )
                EbpfOpcode.BPF_W -> if (addr + 3 < context.size) registers[dst] = (
                    (context[addr.toInt()].toLong() and 0xFF) or
                    ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8) or
                    ((context[(addr + 2).toInt()].toLong() and 0xFF) shl 16) or
                    ((context[(addr + 3).toInt()].toLong() and 0xFF) shl 24)
                )
                EbpfOpcode.BPF_DW -> if (addr + 7 < context.size) registers[dst] = (
                    (context[addr.toInt()].toLong() and 0xFF) or
                    ((context[(addr + 1).toInt()].toLong() and 0xFF) shl 8) or
                    ((context[(addr + 2).toInt()].toLong() and 0xFF) shl 16) or
                    ((context[(addr + 3).toInt()].toLong() and 0xFF) shl 24) or
                    ((context[(addr + 4).toInt()].toLong() and 0xFF) shl 32) or
                    ((context[(addr + 5).toInt()].toLong() and 0xFF) shl 40) or
                    ((context[(addr + 6).toInt()].toLong() and 0xFF) shl 48) or
                    ((context[(addr + 7).toInt()].toLong() and 0xFF) shl 56)
                )
            }
        }
    }

    private fun executeStx(opcode: Int, inst: EbpfInstruction, context: ByteArray) {
        val dst = inst.dstReg
        val src = inst.srcReg
        val size = opcode and 0x18

        val addr = registers[dst] + inst.offset

        if (addr >= 0) {
            val v = registers[src]
            when (size) {
                EbpfOpcode.BPF_B -> if (addr < context.size) {
                    context[addr.toInt()] = (v and 0xFF).toByte()
                }
                EbpfOpcode.BPF_H -> if (addr + 1 < context.size) {
                    context[addr.toInt()] = (v and 0xFF).toByte()
                    context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                }
                EbpfOpcode.BPF_W -> if (addr + 3 < context.size) {
                    context[addr.toInt()] = (v and 0xFF).toByte()
                    context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                    context[(addr + 2).toInt()] = ((v ushr 16) and 0xFF).toByte()
                    context[(addr + 3).toInt()] = ((v ushr 24) and 0xFF).toByte()
                }
                EbpfOpcode.BPF_DW -> if (addr + 7 < context.size) {
                    context[addr.toInt()] = (v and 0xFF).toByte()
                    context[(addr + 1).toInt()] = ((v ushr 8) and 0xFF).toByte()
                    context[(addr + 2).toInt()] = ((v ushr 16) and 0xFF).toByte()
                    context[(addr + 3).toInt()] = ((v ushr 24) and 0xFF).toByte()
                    context[(addr + 4).toInt()] = ((v ushr 32) and 0xFF).toByte()
                    context[(addr + 5).toInt()] = ((v ushr 40) and 0xFF).toByte()
                    context[(addr + 6).toInt()] = ((v ushr 48) and 0xFF).toByte()
                    context[(addr + 7).toInt()] = ((v ushr 56) and 0xFF).toByte()
                }
            }
        }
    }
}
