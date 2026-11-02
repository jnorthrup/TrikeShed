@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.Winsock
import kotlinx.cinterop.*
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_STREAM
import platform.posix.memset
import platform.windows.addrinfo
import platform.windows.freeaddrinfo
import platform.windows.getaddrinfo

/** [ChannelOperations] on Windows: the facade's channel handle over the Winsock ring (UserspaceIO.mingw.kt). */
class WinsockChannelOperations : ChannelOperations {
    init { check(Winsock.started) }

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)

    /** The descriptor is borrowed; prepClose explicitly closes it. */
    fun handleFor(fd: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(256, fd)

    /**
     * getaddrinfo for a stream address: the first AF_INET address's 4 octets (sin_addr, offset 4 of sockaddr_in), else
     * the first AF_INET6 address's 16 (sin6_addr, offset 8), as the POSIX resolver answers.
     */
    override fun resolve(host: String): ByteArray? = memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = 0
        hints.ai_socktype = SOCK_STREAM
        val result = allocPointerTo<addrinfo>()
        if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped null
        try {
            val entries = generateSequence(result.value) { it.pointed.ai_next }.map { it.pointed }.toList()
            entries.firstOrNull { it.ai_family == AF_INET }?.ai_addr?.reinterpret<ByteVar>()?.let { address -> ByteArray(4) { address[4 + it] } }
                ?: entries.firstOrNull { it.ai_family == AF_INET6 }?.ai_addr?.reinterpret<ByteVar>()?.let { address -> ByteArray(16) { address[8 + it] } }
        } finally {
            freeaddrinfo(result.value)
        }
    }
}
