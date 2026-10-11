package borg.trikeshed.job

import borg.trikeshed.userspace.nio.platform.spi.*

/** (X:Y:Z) with x = X/Z and y = Y/Z, coordinates in Montgomery form; Z = 0 is the point at infinity. */
class WeierstrassPoint(val x: IntArray, val y: IntArray, val z: IntArray)

/**
 * SP 800-186 3.2.1 Weierstrass curve y^2 = x^3 - 3x + b over GF(p) of prime order n, for ECDH (SEC 1
 * 3.3.1) and ECDSA verification (FIPS 186-5 6.4.2). Field elements are [Montgomery] residues of 32-bit
 * limbs; points add by Renes, Costello and Batina's complete formulas for a = -3 (eprint 2015/1060,
 * algorithms 4 and 6), and scalar multiplication runs a fixed 4-bit window whose table reads and
 * sequence of operations do not depend on the scalar. Encodings are big endian as SEC 1 2.3 defines.
 */
class WeierstrassCurve(p: String, b: String, gx: String, gy: String, n: String) {
    /** Octets of a field element or scalar. */
    val size = p.length / 2
    val field = Montgomery(RS256.os2ip(p.hexToByteArray(), (size + 3) / 4))
    val order = Montgomery(RS256.os2ip(n.hexToByteArray(), (size + 3) / 4))
    val limbs = field.n.size
    val unit = IntArray(limbs).also { it[0] = 1 }
    val one = field.mul(field.rr, unit)
    val b = residue(RS256.os2ip(b.hexToByteArray(), limbs))
    val generator = WeierstrassPoint(residue(RS256.os2ip(gx.hexToByteArray(), limbs)), residue(RS256.os2ip(gy.hexToByteArray(), limbs)), one)
    val pMinus2 = minus2(field.n)
    val nMinus2 = minus2(order.n)

    fun residue(x: IntArray): IntArray = field.mul(x, field.rr)

    fun minus2(m: IntArray): IntArray {
        val r = m.copyOf()
        var borrow = 2L
        for (i in r.indices) {
            val d = (r[i].toLong() and Montgomery.MASK) - borrow
            r[i] = d.toInt()
            borrow = d ushr 63
        }
        return r
    }

    fun identity() = WeierstrassPoint(IntArray(limbs), one.copyOf(), IntArray(limbs))

    /** p + q, complete for all inputs (algorithm 4). */
    fun add(p: WeierstrassPoint, q: WeierstrassPoint): WeierstrassPoint {
        val f = field
        var t0 = f.mul(p.x, q.x)
        var t1 = f.mul(p.y, q.y)
        var t2 = f.mul(p.z, q.z)
        var t3 = f.add(p.x, p.y)
        var t4 = f.add(q.x, q.y)
        t3 = f.mul(t3, t4)
        t4 = f.add(t0, t1)
        t3 = f.sub(t3, t4)
        t4 = f.add(p.y, p.z)
        var x3 = f.add(q.y, q.z)
        t4 = f.mul(t4, x3)
        x3 = f.add(t1, t2)
        t4 = f.sub(t4, x3)
        x3 = f.add(p.x, p.z)
        var y3 = f.add(q.x, q.z)
        x3 = f.mul(x3, y3)
        y3 = f.add(t0, t2)
        y3 = f.sub(x3, y3)
        var z3 = f.mul(b, t2)
        x3 = f.sub(y3, z3)
        z3 = f.add(x3, x3)
        x3 = f.add(x3, z3)
        z3 = f.sub(t1, x3)
        x3 = f.add(t1, x3)
        y3 = f.mul(b, y3)
        t1 = f.add(t2, t2)
        t2 = f.add(t1, t2)
        y3 = f.sub(y3, t2)
        y3 = f.sub(y3, t0)
        t1 = f.add(y3, y3)
        y3 = f.add(t1, y3)
        t1 = f.add(t0, t0)
        t0 = f.add(t1, t0)
        t0 = f.sub(t0, t2)
        t1 = f.mul(t4, y3)
        t2 = f.mul(t0, y3)
        y3 = f.mul(x3, z3)
        y3 = f.add(y3, t2)
        x3 = f.mul(t3, x3)
        x3 = f.sub(x3, t1)
        z3 = f.mul(t4, z3)
        t1 = f.mul(t3, t0)
        z3 = f.add(z3, t1)
        return WeierstrassPoint(x3, y3, z3)
    }

    /** 2p (algorithm 6). */
    fun double(p: WeierstrassPoint): WeierstrassPoint {
        val f = field
        var t0 = f.mul(p.x, p.x)
        val t1 = f.mul(p.y, p.y)
        var t2 = f.mul(p.z, p.z)
        var t3 = f.mul(p.x, p.y)
        t3 = f.add(t3, t3)
        var z3 = f.mul(p.x, p.z)
        z3 = f.add(z3, z3)
        var y3 = f.mul(b, t2)
        y3 = f.sub(y3, z3)
        var x3 = f.add(y3, y3)
        y3 = f.add(x3, y3)
        x3 = f.sub(t1, y3)
        y3 = f.add(t1, y3)
        y3 = f.mul(x3, y3)
        x3 = f.mul(x3, t3)
        t3 = f.add(t2, t2)
        t2 = f.add(t2, t3)
        z3 = f.mul(b, z3)
        z3 = f.sub(z3, t2)
        z3 = f.sub(z3, t0)
        t3 = f.add(z3, z3)
        z3 = f.add(z3, t3)
        t3 = f.add(t0, t0)
        t0 = f.add(t3, t0)
        t0 = f.sub(t0, t2)
        t0 = f.mul(t0, z3)
        y3 = f.add(y3, t0)
        t0 = f.mul(p.y, p.z)
        t0 = f.add(t0, t0)
        z3 = f.mul(t0, z3)
        x3 = f.sub(x3, z3)
        z3 = f.mul(t0, t1)
        z3 = f.add(z3, z3)
        z3 = f.add(z3, z3)
        return WeierstrassPoint(x3, y3, z3)
    }

    /** [k]p for the scalar [k] in little-endian 32-bit limbs, four doublings and one table addition per 4-bit digit. */
    fun multiply(p: WeierstrassPoint, k: IntArray): WeierstrassPoint {
        val table = arrayOfNulls<WeierstrassPoint>(16)
        table[0] = identity()
        table[1] = p
        for (i in 2..15) table[i] = if (i % 2 == 0) double(table[i / 2]!!) else add(table[i - 1]!!, p)
        var r = identity()
        for (window in 8 * k.size - 1 downTo 0) {
            repeat(4) { r = double(r) }
            val digit = (k[window / 8] ushr (4 * (window % 8))) and 0xF
            val entry = identity()
            entry.y.fill(0)
            for (i in 0..15) {
                val mask = ((i xor digit) - 1) shr 31
                val row = table[i]!!
                for (j in 0 until limbs) {
                    entry.x[j] = entry.x[j] or (row.x[j] and mask)
                    entry.y[j] = entry.y[j] or (row.y[j] and mask)
                    entry.z[j] = entry.z[j] or (row.z[j] and mask)
                }
            }
            r = add(r, entry)
        }
        return r
    }

    /** x/Z and y/Z as plain integers below p; null for the point at infinity. */
    fun affine(p: WeierstrassPoint): Pair<IntArray, IntArray>? {
        val z = field.mul(p.z, unit)
        if (z.all { it == 0 }) return null
        val zInverse = field.pow(z, pMinus2)
        return field.mul(p.x, zInverse) to field.mul(p.y, zInverse)
    }

    /** SEC 1 2.3.3 uncompressed encoding 0x04 | X | Y of an affine point. */
    fun encode(x: IntArray, y: IntArray): ByteArray = byteArrayOf(4) + RS256.i2osp(x, size) + RS256.i2osp(y, size)

    /**
     * SEC 1 2.3.4 for the uncompressed form, with 3.2.2.1 partial validation: coordinates below p and on the
     * curve; the order is prime, so every such point but infinity generates the group. Null otherwise.
     */
    fun decode(bytes: ByteArray): WeierstrassPoint? {
        if (bytes.size != 1 + 2 * size || bytes[0] != 4.toByte()) return null
        val x = RS256.os2ip(bytes.copyOfRange(1, 1 + size), limbs)
        val y = RS256.os2ip(bytes.copyOfRange(1 + size, 1 + 2 * size), limbs)
        if (!field.below(x) || !field.below(y)) return null
        val xr = residue(x)
        val yr = residue(y)
        val x3 = field.mul(field.mul(xr, xr), xr)
        val threeX = field.add(field.add(xr, xr), xr)
        val rhs = field.add(field.sub(x3, threeX), b)
        if (!field.mul(yr, yr).contentEquals(rhs)) return null
        return WeierstrassPoint(xr, yr, one.copyOf())
    }

    /** A private key d uniform in [1, n - 1] from the platform CSPRNG, by rejection, as [size] big-endian octets. */
    fun privateKey(): ByteArray {
        val d = ByteArray(size)
        while (true) {
            platformGetRandom(d)
            val k = RS256.os2ip(d, limbs)
            if (k.any { it != 0 } && order.below(k)) return d
        }
    }

    /** The public key Q = [d]G, uncompressed. */
    fun publicKey(d: ByteArray): ByteArray {
        val (x, y) = affine(multiply(generator, scalar(d)))!!
        return encode(x, y)
    }

    /**
     * SEC 1 3.3.1 Elliptic Curve Diffie-Hellman primitive: the x-coordinate of [d]Q as [size] octets (RFC 8446
     * 7.4.2); null when [publicKey] is not a valid point or the product is the point at infinity.
     */
    fun ecdh(d: ByteArray, publicKey: ByteArray): ByteArray? {
        val q = decode(publicKey) ?: return null
        val (x, _) = affine(multiply(q, scalar(d))) ?: return null
        return RS256.i2osp(x, size)
    }

    fun scalar(d: ByteArray): IntArray {
        val k = RS256.os2ip(d, limbs)
        require(d.size == size && k.any { it != 0 } && order.below(k)) { "scalar outside [1, n - 1]" }
        return k
    }

    /**
     * FIPS 186-5 6.4.2 ECDSA signature verification of (r, s), unsigned big-endian, against the public key [publicKey]
     * and the message [digest]: e is the leftmost bit length of n bits of the digest.
     */
    fun verify(publicKey: ByteArray, digest: ByteArray, r: ByteArray, s: ByteArray): Boolean {
        val q = decode(publicKey) ?: return false
        if (r.size > size || s.size > size) return false
        val rl = RS256.os2ip(r, limbs)
        val sl = RS256.os2ip(s, limbs)
        if (rl.all { it == 0 } || sl.all { it == 0 } || !order.below(rl) || !order.below(sl)) return false
        val e = RS256.os2ip(digest.copyOf(minOf(digest.size, size)), limbs)
        if (!order.below(e)) order.subtract(e)
        val w = order.mul(order.pow(sl, nMinus2), order.rr)
        val u1 = order.mul(e, w)
        val u2 = order.mul(rl, w)
        val (x, _) = affine(add(multiply(generator, u1), multiply(q, u2))) ?: return false
        if (!order.below(x)) order.subtract(x)
        return x.contentEquals(rl)
    }

    /** [verify] of a DER Ecdsa-Sig-Value SEQUENCE { r INTEGER, s INTEGER } (RFC 5480 2.2, RFC 8446 4.2.3); false for any other encoding. */
    fun verify(publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        val (r, s) = try {
            val outer = Der(signature, 0, signature.size)
            val sequence = outer.next(0x30)
            outer.end()
            (sequence.integer() to sequence.integer()).also { sequence.end() }
        } catch (_: IllegalArgumentException) {
            return false
        }
        return verify(publicKey, digest, r, s)
    }

    companion object {
        /** SP 800-186 3.2.1.3 P-256 (secp256r1). */
        val P256 = WeierstrassCurve(
            p = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            b = "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
            gx = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            gy = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            n = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
        )

        /** SP 800-186 3.2.1.4 P-384 (secp384r1). */
        val P384 = WeierstrassCurve(
            p = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000ffffffff",
            b = "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875ac656398d8a2ed19d2a85c8edd3ec2aef",
            gx = "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7",
            gy = "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f",
            n = "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973",
        )
    }
}
