@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Winsock for the process: WSAStartup once, the Linux errno of a Winsock error, and the descriptors that are sockets.
 * A SOCKET is a handle; Windows hands out values that fit an Int, which is the descriptor everywhere above this file.
 */
internal object Winsock {
    val started: Boolean by lazy {
        memScoped {
            val data = alloc<WSADATA>()
            check(WSAStartup(0x0202u, data.ptr) == 0) { "WSAStartup failed: ${WSAGetLastError()}" }
        }
        true
    }

    /** Descriptors this process opened as sockets; every other descriptor is a C runtime file. */
    val sockets = HashSet<Int>()

    fun isSocket(fd: Int): Boolean = fd in sockets

    /** The Linux errno, negated, of the calling thread's last Winsock error. */
    fun error(): Int = -linuxErrno(WSAGetLastError())

    fun linuxErrno(code: Int): Int = when (code) {
        WSAEWOULDBLOCK, WSAEINPROGRESS -> 11
        WSAEINTR -> 4
        WSAEBADF, WSAENOTSOCK -> 9
        WSAEACCES -> 13
        WSAEFAULT -> 14
        WSAEINVAL -> 22
        WSAEMFILE -> 24
        WSAEMSGSIZE -> 90
        WSAEAFNOSUPPORT -> 97
        WSAEADDRINUSE -> 98
        WSAEADDRNOTAVAIL -> 99
        WSAENETDOWN -> 100
        WSAENETUNREACH -> 101
        WSAECONNABORTED -> 103
        WSAECONNRESET -> 104
        WSAENOBUFS -> 105
        WSAEISCONN -> 106
        WSAENOTCONN -> 107
        WSAETIMEDOUT -> 110
        WSAECONNREFUSED -> 111
        WSAEHOSTUNREACH -> 113
        WSAEALREADY -> 114
        else -> 5
    }

    /** Socket descriptor [fd] in nonblocking mode, or the negated Linux errno. */
    fun nonblocking(fd: Int): Int = memScoped {
        val on = alloc<u_longVar>().apply { value = 1u }
        if (ioctlsocket(fd.toULong(), FIONBIO.convert(), on.ptr) != 0) error() else 0
    }

    /**
     * Linux-framed sockaddr bytes (sockaddr.kt: little-endian u16 family, port in network order) as Windows reads
     * them. The layout is Windows' own except the AF_INET6 number: Linux 10, Windows 23.
     */
    fun native(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        if ((out[0].toInt() and 0xff) == 10 && out[1].toInt() == 0) out[0] = AF_INET6.toByte()
        return out
    }
}

actual class FileImpl actual constructor(actual val id: Int) {
    private var closed = id < 0
    actual fun isOpen(): Boolean = !closed
    actual fun close() {
        if (closed) return
        closed = true
        if (Winsock.sockets.remove(id)) closesocket(id.toULong()) else close(id)
    }

    actual fun size(): Long = if (closed || Winsock.isSocket(id)) -1L else memScoped {
        val st = alloc<_stat64>()
        if (_fstat64(id, st.ptr) == 0) st.st_size else -1L
    }
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val fd = open(path, (if (readOnly) O_RDONLY else O_RDWR or O_CREAT) or O_BINARY, 420)
        if (fd < 0) throw IllegalStateException("open($path) errno=$errno")
        return FileImpl(fd)
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl {
        val fd = MingwUserspaceChannelBackend.openSocket(domain, type, protocol)
        if (fd < 0) throw IllegalStateException("socket errno=${-fd}")
        return FileImpl(fd)
    }
}

/**
 * The Windows ring: an emulated ring whose SQEs this process services with Winsock and C runtime calls, one CQE per
 * SQE before submitBatch returns, as the WASI ring does. Sockets are nonblocking: a READ or WRITE that would block
 * completes -11 (EAGAIN) and the caller waits on its reactor. CONNECT settles before it completes: WSAPoll for
 * writability, then SO_ERROR. Results use Linux errno numbers. Windows has no kernel ring, so NATIVE is refused.
 */
internal class MingwUserspaceChannelBackend(val entries: Int) : UserspaceChannelBackend {
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
        UringOp.FSYNC, UringOp.CLOSE, UringOp.SOCKET, UringOp.BIND, UringOp.LISTEN, UringOp.ACCEPT, UringOp.CONNECT)
    override val availability: String get() = "emulated: Winsock and C runtime effects; no kernel ring on Windows"
    private var closed = false

    companion object {
        /** A nonblocking socket; [type] drops the Linux SOCK_NONBLOCK / SOCK_CLOEXEC bits Windows does not know. */
        fun openSocket(domain: Int, type: Int, protocol: Int): Int {
            check(Winsock.started)
            val s = socket(if (domain == 10) AF_INET6 else domain, type and 0xf, protocol)
            if (s == INVALID_SOCKET) return Winsock.error()
            val fd = s.toInt()
            val set = Winsock.nonblocking(fd)
            if (set < 0) { closesocket(s); return set }
            Winsock.sockets.add(fd)
            return fd
        }

        /** Linux open(2) flags as the C runtime's, always binary. */
        fun openFlags(linux: Int): Int {
            var flags = (linux and 3) or O_BINARY
            if (linux and UringOp.O_CREAT != 0) flags = flags or O_CREAT
            if (linux and UringOp.O_EXCL != 0) flags = flags or O_EXCL
            if (linux and UringOp.O_TRUNC != 0) flags = flags or O_TRUNC
            if (linux and UringOp.O_APPEND != 0) flags = flags or O_APPEND
            return flags
        }
    }

    private fun address(sub: UringSubmission, call: (CPointer<sockaddr>, Int) -> Int): Int {
        val buffer = sub.buffer ?: return -14
        val start = buffer.arrayOffset() + buffer.position()
        val bytes = Winsock.native(buffer.array().copyOfRange(start, start + sub.len))
        return bytes.usePinned { call(it.addressOf(0).reinterpret(), bytes.size) }
    }

    private fun connect(sub: UringSubmission): Int {
        val fd = sub.fd
        val started = address(sub) { sa, len -> if (connect(fd.toULong(), sa, len) == 0) 0 else Winsock.error() }
        if (started != -11) return started
        return memScoped {
            val pfd = alloc<WSAPOLLFD>().apply { this.fd = fd.toULong(); events = POLLWRNORM.convert(); revents = 0 }
            val n = WSAPoll(pfd.ptr, 1u, 30_000)
            if (n < 0) return@memScoped Winsock.error()
            if (n == 0) return@memScoped -110
            val err = alloc<IntVar>()
            val len = alloc<IntVar>().apply { value = sizeOf<IntVar>().toInt() }
            if (getsockopt(fd.toULong(), SOL_SOCKET, SO_ERROR, err.ptr.reinterpret(), len.ptr) != 0) Winsock.error()
            else if (err.value == 0) 0 else -Winsock.linuxErrno(err.value)
        }
    }

    private fun transfer(sub: UringSubmission, write: Boolean): Int {
        val buffer = sub.buffer ?: return -14
        val start = buffer.arrayOffset() + buffer.position()
        if (sub.len == 0) return 0
        val n = buffer.array().usePinned { pinned ->
            val at = pinned.addressOf(start)
            if (Winsock.isSocket(sub.fd)) {
                val r = if (write) send(sub.fd.toULong(), at, sub.len, 0) else recv(sub.fd.toULong(), at, sub.len, 0)
                if (r < 0) Winsock.error() else r
            } else {
                if (sub.offset >= 0 && _lseeki64(sub.fd, sub.offset, SEEK_SET) < 0) return@usePinned -errno
                val r = if (write) write(sub.fd, at, sub.len.convert()) else read(sub.fd, at, sub.len.convert())
                if (r < 0) -errno else r
            }
        }
        if (n > 0) buffer.position(buffer.position() + n)
        return n
    }

    private fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0) return -95
        return when (sub.opcode) {
            UringOp.NOP -> 0
            UringOp.OPENAT -> {
                if (sub.fd != UringOp.AT_FDCWD) return -95
                val bytes = sub.buffer ?: return -14
                val start = bytes.arrayOffset() + bytes.position()
                val path = bytes.array().copyOfRange(start, start + sub.len).decodeToString().trimEnd('\u0000')
                val fd = open(path, openFlags(sub.offset.toInt()), 420)
                if (fd < 0) -errno else fd
            }
            UringOp.READ -> transfer(sub, write = false)
            UringOp.WRITE -> transfer(sub, write = true)
            UringOp.FSYNC -> if (_commit(sub.fd) == 0) 0 else -errno
            UringOp.SOCKET -> openSocket(sub.fd, sub.offset.toInt(), sub.len)
            UringOp.BIND -> address(sub) { sa, len -> if (bind(sub.fd.toULong(), sa, len) == 0) 0 else Winsock.error() }
            UringOp.LISTEN -> if (listen(sub.fd.toULong(), sub.len) == 0) 0 else Winsock.error()
            UringOp.ACCEPT -> {
                val s = accept(sub.fd.toULong(), null, null)
                if (s == INVALID_SOCKET) Winsock.error() else {
                    val fd = s.toInt()
                    Winsock.nonblocking(fd)
                    Winsock.sockets.add(fd)
                    fd
                }
            }
            UringOp.CONNECT -> connect(sub)
            UringOp.CLOSE ->
                if (Winsock.sockets.remove(sub.fd)) { if (closesocket(sub.fd.toULong()) == 0) 0 else Winsock.error() }
                else if (close(sub.fd) == 0) 0 else -errno
            else -> -95
        }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = Array(submissions.size) { index ->
            val sub = submissions[index]
            UringCompletion(sub.userData, execute(sub), 0)
        }
        return results.size j { results[it] }
    }

    override fun close() { closed = true }
}

actual fun openUserspaceChannelBackend(entries: Int, mode: UringBackendMode?): UserspaceChannelBackend {
    require(entries > 0)
    check(mode != UringBackendMode.NATIVE) { "Native io_uring required: Windows has no kernel ring" }
    return MingwUserspaceChannelBackend(entries)
}
