package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfInterpreterStxTest {

    @Test
    fun testStxByte() {
        val context = ByteArray(10)

        // R1 = 5 (address offset in context)
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 5)
        // R2 = 0xAA (value to store)
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 2, 0, 0, 0xAA)
        // *(u8 *)(R1 + 0) = R2
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_STX or EbpfOpcode.BPF_MEM or EbpfOpcode.BPF_B, 1, 2, 0, 0)
        // EXIT
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw))
        val interpreter = EbpfInterpreter(program)

        interpreter.execute(context)
        assertEquals(0xAA.toByte(), context[5])
    }
}
