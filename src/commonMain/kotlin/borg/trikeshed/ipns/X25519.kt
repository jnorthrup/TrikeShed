package borg.trikeshed.ipns

/** RFC 7748 5: the u-coordinate 9 of the X25519 base point, little endian. */
val X25519_BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

/** a24 = (486662 - 2) / 4 for curve25519 (RFC 7748 5). */
val X25519_A24: FieldElement = FieldElement().also { it.limbs[0] = 121665 }

/**
 * RFC 7748 5 X25519(k, u): the 32-byte scalar [k] decoded with bits 0-2 and 255 cleared and bit 254 set,
 * the 32-byte [u] with bit 255 masked (non-canonical values reduce mod p), the Montgomery ladder with
 * a constant-time conditional swap, and the result u-coordinate little endian.
 */
fun x25519(k: ByteArray, u: ByteArray): ByteArray {
    require(k.size == 32 && u.size == 32) { "X25519 takes a 32-byte scalar and a 32-byte u-coordinate" }
    val scalar = k.copyOf()
    scalar[0] = (scalar[0].toInt() and 248).toByte()
    scalar[31] = (scalar[31].toInt() and 127 or 64).toByte()
    val x1 = FieldElement().also { it.fromBytes(u) }
    val x2 = FieldElement().also { it.one() }
    val z2 = FieldElement()
    val x3 = FieldElement().also { it.set(x1) }
    val z3 = FieldElement().also { it.one() }
    val a = FieldElement()
    val aa = FieldElement()
    val b = FieldElement()
    val bb = FieldElement()
    val e = FieldElement()
    val c = FieldElement()
    val d = FieldElement()
    val da = FieldElement()
    val cb = FieldElement()
    var swap = 0
    for (t in 254 downTo 0) {
        val kt = (scalar[t ushr 3].toInt() ushr (t and 7)) and 1
        swap = swap xor kt
        conditionalSwap(x2, x3, swap)
        conditionalSwap(z2, z3, swap)
        swap = kt
        a.add(x2, z2)
        aa.square(a)
        b.sub(x2, z2)
        bb.square(b)
        e.sub(aa, bb)
        c.add(x3, z3)
        d.sub(x3, z3)
        da.mul(d, a)
        cb.mul(c, b)
        x3.add(da, cb)
        x3.square(x3)
        z3.sub(da, cb)
        z3.square(z3)
        z3.mul(x1, z3)
        x2.mul(aa, bb)
        z2.mul(X25519_A24, e)
        z2.add(aa, z2)
        z2.mul(e, z2)
    }
    conditionalSwap(x2, x3, swap)
    conditionalSwap(z2, z3, swap)
    z2.invert(z2)
    x2.mul(x2, z2)
    return x2.toBytes()
}

/** (f, g) = (g, f) when [swap] is 1, by masks rather than a branch. */
fun conditionalSwap(f: FieldElement, g: FieldElement, swap: Int) {
    val mask = -swap
    for (i in 0..9) {
        val x = mask and (f.limbs[i] xor g.limbs[i])
        f.limbs[i] = f.limbs[i] xor x
        g.limbs[i] = g.limbs[i] xor x
    }
}
