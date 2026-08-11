package borg.trikeshed.util

private val LOWER_HEX = "0123456789abcdef".toCharArray()
private val UPPER_HEX = "0123456789ABCDEF".toCharArray()

fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (b in this@toLowerHex) {
        val v = b.toInt()
        append(LOWER_HEX[(v ushr 4) and 0x0F])
        append(LOWER_HEX[v and 0x0F])
    }
}

fun ByteArray.toUpperHex(): String = buildString(size * 2) {
    for (b in this@toUpperHex) {
        val v = b.toInt()
        append(UPPER_HEX[(v ushr 4) and 0x0F])
        append(UPPER_HEX[v and 0x0F])
    }
}

fun hex(bytes: ByteArray): String = bytes.toLowerHex()
