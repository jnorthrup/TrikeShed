package borg.trikeshed.job

/** RFC 5869 HKDF-Extract with HMAC-SHA-256: PRK = HMAC(salt, IKM). An empty salt keys HMAC as HashLen zeros do. */
fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

/** RFC 5869 HKDF-Expand with HMAC-SHA-256: the first [length] octets of T(1) | T(2) | ..., T(i) = HMAC(PRK, T(i-1) | info | i). */
fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 0..255 * 32) { "HKDF-SHA-256 output is at most 8160 octets" }
    val okm = ByteArray(length)
    var t = ByteArray(0)
    var i = 1
    while ((i - 1) * 32 < length) {
        t = hmacSha256(prk, t + info + i.toByte())
        t.copyInto(okm, (i - 1) * 32, 0, minOf(32, length - (i - 1) * 32))
        i++
    }
    return okm
}
