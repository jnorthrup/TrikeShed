package borg.trikeshed.ipns

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.Sha256Pure

/** Bounded protobuf wire primitives; schema owners decide which fields may repeat. */
object IpnsProtobuf {
    data class Field(val number: Int, val wireType: Int, val integer: ULong = 0u, val bytes: ByteArray = byteArrayOf())

    fun decode(wire: ByteArray, maxBytes: Int = 10_240, maxFields: Int = 256): List<Field> {
        require(maxBytes >= 0 && wire.size <= maxBytes) { "Protobuf size limit" }
        val reader = IpnsVarint(wire)
        val fields = mutableListOf<Field>()
        while (reader.position < wire.size) {
            require(fields.size < maxFields) { "Protobuf field limit" }
            val tag = reader.read()
            val number = tag shr 3
            require(number in 1u..536_870_911u) { "Invalid protobuf field number" }
            val type = (tag and 7u).toInt()
            fields += when (type) {
                0 -> Field(number.toInt(), type, reader.read())
                1, 5 -> Field(number.toInt(), type, bytes = reader.take(if (type == 1) 8 else 4))
                2 -> {
                    val length = reader.read()
                    require(length <= (wire.size - reader.position).toULong()) { "Truncated protobuf field" }
                    Field(number.toInt(), type, bytes = reader.take(length.toInt()))
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $type")
            }
        }
        return fields
    }

    fun bytes(number: Int, value: ByteArray): ByteArray = tag(number, 2) + IpnsEncoding.varint(value.size.toULong()) + value
    fun uint(number: Int, value: ULong): ByteArray = tag(number, 0) + IpnsEncoding.varint(value)
    fun tag(number: Int, wireType: Int): ByteArray {
        require(number in 1..536_870_911 && wireType in setOf(0, 1, 2, 5))
        return IpnsEncoding.varint((number.toULong() shl 3) or wireType.toULong())
    }
}

internal class IpnsVarint(val bytes: ByteArray) {
    var position: Int = 0
    fun read(): ULong {
        var result = 0uL
        for (index in 0..9) {
            require(position < bytes.size) { "Truncated varint" }
            val value = bytes[position++].toInt() and 255
            require(index != 9 || value <= 1) { "Varint overflow" }
            result = result or ((value and 127).toULong() shl (index * 7))
            if (value and 128 == 0) {
                require(index == 0 || value != 0) { "Non-minimal varint" }
                return result
            }
        }
        throw IllegalArgumentException("Varint overflow")
    }
    fun take(size: Int): ByteArray {
        require(size >= 0 && size <= bytes.size - position) { "Truncated bytes" }
        return bytes.copyOfRange(position, position + size).also { position += size }
    }
}

object IpnsEncoding {
    fun varint(value: ULong): ByteArray {
        var remaining = value
        val bytes = ByteArray(10)
        var size = 0
        do {
            val next = (remaining and 127u).toInt()
            remaining = remaining shr 7
            bytes[size++] = (next or if (remaining != 0uL) 128 else 0).toByte()
        } while (remaining != 0uL)
        return bytes.copyOf(size)
    }

    fun publicKey(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        return IpnsProtobuf.uint(1, 1u) + IpnsProtobuf.bytes(2, publicKey)
    }
    fun publicKeyRaw(protobuf: ByteArray): ByteArray {
        val fields = IpnsProtobuf.decode(protobuf, 64, 2)
        require(fields.size == 2 && fields.count { it.number == 1 } == 1 && fields.count { it.number == 2 } == 1) { "Invalid public key envelope" }
        val type = fields.single { it.number == 1 }
        val data = fields.single { it.number == 2 }
        require(type.wireType == 0 && type.integer == 1uL && data.wireType == 2 && data.bytes.size == 32) { "Only Ed25519 keys are supported" }
        return data.bytes
    }
    fun sha256(bytes: ByteArray): ByteArray = Sha256Pure.digest(bytes)
    fun multihash(code: ULong, digest: ByteArray): ByteArray = varint(code) + varint(digest.size.toULong()) + digest
    fun multihashParts(bytes: ByteArray): Pair<ULong, ByteArray> {
        require(bytes.size <= 512) { "Multihash size limit" }
        val reader = IpnsVarint(bytes)
        val code = reader.read()
        val size = reader.read()
        require(size == (bytes.size - reader.position).toULong()) { "Invalid multihash length" }
        require(size > 0u || code == 0uL) { "Empty non-identity multihash" }
        return code to reader.take(size.toInt())
    }

    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    fun base36(bytes: ByteArray): String = "k" + radixEncode(bytes, BASE36)
    fun base58(bytes: ByteArray): String = radixEncode(bytes, BASE58)
    fun base32(bytes: ByteArray): String {
        var bits = 0; var buffer = 0
        val text = StringBuilder("b")
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; text.append(BASE32[(buffer ushr bits) and 31]) }
        }
        if (bits > 0) text.append(BASE32[(buffer shl (5 - bits)) and 31])
        return text.toString()
    }
    fun unbase58(text: String): ByteArray = radixDecode(text, BASE58)
    fun unbase(text: String): ByteArray {
        require(text.length in 2..2048) { "Multibase size limit" }
        return when (text[0]) {
            'k', 'K' -> radixDecode(text.drop(1).lowercase(), BASE36)
            'z' -> radixDecode(text.drop(1), BASE58)
            'b', 'B' -> {
                var bits = 0; var buffer = 0; var count = 0
                val bytes = ByteArray((text.length - 1) * 5 / 8)
                for (char in text.drop(1).lowercase()) {
                    val digit = BASE32.indexOf(char)
                    require(digit >= 0) { "Invalid base32" }
                    buffer = (buffer shl 5) or digit; bits += 5
                    if (bits >= 8) { bits -= 8; bytes[count++] = (buffer ushr bits).toByte() }
                }
                require(bits < 5 && (buffer and ((1 shl bits) - 1)) == 0) { "Non-canonical base32 padding" }
                bytes
            }
            else -> throw IllegalArgumentException("Unsupported multibase")
        }
    }
    private fun radixEncode(bytes: ByteArray, alphabet: String): String {
        require(bytes.isNotEmpty() && bytes.size <= 512)
        val work = bytes.map { it.toInt() and 255 }.toIntArray()
        val zeroes = work.takeWhile { it == 0 }.size
        var start = zeroes
        val text = StringBuilder()
        while (start < work.size) {
            var remainder = 0
            for (i in start until work.size) {
                val value = (remainder shl 8) + work[i]
                work[i] = value / alphabet.length; remainder = value % alphabet.length
            }
            text.append(alphabet[remainder])
            while (start < work.size && work[start] == 0) start++
        }
        repeat(zeroes) { text.append(alphabet[0]) }
        return text.reverse().toString()
    }
    private fun radixDecode(text: String, alphabet: String): ByteArray {
        require(text.isNotEmpty() && text.length <= 2048) { "Base size limit" }
        val work = ByteArray(text.length)
        var size = 0
        for (char in text) {
            var carry = alphabet.indexOf(char)
            require(carry >= 0) { "Invalid base digit" }
            for (i in 0 until size) {
                carry += (work[i].toInt() and 255) * alphabet.length
                work[i] = carry.toByte(); carry = carry ushr 8
            }
            while (carry != 0) { work[size++] = carry.toByte(); carry = carry ushr 8 }
        }
        val zeroes = text.takeWhile { it == alphabet[0] }.length
        require(size + zeroes <= 512) { "Decoded base size limit" }
        return ByteArray(size + zeroes) { i -> if (i < zeroes) 0 else work[size - 1 - (i - zeroes)] }
    }
}

/** CIDv1, independent of the existing sha256:hex ContentId vocabulary. */
class IpnsCid(val codec: ULong, multihash: ByteArray) {
    private val hash = multihash.copyOf()
    init { require(codec > 0u); IpnsEncoding.multihashParts(hash) }
    val multihash: ByteArray get() = hash.copyOf()
    val bytes: ByteArray get() = IpnsEncoding.varint(1u) + IpnsEncoding.varint(codec) + hash
    override fun toString(): String = IpnsEncoding.base32(bytes)
    override fun equals(other: Any?): Boolean = other is IpnsCid && codec == other.codec && hash.contentEquals(other.hash)
    override fun hashCode(): Int = codec.hashCode() * 31 + hash.contentHashCode()
    companion object {
        fun parse(text: String): IpnsCid {
            if (text.startsWith("Qm")) return IpnsCid(0x70u, IpnsEncoding.unbase58(text).also {
                val (code, digest) = IpnsEncoding.multihashParts(it); require(code == 0x12uL && digest.size == 32)
            })
            return fromBytes(IpnsEncoding.unbase(text))
        }
        fun fromBytes(bytes: ByteArray): IpnsCid {
            if (bytes.size == 34 && bytes[0] == 0x12.toByte() && bytes[1] == 32.toByte()) return IpnsCid(0x70u, bytes)
            val reader = IpnsVarint(bytes)
            require(reader.read() == 1uL) { "Expected CIDv1" }
            val codec = reader.read()
            return IpnsCid(codec, reader.take(reader.bytes.size - reader.position))
        }
        fun raw(bytes: ByteArray): IpnsCid = IpnsCid(0x55u, IpnsEncoding.multihash(0x12u, IpnsEncoding.sha256(bytes)))
    }
}

/** The codec is explicit: a ContentId digest alone does not identify the encoded block format. */
fun ContentId.toIpnsCid(codec: ULong): IpnsCid = IpnsCid(codec, IpnsEncoding.multihash(0x12u,
    ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }))
