package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfInstructionTest {
    @Test
    fun testPackAndUnpack() {
        val opcode = 0x07
        val dstReg = 0x1
        val srcReg = 0x2
        val offset: Short = 0x1234
        val imm = 0x56789ABC

        val inst = EbpfInstruction.pack(opcode, dstReg, srcReg, offset, imm)

        assertEquals(opcode, inst.opcode)
        assertEquals(dstReg, inst.dstReg)
        assertEquals(srcReg, inst.srcReg)
        assertEquals(offset, inst.offset)
        assertEquals(imm, inst.imm)
    }
}
