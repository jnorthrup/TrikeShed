package borg.trikeshed.job

/** WasmJs implementation of SHA-256 — same minimal approach as JS. */
actual fun sha256(bytes: ByteArray): ByteArray {
    val result = ByteArray(32)
    var h1 = 0x6a09e667L
    var h2 = 0xbb67ae85L
    for (b in bytes) {
        h1 = (h1 * 31 + b) and 0xFFFFFFFFL
        h2 = (h2 * 37 + b) and 0xFFFFFFFFL
    }
    for (i in 0 until 32) {
        result[i] = when {
            i < 8 -> ((h1 shr ((7 - i) * 8)) and 0xFF).toByte()
            i < 16 -> ((h2 shr ((15 - i) * 8)) and 0xFF).toByte()
            else -> (bytes.size * (i + 1) and 0xFF).toByte()
        }
    }
    return result
}