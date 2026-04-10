package borg.trikeshed.platform.nio

/**
 * Unified NIO (Non-Blocking I/O) SPI Facade Layer
 *
 * This module provides a public Service Provider Interface (SPI) facade
 * over platform-specific NIO backends (io_uring, kqueue, epoll).
 */

// Re-exported from submodules:
// backend - PlatformBackend trait and core abstractions
// endgame - Endgame capabilities and UringFacade
// epoll_backend - Linux epoll backend
// kqueue_backend - macOS/BSD kqueue backend
// nio_uring - Linux io_uring backend
// reactor - High-level reactor interface
// session_island - Session isolation and CCEK patterns
// suspend_resume - Suspend/resume primitives

/**
 * Provider identifier for socket operations
 */
enum class Provider {
    Socket,
    IoUring,
    Mmap
}

/**
 * NioObserver trait for observing NIO operations
 * Allows external subscribers (keymux, MCP) to monitor all LLM socket events
 */
interface NioObserver {
    /**
     * Called after a socket write operation
     */
    fun onSocketWrite(fd: Long, bytes: ByteArray, provider: Provider)

    /**
     * Called after a socket read operation
     */
    fun onSocketRead(fd: Long, bytes: ByteArray, provider: Provider)

    /**
     * Called after an io_uring submit operation
     */
    fun onIoUringSubmit(fd: Long, op: String)

    /**
     * Called after an mmap operation
     */
    fun onMmap(addr: Long, len: Int)
}

/**
 * Result type for NIO operations
 */
typealias NioResult<T> = Result<T>
