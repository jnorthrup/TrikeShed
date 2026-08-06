package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.context.nuid.Nuid

class ActionDecoder {
    fun decode(bytes: ByteArray): ReactorActionEnvelope {
        if (bytes.size < 14) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + 14)
        }

        var offset = 0

        val magic = readInt(bytes, offset).also { offset += 4 }
        if (magic != WireprotoFrame.MAGIC) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_MAGIC + magic.toUInt().toString(16).uppercase())
        }

        val version = readShort(bytes, offset).also { offset += 2 }
        if (version != WireprotoFrame.VERSION) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_VERSION + version)
        }

        val nuidLen = readUShort(bytes, offset).also { offset += 2 }
        if (bytes.size < offset + nuidLen) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_NUID_LENGTH + nuidLen)
        }

        val nuidStr = bytes.decodeToString(offset, offset + nuidLen)
        offset += nuidLen

        if (bytes.size < offset + 2) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 2))
        }

        val verbLen = readUShort(bytes, offset).also { offset += 2 }
        if (bytes.size < offset + verbLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + verbLen))
        }

        val verb = bytes.decodeToString(offset, offset + verbLen)
        offset += verbLen

        if (bytes.size < offset + 4) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 4))
        }

        val payloadLen = readInt(bytes, offset).also { offset += 4 }
        if (payloadLen > WireprotoFrame.MAX_PAYLOAD) {
            throw WireprotoFormatException(WireprotoFormatException.OVERSIZE_PAYLOAD)
        }

        if (bytes.size < offset + payloadLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + payloadLen))
        }

        val payload = bytes.copyOfRange(offset, offset + payloadLen)

        val nuid = parseNuid(nuidStr)

        return ReactorActionEnvelope(nuid, verb, payload)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readShort(bytes: ByteArray, offset: Int): Short {
        return (((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)).toShort()
    }

    private fun readUShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
               (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun parseNuid(nuidStr: String): Nuid {
        val parts = nuidStr.split("|", limit = 3)
        if (parts.size != 3) {
            throw WireprotoFormatException("bad nuid format")
        }

        val capParts = parts[0].split(":", limit = 3)
        val capCat = capParts[0]
        val capToken = capParts.getOrNull(1) ?: ""

        val cap = when (capCat) {
            "custom" -> Capability.Custom(capParts.getOrNull(1) ?: "", capParts.getOrNull(2) ?: "")
            "process" -> Capability.Process(capToken)
            "cas" -> Capability.Cas(capToken)
            "wireproto" -> Capability.Wireproto(capToken)
            "sctp" -> Capability.Sctp
            "modelmux" -> Capability.Model
            "blackboard" -> Capability.BlackBoard
            else -> Capability.Custom(capCat, capToken)
        }

        val nonceParts = parts[1].split(":", limit = 2)
        val nonceType = nonceParts[0]
        val nonceBytesStr = nonceParts.getOrNull(1) ?: ""
        val nBytes = if (nonceBytesStr.isEmpty()) ByteArray(0) else nonceBytesStr.split(",").map { it.toByte() }.toByteArray()
        val nonce = if (nonceType == "derived") Nonce.Derived(nBytes.decodeToString()) else Nonce.Restored(nBytes)

        val subnet = Subnet.parse(parts[2])
        return nuid(cap, nonce, subnet)
    }
}
