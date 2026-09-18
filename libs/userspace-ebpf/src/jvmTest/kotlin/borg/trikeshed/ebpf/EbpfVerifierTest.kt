package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertFailsWith

class EbpfVerifierTest {

    @Test
    fun testValidProgram() {
        // R0 = 0
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 0, 0, 0, 0)
        // EXIT
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw))
        EbpfVerifier(program).verify() // Should not throw
    }

    @Test
    fun testInfiniteLoopRejection() {
        // JA -1 (jump to self)
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_JA, 0, 0, -1, 0)
        // EXIT
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw))
        assertFailsWith<EbpfVerifierError> {
            EbpfVerifier(program).verify()
        }
    }

    @Test
    fun testOutOfBoundsJumpRejection() {
        // JA +5 (jump out of program bounds)
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_JA, 0, 0, 5, 0)
        // EXIT
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw))
        assertFailsWith<EbpfVerifierError> {
            EbpfVerifier(program).verify()
        }
    }
}
