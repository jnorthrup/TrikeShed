package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** POSIX socket effects for the JVM emulated ring; sockaddr and errno stay portable above it. */
internal object JvmSocketSyscalls {
    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val darwin = System.getProperty("os.name").lowercase().contains("mac")
    private val int = ValueLayout.JAVA_INT
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private fun symbol(name: String): MemorySegment {
        if (!linux && !darwin) TODO("JVM socket actual requires a Winsock backend on this host")
        return libcSymbol(name)
    }

    // One holder per function, resolved when its class first initializes (the address is a static final the JIT folds);
    // LibcDowncall's build-time handle carries the call.
    private object Socket { @JvmField val at = symbol("socket") }
    private object Bind { @JvmField val at = symbol("bind") }
    private object Listen { @JvmField val at = symbol("listen") }
    private object Accept { @JvmField val at = symbol("accept") }
    private object Connect { @JvmField val at = symbol("connect") }
    private object Close { @JvmField val at = symbol("close") }
    private object Shutdown { @JvmField val at = symbol("shutdown") }
    private object Recv { @JvmField val at = symbol("recv") }
    private object Send { @JvmField val at = symbol("send") }
    private object Poll { @JvmField val at = symbol("poll") }
    private object Pipe { @JvmField val at = symbol("pipe") }
    private object GetOption { @JvmField val at = symbol("getsockopt") }
    private object SetOption { @JvmField val at = symbol("setsockopt") }
    private object SocketName { @JvmField val at = symbol("getsockname") }
    private object PeerName { @JvmField val at = symbol("getpeername") }
    private object Fcntl { @JvmField val at = symbol("fcntl") }
    private object EpollCreate { @JvmField val at = symbol("epoll_create1") }
    private object EpollCtl { @JvmField val at = symbol("epoll_ctl") }
    private object EpollWait { @JvmField val at = symbol("epoll_wait") }
    private object Kqueue { @JvmField val at = symbol("kqueue") }
    private object Kevent { @JvmField val at = symbol("kevent") }

    private fun errno(value: Int): Int = if (linux) value else when (value) {
        35 -> 11; 36 -> 115; 37 -> 114; 38 -> 88; 39 -> 89; 40 -> 90
        41 -> 91; 42 -> 92; 43 -> 93; 44 -> 94; 45 -> 95; 46 -> 96; 47 -> 97
        48 -> 98; 49 -> 99; 50 -> 100; 51 -> 101; 52 -> 102; 53 -> 103
        54 -> 104; 55 -> 105; 56 -> 106; 57 -> 107; 58 -> 108; 59 -> 109
        60 -> 110; 61 -> 111; 62 -> 40; 63 -> 36; 64 -> 112; 65 -> 113
        66 -> 39; 70 -> 116; 78 -> 38; 84 -> 75; 89 -> 125
        else -> value
    }

    /** The calling thread's errno capture: one segment per thread, reused by every call. */
    private val errnoState = ThreadLocal.withInitial { Arena.ofAuto().allocate(stateLayout) }
    /** [result] of a call that captured into [state]: the value, or -errno (Linux numbering). */
    private fun status(state: MemorySegment, result: Long): Int = if (result < 0) -errno(state.get(int, errnoOffset)) else result.toInt()
    private fun status(state: MemorySegment, result: Int): Int = status(state, result.toLong())

    private fun configure(fd: Int): Int {
        val state = errnoState.get()
        val flags = status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 3, 0) as Int)
        if (flags < 0) return flags
        val nonblocking = status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 4, flags or if (linux) 0x800 else 4) as Int)
        if (nonblocking < 0) return nonblocking
        val cloexec = status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 2, 1) as Int)
        if (cloexec < 0) return cloexec
        if (darwin) return Arena.ofConfined().use { arena ->
            val enabled = arena.allocate(int).also { it.set(int, 0, 1) }
            status(state, LibcDowncall.SETSOCKOPT.invokeExact(SetOption.at, state, fd, 0xffff, 0x1022, enabled, 4) as Int) // SO_NOSIGPIPE
        }
        return 0
    }

    fun socket(domain: Int, type: Int, protocol: Int): Int {
        if (type and (0xf or 0x800 or 0x80000).inv() != 0) return -22
        val family = if (darwin && domain == 10) 30 else domain
        val state = errnoState.get()
        val fd = status(state, LibcDowncall.SOCKET.invokeExact(Socket.at, state, family, type and 0xf, protocol) as Int)
        if (fd < 0) return fd
        val configured = configure(fd)
        if (configured < 0) { close(fd); return configured }
        return fd
    }

    /** A Linux-framed sockaddr from [buffer], or else from native memory at [addr], as the kernel reads an SQE's. */
    private inline fun address(buffer: ByteBuffer?, length: Int, addr: Long, action: (MemorySegment, Int) -> Int): Int {
        if (length < 2) return -22
        val bytes = when {
            buffer != null -> if (length > buffer.remaining()) return -22 else ByteArray(length).also { buffer.duplicate().get(it) }
            addr != 0L -> MemorySegment.ofAddress(addr).reinterpret(length.toLong()).toArray(ValueLayout.JAVA_BYTE)
            else -> return -22
        }
        val family = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
        var size = length
        when (family) {
            2 -> if (length != 16) return -22
            10 -> if (length != 28) return -22
            1 -> {
                if (length !in 3..110) return -22
                if (darwin) {
                    if (bytes[2] == 0.toByte()) return -95 // Darwin has no abstract UNIX namespace.
                    val end = (2 until length).firstOrNull { bytes[it] == 0.toByte() } ?: length
                    if (end > 105) return -36
                    size = minOf(end + 1, length)
                }
            }
            else -> return -97
        }
        if (darwin) {
            bytes[0] = size.toByte()
            bytes[1] = (if (family == 10) 30 else family).toByte()
        }
        return Arena.ofConfined().use { arena ->
            val native = arena.allocate(size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, native, 0, size.toLong())
            action(native, size)
        }
    }

    fun bind(fd: Int, buffer: ByteBuffer?, length: Int, at: Long = 0L): Int = address(buffer, length, at) { addr, size ->
        val state = errnoState.get(); status(state, LibcDowncall.FD_ADDRESS_LENGTH.invokeExact(Bind.at, state, fd, addr, size) as Int)
    }
    fun listen(fd: Int, backlog: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD_INT.invokeExact(Listen.at, state, fd, backlog) as Int) }
    fun close(fd: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD.invokeExact(Close.at, state, fd) as Int) }
    fun shutdown(fd: Int, how: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD_INT.invokeExact(Shutdown.at, state, fd, how) as Int) }

    /** SETSOCKOPT reads the submitted window without advancing it; portable constants map at this syscall boundary. */
    fun setOption(fd: Int, buffer: ByteBuffer?, length: Int, level: Int, option: Int, flags: Int): Int {
        if (buffer == null || length < 0 || length > buffer.remaining() || flags != 0) return -22
        if (level != UringOp.SOL_SOCKET || option != UringOp.SO_REUSEADDR) return -92
        return JvmFileSyscalls.native(maxOf(1, length)) { bytes ->
            MemorySegment.copy(MemorySegment.ofArray(buffer.array()), (buffer.arrayOffset() + buffer.position()).toLong(),
                bytes, 0, length.toLong())
            val state = errnoState.get()
            status(state, LibcDowncall.SETSOCKOPT.invokeExact(SetOption.at, state, fd,
                if (darwin) 0xffff else level, if (darwin) 0x4 else option, bytes, length) as Int)
        }
    }

    /** GETSOCKNAME's borrowed output window, with the same Linux sockaddr framing as BIND/CONNECT. */
    fun name(fd: Int, buffer: ByteBuffer?, length: Int, peer: Int): Int {
        if (buffer == null || buffer.isReadOnly() || length < 0 || length > buffer.remaining() || peer !in 0..1) return -22
        return Arena.ofConfined().use { arena ->
            val storage = arena.allocate(128, 8)
            val size = arena.allocate(int).also { it.set(int, 0, 128) }
            val state = errnoState.get()
            val result = status(state, LibcDowncall.FD_ADDRESS_ADDRESS
                .invokeExact(if (peer == 0) SocketName.at else PeerName.at, state, fd, storage, size) as Int)
            if (result < 0) return@use result
            val count = size.get(int, 0)
            if (count !in 2..128) return@use -75
            val bytes = storage.asSlice(0, count.toLong()).toArray(ValueLayout.JAVA_BYTE)
            val family = if (darwin) bytes[1].toInt() and 255 else storage.get(ValueLayout.JAVA_SHORT, 0).toInt() and 65535
            val portable = when {
                family == 1 || family == 2 -> family
                family == (if (darwin) 30 else 10) -> 10
                else -> return@use -97
            }
            bytes[0] = portable.toByte()
            bytes[1] = 0
            bytes.copyInto(buffer.array(), buffer.arrayOffset() + buffer.position(), 0, minOf(count, length))
            0
        }
    }

    fun accept(fd: Int): Int {
        val state = errnoState.get()
        val client = status(state, LibcDowncall.FD_ADDRESS_ADDRESS.invokeExact(Accept.at, state, fd, MemorySegment.NULL, MemorySegment.NULL) as Int)
        if (client < 0) return client
        val configured = configure(client)
        if (configured < 0) { close(client); return configured }
        return client
    }

    /** -EINPROGRESS retains completion ownership in the backend; this call never waits. */
    fun connectStart(fd: Int, buffer: ByteBuffer?, length: Int, at: Long = 0L): Int = address(buffer, length, at) { addr, size ->
        val state = errnoState.get(); status(state, LibcDowncall.FD_ADDRESS_LENGTH.invokeExact(Connect.at, state, fd, addr, size) as Int)
    }

    /** Query only after POLLOUT/POLLERR/POLLHUP; zero before readiness does not prove connection. */
    fun connectFinish(fd: Int): Int = Arena.ofConfined().use { arena ->
        val error = arena.allocate(int)
        val bytes = arena.allocate(int).also { it.set(int, 0, 4) }
        val state = errnoState.get()
        val queried = status(state, LibcDowncall.GETSOCKOPT.invokeExact(GetOption.at, state, fd, if (linux) 1 else 0xffff,
            if (linux) 4 else 0x1007, error, bytes) as Int)
        if (queried < 0) queried else -errno(error.get(int, 0))
    }

    fun transfer(fd: Int, buffer: ByteBuffer?, length: Int, read: Boolean, flags: Int): Int {
        if (buffer == null || length < 0 || length > buffer.remaining() || read && buffer.isReadOnly()) return -22
        if (flags != 0) return -95
        val heap = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        // The thread's native window, uncleared: a send copies its bytes in first, and a recv is copied out only up to
        // what it received, so the window's earlier bytes never reach the buffer.
        return JvmFileSyscalls.native(maxOf(1, length)) { bytes ->
            if (!read) MemorySegment.copy(MemorySegment.ofArray(heap), start.toLong(), bytes, 0, length.toLong())
            val state = errnoState.get()
            val result = status(state, LibcDowncall.RECV.invokeExact(if (read) Recv.at else Send.at, state, fd, bytes, length.toLong(),
                if (!read && linux) 0x4000 else 0) as Long) // MSG_NOSIGNAL
            if (result > 0) {
                if (read) MemorySegment.copy(bytes, 0, MemorySegment.ofArray(heap), start.toLong(), result.toLong())
                buffer.position(buffer.position() + result)
            }
            result
        }
    }

    /** pollfd has the same fd/int, events/short, revents/short layout on Linux and Darwin. */
    fun poll(fds: IntArray, masks: IntArray, timeout: Int): Pair<Int, IntArray> {
        // Every byte of every record is written before the call, so the records take the thread's window, uncleared.
        val records = JvmFileSyscalls.window(maxOf(1, fds.size) * 8)
        for (index in fds.indices) {
            records.set(int, index * 8L, fds[index])
            records.set(ValueLayout.JAVA_SHORT, index * 8L + 4, masks[index].toShort())
            records.set(ValueLayout.JAVA_SHORT, index * 8L + 6, 0)
        }
        val state = errnoState.get()
        val result = status(state, if (linux) LibcDowncall.POLL.invokeExact(Poll.at, state, records, fds.size.toLong(), timeout) as Int
            else LibcDowncall.POLL.invokeExact(Poll.at, state, records, fds.size, timeout) as Int)
        return result to IntArray(fds.size) { records.get(ValueLayout.JAVA_SHORT, it * 8L + 6).toInt() and 0xffff }
    }

    /** A cancellable poll effect. The owning common coroutine waits for the worker's cleanup on cancellation. */
    suspend fun await(fds: IntArray, masks: IntArray, register: ((() -> Unit) -> AutoCloseable)? = null) {
        require(fds.size == masks.size)
        val pipe = Arena.ofConfined().use { arena ->
            val descriptors = arena.allocate(8)
            val state = errnoState.get()
            check(status(state, LibcDowncall.PIPE.invokeExact(Pipe.at, state, descriptors) as Int) == 0) { "completion wake pipe failed" }
            IntArray(2) { descriptors.get(int, it * 4L) }
        }
        val guard = Any()
        var open = true
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            for (fd in pipe) {
                val state = errnoState.get()
                val flags = status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 3, 0) as Int)
                check(flags >= 0 && status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 4,
                    flags or if (linux) 0x800 else 4) as Int) == 0) { "completion wake pipe flags failed" }
                check(status(state, LibcDowncall.FCNTL.invokeExact(Fcntl.at, state, fd, 2, 1) as Int) == 0)
            }
            val wake = {
                synchronized(guard) {
                    if (open) JvmFileSyscalls.transfer(pipe[1], byteArrayOf(1), 0, 1, -1, false)
                    Unit
                }
            }
            val registration = register?.invoke(wake)
            try {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { wake() }
                    try { kotlinx.coroutines.Dispatchers.IO.dispatch(continuation.context, Runnable {
                        val outcome = runCatching {
                            var status: Int
                            do { status = poll(fds + pipe[0], masks + 1, -1).first } while (status == -4)
                            check(status >= 0) { "completion poll failed: $status" }
                        }
                        synchronized(guard) {
                            open = false
                            pipe.forEach(JvmFileSyscalls::close)
                        }
                        done.complete(Unit)
                        continuation.resumeWith(outcome)
                    }) } catch (failure: Throwable) {
                        done.complete(Unit)
                        continuation.resumeWith(Result.failure(failure))
                    }
                }
            } finally {
                try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { done.await() } }
                finally { registration?.close() }
            }
        } finally {
            synchronized(guard) {
                if (open) { open = false; pipe.forEach(JvmFileSyscalls::close) }
            }
        }
    }

    /** recv/send on native memory the caller owns: no heap copy, as the kernel ring reads an SQE's address. */
    fun transferAt(fd: Int, address: Long, length: Int, read: Boolean, flags: Int): Int {
        if (length < 0) return -22
        if (flags != 0) return -95
        val state = errnoState.get()
        val bytes = MemorySegment.ofAddress(address).reinterpret(length.toLong())
        return status(state, LibcDowncall.RECV.invokeExact(if (read) Recv.at else Send.at, state, fd, bytes, length.toLong(),
            if (!read && linux) 0x4000 else 0) as Long) // MSG_NOSIGNAL
    }
    /** poll(2) over [count] pollfd records the caller keeps between calls; revents are read back from them. */
    fun pollRecords(records: MemorySegment, count: Int, timeout: Int): Int {
        val state = errnoState.get()
        return status(state, if (linux) LibcDowncall.POLL.invokeExact(Poll.at, state, records, count.toLong(), timeout) as Int
            else LibcDowncall.POLL.invokeExact(Poll.at, state, records, count, timeout) as Int)
    }
    /** epoll_create1(EPOLL_CLOEXEC). */
    fun epollCreate(): Int {
        val state = errnoState.get()
        return status(state, LibcDowncall.FD.invokeExact(EpollCreate.at, state, 0x80000) as Int)
    }
    /** epoll_ctl with one epoll_event written to [scratch]: data carries [fd]; x86_64 packs the struct to 12 bytes. */
    fun epollCtl(epfd: Int, op: Int, fd: Int, events: Int, scratch: MemorySegment, packed: Boolean): Int {
        scratch.set(int, 0, events)
        if (packed) {
            scratch.set(int, 4, fd)
            scratch.set(int, 8, 0)
        } else scratch.set(ValueLayout.JAVA_LONG, 8, fd.toLong())
        val state = errnoState.get()
        return status(state, LibcDowncall.EPOLL_CTL.invokeExact(EpollCtl.at, state, epfd, op, fd, scratch) as Int)
    }
    fun epollWait(epfd: Int, events: MemorySegment, max: Int, timeout: Int): Int {
        val state = errnoState.get()
        return status(state, LibcDowncall.EPOLL_WAIT.invokeExact(EpollWait.at, state, epfd, events, max, timeout) as Int)
    }
    fun kqueueCreate(): Int {
        val state = errnoState.get()
        return status(state, LibcDowncall.KQUEUE.invokeExact(Kqueue.at, state) as Int)
    }
    fun kevent(kq: Int, changes: MemorySegment, count: Int, events: MemorySegment, max: Int, timeout: MemorySegment): Int {
        val state = errnoState.get()
        return status(state, LibcDowncall.KEVENT.invokeExact(Kevent.at, state, kq, changes, count, events, max, timeout) as Int)
    }
}
