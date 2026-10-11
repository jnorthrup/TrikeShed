@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.cinterop.*
import platform.posix.*

class PosixChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)

    /** The descriptor is borrowed; prepClose explicitly closes it. */
    fun handleFor(fd: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(256, fd)

    /**
     * getaddrinfo(3) for a stream address: the first AF_INET address's 4 octets (sin_addr, offset 4 of sockaddr_in on
     * Linux and BSD), as the JVM resolver answers, else the first AF_INET6 address's 16 (sin6_addr, offset 8).
     */
    override fun resolve(host: String): ByteArray? = memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = AF_UNSPEC
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
