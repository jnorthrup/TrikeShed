package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** POSIX socket effects for the JVM emulated ring; sockaddr and errno stay portable above it. */
internal object JvmSocketSyscalls {
    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val darwin = System.getProperty("os.name").lowercase().contains("mac")
    private val linker by lazy {
        if (!linux && !darwin) TODO("JVM socket actual requires a Winsock backend on this host")
        Linker.nativeLinker()
    }
    private val int = ValueLayout.JAVA_INT
    private val long = ValueLayout.JAVA_LONG
    private val pointer = ValueLayout.ADDRESS
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow { UnsupportedOperationException("libc $name is unavailable") },
        descriptor, Linker.Option.captureCallState("errno"),
    )
    private val socketCall by lazy { function("socket", FunctionDescriptor.of(int, int, int, int)) }
    private val bindCall by lazy { function("bind", FunctionDescriptor.of(int, int, pointer, int)) }
    private val listenCall by lazy { function("listen", FunctionDescriptor.of(int, int, int)) }
    private val acceptCall by lazy { function("accept", FunctionDescriptor.of(int, int, pointer, pointer)) }
    private val connectCall by lazy { function("connect", FunctionDescriptor.of(int, int, pointer, int)) }
    private val closeCall by lazy { function("close", FunctionDescriptor.of(int, int)) }
    private val shutdownCall by lazy { function("shutdown", FunctionDescriptor.of(int, int, int)) }
    private val recvCall by lazy { function("recv", FunctionDescriptor.of(long, int, pointer, long, int)) }
    private val sendCall by lazy { function("send", FunctionDescriptor.of(long, int, pointer, long, int)) }
    private val pollCall by lazy { function("poll", FunctionDescriptor.of(int, pointer, if (linux) long else int, int)) }
    private val getOptionCall by lazy { function("getsockopt", FunctionDescriptor.of(int, int, int, int, pointer, pointer)) }
    private val setOptionCall by lazy { function("setsockopt", FunctionDescriptor.of(int, int, int, int, pointer, int)) }
    private val fcntlCall by lazy { linker.downcallHandle(
        linker.defaultLookup().find("fcntl").orElseThrow(), FunctionDescriptor.of(int, int, int, int),
        Linker.Option.captureCallState("errno"), Linker.Option.firstVariadicArg(2),
    ) }

    private fun errno(value: Int): Int = if (linux) value else when (value) {
        35 -> 11; 36 -> 115; 37 -> 114; 38 -> 88; 39 -> 89; 40 -> 90
        41 -> 91; 42 -> 92; 43 -> 93; 44 -> 94; 45 -> 95; 46 -> 96; 47 -> 97
        48 -> 98; 49 -> 99; 50 -> 100; 51 -> 101; 52 -> 102; 53 -> 103
        54 -> 104; 55 -> 105; 56 -> 106; 57 -> 107; 58 -> 108; 59 -> 109
        60 -> 110; 61 -> 111; 62 -> 40; 63 -> 36; 64 -> 112; 65 -> 113
        66 -> 39; 70 -> 116; 78 -> 38; 84 -> 75; 89 -> 125
        else -> value
    }

    private fun status(handle: MethodHandle, vararg args: Any): Int = Arena.ofConfined().use { arena ->
        val state = arena.allocate(stateLayout)
        val result = (handle.invokeWithArguments(listOf(state) + args) as Number).toLong()
        if (result < 0) -errno(state.get(int, errnoOffset)) else result.toInt()
    }

    private fun configure(fd: Int): Int {
        val flags = status(fcntlCall, fd, 3, 0)
        if (flags < 0) return flags
        val nonblocking = status(fcntlCall, fd, 4, flags or if (linux) 0x800 else 4)
        if (nonblocking < 0) return nonblocking
        val cloexec = status(fcntlCall, fd, 2, 1)
        if (cloexec < 0) return cloexec
        if (darwin) return Arena.ofConfined().use { arena ->
            val enabled = arena.allocate(int).also { it.set(int, 0, 1) }
            status(setOptionCall, fd, 0xffff, 0x1022, enabled, 4) // SO_NOSIGPIPE
        }
        return 0
    }

    fun socket(domain: Int, type: Int, protocol: Int): Int {
        if (type and (0xf or 0x800 or 0x80000).inv() != 0) return -22
        val family = if (darwin && domain == 10) 30 else domain
        val fd = status(socketCall, family, type and 0xf, protocol)
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
        status(bindCall, fd, addr, size)
    }
    fun listen(fd: Int, backlog: Int): Int = status(listenCall, fd, backlog)
    fun close(fd: Int): Int = status(closeCall, fd)
    fun shutdown(fd: Int, how: Int): Int = status(shutdownCall, fd, how)

    fun accept(fd: Int): Int {
        val client = status(acceptCall, fd, MemorySegment.NULL, MemorySegment.NULL)
        if (client < 0) return client
        val configured = configure(client)
        if (configured < 0) { close(client); return configured }
        return client
    }

    /** -EINPROGRESS retains completion ownership in the backend; this call never waits. */
    fun connectStart(fd: Int, buffer: ByteBuffer?, length: Int): Int = address(buffer, length) { addr, size ->
        status(connectCall, fd, addr, size)
    }

    /** Query only after POLLOUT/POLLERR/POLLHUP; zero before readiness does not prove connection. */
    fun connectFinish(fd: Int): Int = Arena.ofConfined().use { arena ->
        val error = arena.allocate(int)
        val bytes = arena.allocate(int).also { it.set(int, 0, 4) }
        val queried = status(getOptionCall, fd, if (linux) 1 else 0xffff,
            if (linux) 4 else 0x1007, error, bytes)
        if (queried < 0) queried else -errno(error.get(int, 0))
    }

    fun transfer(fd: Int, buffer: ByteBuffer?, length: Int, read: Boolean, flags: Int): Int {
        if (buffer == null || length < 0 || length > buffer.remaining() || read && buffer.isReadOnly()) return -22
        if (flags != 0) return -95
        return Arena.ofConfined().use { arena ->
            val bytes = arena.allocate(maxOf(1, length).toLong())
            val start = buffer.arrayOffset() + buffer.position()
            if (!read) MemorySegment.copy(MemorySegment.ofArray(buffer.array()), start.toLong(), bytes, 0, length.toLong())
            val result = status(if (read) recvCall else sendCall, fd, bytes, length.toLong(),
                if (!read && linux) 0x4000 else 0) // MSG_NOSIGNAL
            if (result > 0) {
                if (read) MemorySegment.copy(bytes, 0, MemorySegment.ofArray(buffer.array()), start.toLong(), result.toLong())
                buffer.position(buffer.position() + result)
            }
            result
        }
    }

    /** pollfd has the same fd/int, events/short, revents/short layout on Linux and Darwin. */
    fun poll(fds: IntArray, masks: IntArray, timeout: Int): Pair<Int, IntArray> = Arena.ofConfined().use { arena ->
        val records = arena.allocate(maxOf(1, fds.size).toLong() * 8, 4)
        for (index in fds.indices) {
            records.set(int, index * 8L, fds[index])
            records.set(ValueLayout.JAVA_SHORT, index * 8L + 4, masks[index].toShort())
            records.set(ValueLayout.JAVA_SHORT, index * 8L + 6, 0)
        }
        val count: Any = if (linux) fds.size.toLong() else fds.size
        val result = status(pollCall, records, count, timeout)
        result to IntArray(fds.size) { records.get(ValueLayout.JAVA_SHORT, it * 8L + 6).toInt() and 0xffff }
    }
}
