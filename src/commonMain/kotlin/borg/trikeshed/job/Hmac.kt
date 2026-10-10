package borg.trikeshed.job

/**
 * RFC 2104 HMAC: H((K0 xor opad) | H((K0 xor ipad) | text)), K0 the key zero-padded to the
 * hash's block size [blockSize] after hashing a key longer than the block.
 */
fun hmac(hash: (ByteArray) -> ByteArray, blockSize: Int, key: ByteArray, text: ByteArray): ByteArray {
    val k0 = if (key.size > blockSize) hash(key) else key
    val inner = ByteArray(blockSize + text.size)
    val outer = ByteArray(blockSize)
    for (i in 0 until blockSize) {
        val k = if (i < k0.size) k0[i].toInt() else 0
        inner[i] = (k xor 0x36).toByte()
        outer[i] = (k xor 0x5c).toByte()
    }
    text.copyInto(inner, blockSize)
    return hash(outer + hash(inner))
}

/** RFC 4231 HMAC-SHA-256. */
fun hmacSha256(key: ByteArray, text: ByteArray): ByteArray = hmac(Sha256Pure::digest, 64, key, text)

/** RFC 4231 HMAC-SHA-512. */
fun hmacSha512(key: ByteArray, text: ByteArray): ByteArray = hmac({ Sha512Pure.digest(it) }, 128, key, text)
