package borg.literbike.dht

/**
 * Multihash - Content addressing hash for IPFS/Kademlia.
 * Ported from literbike/src/dht/kademlia.rs (Multihash, HashType).
 */
data class Multihash(
    val hashType: HashType,
    val digest: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Multihash) return false
        return hashType == other.hashType && digest.contentEquals(other.digest)
    }

    override fun hashCode(): Int = hashType.hashCode() xor digest.contentHashCode()

    companion object {
        /** Create SHA256 multihash from data */
        fun sha256(data: ByteArray): Multihash {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            return Multihash(HashType.Sha256, digest)
        }

        /** Decode multihash from bytes */
        fun decode(bytes: ByteArray): Multihash? {
            if (bytes.size < 2) return null
            val typeCode = bytes[0].toInt() and 0xFF
            val size = bytes[1].toInt() and 0xFF
            if (bytes.size < 2 + size) return null

            val hashType = when (typeCode) {
                0x12 -> HashType.Sha256
                0x13 -> HashType.Sha512
                0x16 -> HashType.Sha3_256
                0x17 -> HashType.Sha3_512
                else -> return null
            }

            val digest = bytes.copyOfRange(2, 2 + size)
            return Multihash(hashType, digest)
        }
    }

    /** Encode multihash to bytes */
    fun encode(): ByteArray {
        val result = ByteArray(2 + digest.size)
        result[0] = hashType.code.toByte()
        result[1] = digest.size.toByte()
        digest.copyInto(result, 2)
        return result
    }
}

/**
 * Hash type enumeration for multihash.
 */
enum class HashType(val code: Int) {
    Sha256(0x12),
    Sha512(0x13),
    Sha3_256(0x16),
    Sha3_512(0x17),
    Blake2b256(0xb220),
    Blake2b512(0xb240)
}

/**
 * CID - Content Identifier for IPFS.
 * Ported from literbike/src/dht/kademlia.rs (CID, Codec).
 */
data class CID(
    val version: Int,
    val codec: Codec,
    val multihash: Multihash
) {
    companion object {
        /** Create CIDv0 from data (SHA256 + DagPb) */
        fun v0(data: ByteArray): CID {
            val multihash = Multihash.sha256(data)
            return CID(0, Codec.DagPb, multihash)
        }

        /** Create CIDv1 from data */
        fun v1(data: ByteArray, codec: Codec = Codec.Raw): CID {
            val multihash = Multihash.sha256(data)
            return CID(1, codec, multihash)
        }

        /** Decode CID from string */
        fun decode(s: String): CID? {
            return if (s.startsWith('b')) {
                // CIDv1 (Base32 prefix - simplified: uses hex in Rust)
                val hexPart = s.substring(1)
                val bytes = hexDecode(hexPart) ?: return null
                if (bytes.isEmpty() || bytes[0] != 1.toByte()) return null
                // Simplified varint codec parse
                if (bytes.size < 2) return null
                val codecVal = bytes[1].toLong() and 0xFF
                val codecEnum = codecToEnum(codecVal) ?: return null
                val mh = Multihash.decode(bytes.copyOfRange(2, bytes.size)) ?: return null
                CID(1, codecEnum, mh)
            } else {
                // CIDv0 (Base58)
                val bytes = base58Decode(s) ?: return null
                val mh = Multihash.decode(bytes) ?: return null
                CID(0, Codec.DagPb, mh)
            }
        }

        private fun codecToEnum(n: Long): Codec? = when (n) {
            0x70L -> Codec.DagPb
            0x71L -> Codec.DagCbor
            0x55L -> Codec.Raw
            0x0200L -> Codec.Json
            else -> null
        }

        private fun hexDecode(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return ByteArray(s.length / 2) { i ->
                s.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
    }

    /** Encode CID to string */
    fun encode(): String {
        return when (version) {
            0 -> base58Encode(multihash.encode())
            1 -> {
                val bytes = mutableListOf<Byte>()
                bytes.add(1) // version
                bytes.add(codec.code.toByte())
                bytes.addAll(multihash.encode().toList())
                "b" + bytes.toByteArray().toHexString()
            }
            else -> ""
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Codec enumeration for CID.
 */
enum class Codec(val code: Int) {
    DagPb(0x70),
    DagCbor(0x71),
    Raw(0x55),
    Json(0x0200)
}
