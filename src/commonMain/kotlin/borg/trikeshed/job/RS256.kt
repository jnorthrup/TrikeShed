@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.job

import kotlin.io.encoding.*

/** RFC 8017 A.1.1 RSAPublicKey: modulus n and publicExponent e as unsigned big-endian integers. */
class RSAPublicKey(val modulus: ByteArray, val publicExponent: ByteArray)

/**
 * RFC 8017 A.1.2 RSAPrivateKey (version 0, two primes) as unsigned big-endian integers. The primes and
 * CRT values are parsed and not kept: [RS256] exponentiates by d mod n, the same signature CRT yields.
 */
class RSAPrivateKey(val modulus: ByteArray, val publicExponent: ByteArray, val privateExponent: ByteArray) {
    val publicKey: RSAPublicKey get() = RSAPublicKey(modulus, publicExponent)

    companion object {
        /** rsaEncryption, 1.2.840.113549.1.1.1. */
        val RSA_ENCRYPTION = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)

        /** RFC 5208 PrivateKeyInfo in DER: version 0, rsaEncryption with NULL parameters, no attributes. */
        fun pkcs8(der: ByteArray): RSAPrivateKey {
            val outer = Der(der, 0, der.size)
            val info = outer.next(0x30)
            outer.end()
            require(info.integer().isEmpty()) { "PrivateKeyInfo version" }
            val algorithm = info.next(0x30)
            require(algorithm.next(0x06).value().contentEquals(RSA_ENCRYPTION)) { "not rsaEncryption" }
            require(algorithm.next(0x05).value().isEmpty()) { "rsaEncryption parameters" }
            algorithm.end()
            val key = info.next(0x04)
            info.end()
            val sequence = key.next(0x30)
            key.end()
            require(sequence.integer().isEmpty()) { "RSAPrivateKey version" }
            val modulus = sequence.integer()
            val publicExponent = sequence.integer()
            val privateExponent = sequence.integer()
            repeat(5) { sequence.integer() } // prime1, prime2, exponent1, exponent2, coefficient
            sequence.end()
            require(modulus.isNotEmpty() && modulus.last().toInt() and 1 == 1) { "RSA modulus" }
            return RSAPrivateKey(modulus, publicExponent, privateExponent)
        }

        /** RFC 7468 textual encoding labelled PRIVATE KEY around [pkcs8]. */
        fun pem(text: String): RSAPrivateKey {
            val begin = "-----BEGIN PRIVATE KEY-----"
            val end = "-----END PRIVATE KEY-----"
            val from = text.indexOf(begin)
            require(from >= 0) { "no PRIVATE KEY section" }
            val to = text.indexOf(end, from + begin.length)
            require(to >= 0) { "unterminated PRIVATE KEY section" }
            val body = text.substring(from + begin.length, to).filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
            return pkcs8(Base64.Default.decode(body))
        }
    }
}

/** A DER cursor over bytes[at until limit]: definite minimal lengths, nothing left over. */
class Der(val bytes: ByteArray, var at: Int, val limit: Int) {
    fun byte(): Int {
        require(at < limit) { "DER truncated" }
        return bytes[at++].toInt() and 0xFF
    }

    /** The next element, which must carry [tag]; its contents as a cursor. */
    fun next(tag: Int): Der {
        require(byte() == tag) { "DER tag" }
        var length = byte()
        if (length >= 0x80) {
            val count = length and 0x7F
            require(count in 1..3) { "DER length" }
            length = 0
            repeat(count) { length = (length shl 8) or byte() }
            require(length >= 0x80 && length shr (8 * (count - 1)) != 0) { "DER length not minimal" }
        }
        require(length <= limit - at) { "DER truncated" }
        return Der(bytes, at, at + length).also { at += length }
    }

    fun value(): ByteArray = bytes.copyOfRange(at, limit).also { at = limit }

    /** A non-negative INTEGER as its unsigned big-endian magnitude (zero is empty). */
    fun integer(): ByteArray {
        val content = next(0x02).value()
        require(content.isNotEmpty() && content[0] >= 0) { "DER INTEGER sign" }
        require(content.size == 1 || content[0] != 0.toByte() || content[1] < 0) { "DER INTEGER not minimal" }
        return if (content[0] == 0.toByte()) content.copyOfRange(1, content.size) else content
    }

    fun end() = require(at == limit) { "DER trailing bytes" }
}

/** RFC 7518 3.3 RS256: RSASSA-PKCS1-v1_5 (RFC 8017 8.2) with SHA-256. */
object RS256 {
    /** The DER DigestInfo prefix for SHA-256, RFC 8017 9.2 note 1. */
    val DIGEST_INFO = byteArrayOf(
        0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20,
    )

    /** RSASSA-PKCS1-v1_5-SIGN: EMSA-PKCS1-v1_5 encode, OS2IP, RSASP1, I2OSP. */
    fun sign(key: RSAPrivateKey, message: ByteArray): ByteArray {
        val k = key.modulus.size
        val ring = Montgomery(os2ip(key.modulus, (k + 3) / 4))
        val m = os2ip(emsa(message, k), ring.n.size)
        return i2osp(ring.pow(m, os2ip(key.privateExponent, ring.n.size)), k)
    }

    /** RSASSA-PKCS1-v1_5-VERIFY: false for a wrong length, a representative not below n, or another encoding. */
    fun verify(key: RSAPublicKey, message: ByteArray, signature: ByteArray): Boolean {
        val k = key.modulus.size
        if (k == 0 || signature.size != k) return false
        val ring = Montgomery(os2ip(key.modulus, (k + 3) / 4))
        val s = os2ip(signature, ring.n.size)
        if (!ring.below(s)) return false
        val e = os2ip(key.publicExponent, (key.publicExponent.size + 3) / 4)
        return i2osp(ring.pow(s, e), k).contentEquals(emsa(message, k))
    }

    /** EMSA-PKCS1-v1_5-ENCODE: 0x00 0x01 FF..FF 0x00 DigestInfo(SHA-256(message)), [k] bytes. */
    fun emsa(message: ByteArray, k: Int): ByteArray {
        val t = DIGEST_INFO + Sha256Pure.digest(message)
        require(k >= t.size + 11) { "intended encoded message length too short" }
        val em = ByteArray(k) { 0xFF.toByte() }
        em[0] = 0
        em[1] = 1
        em[k - t.size - 1] = 0
        t.copyInto(em, k - t.size)
        return em
    }

    /** OS2IP into [size] little-endian 32-bit limbs. */
    fun os2ip(bytes: ByteArray, size: Int): IntArray {
        val limbs = IntArray(size)
        for (i in bytes.indices) {
            val bit = 8 * (bytes.size - 1 - i)
            if (bytes[i] != 0.toByte()) {
                require(bit < 32 * size) { "integer too large" }
                limbs[bit / 32] = limbs[bit / 32] or ((bytes[i].toInt() and 0xFF) shl (bit % 32))
            }
        }
        return limbs
    }

    /** I2OSP: [k] big-endian bytes of [limbs], which must fit. */
    fun i2osp(limbs: IntArray, k: Int): ByteArray {
        for (bit in 8 * k until 32 * limbs.size step 8) require((limbs[bit / 32] ushr (bit % 32)) and 0xFF == 0) { "integer too large" }
        return ByteArray(k) { i ->
            val bit = 8 * (k - 1 - i)
            if (bit < 32 * limbs.size) (limbs[bit / 32] ushr (bit % 32)).toByte() else 0
        }
    }
}

/**
 * Montgomery multiplication modulo the odd [n] (little-endian 32-bit limbs, R = 2^(32 n.size)):
 * CIOS products, a masked final subtraction, and a fixed 4-bit window exponentiation whose sequence
 * of products does not depend on the exponent's bits.
 */
class Montgomery(val n: IntArray) {
    val nPrime: Long
    /** R^2 mod n. */
    val rr: IntArray

    init {
        require(n.isNotEmpty() && n[0] and 1 == 1) { "Montgomery modulus must be odd" }
        var inverse = 1L
        repeat(5) { inverse = (inverse * (2 - (n[0].toLong() and MASK) * inverse)) and MASK }
        nPrime = (-inverse) and MASK
        val x = IntArray(n.size)
        x[0] = 1
        repeat(64 * n.size) { double(x) }
        rr = x
    }

    /** x = 2x mod n for x below n. */
    fun double(x: IntArray) {
        var carry = 0
        for (i in x.indices) {
            val limb = x[i]
            x[i] = (limb shl 1) or carry
            carry = limb ushr 31
        }
        if (carry == 1 || !below(x)) subtract(x)
    }

    /** x below n. */
    fun below(x: IntArray): Boolean {
        for (i in n.indices.reversed()) {
            val a = x[i].toLong() and MASK
            val b = n[i].toLong() and MASK
            if (a != b) return a < b
        }
        return false
    }

    /** x = x - n mod R. */
    fun subtract(x: IntArray) {
        var borrow = 0L
        for (i in n.indices) {
            val d = (x[i].toLong() and MASK) - (n[i].toLong() and MASK) - borrow
            x[i] = d.toInt()
            borrow = (d ushr 63)
        }
    }

    /** a b R^-1 mod n for a, b below n. */
    fun mul(a: IntArray, b: IntArray): IntArray {
        val s = n.size
        val t = LongArray(s + 2)
        for (i in 0 until s) {
            val bi = b[i].toLong() and MASK
            var carry = 0L
            for (j in 0 until s) {
                val sum = t[j] + (a[j].toLong() and MASK) * bi + carry
                t[j] = sum and MASK
                carry = sum ushr 32
            }
            var sum = t[s] + carry
            t[s] = sum and MASK
            t[s + 1] = sum ushr 32
            val m = (t[0] * nPrime) and MASK
            carry = (t[0] + m * (n[0].toLong() and MASK)) ushr 32
            for (j in 1 until s) {
                sum = t[j] + m * (n[j].toLong() and MASK) + carry
                t[j - 1] = sum and MASK
                carry = sum ushr 32
            }
            sum = t[s] + carry
            t[s - 1] = sum and MASK
            t[s] = t[s + 1] + (sum ushr 32)
        }
        val u = IntArray(s)
        var borrow = 0L
        for (j in 0 until s) {
            val d = t[j] - (n[j].toLong() and MASK) - borrow
            u[j] = d.toInt()
            borrow = d ushr 63
        }
        // t >= n unless the subtraction borrowed past t[s].
        val keep = ((t[s] - borrow) ushr 63).toInt() * -1
        return IntArray(s) { (t[it].toInt() and keep) or (u[it] and keep.inv()) }
    }

    /** base^exponent mod n for base below n. */
    fun pow(base: IntArray, exponent: IntArray): IntArray {
        val table = arrayOfNulls<IntArray>(16)
        table[0] = mul(rr, IntArray(n.size).also { it[0] = 1 })
        table[1] = mul(base, rr)
        for (i in 2 until 16) table[i] = mul(table[i - 1]!!, table[1]!!)
        var x = table[0]!!
        for (window in 8 * exponent.size - 1 downTo 0) {
            repeat(4) { x = mul(x, x) }
            val digit = (exponent[window / 8] ushr (4 * (window % 8))) and 0xF
            val entry = IntArray(n.size)
            for (i in 0 until 16) {
                val mask = (((i xor digit) - 1) shr 31)
                val row = table[i]!!
                for (j in entry.indices) entry[j] = entry[j] or (row[j] and mask)
            }
            x = mul(x, entry)
        }
        return mul(x, IntArray(n.size).also { it[0] = 1 })
    }

    companion object {
        const val MASK = 0xFFFFFFFFL
    }
}
