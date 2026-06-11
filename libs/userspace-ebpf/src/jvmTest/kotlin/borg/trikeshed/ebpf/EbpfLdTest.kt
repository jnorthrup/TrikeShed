package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfLdTest {

    @Test
    fun testLd64Imm() {
        // LD_DW_IMM requires two instructions
        // R1 = 0x1122334455667788
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_LD or EbpfOpcode.BPF_DW or EbpfOpcode.BPF_IMM, 1, 0, 0, 0x55667788)
        val inst2 = EbpfInstruction.pack(0, 0, 0, 0, 0x11223344)

        // R0 = R1
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_X, 0, 1, 0, 0)

        // EXIT
        val inst4 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw, inst4.raw))
        val interpreter = EbpfInterpreter(program)

        val result = interpreter.execute(ByteArray(0))
        assertEquals(0x1122334455667788L, result)
    }
}
