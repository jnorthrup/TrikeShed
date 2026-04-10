package borg.trikeshed.platform.nio

import java.io.IOException

/**
 * Cross-platform NIO backend trait
 *
 * Provides a unified interface for different platform-specific NIO backends:
 * - Linux: io_uring (primary), epoll (fallback)
 * - macOS/BSD: kqueue
 * - Windows: IOCP (future)
 *
 * This module defines the core abstractions for the NIO subsystem,
 * enabling platform-specific optimizations while maintaining a consistent API.
 *
 * # SPI Hierarchy
 *
 * The NIO system follows a factory-based SPI pattern:
 *
 * ```text
 * NioProvider (root factory)
 * ├── SocketFactory (creates NioSocket)
 * ├── BufferFactory (creates NioBuffer)
 * ├── BackendFactory (creates PlatformBackend)
 * └── CompletionFactory (creates Completion)
 * ```
 *
 * Ported from Rust: /Users/jim/work/literbike/src/userspace/nio/backend.rs
 */

/** Operation types for NIO backends */
enum class OpType {
    Read,
    Write,
    Accept,
    Connect,
    PollAdd,
    PollRemove,
    Nop,
}

/** A completion event from the NIO backend */
data class Completion(
    val userData: Long,
    val result: Result<Int>,
    val opType: OpType,
)

/** Registration token for a monitored file descriptor */
typealias Token = Long

/** Interest flags for file descriptor monitoring */
data class Interest(
    val readable: Boolean,
    val writable: Boolean,
) {
    companion object {
        val READABLE = Interest(readable = true, writable = false)
        val WRITABLE = Interest(readable = false, writable = true)
        val READ_WRITE = Interest(readable = true, writable = true)
    }
}

/** NIO object trait - all NIO objects implement this */
interface NioObject {
    fun asRawFd(): Int?
    fun isOpen(): Boolean
}

/** NIO socket handle */
class NioSocket(
    private val fd: Int,
    private val domain: SocketDomain,
    private val socketType: SocketType,
) : NioObject {
    fun fd(): Int = fd
    fun domain(): SocketDomain = domain
    fun socketType(): SocketType = socketType

    override fun asRawFd(): Int? = if (fd >= 0) fd else null
    override fun isOpen(): Boolean = fd >= 0
}

/** Socket domain */
enum class SocketDomain {
    Inet,
    Inet6,
    Unix,
}

/** Socket type */
enum class SocketType {
    Stream,
    Dgram,
}

/** NIO buffer for zero-copy operations */
interface NioBuffer : NioObject {
    fun asPtr(): Long
    fun len(): Int
    fun clear()
}

/** Memory-mapped buffer */
class MmapBuffer(
    private var ptr: Long,
    private var size: Int,
) : NioBuffer {
    override fun asPtr(): Long = ptr
    override fun len(): Int = size
    override fun clear() {
        if (ptr != 0L) {
            // UNSAFE: Zero memory via FFI
            zeroMemory(ptr, size)
        }
    }

    // UNSAFE: MmapBuffer is thread-safe because we ensure exclusive access via mmap
    // and the pointer is only used for read/write operations that are controlled
    protected fun finalize() {
        if (ptr != 0L && size > 0) {
            munmap(ptr, size)
        }
    }
}

// ============================================================================
// FACTORY TRAITS - SPI Pattern
// ============================================================================

/** Root NIO provider factory - all other factories derive from this */
interface NioProvider {
    fun socketFactory(): SocketFactory
    fun bufferFactory(): BufferFactory
    fun backendFactory(): BackendFactory
    fun completionFactory(): CompletionFactory
    fun name(): String
    fun priority(): Int
}

/** Socket factory - creates NioSocket instances */
interface SocketFactory {
    @Throws(IOException::class)
    fun createSocket(domain: SocketDomain, socketType: SocketType): NioSocket

    @Throws(IOException::class)
    fun createPair(domain: SocketDomain, socketType: SocketType): Pair<NioSocket, NioSocket>

    @Throws(IOException::class)
    fun setNonblocking(socket: NioSocket, nonblocking: Boolean)

    @Throws(IOException::class)
    fun bind(socket: NioSocket, addr: ByteArray)

    @Throws(IOException::class)
    fun listen(socket: NioSocket, backlog: Int)

    @Throws(IOException::class)
    fun connect(socket: NioSocket, addr: ByteArray)

    @Throws(IOException::class)
    fun accept(socket: NioSocket): NioSocket
}

/** Buffer factory - creates NioBuffer instances */
interface BufferFactory {
    @Throws(IOException::class)
    fun createMmapBuffer(size: Int): MmapBuffer

    @Throws(IOException::class)
    fun createBuffer(size: Int): NioBuffer

    @Throws(IOException::class)
    fun createAlignedBuffer(size: Int, align: Int): NioBuffer
}

/** Backend factory - creates PlatformBackend instances */
interface BackendFactory {
    @Throws(IOException::class)
    fun createBackend(config: BackendConfig): PlatformBackend
    fun isAvailable(): Boolean
}

/** Completion factory - creates Completion instances */
interface CompletionFactory {
    fun createCompletion(
        userData: Long,
        result: Result<Int>,
        opType: OpType,
    ): Completion
    fun createCompletionVec(capacity: Int): MutableList<Completion>
}

/** Platform-agnostic NIO backend trait
 *
 * Each platform implements this trait to provide non-blocking I/O:
 * - Linux: io_uring or epoll
 * - macOS/BSD: kqueue
 *
 * The trait is designed for zero-allocation hot paths and
 * supports batching operations for maximum throughput.
 */
interface PlatformBackend {
    @Throws(IOException::class)
    fun register(fd: Int, token: Token, interest: Interest)

    @Throws(IOException::class)
    fun reregister(fd: Int, token: Token, interest: Interest)

    @Throws(IOException::class)
    fun unregister(fd: Int)

    @Throws(IOException::class)
    fun submitRead(fd: Int, buf: ByteArray, userData: Long)

    @Throws(IOException::class)
    fun submitWrite(fd: Int, buf: ByteArray, userData: Long)

    @Throws(IOException::class)
    fun submitReadAt(fd: Int, offset: Long, buf: ByteArray, userData: Long)

    @Throws(IOException::class)
    fun submitWriteAt(fd: Int, offset: Long, buf: ByteArray, userData: Long)

    @Throws(IOException::class)
    fun submitPoll(fd: Int, interest: Interest, userData: Long)

    @Throws(IOException::class)
    fun submitNop(userData: Long)

    @Throws(IOException::class)
    fun submit(): Long

    @Throws(IOException::class)
    fun wait(min: Int): Long

    @Throws(IOException::class)
    fun peek(): Long

    @Throws(IOException::class)
    fun pollCompletion(): Completion?

    @Throws(IOException::class)
    fun pollCompletions(completions: Array<Completion?>): Int

    fun asRawFd(): Int? = null
}

/** Backend configuration */
data class BackendConfig(
    var entries: Int = 256,
    var sqpoll: Boolean = false,
    var iopoll: Boolean = false,
)

/** Default completion factory */
object DefaultCompletionFactory : CompletionFactory {
    override fun createCompletion(
        userData: Long,
        result: Result<Int>,
        opType: OpType,
    ): Completion = Completion(userData, result, opType)

    override fun createCompletionVec(capacity: Int): MutableList<Completion> =
        mutableListOf()
}

/** Default buffer factory */
object DefaultBufferFactory : BufferFactory {
    override fun createMmapBuffer(size: Int): MmapBuffer {
        val ptr = mmap(size)
        if (ptr == 0L) throw IOException("mmap failed: ${lastOsError()}")
        return MmapBuffer(ptr, size)
    }

    override fun createBuffer(size: Int): NioBuffer = createMmapBuffer(size)
    override fun createAlignedBuffer(size: Int, align: Int): NioBuffer = createBuffer(size)
}

/** Full NIO provider combining all factories */
class NioProviderImpl : NioProvider {
    val socketFactoryImpl = DefaultSocketFactory
    val bufferFactoryImpl = DefaultBufferFactory
    val completionFactoryImpl = DefaultCompletionFactory

    override fun socketFactory(): SocketFactory = socketFactoryImpl
    override fun bufferFactory(): BufferFactory = bufferFactoryImpl
    override fun completionFactory(): CompletionFactory = completionFactoryImpl
    override fun backendFactory(): BackendFactory = DefaultBackendFactory
    override fun name(): String = "default"
    override fun priority(): Int = 0
}

/** Default backend factory */
object DefaultBackendFactory : BackendFactory {
    @Throws(IOException::class)
    override fun createBackend(config: BackendConfig): PlatformBackend = detectBackend(config)
    override fun isAvailable(): Boolean = true
}

/** Global provider registry */
private val providerRegistry = mutableListOf<NioProvider>()

/** Register a NIO provider */
@Synchronized
fun registerProvider(provider: NioProvider) {
    providerRegistry.add(provider)
    providerRegistry.sortByDescending { it.priority() }
}

/** Get the best available provider */
@Synchronized
fun getProvider(): NioProvider? = providerRegistry.firstOrNull()

/** Create a NioSocket using the best available provider */
@Throws(IOException::class)
fun createSocket(domain: SocketDomain, socketType: SocketType): NioSocket =
    getProvider()?.socketFactory()?.createSocket(domain, socketType)
        ?: throw IOException("No NIO provider registered")

/** Create a buffer using the best available provider */
@Throws(IOException::class)
fun createBuffer(size: Int): NioBuffer =
    getProvider()?.bufferFactory()?.createBuffer(size)
        ?: throw IOException("No NIO provider registered")

/** Create a mmap buffer using the best available provider */
@Throws(IOException::class)
fun createMmapBuffer(size: Int): MmapBuffer =
    getProvider()?.bufferFactory()?.createMmapBuffer(size)
        ?: throw IOException("No NIO provider registered")

/** Initialize with default provider */
fun initDefault() {
    registerProvider(NioProviderImpl())
}

// ============================================================================
// FFI stubs — replaced by expect/actual in platform source sets
// ============================================================================

/** Detect the best available backend for the current platform */
@Throws(IOException::class)
fun detectBackend(config: BackendConfig): PlatformBackend {
    // Platform-specific: Linux → io_uring → epoll, macOS → kqueue
    // Actual implementations in linuxMain/posixMain/jvmMain
    throw IOException("No NIO backend available for this platform")
}

/** UNSAFE: FFI — mmap anonymous memory */
expect fun mmap(size: Int): Long

/** UNSAFE: FFI — munmap */
expect fun munmap(ptr: Long, size: Int)

/** UNSAFE: FFI — zero memory region */
expect fun zeroMemory(ptr: Long, size: Int)

/** FFI — get last OS error string */
expect fun lastOsError(): String

/** FFI — socket creation */
expect fun socketCreate(domain: Int, type: Int): Int

/** FFI — socket close */
expect fun socketClose(fd: Int)

/** FFI — set nonblocking */
expect fun setNonblocking(fd: Int, nonblocking: Boolean)

/** FFI — bind */
expect fun bind(fd: Int, addr: ByteArray): Int

/** FFI — listen */
expect fun listen(fd: Int, backlog: Int): Int

/** FFI — connect */
expect fun connect(fd: Int, addr: ByteArray): Int

/** FFI — accept */
expect fun accept(fd: Int): Int

/** FFI — read */
expect fun read(fd: Int, buf: ByteArray): Int

/** FFI — write */
expect fun write(fd: Int, buf: ByteArray): Int
