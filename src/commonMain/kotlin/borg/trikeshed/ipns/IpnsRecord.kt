package borg.trikeshed.ipns

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import kotlin.time.Instant

typealias IpnsSequence = ULong
typealias IpnsTtlNanos = ULong

/** Verified Ed25519 IPNS V2 record; bytes and the name remain bound to the signature. */
class IpnsRecord private constructor(
    val name: IpnsName,
    wire: ByteArray,
    val value: String,
    val sequence: IpnsSequence,
    val validUntil: Instant,
    val ttlNanos: IpnsTtlNanos,
) : Comparable<IpnsRecord> {
    private val wire = wire.copyOf()
    val bytes: ByteArray get() = wire.copyOf()

    /** A cache hint may shorten validity, never extend the signed EOL. */
    fun cacheUntil(receivedAt: Instant): Instant {
        val seconds = (ttlNanos / 1_000_000_000u).toLong()
        val nanos = (ttlNanos % 1_000_000_000u).toLong()
        val ttlEnd = runCatching { Instant.fromEpochSeconds(receivedAt.epochSeconds + seconds,
            receivedAt.nanosecondsOfSecond.toLong() + nanos) }.getOrElse { return validUntil }
        return minOf(validUntil, ttlEnd)
    }

    override fun compareTo(other: IpnsRecord): Int {
        require(name == other.name) { "Cannot order records belonging to different names" }
        val version = sequence.compareTo(other.sequence)
        if (version != 0) return version
        val expiry = validUntil.compareTo(other.validUntil)
        if (expiry != 0) return expiry
        for (i in 0 until minOf(wire.size, other.wire.size)) {
            val delta = (wire[i].toInt() and 255) - (other.wire[i].toInt() and 255)
            if (delta != 0) return delta
        }
        return wire.size.compareTo(other.wire.size)
    }

    companion object {
        const val MAX_BYTES = 10_240
        private val SIGNATURE_PREFIX = "ipns-signature:".encodeToByteArray()

        fun create(identity: IpnsKeyPair, value: String, sequence: ULong, validUntil: Instant,
                   ttlNanos: ULong, crypto: IpnsCrypto): IpnsRecord {
            require(value.length <= MAX_BYTES) { "IPNS record size limit" }
            val data = IpnsDagCbor.encode(value.encodeToByteArray(), sequence,
                validUntil.toString().encodeToByteArray(), ttlNanos)
            require(data.size + 67 + IpnsEncoding.varint(data.size.toULong()).size <= MAX_BYTES) { "IPNS record size limit" }
            val signature = crypto.sign(identity.privateKey, SIGNATURE_PREFIX + data)
            require(signature.size == 64 && crypto.verify(identity.publicKey, SIGNATURE_PREFIX + data, signature)) {
                "IPNS identity does not match its private seed"
            }
            val wire = IpnsProtobuf.bytes(8, signature) + IpnsProtobuf.bytes(9, data)
            return verify(identity.name, wire, validUntil, crypto, allowExpired = true)
        }

        /** allowExpired is for authenticated journal recovery; routing/cache reads leave it false. */
        fun verify(name: IpnsName, wire: ByteArray, now: Instant, crypto: IpnsCrypto,
                   allowExpired: Boolean = false): IpnsRecord {
            require(wire.size <= MAX_BYTES) { "IPNS record size limit" }
            val fields = IpnsProtobuf.decode(wire, MAX_BYTES, 512)
            val known = fields.filter { it.number in 1..9 }
            require(known.map { it.number }.toSet().size == known.size) { "Duplicate IPNS protobuf field" }
            val byNumber = known.associateBy { it.number }
            for ((number, field) in byNumber) require(field.wireType == if (number in setOf(3, 5, 6)) 0 else 2) {
                "Wrong IPNS protobuf wire type"
            }
            val signature = byNumber[8]?.bytes ?: throw IllegalArgumentException("Missing IPNS V2 signature")
            val data = byNumber[9]?.bytes ?: throw IllegalArgumentException("Missing IPNS signed data")
            require(signature.size == 64 && data.isNotEmpty()) { "Empty or malformed IPNS V2 fields" }
            val publicKey = byNumber[7]?.let { IpnsEncoding.publicKeyRaw(it.bytes) } ?: name.publicKey
            require(name.matches(publicKey)) { "IPNS public key does not match the requested name" }
            val decoded = IpnsDagCbor.decode(data)
            require(crypto.verify(publicKey, SIGNATURE_PREFIX + data, signature)) { "Invalid IPNS V2 signature" }
            fun uint(key: String): ULong = decoded[key] as? ULong ?: throw IllegalArgumentException("Missing/invalid IPNS $key")
            fun bin(key: String): ByteArray = decoded[key] as? ByteArray ?: throw IllegalArgumentException("Missing/invalid IPNS $key")
            val valueBytes = bin("Value")
            val validity = bin("Validity")
            val validityType = uint("ValidityType")
            val sequence = uint("Sequence")
            val ttl = uint("TTL")
            require(validityType == 0uL) { "Unsupported IPNS validity type" }
            if (byNumber.containsKey(1) || byNumber.containsKey(2)) {
                require((byNumber[1]?.bytes ?: byteArrayOf()).contentEquals(valueBytes) &&
                    (byNumber[4]?.bytes ?: byteArrayOf()).contentEquals(validity) &&
                    (byNumber[3]?.integer ?: 0u) == validityType &&
                    (byNumber[5]?.integer ?: 0u) == sequence && (byNumber[6]?.integer ?: 0u) == ttl) {
                    "IPNS legacy fields disagree with signed V2 data"
                }
            }
            require(validity.all { it.toInt() in 0..127 }) { "IPNS EOL must be ASCII" }
            val eol = validity.decodeToString(throwOnInvalidSequence = true)
            require(RFC3339.matches(eol)) { "Invalid IPNS RFC3339 EOL" }
            val validUntil = Instant.parse(eol)
            require(allowExpired || now < validUntil) { "Expired IPNS record" }
            val value = when {
                valueBytes.isEmpty() -> "/ipfs/bafkqaaa"
                valueBytes[0] == '/'.code.toByte() -> IpnsDagCbor.text(valueBytes)
                else -> "/ipfs/${IpnsCid.fromBytes(valueBytes)}"
            }
            require('\u0000' !in value) { "NUL in IPNS path" }
            return IpnsRecord(name, wire, value, sequence, validUntil, ttl)
        }

        private val RFC3339 = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})")
    }
}

/** Strict DAG-CBOR boundary: canonical heads/key order, bounded nesting, no duplicate keys or trailing bytes. */
internal object IpnsDagCbor {
    fun text(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid IPNS UTF-8", failure)
    }

    fun encode(value: ByteArray, sequence: ULong, validity: ByteArray, ttl: ULong): ByteArray {
        val fields = listOf("TTL" to head(0, ttl), "Value" to Cbor.encode(Item.Bin(value)),
            "Sequence" to head(0, sequence), "Validity" to Cbor.encode(Item.Bin(validity)),
            "ValidityType" to head(0, 0u))
        return byteArrayOf(0xa5.toByte()) + fields.fold(byteArrayOf()) { bytes, (key, encoded) ->
            bytes + Cbor.encode(Item.Str(key)) + encoded
        }
    }
    private fun head(major: Int, value: ULong): ByteArray {
        val width = when { value < 24u -> 0; value <= 255u -> 1; value <= 65535u -> 2; value <= UInt.MAX_VALUE.toULong() -> 4; else -> 8 }
        val additional = when (width) { 0 -> value.toInt(); 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 }
        return ByteArray(width + 1) { i -> if (i == 0) ((major shl 5) or additional).toByte()
            else (value shr ((width - i) * 8)).toByte() }
    }
    fun decode(bytes: ByteArray): Map<String, Any?> {
        require(bytes.size <= IpnsRecord.MAX_BYTES)
        val reader = Reader(bytes)
        val result = reader.item(0)
        require(reader.position == bytes.size && result is Map<*, *>) { "Invalid IPNS DAG-CBOR root/trailing bytes" }
        @Suppress("UNCHECKED_CAST")
        return result as Map<String, Any?>
    }
    private class Reader(val bytes: ByteArray) {
        var position = 0
        var nodes = 0
        fun byte(): Int { require(position < bytes.size) { "Truncated DAG-CBOR" }; return bytes[position++].toInt() and 255 }
        fun take(size: ULong): ByteArray {
            require(size <= (bytes.size - position).toULong()) { "Truncated DAG-CBOR value" }
            return bytes.copyOfRange(position, position + size.toInt()).also { position += size.toInt() }
        }
        fun item(depth: Int): Any? {
            require(depth <= 32 && nodes++ < 2048) { "DAG-CBOR complexity limit" }
            val initial = byte(); val major = initial ushr 5; val extra = initial and 31
            require(extra <= 27) { "Indefinite/reserved DAG-CBOR item" }
            val width = when (extra) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> 0 }
            var value = if (width == 0) extra.toULong() else 0uL
            repeat(width) { value = (value shl 8) or byte().toULong() }
            if (major != 7 && width > 0) require(when (width) {
                1 -> value >= 24u; 2 -> value > 255u; 4 -> value > 65535u; else -> value > UInt.MAX_VALUE.toULong()
            }) { "Non-minimal DAG-CBOR head" }
            return when (major) {
                0 -> value
                1 -> { require(value <= Long.MAX_VALUE.toULong()) { "DAG-CBOR negative integer range" }; -1L - value.toLong() }
                2 -> take(value)
                3 -> text(take(value))
                4 -> { require(value <= (bytes.size - position).toULong()); List(value.toInt()) { item(depth + 1) } }
                5 -> {
                    require(value <= (bytes.size - position).toULong() / 2u)
                    val map = linkedMapOf<String, Any?>()
                    var prior: ByteArray? = null
                    repeat(value.toInt()) {
                        val key = item(depth + 1) as? String ?: throw IllegalArgumentException("DAG-CBOR map key is not text")
                        val encoded = key.encodeToByteArray()
                        prior?.let { require(compareKeys(it, encoded) < 0) { "Non-canonical/duplicate DAG-CBOR key" } }
                        prior = encoded
                        map[key] = item(depth + 1)
                    }
                    map
                }
                6 -> {
                    require(value == 42uL) { "Unsupported DAG-CBOR tag" }
                    val cid = item(depth + 1) as? ByteArray ?: throw IllegalArgumentException("DAG-CBOR CID is not bytes")
                    require(cid.isNotEmpty() && cid[0] == 0.toByte()) { "DAG-CBOR CID identity prefix missing" }
                    IpnsCid.fromBytes(cid.copyOfRange(1, cid.size))
                }
                7 -> when (extra) {
                    20 -> false; 21 -> true; 22 -> null
                    27 -> Double.fromBits(value.toLong()).also { require(it.isFinite()) { "Non-finite DAG-CBOR float" } }
                    else -> throw IllegalArgumentException("Unsupported DAG-CBOR simple/float encoding")
                }
                else -> throw IllegalArgumentException("Invalid DAG-CBOR")
            }
        }
        fun compareKeys(a: ByteArray, b: ByteArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices) { val d = (a[i].toInt() and 255) - (b[i].toInt() and 255); if (d != 0) return d }
            return 0
        }
    }
}
