package borg.trikeshed.job

/** RFC 5869 HKDF-Extract: PRK = HMAC-Hash(salt, IKM), HMAC-SHA-256 unless [hmac] names another. An empty salt keys HMAC as HashLen zeros do. */
fun hkdfExtract(salt: ByteArray, ikm: ByteArray, hmac: (ByteArray, ByteArray) -> ByteArray = ::hmacSha256): ByteArray = hmac(salt, ikm)

/** RFC 5869 HKDF-Expand: the first [length] octets of T(1) | T(2) | ..., T(i) = HMAC-Hash(PRK, T(i-1) | info | i), at most 255 HashLen. */
fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int, hmac: (ByteArray, ByteArray) -> ByteArray = ::hmacSha256): ByteArray {
    require(length >= 0) { "HKDF output length" }
    if (length == 0) return ByteArray(0)
    var t = hmac(prk, info + 1.toByte())
    val hashLength = t.size
    require(hashLength > 0 && length.toLong() <= 255L * hashLength) { "HKDF output is at most 255 HashLen octets" }
    val okm = ByteArray(length)
    var at = 0
    var i = 1
    while (at < length) {
        val count = minOf(hashLength, length - at)
        t.copyInto(okm, at, 0, count)
        at += count
        if (at < length) {
            i++
            t = hmac(prk, t + info + i.toByte())
            require(t.size == hashLength) { "HKDF HMAC output width changed" }
        }
    }
    return okm
}
