package borg.trikeshed.cursor

import kotlin.test.*

/**
 * FieldSynapse TDD — RED before GREEN
 *
 * Wireproto layout (24 bytes, little-endian):
 *   offset  0: opcode       u8
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: methodIdx    u16    — InternPool index
 *   offset  4: addr         i32
 *   offset  8: seq          i32
 *   offset 12: nano         i64
 *   offset 20: callsiteHash u16
 *   offset 22: templateIdx  u16
 *
 * Opcodes:
 *   0xA5 L_GET  — field read, static
 *   0xA6 L_SET  — field write, static
 *   0xA7 P_GET  — field read, polymorphic
 *   0xA8 P_SET  — field write, polymorphic
 *
 * Phase:
 *   0 = BEFORE (pre-modification observation)
 *   1 = AFTER  (post-modification observation)
 */
class FieldSynapseTest {

    // ── Opcode → wire value ────────────────────────────────────────

    private fun opcodeFor(op: FieldOpcode): Byte = when (op) {
        FieldOpcode.L_GET -> 0xA5
        FieldOpcode.L_SET -> 0xA6
        FieldOpcode.P_GET -> 0xA7
        FieldOpcode.P_SET -> 0xA8
    }

    // ── isSet truth table ──────────────────────────────────────────

    private fun expectedIsSet(op: FieldOpcode): Boolean = when (op) {
        FieldOpcode.L_GET -> false
        FieldOpcode.L_SET -> true
        FieldOpcode.P_GET -> false
        FieldOpcode.P_SET -> true
    }

    // ── Phase ─────────────────────────────────────────────────────

    private fun phaseOf(ph: Phase): Byte = when (ph) {
        Phase.BEFORE -> 0
        Phase.AFTER  -> 1
    }

    // ── Table-driven: all 4 opcodes × both phases ─────────────────

    @Test
    fun `opcode L_GET phase BEFORE`() {
        val fs = FieldSynapse(
            phase = Phase.BEFORE,
            opcode = FieldOpcode.L_GET,
            methodIdx = 1,
            addr = 0x0040,
            seq = 100,
            nano = 1_500_000_000L,
            callsiteHash = 0x1234,
            templateIdx = FieldSynapse.TPL_BEFORE_GET,
        )
        assertEquals(0xA5.toByte(), fs.opcode)
        assertFalse(fs.isSet)
        assertEquals(1, fs.methodIdx)
        assertEquals(0x0040, fs.addr)
        assertEquals(100, fs.seq)
        assertEquals(1_500_000_000L, fs.nano)
        assertEquals(0x1234, fs.callsiteHash)
        assertEquals(FieldSynapse.TPL_BEFORE_GET, fs.templateIdx)
    }

    @Test
    fun `opcode L_GET phase AFTER`() {
        val fs = FieldSynapse(
            phase = Phase.AFTER,
            opcode = FieldOpcode.L_GET,
            methodIdx = 2,
            addr = 0x0044,
            seq = 101,
            nano = 1_500_000_001L,
            callsiteHash = 0x1235,
            templateIdx = FieldSynapse.TPL_AFTER_GET,
        )
        assertEquals(0xA5.toByte(), fs.opcode)
        assertFalse(fs.isSet)
        assertEquals(FieldSynapse.TPL_AFTER_GET, fs.templateIdx)
    }

    @Test
    fun `opcode L_SET phase BEFORE`() {
        val fs = FieldSynapse(
            phase = Phase.BEFORE,
            opcode = FieldOpcode.L_SET,
            methodIdx = 3,
            addr = 0x0080,
            seq = 200,
            nano = 1_600_000_000L,
            callsiteHash = 0x2000,
            templateIdx = FieldSynapse.TPL_BEFORE_SET,
        )
        assertEquals(0xA6.toByte(), fs.opcode)
        assertTrue(fs.isSet)
        assertEquals(FieldSynapse.TPL_BEFORE_SET, fs.templateIdx)
    }

    @Test
    fun `opcode L_SET phase AFTER`() {
        val fs = FieldSynapse(
            phase = Phase.AFTER,
            opcode = FieldOpcode.L_SET,
            methodIdx = 4,
            addr = 0x0084,
            seq = 201,
            nano = 1_600_000_001L,
            callsiteHash = 0x2001,
            templateIdx = FieldSynapse.TPL_AFTER_SET,
        )
        assertEquals(0xA6.toByte(), fs.opcode)
        assertTrue(fs.isSet)
        assertEquals(FieldSynapse.TPL_AFTER_SET, fs.templateIdx)
    }

    @Test
    fun `opcode P_GET phase BEFORE`() {
        val fs = FieldSynapse(
            phase = Phase.BEFORE,
            opcode = FieldOpcode.P_GET,
            methodIdx = 5,
            addr = 0x00C0,
            seq = 300,
            nano = 1_700_000_000L,
            callsiteHash = 0x3000,
            templateIdx = FieldSynapse.TPL_BEFORE_GET,
        )
        assertEquals(0xA7.toByte(), fs.opcode)
        assertFalse(fs.isSet)
    }

    @Test
    fun `opcode P_GET phase AFTER`() {
        val fs = FieldSynapse(
            phase = Phase.AFTER,
            opcode = FieldOpcode.P_GET,
            methodIdx = 6,
            addr = 0x00C4,
            seq = 301,
            nano = 1_700_000_001L,
            callsiteHash = 0x3001,
            templateIdx = FieldSynapse.TPL_AFTER_GET,
        )
        assertEquals(0xA7.toByte(), fs.opcode)
        assertFalse(fs.isSet)
    }

    @Test
    fun `opcode P_SET phase BEFORE`() {
        val fs = FieldSynapse(
            phase = Phase.BEFORE,
            opcode = FieldOpcode.P_SET,
            methodIdx = 7,
            addr = 0x0100,
            seq = 400,
            nano = 1_800_000_000L,
            callsiteHash = 0x4000,
            templateIdx = FieldSynapse.TPL_BEFORE_SET,
        )
        assertEquals(0xA8.toByte(), fs.opcode)
        assertTrue(fs.isSet)
    }

    @Test
    fun `opcode P_SET phase AFTER`() {
        val fs = FieldSynapse(
            phase = Phase.AFTER,
            opcode = FieldOpcode.P_SET,
            methodIdx = 8,
            addr = 0x0104,
            seq = 401,
            nano = 1_800_000_001L,
            callsiteHash = 0x4001,
            templateIdx = FieldSynapse.TPL_AFTER_SET,
        )
        assertEquals(0xA8.toByte(), fs.opcode)
        assertTrue(fs.isSet)
    }

    // ── Template index constants ────────────────────────────────────

    @Test
    fun `TPL constants are sequential`() {
        assertEquals(0, FieldSynapse.TPL_BEFORE_GET)
        assertEquals(1, FieldSynapse.TPL_AFTER_GET)
        assertEquals(2, FieldSynapse.TPL_BEFORE_SET)
        assertEquals(3, FieldSynapse.TPL_AFTER_SET)
    }

    // ── isSet derivation ───────────────────────────────────────────

    @Test
    fun `isSet true for SET opcodes only`() {
        val gets = listOf(FieldOpcode.L_GET, FieldOpcode.P_GET)
        val sets = listOf(FieldOpcode.L_SET, FieldOpcode.P_SET)
        for (op in gets) {
            val fs = make(op, Phase.BEFORE)
            assertFalse(fs.isSet, "$op should not be a set")
        }
        for (op in sets) {
            val fs = make(op, Phase.BEFORE)
            assertTrue(fs.isSet, "$op should be a set")
        }
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun make(op: FieldOpcode, ph: Phase): FieldSynapse = FieldSynapse(
        phase = ph,
        opcode = op,
        methodIdx = 0,
        addr = 0,
        seq = 0,
        nano = 0L,
        callsiteHash = 0,
        templateIdx = 0,
    )
}