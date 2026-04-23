package borg.trikeshed.context

/**
 * SPI for pluggable userspace NIO backends (io_uring, epoll, kqueue, etc).
 * Fanout MUST deliver to ALL listeners before the caller coroutine resumes.
 */
interface UserspaceNioSpi {
    suspend fun open(fd: Int): NioUserspaceElement
    suspend fun close(element: NioUserspaceElement)
    /** Deliver event to every listener; structured — all must receive before return. */
    suspend fun fanout(event: Any, listeners: List<AsyncContextElement>)
}
