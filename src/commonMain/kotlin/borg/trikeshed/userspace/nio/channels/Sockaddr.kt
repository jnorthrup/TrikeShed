package borg.trikeshed.userspace.nio.channels

/**
 * Sockaddr encoders for SQE-borne addresses. BIND and CONNECT carry the address
 * as SQE buffer bytes; encoding is the caller's job, decoding the backend's.
 * Layout matches struct sockaddr_in / sockaddr_un as the kernel parses them:
 * family is little-endian u16, port big-endian ("network order"), payload
 * zero-padded to the struct size.
 */

/** struct sockaddr_in: 16 bytes — family AF_INET(2), port, 4 address octets, padding. */
fun sockaddrIpv4(octets: ByteArray, port: Int): ByteArray {
    require(octets.size == 4) { "ipv4 needs 4 octets, got ${octets.size}" }
    require(port in 0..0xFFFF)
    return ByteArray(16).also {
        it[0] = SocketDomain.AF_INET.posix.toByte()
        it[1] = 0
        it[2] = (port ushr 8).toByte()
        it[3] = port.toByte()
        octets.copyInto(it, 4)
    }
}

/** struct sockaddr_un: family AF_UNIX(1) then the NUL-terminated path. */
fun sockaddrUnix(path: String): ByteArray {
    require(path.isNotEmpty() && '\u0000' !in path) { "unix socket path must be non-empty and NUL-free" }
    val pathBytes = path.encodeToByteArray()
    require(pathBytes.size < 108) { "unix socket path exceeds sockaddr_un sun_path" }
    return ByteArray(2 + 108).also {
        it[0] = SocketDomain.AF_UNIX.posix.toByte()
        it[1] = 0
        pathBytes.copyInto(it, 2)
    }
}

/** Address family from encoded sockaddr bytes (little-endian u16). */
fun sockaddrFamily(bytes: ByteArray): Int =
    (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)

/** struct sockaddr_in port field (big-endian u16 at offset 2). */
fun sockaddrPort(bytes: ByteArray): Int =
    ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)

/** struct sockaddr_in address octets (offset 4..8). */
fun sockaddrOctets(bytes: ByteArray): ByteArray = bytes.copyOfRange(4, 8)
