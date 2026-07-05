package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfInterpreterTest {

    @Test
    fun testAlu64MovAndAdd() {
        // R1 = 10 (MOV64_IMM)
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 10)
        // R1 += 5 (ADD64_IMM)
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_ADD or EbpfOpcode.BPF_K, 1, 0, 0, 5)
        // R0 = R1 (MOV64_REG)
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_X, 0, 1, 0, 0)
        // EXIT
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw))
        val interpreter = EbpfInterpreter(program)

        val result = interpreter.execute(ByteArray(0))
        assertEquals(15L, result)
    }

    @Test
    fun testAlu32SubAndMul() {
        // R2 = 20
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 2, 0, 0, 20)
        // R2 -= 5
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU or EbpfOpcode.BPF_SUB or EbpfOpcode.BPF_K, 2, 0, 0, 5)
        // R2 *= 2
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU or EbpfOpcode.BPF_MUL or EbpfOpcode.BPF_K, 2, 0, 0, 2)
        // R0 = R2
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_X, 0, 2, 0, 0)
        // EXIT
        val inst5 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw, inst5.raw))
        val interpreter = EbpfInterpreter(program)

        val result = interpreter.execute(ByteArray(0))
        assertEquals(30L, result)
    }

    @Test
    fun testJumpJeq() {
        // R1 = 5
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 5)
        // if R1 == 5, jump offset +1
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_JEQ or EbpfOpcode.BPF_K, 1, 0, 1, 5)
        // R0 = 100 (skipped)
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 0, 0, 0, 100)
        // R0 = 200 (target)
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 0, 0, 0, 200)
        // EXIT
        val inst5 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw, inst5.raw))
        val interpreter = EbpfInterpreter(program)

        val result = interpreter.execute(ByteArray(0))
        assertEquals(200L, result)
    }

    @Test
    fun testLdxByte() {
        // Setup context array with some data
        val context = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        // R1 = 2 (address offset in context)
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 2)
        // R2 = *(u8 *)(R1 + 0) -> should read 0x30
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_LDX or EbpfOpcode.BPF_MEM or EbpfOpcode.BPF_B, 2, 1, 0, 0)
        // R0 = R2
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_X, 0, 2, 0, 0)
        // EXIT
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw))
        val interpreter = EbpfInterpreter(program)

        val result = interpreter.execute(context)
        assertEquals(0x30L, result)
    }
}
