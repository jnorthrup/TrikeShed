package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid

class ActionDecoder {
    fun decode(bytes: ByteArray): ReactorActionEnvelope {
        if (bytes.size < 14) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + 14)
        }
<<<<<<< HEAD
        
        var offset = 0
        
=======

        var offset = 0

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Magic
        val m1 = bytes[offset++].toInt() and 0xFF
        val m2 = bytes[offset++].toInt() and 0xFF
        val m3 = bytes[offset++].toInt() and 0xFF
        val m4 = bytes[offset++].toInt() and 0xFF
        val magic = (m1 shl 24) or (m2 shl 16) or (m3 shl 8) or m4
        if (magic != WireprotoFrame.MAGIC) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_MAGIC + magic.toUInt().toString(16).uppercase())
        }
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Version
        val v1 = bytes[offset++].toInt() and 0xFF
        val v2 = bytes[offset++].toInt() and 0xFF
        val version = ((v1 shl 8) or v2).toShort()
        if (version != WireprotoFrame.VERSION) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_VERSION + version)
        }
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        // NUID length
        val nl1 = bytes[offset++].toInt() and 0xFF
        val nl2 = bytes[offset++].toInt() and 0xFF
        val nuidLen = (nl1 shl 8) or nl2
<<<<<<< HEAD
        
        if (bytes.size < offset + nuidLen) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_NUID_LENGTH + nuidLen)
        }
        
        val nuidBytes = bytes.copyOfRange(offset, offset + nuidLen)
        offset += nuidLen
        val nuidStr = nuidBytes.decodeToString()
        
        if (bytes.size < offset + 2) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 2))
        }
        
=======

        if (bytes.size < offset + nuidLen) {
            throw WireprotoFormatException(WireprotoFormatException.BAD_NUID_LENGTH + nuidLen)
        }

        val nuidBytes = bytes.copyOfRange(offset, offset + nuidLen)
        offset += nuidLen
        val nuidStr = nuidBytes.decodeToString()

        if (bytes.size < offset + 2) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 2))
        }

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Verb length
        val vl1 = bytes[offset++].toInt() and 0xFF
        val vl2 = bytes[offset++].toInt() and 0xFF
        val verbLen = (vl1 shl 8) or vl2
<<<<<<< HEAD
        
        if (bytes.size < offset + verbLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + verbLen))
        }
        
        val verbBytes = bytes.copyOfRange(offset, offset + verbLen)
        offset += verbLen
        val verb = verbBytes.decodeToString()
        
        if (bytes.size < offset + 4) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 4))
        }
        
=======

        if (bytes.size < offset + verbLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + verbLen))
        }

        val verbBytes = bytes.copyOfRange(offset, offset + verbLen)
        offset += verbLen
        val verb = verbBytes.decodeToString()

        if (bytes.size < offset + 4) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + 4))
        }

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Payload length
        val pl1 = bytes[offset++].toInt() and 0xFF
        val pl2 = bytes[offset++].toInt() and 0xFF
        val pl3 = bytes[offset++].toInt() and 0xFF
        val pl4 = bytes[offset++].toInt() and 0xFF
        val payloadLen = (pl1 shl 24) or (pl2 shl 16) or (pl3 shl 8) or pl4
<<<<<<< HEAD
        
        if (payloadLen > WireprotoFrame.MAX_PAYLOAD) {
            throw WireprotoFormatException(WireprotoFormatException.OVERSIZE_PAYLOAD)
        }
        
        if (bytes.size < offset + payloadLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + payloadLen))
        }
        
        val payload = bytes.copyOfRange(offset, offset + payloadLen)
        offset += payloadLen
        
        // Deserialize Nuid
        // Note: as instructed, using custom format as fallback when Nuid toString is used.
        // Wait, I am asked to use canonical Nuid.toString. But Nuid doesn't override toString. 
=======

        if (payloadLen > WireprotoFrame.MAX_PAYLOAD) {
            throw WireprotoFormatException(WireprotoFormatException.OVERSIZE_PAYLOAD)
        }

        if (bytes.size < offset + payloadLen) {
            throw WireprotoFormatException(WireprotoFormatException.TRUNCATED + (offset + payloadLen))
        }

        val payload = bytes.copyOfRange(offset, offset + payloadLen)
        offset += payloadLen

        // Deserialize Nuid
        // Note: as instructed, using custom format as fallback when Nuid toString is used.
        // Wait, I am asked to use canonical Nuid.toString. But Nuid doesn't override toString.
>>>>>>> origin/wireproto-codec-9444185639294947999
        // We will just decode what we serialized in ActionEncoder.
        val parts = nuidStr.split("|", limit = 3)
        if (parts.size != 3) {
            throw WireprotoFormatException("bad nuid format")
        }
<<<<<<< HEAD
        
        val capParts = parts[0].split(":", limit = 3)
        val capCat = capParts[0]
        val capToken = capParts.getOrNull(1) ?: ""
        
=======

        val capParts = parts[0].split(":", limit = 3)
        val capCat = capParts[0]
        val capToken = capParts.getOrNull(1) ?: ""

>>>>>>> origin/wireproto-codec-9444185639294947999
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
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        val nonceParts = parts[1].split(":", limit = 2)
        val nonceType = nonceParts[0]
        val nonceBytesStr = nonceParts.getOrNull(1) ?: ""
        val nBytes = if (nonceBytesStr.isEmpty()) ByteArray(0) else nonceBytesStr.split(",").map { it.toByte() }.toByteArray()
        val nonce = if (nonceType == "derived") Nonce.Derived(nBytes.decodeToString()) else Nonce.Restored(nBytes)
<<<<<<< HEAD
        
        val subnet = Subnet.parse(parts[2])
        val nuid = nuid(cap, nonce, subnet)
        
=======

        val subnet = Subnet.parse(parts[2])
        val nuid = nuid(cap, nonce, subnet)

>>>>>>> origin/wireproto-codec-9444185639294947999
        return ReactorActionEnvelope(nuid, verb, payload)
    }
}
