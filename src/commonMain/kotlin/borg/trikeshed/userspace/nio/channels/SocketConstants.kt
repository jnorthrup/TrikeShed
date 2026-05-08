@file:Suppress("unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.context.BitMasked

/** POSIX socket domain (address family). Mutually exclusive — pick one. */
enum class SocketDomain(val posix: Int) {
    AF_INET(2),
    AF_INET6(10),
    AF_UNIX(1);
}

/** POSIX socket type — flag-like, ORable with SOCK_NONBLOCK/SOCK_CLOEXEC. */
enum class SocketType(override val mask: Int) : BitMasked<Int> {
    SOCK_STREAM(1),
    SOCK_DGRAM(2),
    SOCK_RAW(3),
    SOCK_NONBLOCK(0x800),
    SOCK_CLOEXEC(0x80000);

    companion object {
        fun withFlags(vararg flags: SocketType): Int =
            flags.fold(0) { acc, f -> acc or f.mask }
    }
}

/** POSIX socket protocol. Mutually exclusive — pick one. */
enum class SocketProtocol(val posix: Int) {
    IPPROTO_IP(0),
    IPPROTO_TCP(6),
    IPPROTO_UDP(17);
}
