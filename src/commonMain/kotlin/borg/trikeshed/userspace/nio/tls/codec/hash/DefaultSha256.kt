package borg.trikeshed.userspace.nio.tls.codec.hash

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4) — commonMain default.
 *
 * No platform dependencies. Runs on JVM, JS, WASM, and native.
 * Register this in the coroutine context via `DefaultSha256()`.
 * Platforms can swap in hardware-accelerated impls at composition time.
 */
class DefaultSha256 : borg.trikeshed.userspace.nio.tls.codec.hash.Sha256 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = borg.trikeshed.userspace.nio.tls.codec.hash.Sha256.Key

    override fun hash(data: ByteArray): ByteArray {
        val padded = pad(data)
        val h = intArrayOf(0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(), 0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt())
        for (chunk in 0 until padded.size step 64) compress(padded, chunk, h)
        val out = ByteArray(32)
        for (i in 0..7) { out[i * 4] = ((h[i] ushr 24) and 0xFF).toByte(); out[i * 4 + 1] = ((h[i] ushr 16) and 0xFF).toByte(); out[i * 4 + 2] = ((h[i] ushr 8) and 0xFF).toByte(); out[i * 4 + 3] = (h[i] and 0xFF).toByte() }
        return out
    }

    override fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val key1 = if (key.size > blockSize) hash(key) else key
        val iKeyPad = ByteArray(blockSize) { if (it < key1.size) (key1[it].toInt() xor 0x36).toByte() else 0x36.toByte() }
        val oKeyPad = ByteArray(blockSize) { if (it < key1.size) (key1[it].toInt() xor 0x5c).toByte() else 0x5c.toByte() }
        return hash(oKeyPad + hash(iKeyPad + data))
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun pad(data: ByteArray): ByteArray {
        val ml = data.size.toLong() * 8
        val padLen = (if ((data.size + 9) % 64 == 0) 0 else 64 - (data.size + 9) % 64) + 9
        val out = ByteArray(data.size + padLen)
        data.copyInto(out, 0); out[data.size] = 0x80.toByte()
        for (i in 1 until padLen - 8) out[data.size + i] = 0
        for (i in 0..7) out[out.size - 8 + i] = ((ml ushr (56 - i * 8)) and 0xFF).toByte()
        return out
    }

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(), 0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(), 0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(), 0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(), 0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    private fun compress(block: ByteArray, offset: Int, h: IntArray) {
        val w = IntArray(64)
        for (i in 0..15) w[i] = intFrom(block, offset + i * 4)
        for (i in 16..63) { val s0 = ror(w[i - 15], 7) xor ror(w[i - 15], 18) xor (w[i - 15] ushr 3); val s1 = ror(w[i - 2], 17) xor ror(w[i - 2], 19) xor (w[i - 2] ushr 10); w[i] = w[i - 16] + s0 + w[i - 7] + s1 }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hv = h[7]
        for (i in 0..63) { val s1 = ror(e, 6) xor ror(e, 11) xor ror(e, 25); val ch = (e and f) xor (e.inv() and g); val t1 = hv + s1 + ch + K[i] + w[i]; val s0 = ror(a, 2) xor ror(a, 13) xor ror(a, 22); val maj = (a and b) xor (a and c) xor (b and c); val t2 = s0 + maj; hv = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2 }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hv
    }

    private fun intFrom(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun ror(v: Int, n: Int): Int = (v ushr n) or (v shl (32 - n))
}
