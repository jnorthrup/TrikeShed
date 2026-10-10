package borg.trikeshed.job

/**
 * Pure Kotlin SHA-256 implementation — FIPS 180-4.
 *
 * Used by JS and Wasm targets where java.security.MessageDigest is unavailable.
 * The JVM target uses java.security.MessageDigest directly (see Sha256.kt jvmMain).
 */
internal object Sha256Pure {

    private const val K_SIZE = 64
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun digest(input: ByteArray): ByteArray = Sha256().update(input).digest()

    fun rotR(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    /** One 64-byte block of [block] at [offset] into the state [h]; [w] is the message schedule. */
    fun compress(h: IntArray, w: IntArray, block: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val off = offset + i * 4
            w[i] = ((block[off].toInt() and 0xFF) shl 24) or
                ((block[off + 1].toInt() and 0xFF) shl 16) or
                ((block[off + 2].toInt() and 0xFF) shl 8) or
                (block[off + 3].toInt() and 0xFF)
        }
        for (i in 16 until K_SIZE) {
            val s0 = rotR(w[i - 15], 7) xor rotR(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotR(w[i - 2], 17) xor rotR(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until K_SIZE) {
            val s1 = rotR(e, 6) xor rotR(e, 11) xor rotR(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = hh + s1 + ch + K[i] + w[i]
            val s0 = rotR(a, 2) xor rotR(a, 13) xor rotR(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
}

/** Incremental SHA-256 (the `sha2::Sha256` update/finalize shape) over [Sha256Pure.compress]. */
class Sha256 {
    val h = intArrayOf(
        0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
        0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt(),
    )
    val w = IntArray(64)
    val block = ByteArray(64)
    var filled = 0
    var length = 0L

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Sha256 {
        var at = offset
        val end = offset + count
        length += count
        if (filled > 0) {
            val take = minOf(64 - filled, end - at)
            bytes.copyInto(block, filled, at, at + take)
            filled += take
            at += take
            if (filled < 64) return this
            Sha256Pure.compress(h, w, block, 0)
            filled = 0
        }
        while (end - at >= 64) {
            Sha256Pure.compress(h, w, bytes, at)
            at += 64
        }
        bytes.copyInto(block, 0, at, end)
        filled = end - at
        return this
    }

    /** The digest of everything updated so far; the instance is spent afterwards. */
    fun digest(): ByteArray {
        val bits = length * 8
        block[filled++] = 0x80.toByte()
        if (filled > 56) {
            block.fill(0, filled, 64)
            Sha256Pure.compress(h, w, block, 0)
            filled = 0
        }
        block.fill(0, filled, 56)
        for (i in 0 until 8) block[56 + i] = (bits ushr (56 - 8 * i)).toByte()
        Sha256Pure.compress(h, w, block, 0)
        return ByteArray(32) { i -> (h[i / 4] ushr (24 - (i % 4) * 8)).toByte() }
    }
}
