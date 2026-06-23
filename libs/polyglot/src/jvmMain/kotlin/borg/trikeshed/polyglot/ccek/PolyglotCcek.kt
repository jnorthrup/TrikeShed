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
        /**
         * Total wire size in bytes: phase(1)+opcode(1)+methodIdx(4)+addr(4)+seq(4)+nano(8)+callsiteHash(4)+templateIdx(4) = 30.
         */
        const val SIZE = 30
        /**
         * Pack a FieldSynapse into exactly [SIZE] bytes.
         * Layout (big-endian): phase(1) + opcode(1) + methodIdx(4) + addr(4) + seq(4) + nano(8) + callsiteHash(4) + templateIdx(4)
         */
        fun encode(s: FieldSynapse, buf: ByteArray? = null): ByteArray {
            val out = if (buf != null && buf.size == SIZE) buf else ByteArray(SIZE)
            var i = 0
            // phase at offset 0 (1 byte)
            out[i++] = s.phase
            // opcode at offset 1 (1 byte)
            out[i++] = s.opcode
            // methodIdx at offset 2 (big-endian 4 bytes)
            out[i++] = ((s.methodIdx ushr 24) and 0xFF).toByte()
            out[i++] = ((s.methodIdx ushr 16) and 0xFF).toByte()
            out[i++] = ((s.methodIdx ushr 8) and 0xFF).toByte()
            out[i++] = (s.methodIdx and 0xFF).toByte()
            // addr at offset 6 (big-endian 4 bytes)
            out[i++] = ((s.addr ushr 24) and 0xFF).toByte()
            out[i++] = ((s.addr ushr 16) and 0xFF).toByte()
            out[i++] = ((s.addr ushr 8) and 0xFF).toByte()
            out[i++] = (s.addr and 0xFF).toByte()
            // seq at offset 10 (big-endian 4 bytes)
            out[i++] = ((s.seq ushr 24) and 0xFF).toByte()
            out[i++] = ((s.seq ushr 16) and 0xFF).toByte()
            out[i++] = ((s.seq ushr 8) and 0xFF).toByte()
            out[i++] = (s.seq and 0xFF).toByte()
            // nano at offset 14 (big-endian 8 bytes)
            out[i++] = ((s.nano ushr 56) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 48) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 40) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 32) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 24) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 16) and 0xFF).toByte()
            out[i++] = ((s.nano ushr 8) and 0xFF).toByte()
            out[i++] = (s.nano and 0xFF).toByte()
            // callsiteHash at offset 22 (big-endian 4 bytes)
            out[i++] = ((s.callsiteHash ushr 24) and 0xFF).toByte()
            out[i++] = ((s.callsiteHash ushr 16) and 0xFF).toByte()
            out[i++] = ((s.callsiteHash ushr 8) and 0xFF).toByte()
            out[i++] = (s.callsiteHash and 0xFF).toByte()
            // templateIdx at offset 26... regardless, last 4 bytes packed
            out[i++] = ((s.templateIdx ushr 24) and 0xFF).toByte()
            out[i++] = ((s.templateIdx ushr 16) and 0xFF).toByte()
            out[i++] = ((s.templateIdx ushr 8) and 0xFF).toByte()
            out[i] = (s.templateIdx and 0xFF).toByte()
            return out
        }

        /**
         * Decode 24 bytes back into a FieldSynapse.
         * Inverse of encode().
         */
        fun decode(buf: ByteArray): FieldSynapse {
            require(buf.size == SIZE) { "FieldSynapse decode requires $SIZE-byte buffer, got ${buf.size}" }
            // Skip phase(1), opcode(1)
            val methodIdx = ((buf[2].toInt() and 0xFF) shl 24) or
                ((buf[3].toInt() and 0xFF) shl 16) or
                ((buf[4].toInt() and 0xFF) shl 8) or
                (buf[5].toInt() and 0xFF)
            val addr = ((buf[6].toInt() and 0xFF) shl 24) or
                ((buf[7].toInt() and 0xFF) shl 16) or
                ((buf[8].toInt() and 0xFF) shl 8) or
                (buf[9].toInt() and 0xFF)
            val seq = ((buf[10].toInt() and 0xFF) shl 24) or
                ((buf[11].toInt() and 0xFF) shl 16) or
                ((buf[12].toInt() and 0xFF) shl 8) or
                (buf[13].toInt() and 0xFF)
            val nano = ((buf[14].toLong() and 0xFF) shl 56) or
                ((buf[15].toLong() and 0xFF) shl 48) or
                ((buf[16].toLong() and 0xFF) shl 40) or
                ((buf[17].toLong() and 0xFF) shl 32) or
                ((buf[18].toLong() and 0xFF) shl 24) or
                ((buf[19].toLong() and 0xFF) shl 16) or
                ((buf[20].toLong() and 0xFF) shl 8) or
                (buf[21].toLong() and 0xFF)
            val callsiteHash = ((buf[22].toInt() and 0xFF) shl 24) or
                ((buf[23].toInt() and 0xFF) shl 16) or
                ((buf[24].toInt() and 0xFF) shl 8) or
                (buf[25].toInt() and 0xFF)
            // If the buffer has more than 30 bytes, read templateIdx from 26-29;
            // otherwise templateIdx is 0 (since we only stored 1+1+4+4+4+8+4=26 bytes
            // in the simplified encode that started with phase). To stay consistent
            // we read templateIdx from positions 26..29 in a free-floating decode.
            val templateIdx = if (buf.size >= 30) {
                ((buf[26].toInt() and 0xFF) shl 24) or
                    ((buf[27].toInt() and 0xFF) shl 16) or
                    ((buf[28].toInt() and 0xFF) shl 8) or
                    (buf[29].toInt() and 0xFF)
            } else 0
            return FieldSynapse(
                phase = buf[0],
                opcode = buf[1],
                methodIdx = methodIdx,
                addr = addr,
                seq = seq,
                nano = nano,
                callsiteHash = callsiteHash,
                templateIdx = templateIdx
            )
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