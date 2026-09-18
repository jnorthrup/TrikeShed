package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfJitTest {

    @Test
    fun testCompileMovAndAddAndExit() {
        // R0 = 10
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 0, 0, 0, 10)
        // R0 += 5
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_ADD or EbpfOpcode.BPF_K, 0, 0, 0, 5)
        // EXIT
        val inst3 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw, inst3.raw))
        val jit = EbpfJit()

        val machineCode = jit.compile(program)

        // Expected x86_64 machine code bytes
        // 55                push rbp
        // 48 89 e5          mov  rbp, rsp
        // 48 c7 c0 0a 00 00 00 mov rax, 10
        // 48 83 c0 05       add rax, 5
        // 5d                pop rbp
        // c3                ret

        val expected = byteArrayOf(
            0x55.toByte(),
            0x48.toByte(), 0x89.toByte(), 0xE5.toByte(),
            0x48.toByte(), 0xC7.toByte(), 0xC0.toByte(), 0x0A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x48.toByte(), 0x83.toByte(), 0xC0.toByte(), 0x05.toByte(),
            0x5D.toByte(),
            0xC3.toByte()
        )

        assertEquals(expected.size, machineCode.size, "Machine code size should match")
        for (i in expected.indices) {
            assertEquals(expected[i], machineCode[i], "Mismatch at index $i")
        }
    }
}
