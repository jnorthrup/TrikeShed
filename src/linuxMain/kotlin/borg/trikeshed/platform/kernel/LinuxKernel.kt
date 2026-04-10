package borg.trikeshed.platform.kernel

/**
 * Linux-specific syscall implementations
 */

/**
 * Densified copy for Linux
 */
actual fun densifiedCopy(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
    src.copyInto(dst, dstOffset, srcOffset, srcOffset + len)
}

/**
 * Densified compare for Linux
 */
actual fun densifiedCompare(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, len: Int): Int {
    for (i in 0 until len) {
        val diff = (a[aOffset + i].toInt() and 0xFF) - (b[bOffset + i].toInt() and 0xFF)
        if (diff != 0) return diff
    }
    return 0
}

/**
 * Densified XOR for Linux
 */
actual fun densifiedXor(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
    for (i in 0 until len) {
        dst[dstOffset + i] = (a[aOffset + i].toInt() xor b[bOffset + i].toInt()).toByte()
    }
}

/**
 * Get default gateway on Linux (would parse /proc/net/route)
 */
actual fun getDefaultGateway(): Result<String> {
    return Result.failure(NotImplementedError("Gateway detection requires native access"))
}

/**
 * Get default IPv6 gateway on Linux (would parse /proc/net/ipv6_route)
 */
actual fun getDefaultGatewayV6(): Result<String> {
    return Result.failure(NotImplementedError("IPv6 gateway detection requires native access"))
}

/**
 * List network interfaces on Linux (would use getifaddrs)
 */
actual fun listInterfaces(): Result<Map<String, NetworkInterface>> {
    return Result.failure(NotImplementedError("Interface listing requires native access"))
}
