package borg.trikeshed.userspace.nio.channels

/**
 * Sockaddr encoders for SQE-borne addresses. BIND and CONNECT carry the address
 * as SQE buffer bytes; encoding is the caller's job, decoding the backend's.
 * The facade uses the Linux little-endian sockaddr ABI on every runtime.
 * Current native Linux targets are x86_64 and arm64; emulated targets decode
 * this framing before calling their host API (it is not a BSD sockaddr).
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

/** struct sockaddr_in6: 28 bytes — family AF_INET6(10), port, flowinfo 0, 16 address octets, scope id 0. */
fun sockaddrIpv6(octets: ByteArray, port: Int): ByteArray {
    require(octets.size == 16) { "ipv6 needs 16 octets, got ${octets.size}" }
    require(port in 0..0xFFFF)
    return ByteArray(28).also {
        it[0] = SocketDomain.AF_INET6.posix.toByte()
        it[1] = 0
        it[2] = (port ushr 8).toByte()
        it[3] = port.toByte()
        octets.copyInto(it, 8)
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
    bytes.let {
        require(it.size >= 2) { "sockaddr requires an address family" }
        (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8)
    }

/** struct sockaddr_in port field (big-endian u16 at offset 2). */
fun sockaddrPort(bytes: ByteArray): Int =
    bytes.let {
        require(it.size >= 16 && sockaddrFamily(it) == SocketDomain.AF_INET.posix) { "IPv4 sockaddr requires 16 bytes and AF_INET" }
        ((it[2].toInt() and 255) shl 8) or (it[3].toInt() and 255)
    }

/** struct sockaddr_in address octets (offset 4..8). */
fun sockaddrOctets(bytes: ByteArray): ByteArray {
    require(bytes.size >= 16 && sockaddrFamily(bytes) == SocketDomain.AF_INET.posix) { "IPv4 sockaddr requires 16 bytes and AF_INET" }
    return bytes.copyOfRange(4, 8)
}

/**
 * The facade's text form of an IP socket address, `a.b.c.d:port` or `[ipv6]:port`, as its sockaddr. Host names are
 * not resolved here ([UnresolvedAddressException]); DNS is [spi.ChannelOperations.resolve].
 */
fun sockaddr(address: String): ByteArray {
    val v6 = address.startsWith('[')
    val split = if (v6) address.indexOf("]:") + 1 else address.lastIndexOf(':')
    if (split <= 0) throw UnresolvedAddressException()
    val port = address.substring(split + 1).toIntOrNull()?.takeIf { it in 0..0xffff } ?: throw UnresolvedAddressException()
    val host = if (v6) address.substring(1, split - 1) else address.substring(0, split)
    val octets = inet_pton(if (v6) SocketDomain.AF_INET6 else SocketDomain.AF_INET, host) ?: throw UnresolvedAddressException()
    return if (v6) sockaddrIpv6(octets, port) else sockaddrIpv4(octets, port)
}

/** The text form of an AF_INET or AF_INET6 [sockaddr], as [sockaddr] reads it. */
fun sockaddrText(bytes: ByteArray): String {
    val port = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
    return when (sockaddrFamily(bytes)) {
        SocketDomain.AF_INET.posix -> "${inet_ntop(bytes.copyOfRange(4, 8))}:$port"
        SocketDomain.AF_INET6.posix -> "[${inet_ntop(bytes.copyOfRange(8, 24))}]:$port"
        else -> throw UnsupportedAddressTypeException()
    }
}

/** POSIX inet_pton: dotted-decimal AF_INET or RFC 4291 AF_INET6 text to network-order octets; null when malformed. */
fun inet_pton(family: SocketDomain, text: String): ByteArray? = when (family) {
    SocketDomain.AF_INET -> text.split('.').takeIf { it.size == 4 }?.let { parts ->
        val octets = parts.map { part ->
            part.takeIf { it.length in 1..3 && it.all(Char::isDigit) && (it.length == 1 || it[0] != '0') }
                ?.toInt()?.takeIf { it <= 255 } ?: return null
        }
        ByteArray(4) { octets[it].toByte() }
    }
    SocketDomain.AF_INET6 -> {
        val dotted = text.substringAfterLast(':').takeIf { '.' in it }
        val ipv4 = dotted?.let { inet_pton(SocketDomain.AF_INET, it) ?: return null }
        val groups = (if (dotted == null) text else text.dropLast(dotted.length) + "0:0").split("::")
        if (groups.size > 2) return null
        fun words(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map {
            it.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null
        }
        val head = words(groups[0]) ?: return null
        val tail = if (groups.size == 2) words(groups[1]) ?: return null else emptyList()
        val all = if (groups.size == 2) head + List(8 - head.size - tail.size) { 0 } + tail else head
        if (all.size != 8 || groups.size == 2 && head.size + tail.size > 7) return null
        ByteArray(16) { (all[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }.also { ipv4?.copyInto(it, 12) }
    }
    SocketDomain.AF_UNIX -> null
}

/** POSIX inet_ntop: 4 octets dotted-decimal, 16 octets in RFC 5952 form (longest zero run of two or more as `::`). */
fun inet_ntop(octets: ByteArray): String {
    if (octets.size == 4) return octets.joinToString(".") { (it.toInt() and 255).toString() }
    require(octets.size == 16) { "an address is 4 or 16 octets" }
    val words = IntArray(8) { ((octets[2 * it].toInt() and 255) shl 8) or (octets[2 * it + 1].toInt() and 255) }
    var start = -1
    var length = 1
    var at = 0
    while (at < 8) {
        var end = at
        while (end < 8 && words[end] == 0) end++
        if (end - at > length) { start = at; length = end - at }
        at = maxOf(end, at + 1)
    }
    fun hex(range: IntRange) = range.joinToString(":") { words[it].toString(16) }
    return if (start < 0) hex(0..7) else hex(0 until start) + "::" + hex(start + length..7)
}
