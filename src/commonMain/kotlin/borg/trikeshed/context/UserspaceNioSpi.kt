package borg.trikeshed.context

/**
 * SPI surface for pluggable userspace NIO backends (io_uring FFI, epoll, kqueue, ...).
 *
 * Implementations expose:
 * - file/socket lifecycle via [NioUserspaceElement]
 * - structured event fanout via [fanout] — every listener receives before the caller resumes
 * - an optional liburing facade matching [borg.trikeshed.userspace.LiburingFacade]
 *
 * Discovery is via ServiceLoader (`META-INF/services/borg.trikeshed.context.UserspaceNioSpi`)
 * to keep JVM/JS/Native installs isolated.
 */
interface UserspaceNioSpi {
    suspend fun open(fd: Int): NioUserspaceElement
    suspend fun close(element: NioUserspaceElement)

    /** Deliver event to every listener; structured — all must receive before return. */
    suspend fun fanout(event: Any, listeners: List<AsyncContextElement>)

    /**
     * Optional liburing surface. Implementations that do not bind to liburing
     * return null. JVM resolve: `spi.liburing?.asLiburingFacade()`.
     */
    val liburing: LiburingSurface? get() = null

    /** Convenience for SPI implementations that back the liburing surface. */
    interface LiburingSurface : borg.trikeshed.userspace.LiburingFacade
}
