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
    private object GetOption { @JvmField val at = symbol("getsockopt") }
    private object SetOption { @JvmField val at = symbol("setsockopt") }
    private object SocketName { @JvmField val at = symbol("getsockname") }
    private object PeerName { @JvmField val at = symbol("getpeername") }
    private object Fcntl { @JvmField val at = symbol("fcntl") }

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

    private inline fun address(buffer: ByteBuffer?, length: Int, action: (MemorySegment, Int) -> Int): Int {
        if (buffer == null || length < 2 || length > buffer.remaining()) return -22
        val bytes = ByteArray(length).also { buffer.duplicate().get(it) }
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

    fun bind(fd: Int, buffer: ByteBuffer?, length: Int): Int = address(buffer, length) { addr, size ->
        val state = errnoState.get(); status(state, LibcDowncall.FD_ADDRESS_LENGTH.invokeExact(Bind.at, state, fd, addr, size) as Int)
    }
    fun listen(fd: Int, backlog: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD_INT.invokeExact(Listen.at, state, fd, backlog) as Int) }
    fun close(fd: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD.invokeExact(Close.at, state, fd) as Int) }
    fun shutdown(fd: Int, how: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD_INT.invokeExact(Shutdown.at, state, fd, how) as Int) }

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
    fun connectStart(fd: Int, buffer: ByteBuffer?, length: Int): Int = address(buffer, length) { addr, size ->
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
}
