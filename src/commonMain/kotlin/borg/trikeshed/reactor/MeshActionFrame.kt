package borg.trikeshed.reactor

import borg.trikeshed.lcnc.reactor.ReactorAction

class MeshActionFrame(val payload: ByteArray) {
    companion object {
        fun encode(action: ReactorAction): MeshActionFrame {
            val payloadBytes = action.toString().encodeToByteArray()
            return MeshActionFrame(payloadBytes)
        }

        fun decode(bytes: ByteArray): MeshActionFrame {
            if (bytes.size < 4) throw IllegalArgumentException("truncated frame")
            val length = ((bytes[0].toInt() and 0xFF) shl 24) or
                         ((bytes[1].toInt() and 0xFF) shl 16) or
                         ((bytes[2].toInt() and 0xFF) shl 8) or
                         (bytes[3].toInt() and 0xFF)
            if (length > 100_000_000) throw IllegalArgumentException("payload too large")
            if (bytes.size < 4 + length) throw IllegalArgumentException("truncated frame")
            val payload = bytes.copyOfRange(4, 4 + length)
            return MeshActionFrame(payload)
        }

        fun encodeBytes(payloadBytes: ByteArray): ByteArray {
            val length = payloadBytes.size
            val frameBytes = ByteArray(4 + length)
            frameBytes[0] = (length ushr 24).toByte()
            frameBytes[1] = (length ushr 16).toByte()
            frameBytes[2] = (length ushr 8).toByte()
            frameBytes[3] = length.toByte()
            payloadBytes.copyInto(frameBytes, 4)
            return frameBytes
        }
    }
}
