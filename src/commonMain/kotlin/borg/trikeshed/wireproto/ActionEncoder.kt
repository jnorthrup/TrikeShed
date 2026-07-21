package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce

class ActionEncoder {
    fun encode(envelope: ReactorActionEnvelope): ByteArray {
        val payload = envelope.payload
        if (payload.size > WireprotoFrame.MAX_PAYLOAD) {
            throw WireprotoFormatException(WireprotoFormatException.OVERSIZE_PAYLOAD)
        }

        // Convert Nuid to a deterministic string
        val nuid = envelope.nuid
        val cap = nuid.a
        val nonce = nuid.b.a
        val subnet = nuid.b.b

        val capStr = if (cap is Capability.Custom) {
            "custom:${cap.kind}:${cap.token}"
        } else if (cap is Capability.Process) {
            "process:${cap.name}"
        } else if (cap is Capability.Cas) {
            "cas:${cap.mode}"
        } else if (cap is Capability.Wireproto) {
            "wireproto:${cap.route}"
        } else if (cap is Capability.Sctp) {
            "sctp"
        } else if (cap is Capability.Model) {
            "modelmux"
        } else if (cap is Capability.BlackBoard) {
            "blackboard"
        } else {
            "unknown"
        }

        val nonceStr = if (nonce is Nonce.Derived) {
            "derived:${nonce.bytes.joinToString(",")}"
        } else {
            "restored:${nonce.bytes.joinToString(",")}"
        }

        val subnetStr = subnet.toString()
        val nuidStr = "$capStr|$nonceStr|$subnetStr"
        val nuidBytes = nuidStr.encodeToByteArray()

        val verbBytes = envelope.verb.encodeToByteArray()
        val nuidLen = nuidBytes.size
        val verbLen = verbBytes.size

        if (nuidLen > 65535) throw WireprotoFormatException(WireprotoFormatException.BAD_NUID_LENGTH + nuidLen)
        if (verbLen > 65535) throw WireprotoFormatException(WireprotoFormatException.BAD_VERB_LENGTH + verbLen)

        // Total size: 4 (magic) + 2 (version) + 2 (nuid_length) + nuidLen + 2 (verb_length) + verbLen + 4 (payload_length) + payload.size
        val totalSize = 14 + nuidLen + verbLen + payload.size
        val bytes = ByteArray(totalSize)
        var offset = 0

        // Magic (4 bytes BE)
        val magic = WireprotoFrame.MAGIC
        bytes[offset++] = (magic ushr 24).toByte()
        bytes[offset++] = (magic ushr 16).toByte()
        bytes[offset++] = (magic ushr 8).toByte()
        bytes[offset++] = magic.toByte()

        // Version (2 bytes BE)
        val version = WireprotoFrame.VERSION.toInt()
        bytes[offset++] = (version ushr 8).toByte()
        bytes[offset++] = version.toByte()

        // NUID length (2 bytes BE)
        bytes[offset++] = (nuidLen ushr 8).toByte()
        bytes[offset++] = nuidLen.toByte()

        // NUID bytes
        nuidBytes.copyInto(bytes, offset)
        offset += nuidLen

        // Verb length (2 bytes BE)
        bytes[offset++] = (verbLen ushr 8).toByte()
        bytes[offset++] = verbLen.toByte()

        // Verb bytes
        verbBytes.copyInto(bytes, offset)
        offset += verbLen

        // Payload length (4 bytes BE)
        val payloadLen = payload.size
        bytes[offset++] = (payloadLen ushr 24).toByte()
        bytes[offset++] = (payloadLen ushr 16).toByte()
        bytes[offset++] = (payloadLen ushr 8).toByte()
        bytes[offset++] = payloadLen.toByte()

        // Payload bytes
        payload.copyInto(bytes, offset)

        return bytes
    }
}
