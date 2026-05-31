package borg.trikeshed.cursor

/**
 * Synapse record for field pointcut events (P_GET, P_SET, L_GET, L_SET).
 *
 * Wireproto record (24 bytes, little-endian):
 *   offset  0: opcode       u8
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: methodIdx    u16    — InternPool index
 *   offset  4: addr         i32
 *   offset  8: seq          i32
 *   offset 12: nano         i64
 *   offset 20: callsiteHash u16
 *   offset 22: templateIdx  u16
 */
data class FieldSynapse(
    val phase: Byte,         // 0=BEFORE, 1=AFTER
    val opcode: Byte,        // 0xA5=L_GET, 0xA6=L_SET, 0xA7=P_GET, 0xA8=P_SET
    val methodIdx: Int,     // InternPool index
    val addr: Int,           // PC address
    val seq: Int,            // monotonic sequence
    val nano: Long,          // System.nanoTime() at publish
    val callsiteHash: Int,   // FNV-1a hash of (opcode, methodIdx, addr)
    val templateIdx: Int,   // InternPool index of format template
) {
    val isSet: Boolean get() = (opcode.toInt() and 0xFF) == 0xA6 || (opcode.toInt() and 0xFF) == 0xA8

    companion object {
        // Template indices (set by InternPool in xvm's FieldSynapse)
        const val TPL_BEFORE_GET = 0
        const val TPL_AFTER_GET  = 1
        const val TPL_BEFORE_SET = 2
        const val TPL_AFTER_SET  = 3

        const val OP_L_GET = 4
        const val OP_L_SET = 5
        const val OP_P_GET = 6
        const val OP_P_SET = 7
    }
}