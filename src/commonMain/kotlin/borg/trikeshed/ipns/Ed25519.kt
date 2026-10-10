package borg.trikeshed.ipns

import borg.trikeshed.job.*
import borg.trikeshed.userspace.nio.platform.spi.*

/**
 * RFC 8032 Ed25519 (PureEdDSA over edwards25519 with SHA-512). [verify] is ed25519-dalek 2.2.0
 * VerifyingKey::verify_strict, the check loom-mesh applies to every signature: S < L, R and A not
 * of small order, A decoded as dalek decodes it, and [S]B - [k]A compared with R as bytes, so a
 * non-canonical R never verifies.
 */
object Ed25519 : IpnsCrypto {
    /** L = 2^252 + 27742317777372353535851937790883648493, the order of B, little endian. */
    val L = "edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010".hexToByteArray()
    val LIMBS = LongArray(32) { L[it].toLong() and 0xff }

    override fun generate(): IpnsKeyPair {
        val privateKey = ByteArray(32)
        platformGetRandom(privateKey)
        return IpnsKeyPair(publicKey(privateKey), privateKey)
    }

    /** RFC 8032 5.1.5: A = [s]B, s the pruned low half of SHA-512(private key). */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        return EdwardsPoint().also { it.mulBase(secretScalar(Sha512Pure.digest(privateKey))) }.encode()
    }

    /** RFC 8032 5.1.6. */
    override fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        val h = Sha512Pure.digest(privateKey)
        val s = secretScalar(h)
        val point = EdwardsPoint()
        point.mulBase(s)
        val publicKey = point.encode()
        val r = reduce(Sha512Pure.digest(h.copyOfRange(32, 64), payload))
        point.mulBase(r)
        val signature = point.encode().copyOf(64)
        val k = reduce(Sha512Pure.digest(signature.copyOf(32), publicKey, payload))
        mulAdd(k, s, r).copyInto(signature, 32)
        return signature
    }

    /** ed25519-dalek VerifyingKey::from_bytes then verify_strict. */
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64 || !isCanonical(signature, 32)) return false
        val a = EdwardsPoint()
        val r = EdwardsPoint()
        if (!a.decode(publicKey) || !r.decode(signature) || r.isSmallOrder() || a.isSmallOrder()) return false
        val k = reduce(Sha512Pure.digest(signature.copyOf(32), publicKey, payload))
        a.negate(a)
        r.vartimeDoubleScalarMulBasepoint(k, a, signature.copyOfRange(32, 64))
        return r.encode().contentEquals(signature.copyOf(32))
    }

    /**
     * ed25519-dalek VerifyingKey::is_weak: the key has small order. Bytes that are not a curve
     * point throw, as VerifyingKey::from_bytes refuses them before is_weak can be asked.
     */
    fun isWeak(publicKey: ByteArray): Boolean {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val a = EdwardsPoint()
        require(a.decode(publicKey)) { "Ed25519 public key is not a curve point" }
        return a.isSmallOrder()
    }

    /** RFC 8032 5.1.5 step 2: the low 32 bytes of [h] with bits 0-2 and 255 cleared and bit 254 set. */
    fun secretScalar(h: ByteArray): ByteArray = h.copyOf(32).also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = (it[31].toInt() and 127 or 64).toByte()
    }

    /** The 32 bytes at [offset] are below L (curve25519-dalek Scalar::from_canonical_bytes). */
    fun isCanonical(s: ByteArray, offset: Int = 0): Boolean {
        for (i in 31 downTo 0) {
            val x = s[offset + i].toInt() and 0xff
            val l = L[i].toInt() and 0xff
            if (x != l) return x < l
        }
        return false
    }

    /** A 64-byte little-endian integer mod L (ref10 sc_reduce, dalek Scalar::from_bytes_mod_order_wide). */
    fun reduce(x: ByteArray): ByteArray = modL(LongArray(64) { x[it].toLong() and 0xff })

    /** (a b + c) mod L for 32-byte little-endian a, b, c (ref10 sc_muladd). */
    fun mulAdd(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val x = LongArray(64)
        for (i in 0..31) x[i] = c[i].toLong() and 0xff
        for (i in 0..31) {
            val ai = a[i].toLong() and 0xff
            for (j in 0..31) x[i + j] += ai * (b[j].toLong() and 0xff)
        }
        return modL(x)
    }

    /** TweetNaCl modL: 64 byte-sized limbs reduced mod L to 32 bytes. */
    fun modL(x: LongArray): ByteArray {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * LIMBS[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0..31) {
            x[j] += carry - (x[31] shr 4) * LIMBS[j]
            carry = x[j] shr 8
            x[j] = x[j] and 0xff
        }
        for (j in 0..31) x[j] -= carry * LIMBS[j]
        val r = ByteArray(32)
        for (i in 0..31) {
            x[i + 1] += x[i] shr 8
            r[i] = x[i].toByte()
        }
        return r
    }
}
