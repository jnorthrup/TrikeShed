package org.xvm.cursor

/**
 * VM opcode pointcut kinds for CRUdux event journal.
 * Subset of [org.xvm.asm.Op] opcodes chosen for high signal in testing/replay.
 */
enum class VmPointcutKind(val opcode: Int) {
    // ── Call sites ──────────────────────────────────────────────────────────
    CALL_00(0x10), CALL_01(0x11), CALL_0N(0x12), CALL_0T(0x13),
    CALL_10(0x14), CALL_11(0x15), CALL_1N(0x16), CALL_1T(0x17),
    CALL_N0(0x18), CALL_N1(0x19), CALL_NN(0x1A), CALL_NT(0x1B),
    CALL_T0(0x1C), CALL_T1(0x1D), CALL_TN(0x1E), CALL_TT(0x1F),
    // ── Virtual invoke ───────────────────────────────────────────────────────
    NVOK_00(0x20), NVOK_01(0x21), NVOK_0N(0x22), NVOK_0T(0x23),
    NVOK_10(0x24), NVOK_11(0x25), NVOK_1N(0x26), NVOK_1T(0x27),
    NVOK_N0(0x28), NVOK_N1(0x29), NVOK_NN(0x2A), NVOK_NT(0x2B),
    NVOK_T0(0x2C), NVOK_T1(0x2D), NVOK_TN(0x2E), NVOK_TT(0x2F),
    // ── Synchronization / construction ──────────────────────────────────────
    SYN_INIT(0x33),
    CONSTR_0(0x34), CONSTR_1(0x35), CONSTR_N(0x36), CONSTR_T(0x37),
    // ── Object allocation ───────────────────────────────────────────────────
    NEW_0(0x38), NEW_1(0x39), NEW_N(0x3A), NEW_T(0x3B),
    NEWC_0(0x40), NEWC_1(0x41), NEWC_N(0x42), NEWC_T(0x43),
    NEWV_0(0x48), NEWV_1(0x49), NEWV_N(0x4A), NEWV_T(0x4B),
    // ── Returns ────────────────────────────────────────────────────────────
    RETURN_0(0x4C), RETURN_1(0x4D), RETURN_N(0x4E), RETURN_T(0x4F),
    // ── Type moves / casts ────────────────────────────────────────────────
    MOV_TYPE(0x65), CAST(0x66),
    // ── Property access ───────────────────────────────────────────────────
    L_GET(0xA5), L_SET(0xA6),
    P_GET(0xA7), P_SET(0xA8),
    // ── Assertions ────────────────────────────────────────────────────────
    ASSERT(0x90), ASSERT_M(0x91), ASSERT_V(0x92),
    // ── Control flow ───────────────────────────────────────────────────────
    LOOP(0x77), LOOP_END(0x78),
    JMP(0x79), JMP_TRUE(0x7A), JMP_FALSE(0x7B),
    // ── Unmatched (catch-all for unknown opcodes) ───────────────────────────
    UNKNOWN(-1),
    ;

    companion object {
        private val map = entries.associateBy { it.opcode }
        fun fromOpcode(op: Int): VmPointcutKind = map[op] ?: UNKNOWN
    }
}
