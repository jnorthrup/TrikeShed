package borg.trikeshed.platform.kernel

/**
 * JVM-specific syscall implementations
 * JVM does not have direct syscall access; uses Java NIO instead.
 */

/**
 * Densified copy for JVM
 */
actual fun densifiedCopy(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
    src.copyInto(dst, dstOffset, srcOffset, srcOffset + len)
}

/**
 * Densified compare for JVM
 */
actual fun densifiedCompare(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, len: Int): Int {
    for (i in 0 until len) {
        val diff = (a[aOffset + i].toInt() and 0xFF) - (b[bOffset + i].toInt() and 0xFF)
        if (diff != 0) return diff
    }
    return 0
}

/**
 * Densified XOR for JVM
 */
actual fun densifiedXor(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
    for (i in 0 until len) {
        dst[dstOffset + i] = (a[aOffset + i].toInt() xor b[bOffset + i].toInt()).toByte()
    }
}

/**
 * Get default gateway on JVM (not available)
 */
actual fun getDefaultGateway(): Result<String> {
    return Result.failure(NotImplementedError("Gateway detection not available on JVM"))
}

/**
 * Get default IPv6 gateway on JVM (not available)
 */
actual fun getDefaultGatewayV6(): Result<String> {
    return Result.failure(NotImplementedError("IPv6 gateway detection not available on JVM"))
}

/**
 * List network interfaces on JVM (not available)
 */
actual fun listInterfaces(): Result<Map<String, NetworkInterface>> {
    return Result.failure(NotImplementedError("Interface listing not available on JVM"))
}
