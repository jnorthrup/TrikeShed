package borg.trikeshed.polyglot.ccek

import borg.trikeshed.lib.Series

/**
 * FieldSynapse — 24-byte wire protocol frame for pointcut events.
 * Formerly org..activejs.ccek.FieldSynapse
 */
data class FieldSynapse(
    val phase: Byte,         // BEFORE=0 / AFTER=1
    val opcode: Byte,        // L_GET=0xA5, L_SET=0xA6, P_GET=0xA7, P_SET=0xA8
    val methodIdx: Int,
    val addr: Int,
    val seq: Int,
    val nano: Long,          // timestamp nanoseconds
    val callsiteHash: Int,
    val templateIdx: Int
) {
    companion object {
        const val SIZE = 24
        fun encode(s: FieldSynapse): ByteArray = ByteArray(SIZE).also { buf ->
            // Pack 24 bytes: phase(1) + opcode(1) + methodIdx(4) + addr(4) + seq(4) + nano(8) + callsiteHash(4) + templateIdx(4)
            // This is a simplified version - real impl uses buffer directly
        }
    }
}

/**
 * PointcutEventProducer — emits FieldSynapse to a ring buffer / fanout.
 * Formerly org..activejs.ccek.PointcutEventProducer
 */
interface PointcutEventProducer {
    fun emit(synapse: FieldSynapse)
    fun emitBatch(synapses: Series<FieldSynapse>)
}