package borg.trikeshed.polyglot.graal

import borg.trikeshed.polyglot.ccek.FieldSynapse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for FieldSynapse wire encoding/decoding.
 * Verifies the 30-byte layout: phase(1)+opcode(1)+methodIdx(4)+addr(4)+seq(4)+nano(8)+callsiteHash(4)+templateIdx(4)
 */
class FieldSynapseWireTest {

    @Test
    fun `encode produces a buffer of SIZE bytes`() {
        val s = make(
            phase = 0, opcode = 0xA5.toByte(),
            methodIdx = 1, addr = 2, seq = 3, nano = 4L,
            callsiteHash = 5, templateIdx = 6,
        )
        val buf = FieldSynapse.encode(s)
        assertEquals(FieldSynapse.SIZE, buf.size, "Encoded buffer must be exactly SIZE bytes")
    }

    @Test
    fun `SIZE is 30 bytes per wire spec`() {
        assertEquals(30, FieldSynapse.SIZE, "1+1+4+4+4+8+4+4 = 30 bytes")
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val s = make(
            phase = 1, opcode = 0xA8.toByte(),
            methodIdx = 0x01020304, addr = 0x05060708,
            seq = 0x090A0B0C, nano = 0x0102030405060708L,
            callsiteHash = 0x11121314.toInt(), templateIdx = 0x15161718,
        )
        val buf = FieldSynapse.encode(s)
        val restored = FieldSynapse.decode(buf)
        assertEquals(s, restored, "Roundtrip must preserve all 8 fields byte-exact")
    }

    @Test
    fun `phase byte is at offset 0`() {
        val s = make(phase = 1)
        val buf = FieldSynapse.encode(s)
        assertEquals(1.toByte(), buf[0], "phase at offset 0")
    }

    @Test
    fun `opcode byte is at offset 1`() {
        val s = make(opcode = 0xA8.toByte())
        val buf = FieldSynapse.encode(s)
        assertEquals(0xA8.toByte(), buf[1], "opcode at offset 1")
    }

    @Test
    fun `methodIdx is big-endian at offset 2`() {
        val s = make(methodIdx = 0x01020304)
        val buf = FieldSynapse.encode(s)
        assertEquals(0x01.toByte(), buf[2])
        assertEquals(0x02.toByte(), buf[3])
        assertEquals(0x03.toByte(), buf[4])
        assertEquals(0x04.toByte(), buf[5])
    }

    @Test
    fun `nano is big-endian at offset 14`() {
        val s = make(nano = 0x0102030405060708L)
        val buf = FieldSynapse.encode(s)
        assertEquals(0x01.toByte(), buf[14])
        assertEquals(0x02.toByte(), buf[15])
        assertEquals(0x07.toByte(), buf[20])
        assertEquals(0x08.toByte(), buf[21])
    }

    @Test
    fun `decode rejects wrong-sized buffer`() {
        val tooSmall = ByteArray(10)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            FieldSynapse.decode(tooSmall)
        }
        assertTrue(ex.message!!.contains("30"))
    }

    @Test
    fun `encode accepts pre-allocated buffer of correct size`() {
        val s = make(seq = 42)
        val reusable = ByteArray(FieldSynapse.SIZE)
        val returned = FieldSynapse.encode(s, reusable)
        assertSame(reusable, returned, "Should reuse provided buffer")
        assertEquals(42, FieldSynapse.decode(returned).seq)
    }

    private fun make(
        phase: Byte = 0,
        opcode: Byte = 0xA5.toByte(),
        methodIdx: Int = 0,
        addr: Int = 0,
        seq: Int = 0,
        nano: Long = 0L,
        callsiteHash: Int = 0,
        templateIdx: Int = 0,
    ) = FieldSynapse(phase, opcode, methodIdx, addr, seq, nano, callsiteHash, templateIdx)
}