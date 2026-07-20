package borg.trikeshed.reactor.endpoint

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

class ConfixEnvelopeCodec(private val config: ReactorEndpointConfig = ReactorEndpointConfig()) {

    fun encode(envelope: ReactorActionEnvelope): ByteArray {
        require(envelope.payload.size <= config.maxPayloadBytes) {
            "payload ${envelope.payload.size} > max ${config.maxPayloadBytes}"
        }
        require(envelope.payload.size <= 65535) {
            "payload too large for 16-bit length"
        }
        require(envelope.verb in config.permittedVerbs) { "verb ${envelope.verb} not in permittedVerbs" }

        val nuidStr = "${serializeCapability(envelope.nuid.a)}|${toHex(envelope.nuid.b.a.bytes)}|${envelope.nuid.b.b.toString()}"
        val nuidBytes = nuidStr.encodeToByteArray()
        val verbBytes = envelope.verb.encodeToByteArray()

        val payloadLen = envelope.payload.size
        val totalLen = 8 + 2 + nuidBytes.size + 2 + verbBytes.size + payloadLen
        val out = ByteArray(totalLen)

        writeInt(out, 0, MAGIC)
        writeShort(out, 4, VERSION)
        writeShort(out, 6, payloadLen.toShort())

        var offset = 8

        writeShort(out, offset, nuidBytes.size.toShort())
        offset += 2
        for (i in nuidBytes.indices) out[offset++] = nuidBytes[i]

        writeShort(out, offset, verbBytes.size.toShort())
        offset += 2
        for (i in verbBytes.indices) out[offset++] = verbBytes[i]

        for (i in envelope.payload.indices) out[offset++] = envelope.payload[i]

        return out
    }

    fun decode(bytes: ByteArray): ReactorActionEnvelope {
        require(bytes.size >= 8) { "frame too short: ${bytes.size}" }
        val magic = readInt(bytes, 0)
        require(magic == MAGIC) { "bad magic 0x${magic.toString(16)}" }
        val version = readShort(bytes, 4)
        require(version == VERSION) { "bad version $version" }
        val payloadLen = readShort(bytes, 6).toInt() and 0xFFFF

        var offset = 8
        require(bytes.size >= offset + 2) { "frame too short for nuid length" }
        val nuidLen = readShort(bytes, offset).toInt() and 0xFFFF
        offset += 2

        require(bytes.size >= offset + nuidLen) { "frame too short for nuid" }
        val nuidBytes = ByteArray(nuidLen)
        for (i in 0 until nuidLen) nuidBytes[i] = bytes[offset++]
        val nuidStr = nuidBytes.decodeToString()

        require(bytes.size >= offset + 2) { "frame too short for verb length" }
        val verbLen = readShort(bytes, offset).toInt() and 0xFFFF
        offset += 2

        require(bytes.size >= offset + verbLen) { "frame too short for verb" }
        val verbBytes = ByteArray(verbLen)
        for (i in 0 until verbLen) verbBytes[i] = bytes[offset++]
        val verb = verbBytes.decodeToString()

        require(bytes.size >= offset + payloadLen) { "frame too short for payload" }
        val payload = ByteArray(payloadLen)
        for (i in 0 until payloadLen) payload[i] = bytes[offset++]

        val parts = nuidStr.split("|")
        val cap = deserializeCapability(parts[0])
        val nonce = Nonce.Restored(fromHex(parts[1]))
        val subnet = Subnet.parse(parts[2])

        return ReactorActionEnvelope(nuid(cap, nonce, subnet), verb, payload)
    }

    private fun serializeCapability(cap: Capability): String {
        return when (cap) {
            is Capability.Process -> "process:${cap.name}"
            is Capability.Cas -> "cas:${cap.mode}"
            is Capability.Wireproto -> "wireproto:${cap.route}"
            is Capability.Custom -> "custom:${cap.kind}:${cap.token}"
            is Capability.Sctp -> "sctp:"
            is Capability.Model -> "modelmux:"
            is Capability.BlackBoard -> "blackboard:"
            else -> "${cap.category}:"
        }
    }

    private fun deserializeCapability(str: String): Capability {
        val parts = str.split(":", limit = 3)
        val cat = parts[0]
        val arg = if (parts.size > 1) parts[1] else ""
        return when (cat) {
            "process" -> Capability.Process(arg)
            "cas" -> Capability.Cas(arg)
            "wireproto" -> Capability.Wireproto(arg)
            "custom" -> Capability.Custom(arg, if (parts.size > 2) parts[2] else "")
            "sctp" -> Capability.Sctp
            "modelmux" -> Capability.Model
            "blackboard" -> Capability.BlackBoard
            else -> Capability.Custom(cat, arg)
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt()
            result.append(hexChars[(i shr 4) and 0x0f])
            result.append(hexChars[i and 0x0f])
        }
        return result.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            result[i] = ((high shl 4) + low).toByte()
        }
        return result
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeShort(bytes: ByteArray, offset: Int, value: Short) {
        val v = value.toInt()
        bytes[offset] = (v ushr 8).toByte()
        bytes[offset + 1] = v.toByte()
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

    companion object {
        const val MAGIC: Int = 0xCAFEFACE.toInt()
        const val VERSION: Short = 1
    }
}
