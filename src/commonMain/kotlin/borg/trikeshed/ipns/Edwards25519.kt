package borg.trikeshed.ipns

import kotlin.jvm.JvmInline

/**
 * Element of GF(2^255 - 19): ten signed limbs of alternately 26 and 25 bits (radix 2^25.5), the
 * ref10 representation that curve25519-dalek's 32-bit backend also uses. Every operation reads
 * its arguments before it writes the receiver, so the receiver may be one of the arguments.
 */
@JvmInline
value class FieldElement(val limbs: IntArray = IntArray(10)) {
    fun zero() = limbs.fill(0)

    fun one() {
        limbs.fill(0)
        limbs[0] = 1
    }

    fun set(f: FieldElement) {
        f.limbs.copyInto(limbs)
    }

    fun add(f: FieldElement, g: FieldElement) {
        for (i in 0..9) limbs[i] = f.limbs[i] + g.limbs[i]
    }

    fun sub(f: FieldElement, g: FieldElement) {
        for (i in 0..9) limbs[i] = f.limbs[i] - g.limbs[i]
    }

    fun negate(f: FieldElement) {
        for (i in 0..9) limbs[i] = -f.limbs[i]
    }

    /** (this, g) = (this + g, this - g). */
    fun addSub(g: FieldElement) {
        for (i in 0..9) {
            val a = limbs[i]
            val b = g.limbs[i]
            limbs[i] = a + b
            g.limbs[i] = a - b
        }
    }

    fun mul(f: FieldElement, g: FieldElement) {
        val a = f.limbs
        val b = g.limbs
        val f0 = a[0].toLong(); val f1 = a[1].toLong(); val f2 = a[2].toLong(); val f3 = a[3].toLong(); val f4 = a[4].toLong()
        val f5 = a[5].toLong(); val f6 = a[6].toLong(); val f7 = a[7].toLong(); val f8 = a[8].toLong(); val f9 = a[9].toLong()
        val g0 = b[0].toLong(); val g1 = b[1].toLong(); val g2 = b[2].toLong(); val g3 = b[3].toLong(); val g4 = b[4].toLong()
        val g5 = b[5].toLong(); val g6 = b[6].toLong(); val g7 = b[7].toLong(); val g8 = b[8].toLong(); val g9 = b[9].toLong()
        val g1_19 = 19 * g1; val g2_19 = 19 * g2; val g3_19 = 19 * g3; val g4_19 = 19 * g4; val g5_19 = 19 * g5
        val g6_19 = 19 * g6; val g7_19 = 19 * g7; val g8_19 = 19 * g8; val g9_19 = 19 * g9
        val f1_2 = 2 * f1; val f3_2 = 2 * f3; val f5_2 = 2 * f5; val f7_2 = 2 * f7; val f9_2 = 2 * f9
        reduce(
            f0 * g0 + f1_2 * g9_19 + f2 * g8_19 + f3_2 * g7_19 + f4 * g6_19 + f5_2 * g5_19 + f6 * g4_19 + f7_2 * g3_19 + f8 * g2_19 + f9_2 * g1_19,
            f0 * g1 + f1 * g0 + f2 * g9_19 + f3 * g8_19 + f4 * g7_19 + f5 * g6_19 + f6 * g5_19 + f7 * g4_19 + f8 * g3_19 + f9 * g2_19,
            f0 * g2 + f1_2 * g1 + f2 * g0 + f3_2 * g9_19 + f4 * g8_19 + f5_2 * g7_19 + f6 * g6_19 + f7_2 * g5_19 + f8 * g4_19 + f9_2 * g3_19,
            f0 * g3 + f1 * g2 + f2 * g1 + f3 * g0 + f4 * g9_19 + f5 * g8_19 + f6 * g7_19 + f7 * g6_19 + f8 * g5_19 + f9 * g4_19,
            f0 * g4 + f1_2 * g3 + f2 * g2 + f3_2 * g1 + f4 * g0 + f5_2 * g9_19 + f6 * g8_19 + f7_2 * g7_19 + f8 * g6_19 + f9_2 * g5_19,
            f0 * g5 + f1 * g4 + f2 * g3 + f3 * g2 + f4 * g1 + f5 * g0 + f6 * g9_19 + f7 * g8_19 + f8 * g7_19 + f9 * g6_19,
            f0 * g6 + f1_2 * g5 + f2 * g4 + f3_2 * g3 + f4 * g2 + f5_2 * g1 + f6 * g0 + f7_2 * g9_19 + f8 * g8_19 + f9_2 * g7_19,
            f0 * g7 + f1 * g6 + f2 * g5 + f3 * g4 + f4 * g3 + f5 * g2 + f6 * g1 + f7 * g0 + f8 * g9_19 + f9 * g8_19,
            f0 * g8 + f1_2 * g7 + f2 * g6 + f3_2 * g5 + f4 * g4 + f5_2 * g3 + f6 * g2 + f7_2 * g1 + f8 * g0 + f9_2 * g9_19,
            f0 * g9 + f1 * g8 + f2 * g7 + f3 * g6 + f4 * g5 + f5 * g4 + f6 * g3 + f7 * g2 + f8 * g1 + f9 * g0,
        )
    }

    fun square(f: FieldElement) = square(f, 1)

    /** this = 2 f^2 (ref10 fe_sq2), doubled before the carry chain so the limbs stay reduced. */
    fun square2(f: FieldElement) = square(f, 2)

    fun square(f: FieldElement, times: Int) {
        val a = f.limbs
        val f0 = a[0].toLong(); val f1 = a[1].toLong(); val f2 = a[2].toLong(); val f3 = a[3].toLong(); val f4 = a[4].toLong()
        val f5 = a[5].toLong(); val f6 = a[6].toLong(); val f7 = a[7].toLong(); val f8 = a[8].toLong(); val f9 = a[9].toLong()
        val f0_2 = 2 * f0; val f1_2 = 2 * f1; val f2_2 = 2 * f2; val f3_2 = 2 * f3
        val f4_2 = 2 * f4; val f5_2 = 2 * f5; val f6_2 = 2 * f6; val f7_2 = 2 * f7
        val f5_38 = 38 * f5; val f6_19 = 19 * f6; val f7_38 = 38 * f7; val f8_19 = 19 * f8; val f9_38 = 38 * f9
        var h0 = f0 * f0 + f1_2 * f9_38 + f2_2 * f8_19 + f3_2 * f7_38 + f4_2 * f6_19 + f5 * f5_38
        var h1 = f0_2 * f1 + f2 * f9_38 + f3_2 * f8_19 + f4 * f7_38 + f5_2 * f6_19
        var h2 = f0_2 * f2 + f1_2 * f1 + f3_2 * f9_38 + f4_2 * f8_19 + f5_2 * f7_38 + f6 * f6_19
        var h3 = f0_2 * f3 + f1_2 * f2 + f4 * f9_38 + f5_2 * f8_19 + f6 * f7_38
        var h4 = f0_2 * f4 + f1_2 * f3_2 + f2 * f2 + f5_2 * f9_38 + f6_2 * f8_19 + f7 * f7_38
        var h5 = f0_2 * f5 + f1_2 * f4 + f2_2 * f3 + f6 * f9_38 + f7_2 * f8_19
        var h6 = f0_2 * f6 + f1_2 * f5_2 + f2_2 * f4 + f3_2 * f3 + f7_2 * f9_38 + f8 * f8_19
        var h7 = f0_2 * f7 + f1_2 * f6 + f2_2 * f5 + f3_2 * f4 + f8 * f9_38
        var h8 = f0_2 * f8 + f1_2 * f7_2 + f2_2 * f6 + f3_2 * f5_2 + f4 * f4 + f9 * f9_38
        var h9 = f0_2 * f9 + f1_2 * f8 + f2_2 * f7 + f3_2 * f6 + f4_2 * f5
        if (times == 2) {
            h0 += h0; h1 += h1; h2 += h2; h3 += h3; h4 += h4
            h5 += h5; h6 += h6; h7 += h7; h8 += h8; h9 += h9
        }
        reduce(h0, h1, h2, h3, h4, h5, h6, h7, h8, h9)
    }

    /** this = f^(2^k). */
    fun pow2k(f: FieldElement, k: Int) {
        square(f)
        repeat(k - 1) { square(this) }
    }

    /** ref10 carry chain: limbs back to 26/25 bits plus sign, the carry out of limb 9 folded into limb 0 times 19. */
    fun reduce(z0: Long, z1: Long, z2: Long, z3: Long, z4: Long, z5: Long, z6: Long, z7: Long, z8: Long, z9: Long) {
        var h0 = z0; var h1 = z1; var h2 = z2; var h3 = z3; var h4 = z4
        var h5 = z5; var h6 = z6; var h7 = z7; var h8 = z8; var h9 = z9
        var c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26
        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26
        c = (h1 + (1L shl 24)) shr 25; h2 += c; h1 -= c shl 25
        c = (h5 + (1L shl 24)) shr 25; h6 += c; h5 -= c shl 25
        c = (h2 + (1L shl 25)) shr 26; h3 += c; h2 -= c shl 26
        c = (h6 + (1L shl 25)) shr 26; h7 += c; h6 -= c shl 26
        c = (h3 + (1L shl 24)) shr 25; h4 += c; h3 -= c shl 25
        c = (h7 + (1L shl 24)) shr 25; h8 += c; h7 -= c shl 25
        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26
        c = (h8 + (1L shl 25)) shr 26; h9 += c; h8 -= c shl 26
        c = (h9 + (1L shl 24)) shr 25; h0 += c * 19; h9 -= c shl 25
        c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26
        limbs[0] = h0.toInt(); limbs[1] = h1.toInt(); limbs[2] = h2.toInt(); limbs[3] = h3.toInt(); limbs[4] = h4.toInt()
        limbs[5] = h5.toInt(); limbs[6] = h6.toInt(); limbs[7] = h7.toInt(); limbs[8] = h8.toInt(); limbs[9] = h9.toInt()
    }

    /** this = z^(p-2) = 1/z (curve25519-dalek FieldElement::invert); 0 when z is 0. */
    fun invert(z: FieldElement) {
        val t3 = FieldElement()
        pow22501(z, t3)
        pow2k(this, 5)
        mul(this, t3)
    }

    /** this = z^((p-5)/8) = z^(2^252 - 3) (dalek pow_p58, ref10 fe_pow22523). */
    fun powP58(z: FieldElement) {
        val base = FieldElement().also { it.set(z) }
        pow22501(base, FieldElement())
        pow2k(this, 2)
        mul(this, base)
    }

    /** this = z^(2^250 - 1) and t3 = z^11 (dalek pow22501); z is read before this is written. */
    fun pow22501(z: FieldElement, t3: FieldElement) {
        val t0 = FieldElement().also { it.square(z) }
        val t1 = FieldElement().also { it.pow2k(t0, 2) }
        val t2 = FieldElement().also { it.mul(z, t1) }
        t3.mul(t0, t2)
        t0.square(t3)
        t1.mul(t2, t0)
        t0.pow2k(t1, 5); t0.mul(t0, t1)
        t2.pow2k(t0, 10); t2.mul(t2, t0)
        t1.pow2k(t2, 20); t1.mul(t1, t2)
        t1.pow2k(t1, 10); t1.mul(t1, t0)
        t2.pow2k(t1, 50); t2.mul(t2, t1)
        pow2k(t2, 100); mul(this, t2)
        pow2k(this, 50); mul(this, t1)
    }

    /** Canonical 32-byte little-endian encoding, fully reduced below p (ref10 fe_tobytes). */
    fun toBytes(s: ByteArray = ByteArray(32), offset: Int = 0): ByteArray {
        var h0 = limbs[0]; var h1 = limbs[1]; var h2 = limbs[2]; var h3 = limbs[3]; var h4 = limbs[4]
        var h5 = limbs[5]; var h6 = limbs[6]; var h7 = limbs[7]; var h8 = limbs[8]; var h9 = limbs[9]
        var q = (19 * h9 + (1 shl 24)) shr 25
        q = (h0 + q) shr 26
        q = (h1 + q) shr 25
        q = (h2 + q) shr 26
        q = (h3 + q) shr 25
        q = (h4 + q) shr 26
        q = (h5 + q) shr 25
        q = (h6 + q) shr 26
        q = (h7 + q) shr 25
        q = (h8 + q) shr 26
        q = (h9 + q) shr 25
        h0 += 19 * q
        var c = h0 shr 26; h1 += c; h0 -= c shl 26
        c = h1 shr 25; h2 += c; h1 -= c shl 25
        c = h2 shr 26; h3 += c; h2 -= c shl 26
        c = h3 shr 25; h4 += c; h3 -= c shl 25
        c = h4 shr 26; h5 += c; h4 -= c shl 26
        c = h5 shr 25; h6 += c; h5 -= c shl 25
        c = h6 shr 26; h7 += c; h6 -= c shl 26
        c = h7 shr 25; h8 += c; h7 -= c shl 25
        c = h8 shr 26; h9 += c; h8 -= c shl 26
        c = h9 shr 25; h9 -= c shl 25
        val o = offset
        s[o] = h0.toByte(); s[o + 1] = (h0 shr 8).toByte(); s[o + 2] = (h0 shr 16).toByte()
        s[o + 3] = ((h0 shr 24) or (h1 shl 2)).toByte()
        s[o + 4] = (h1 shr 6).toByte(); s[o + 5] = (h1 shr 14).toByte()
        s[o + 6] = ((h1 shr 22) or (h2 shl 3)).toByte()
        s[o + 7] = (h2 shr 5).toByte(); s[o + 8] = (h2 shr 13).toByte()
        s[o + 9] = ((h2 shr 21) or (h3 shl 5)).toByte()
        s[o + 10] = (h3 shr 3).toByte(); s[o + 11] = (h3 shr 11).toByte()
        s[o + 12] = ((h3 shr 19) or (h4 shl 6)).toByte()
        s[o + 13] = (h4 shr 2).toByte(); s[o + 14] = (h4 shr 10).toByte(); s[o + 15] = (h4 shr 18).toByte()
        s[o + 16] = h5.toByte(); s[o + 17] = (h5 shr 8).toByte(); s[o + 18] = (h5 shr 16).toByte()
        s[o + 19] = ((h5 shr 24) or (h6 shl 1)).toByte()
        s[o + 20] = (h6 shr 7).toByte(); s[o + 21] = (h6 shr 15).toByte()
        s[o + 22] = ((h6 shr 23) or (h7 shl 3)).toByte()
        s[o + 23] = (h7 shr 5).toByte(); s[o + 24] = (h7 shr 13).toByte()
        s[o + 25] = ((h7 shr 21) or (h8 shl 4)).toByte()
        s[o + 26] = (h8 shr 4).toByte(); s[o + 27] = (h8 shr 12).toByte()
        s[o + 28] = ((h8 shr 20) or (h9 shl 6)).toByte()
        s[o + 29] = (h9 shr 2).toByte(); s[o + 30] = (h9 shr 10).toByte(); s[o + 31] = (h9 shr 18).toByte()
        return s
    }

    /** The low 255 bits of the 32 little-endian bytes at [offset] as an element mod p: bit 255 is ignored and values >= p are kept (ref10 fe_frombytes, dalek FieldElement::from_bytes). */
    fun fromBytes(s: ByteArray, offset: Int = 0) {
        var h0 = load(s, offset, 4)
        var h1 = load(s, offset + 4, 3) shl 6
        var h2 = load(s, offset + 7, 3) shl 5
        var h3 = load(s, offset + 10, 3) shl 3
        var h4 = load(s, offset + 13, 3) shl 2
        var h5 = load(s, offset + 16, 4)
        var h6 = load(s, offset + 20, 3) shl 7
        var h7 = load(s, offset + 23, 3) shl 5
        var h8 = load(s, offset + 26, 3) shl 4
        var h9 = (load(s, offset + 29, 3) and 0x7fffff) shl 2
        var c = (h9 + (1L shl 24)) shr 25; h0 += c * 19; h9 -= c shl 25
        c = (h1 + (1L shl 24)) shr 25; h2 += c; h1 -= c shl 25
        c = (h3 + (1L shl 24)) shr 25; h4 += c; h3 -= c shl 25
        c = (h5 + (1L shl 24)) shr 25; h6 += c; h5 -= c shl 25
        c = (h7 + (1L shl 24)) shr 25; h8 += c; h7 -= c shl 25
        c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26
        c = (h2 + (1L shl 25)) shr 26; h3 += c; h2 -= c shl 26
        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26
        c = (h6 + (1L shl 25)) shr 26; h7 += c; h6 -= c shl 26
        c = (h8 + (1L shl 25)) shr 26; h9 += c; h8 -= c shl 26
        limbs[0] = h0.toInt(); limbs[1] = h1.toInt(); limbs[2] = h2.toInt(); limbs[3] = h3.toInt(); limbs[4] = h4.toInt()
        limbs[5] = h5.toInt(); limbs[6] = h6.toInt(); limbs[7] = h7.toInt(); limbs[8] = h8.toInt(); limbs[9] = h9.toInt()
    }

    /** Bit 0 of the canonical encoding: the RFC 8032 sign of x. */
    fun isNegative(): Int = toBytes()[0].toInt() and 1

    fun isZero(): Boolean = toBytes().all { it.toInt() == 0 }

    companion object {
        /** [count] little-endian bytes at [offset], unsigned. */
        fun load(s: ByteArray, offset: Int, count: Int): Long {
            var v = 0L
            for (i in count - 1 downTo 0) v = (v shl 8) or (s[offset + i].toLong() and 0xff)
            return v
        }
    }
}

/** ref10 ge_p1p1, dalek CompletedPoint: x = X/Z, y = Y/T, the result of every addition and doubling. */
class CompletedPoint(
    val X: FieldElement = FieldElement(), val Y: FieldElement = FieldElement(),
    val Z: FieldElement = FieldElement(), val T: FieldElement = FieldElement(),
) {
    /** this = 2p from p's X, Y, Z (ref10 ge_p2_dbl). */
    fun double(p: EdwardsPoint) {
        T.add(p.X, p.Y)
        T.square(T)
        X.square(p.X)
        Z.square(p.Y)
        Y.add(Z, X)
        Z.sub(Z, X)
        X.sub(T, Y)
        T.square2(p.Z)
        T.sub(T, Z)
    }

    /** this = p + q (ref10 ge_add). */
    fun add(p: EdwardsPoint, q: ProjectiveNielsPoint) {
        Y.add(p.Y, p.X); X.sub(p.Y, p.X)
        Y.mul(Y, q.YplusX); X.mul(X, q.YminusX)
        Y.addSub(X)
        Z.mul(p.Z, q.Z); Z.add(Z, Z)
        T.mul(q.T2d, p.T)
        Z.addSub(T)
    }

    /** this = p - q (ref10 ge_sub). */
    fun sub(p: EdwardsPoint, q: ProjectiveNielsPoint) {
        Y.add(p.Y, p.X); X.sub(p.Y, p.X)
        Y.mul(Y, q.YminusX); X.mul(X, q.YplusX)
        Y.addSub(X)
        T.mul(p.Z, q.Z); T.add(T, T)
        Z.mul(q.T2d, p.T)
        T.addSub(Z)
    }

    /** this = p + q (ref10 ge_madd). */
    fun add(p: EdwardsPoint, q: AffineNielsPoint) {
        Y.add(p.Y, p.X); X.sub(p.Y, p.X)
        Y.mul(Y, q.yplusx); X.mul(X, q.yminusx)
        Y.addSub(X)
        Z.add(p.Z, p.Z)
        T.mul(q.xy2d, p.T)
        Z.addSub(T)
    }

    /** this = p - q (ref10 ge_msub). */
    fun sub(p: EdwardsPoint, q: AffineNielsPoint) {
        Y.add(p.Y, p.X); X.sub(p.Y, p.X)
        Y.mul(Y, q.yminusx); X.mul(X, q.yplusx)
        Y.addSub(X)
        T.add(p.Z, p.Z)
        Z.mul(q.xy2d, p.T)
        T.addSub(Z)
    }
}

/** ref10 ge_cached, dalek ProjectiveNielsPoint: (Y + X, Y - X, Z, 2dT). */
class ProjectiveNielsPoint(
    val YplusX: FieldElement = FieldElement(), val YminusX: FieldElement = FieldElement(),
    val Z: FieldElement = FieldElement(), val T2d: FieldElement = FieldElement(),
) {
    fun set(p: EdwardsPoint) {
        YplusX.add(p.Y, p.X)
        YminusX.sub(p.Y, p.X)
        Z.set(p.Z)
        T2d.mul(p.T, EdwardsPoint.D2)
    }
}

/** ref10 ge_precomp, dalek AffineNielsPoint: (y + x, y - x, 2dxy) with Z = 1. */
class AffineNielsPoint(
    val yplusx: FieldElement = FieldElement(), val yminusx: FieldElement = FieldElement(),
    val xy2d: FieldElement = FieldElement(),
) {
    fun set(p: EdwardsPoint) {
        val recip = FieldElement().also { it.invert(p.Z) }
        val x = FieldElement().also { it.mul(p.X, recip) }
        val y = FieldElement().also { it.mul(p.Y, recip) }
        yplusx.add(y, x)
        yminusx.sub(y, x)
        xy2d.mul(x, y)
        xy2d.mul(xy2d, EdwardsPoint.D2)
    }

    /** this = [b] row[0] for b in -8..8, reading every entry of [row] whatever b is (ref10 select). */
    fun select(row: Array<AffineNielsPoint>, b: Int) {
        val negative = -(b ushr 31)
        val magnitude = b - ((negative and b) shl 1)
        val p = yplusx.limbs
        val m = yminusx.limbs
        val t = xy2d.limbs
        p.fill(0); m.fill(0); t.fill(0)
        p[0] = 1; m[0] = 1
        for (j in 0..7) {
            val mask = -(((magnitude xor (j + 1)) - 1) ushr 31)
            val q = row[j]
            val qp = q.yplusx.limbs
            val qm = q.yminusx.limbs
            val qt = q.xy2d.limbs
            for (i in 0..9) {
                p[i] = p[i] xor ((p[i] xor qp[i]) and mask)
                m[i] = m[i] xor ((m[i] xor qm[i]) and mask)
                t[i] = t[i] xor ((t[i] xor qt[i]) and mask)
            }
        }
        for (i in 0..9) {
            val swap = (p[i] xor m[i]) and negative
            p[i] = p[i] xor swap
            m[i] = m[i] xor swap
            t[i] = t[i] xor ((t[i] xor -t[i]) and negative)
        }
    }
}

/**
 * Point of edwards25519 (-x^2 + y^2 = 1 + d x^2 y^2) in RFC 8032 extended homogeneous coordinates
 * (X:Y:Z:T): x = X/Z, y = Y/Z, xy = T/Z (ref10 ge_p3, dalek EdwardsPoint). Through X, Y, Z alone
 * it is ref10's projective ge_p2, which [projective] and [vartimeDoubleScalarMulBasepoint] produce.
 */
class EdwardsPoint(
    val X: FieldElement = FieldElement(), val Y: FieldElement = FieldElement(),
    val Z: FieldElement = FieldElement(), val T: FieldElement = FieldElement(),
) {
    fun identity() {
        X.zero(); Y.one(); Z.one(); T.zero()
    }

    fun set(p: EdwardsPoint) {
        X.set(p.X); Y.set(p.Y); Z.set(p.Z); T.set(p.T)
    }

    fun negate(p: EdwardsPoint) {
        X.negate(p.X); Y.set(p.Y); Z.set(p.Z); T.negate(p.T)
    }

    /** X, Y, Z of [r]; T is not kept (ref10 ge_p1p1_to_p2). */
    fun projective(r: CompletedPoint) {
        X.mul(r.X, r.T); Y.mul(r.Y, r.Z); Z.mul(r.Z, r.T)
    }

    /** ref10 ge_p1p1_to_p3. */
    fun extended(r: CompletedPoint) {
        X.mul(r.X, r.T); Y.mul(r.Y, r.Z); Z.mul(r.Z, r.T); T.mul(r.X, r.Y)
    }

    /** RFC 8032 5.1.2: y little endian with the sign of x in bit 255. */
    fun encode(): ByteArray {
        val recip = FieldElement().also { it.invert(Z) }
        val x = FieldElement().also { it.mul(X, recip) }
        val s = FieldElement().also { it.mul(Y, recip) }.toBytes()
        s[31] = (s[31].toInt() xor (x.isNegative() shl 7)).toByte()
        return s
    }

    /**
     * curve25519-dalek 4.1.3 CompressedEdwardsY::decompress: y is the low 255 bits of [s] mod p, so
     * y >= p decodes; x = +-sqrt((y^2 - 1)/(dy^2 + 1)) takes the sign of bit 255, and x = 0 with that
     * bit set decodes as x = 0. False when (y^2 - 1)/(dy^2 + 1) is not a square.
     */
    fun decode(s: ByteArray, offset: Int = 0): Boolean {
        val u = FieldElement()
        val v = FieldElement()
        val v3 = FieldElement()
        val vxx = FieldElement()
        Y.fromBytes(s, offset)
        Z.one()
        u.square(Y)
        v.mul(u, D)
        u.sub(u, Z)
        v.add(v, Z)
        v3.square(v); v3.mul(v3, v)
        X.square(v3); X.mul(X, v); X.mul(X, u)
        X.powP58(X)
        X.mul(X, v3); X.mul(X, u)
        vxx.square(X); vxx.mul(vxx, v)
        val check = FieldElement().also { it.sub(vxx, u) }
        if (!check.isZero()) {
            check.add(vxx, u)
            if (!check.isZero()) return false
            X.mul(X, SQRT_M1)
        }
        if (X.isNegative() != (s[offset + 31].toInt() shr 7 and 1)) X.negate(X)
        T.mul(X, Y)
        return true
    }

    /** [8]P is the identity (curve25519-dalek EdwardsPoint::is_small_order). */
    fun isSmallOrder(): Boolean {
        val r = CompletedPoint()
        val q = EdwardsPoint()
        r.double(this); q.projective(r)
        r.double(q); q.projective(r)
        r.double(q); q.projective(r)
        return q.X.isZero() && FieldElement().also { it.sub(q.Y, q.Z) }.isZero()
    }

    /**
     * this = [a]B for the 32-byte little-endian scalar [a], a[31] <= 127, in signed radix 16 over the
     * 32x8 [BASE_TABLE]; no branch or table index depends on [a] (ref10 ge_scalarmult_base).
     */
    fun mulBase(a: ByteArray) {
        val e = ByteArray(64)
        for (i in 0..31) {
            e[2 * i] = (a[i].toInt() and 15).toByte()
            e[2 * i + 1] = (a[i].toInt() shr 4 and 15).toByte()
        }
        var carry = 0
        for (i in 0..62) {
            val digit = e[i] + carry
            carry = (digit + 8) shr 4
            e[i] = (digit - (carry shl 4)).toByte()
        }
        e[63] = (e[63] + carry).toByte()
        val table = BASE_TABLE
        val r = CompletedPoint()
        val t = AffineNielsPoint()
        identity()
        for (i in 1..63 step 2) {
            t.select(table[i / 2], e[i].toInt())
            r.add(this, t); extended(r)
        }
        r.double(this); projective(r)
        r.double(this); projective(r)
        r.double(this); projective(r)
        r.double(this); extended(r)
        for (i in 0..63 step 2) {
            t.select(table[i / 2], e[i].toInt())
            r.add(this, t); extended(r)
        }
    }

    /**
     * X, Y, Z of [a]A + [b]B for scalars below 2^255, in variable time over width-5 sliding windows
     * (ref10 ge_double_scalarmult_vartime, dalek EdwardsPoint::vartime_double_scalar_mul_basepoint).
     */
    fun vartimeDoubleScalarMulBasepoint(a: ByteArray, A: EdwardsPoint, b: ByteArray) {
        val aSlide = slide(a)
        val bSlide = slide(b)
        val odd = BASE_ODD
        val ai = Array(8) { ProjectiveNielsPoint() }
        val t = CompletedPoint()
        val u = EdwardsPoint()
        val a2 = EdwardsPoint()
        ai[0].set(A)
        t.double(A); a2.extended(t)
        for (i in 1..7) {
            t.add(a2, ai[i - 1]); u.extended(t); ai[i].set(u)
        }
        identity()
        var i = 255
        while (i >= 0 && aSlide[i].toInt() == 0 && bSlide[i].toInt() == 0) i--
        while (i >= 0) {
            t.double(this)
            val x = aSlide[i].toInt()
            val y = bSlide[i].toInt()
            if (x > 0) {
                u.extended(t); t.add(u, ai[x / 2])
            } else if (x < 0) {
                u.extended(t); t.sub(u, ai[-x / 2])
            }
            if (y > 0) {
                u.extended(t); t.add(u, odd[y / 2])
            } else if (y < 0) {
                u.extended(t); t.sub(u, odd[-y / 2])
            }
            projective(t)
            i--
        }
    }

    companion object {
        /** d = -121665/121666 (RFC 8032 5.1). */
        val D = FieldElement().also { it.fromBytes("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352".hexToByteArray()) }
        val D2 = FieldElement().also { it.add(D, D) }

        /** sqrt(-1) = 2^((p-1)/4). */
        val SQRT_M1 = FieldElement().also { it.fromBytes("b0a00e4a271beec478e42fad0618432fa7d7fb3d99004d2b0bdfc14f8024832b".hexToByteArray()) }

        /** RFC 8032 base point B: y = 4/5, x even. */
        val BASEPOINT = EdwardsPoint().also { check(it.decode("5866666666666666666666666666666666666666666666666666666666666666".hexToByteArray())) }

        /** row i, entry j: (j + 1) 256^i B (ref10 base[32][8]). */
        val BASE_TABLE: Array<Array<AffineNielsPoint>> by lazy {
            val p = EdwardsPoint().also { it.set(BASEPOINT) }
            val q = EdwardsPoint()
            val c = ProjectiveNielsPoint()
            val r = CompletedPoint()
            Array(32) {
                c.set(p)
                q.set(p)
                val row = Array(8) { j ->
                    if (j > 0) {
                        r.add(q, c); q.extended(r)
                    }
                    AffineNielsPoint().also { it.set(q) }
                }
                repeat(8) { r.double(p); p.extended(r) }
                row
            }
        }

        /** B, 3B, 5B, ..., 15B (ref10 Bi). */
        val BASE_ODD: Array<AffineNielsPoint> by lazy {
            val r = CompletedPoint()
            val q = EdwardsPoint().also { it.set(BASEPOINT) }
            val twice = ProjectiveNielsPoint()
            r.double(BASEPOINT)
            twice.set(EdwardsPoint().also { it.extended(r) })
            Array(8) { i ->
                if (i > 0) {
                    r.add(q, twice); q.extended(r)
                }
                AffineNielsPoint().also { it.set(q) }
            }
        }

        /** ref10 slide: signed width-5 window digits (odd, -15..15) of a 256-bit little-endian scalar. */
        fun slide(a: ByteArray): ByteArray {
            val r = ByteArray(256) { (a[it shr 3].toInt() shr (it and 7) and 1).toByte() }
            for (i in 0..255) {
                if (r[i].toInt() == 0) continue
                var b = 1
                while (b <= 6 && i + b < 256) {
                    if (r[i + b].toInt() != 0) {
                        val low = r[i].toInt()
                        val high = r[i + b].toInt() shl b
                        if (low + high <= 15) {
                            r[i] = (low + high).toByte(); r[i + b] = 0
                        } else if (low - high >= -15) {
                            r[i] = (low - high).toByte()
                            for (k in i + b..255) {
                                if (r[k].toInt() == 0) {
                                    r[k] = 1; break
                                }
                                r[k] = 0
                            }
                        } else break
                    }
                    b++
                }
            }
            return r
        }
    }
}
