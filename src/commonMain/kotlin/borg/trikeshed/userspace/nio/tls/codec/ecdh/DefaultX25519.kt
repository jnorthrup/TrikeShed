package borg.trikeshed.userspace.nio.tls.codec.ecdh

import kotlin.random.Random

/**
 * Pure-Kotlin X25519 (RFC 7748) — commonMain default.
 *
 * Implements the Montgomery ladder over GF(2^255 - 19) using
 * 10-limb 25.5-bit representation with saturated carry chains.
 * Constant-time scalar multiplication.
 */
class DefaultX25519 : borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = _root_ide_package_.borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519.Key

    override fun generateKeyPair(): borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519.KeyPair {
        val private = Random.nextBytes(ByteArray(32))
        private[0] = (private[0].toInt() and 0xF8).toByte()
        private[31] = ((private[31].toInt() and 0x7F) or 0x40).toByte()
        val public = scalarMult(private, BASE_POINT)
        return _root_ide_package_.borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519.KeyPair(public, private)
    }

    override fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        scalarMult(privateKey, peerPublicKey)

    // ── Field element (10 limbs × 25.5 bits) ───────────────────────

    private typealias Fe = IntArray  // 10 elements

    private fun fe(): Fe = IntArray(10)
    private fun feCopy(a: Fe): Fe = a.copyOf()

    // ── Scalar multiplication (Montgomery ladder) ──────────────────

    private fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val e = scalar.copyOf()
        e[0] = (e[0].toInt() and 0xF8).toByte(); e[31] = ((e[31].toInt() and 0x7F) or 0x40).toByte()
        val u = feFromBytes(point)
        var x1 = fe(); feCopyInto(x1, u)
        var x2 = fe(); x2[0] = 1
        var z2 = fe()
        var x3 = fe(); feCopyInto(x3, u)
        var z3 = fe(); z3[0] = 1
        var swap = 0
        for (t in 255 downTo 0) {
            val kt = (e[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            cswap(swap, x2, x3); cswap(swap, z2, z3)
            swap = kt
            val a = feAdd(x2, z2); val aa = feSq(a)
            val b = feSub(x2, z2); val bb = feSq(b)
            val eT = feSub(aa, bb)
            val c = feAdd(x3, z3); val d = feSub(x3, z3)
            val da = feMul(d, a); val cb = feMul(c, b)
            x3 = feSq(feAdd(da, cb)); z3 = feMul(feSub(da, cb), feSub(da, cb)).let { feMul(it, x1) }
            x2 = feMul(aa, bb); z2 = feMul(eT, feAdd(aa, feMul(fe121665(), eT)))
        }
        cswap(swap, x2, x3); cswap(swap, z2, z3)
        return feToBytes(feMul(x2, feInv(z2)))
    }

    // ── Field operations ───────────────────────────────────────────

    private fun fe121665(): Fe = Fe(10).apply { this[0] = 121665 }

    private fun feFromBytes(bytes: ByteArray): Fe {
        val h = fe()
        for (i in 0 until 10) {
            var v = 0L
            for (j in 0 until minOf(4, 32 - i * 4)) {
                val idx = i * 4 + j; if (idx < 32) v = v or ((bytes[idx].toLong() and 0xFF) shl (j * 8))
            }
            h[i] = (v and 0x3FFFFFF).toInt()
            if (i < 9) for (j in 4 until minOf(8, 32 - i * 4)) {
                val idx = i * 4 + j; if (idx < 32) v = v or ((bytes[idx].toLong() and 0xFF) shl (j * 8))
            }
            if (i > 0) h[i] = (h[i] + ((v ushr 26) and 0x3FFFFFF).toInt()).coerceIn(0, 0x3FFFFFF)
        }
        return h
    }

    private fun feToBytes(h: Fe): ByteArray {
        val carry = IntArray(10)
        val hh = feCopy(h)
        for (i in 0..8) { carry[i] = hh[i] ushr 26; hh[i] = hh[i] and 0x3FFFFFF; hh[i + 1] += carry[i] }
        carry[9] = hh[9] ushr 25; hh[9] = hh[9] and 0x1FFFFFF; hh[0] += carry[9] * 19
        carry[0] = hh[0] ushr 26; hh[0] = hh[0] and 0x3FFFFFF; hh[1] += carry[0]
        val bytes = ByteArray(32)
        for (i in 0..9) {
            val v = hh[i]
            if (i * 4 < 32) bytes[i * 4] = (v and 0xFF).toByte()
            if (i * 4 + 1 < 32) bytes[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            if (i * 4 + 2 < 32) bytes[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            if (i * 4 + 3 < 32) bytes[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return bytes
    }

    private fun feCopyInto(dst: Fe, src: Fe) { for (i in 0..9) dst[i] = src[i] }

    private fun feAdd(a: Fe, b: Fe): Fe = Fe(10) { a[it] + b[it] }
    private fun feSub(a: Fe, b: Fe): Fe = Fe(10) { a[it] - b[it] + (1 shl 30) }

    private fun feMul(a: Fe, b: Fe): Fe {
        val r = LongArray(19)
        for (i in 0..9) for (j in 0..9) r[i + j] += a[i].toLong() * b[j].toLong()
        // carry/contract
        for (i in 0..8) { r[i + 1] += r[i] ushr 26; r[i] = r[i] and 0x3FFFFFF }
        r[0] += (r[9] ushr 25) * 19; r[9] = r[9] and 0x1FFFFFF
        r[1] += r[0] ushr 26; r[0] = r[0] and 0x3FFFFFF
        return Fe(10) { r[it].toInt() }
    }

    private fun feSq(a: Fe): Fe = feMul(a, a)

    private fun feInv(a: Fe): Fe {
        var r = feCopy(a)
        // a^(p-2) ≡ a^(2^255-21)
        for (i in 254 downTo 0) { r = feSq(r); if (i != 0) r = feMul(r, a) }
        return r
    }

    private fun cswap(swap: Int, a: Fe, b: Fe) {
        val mask = -swap  // 0 or -1
        for (i in 0..9) { val dummy = mask and (a[i] xor b[i]); a[i] = a[i] xor dummy; b[i] = b[i] xor dummy }
    }

    // ── Base point (u=9) ───────────────────────────────────────────

    private val BASE_POINT: ByteArray = run {
        val b = ByteArray(32); b[0] = 9; b
    }
}
