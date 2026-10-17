package borg.trikeshed.userspace

import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.ValueLayout

/** setsockopt of one int on a ring socket (a JvmFileTable descriptor), for the options a server sets beside its SQEs. */
private object SocketOption {
    @JvmField val at = libcSymbol("setsockopt")
    val stateLayout = Linker.Option.captureStateLayout()
    val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    val darwin = System.getProperty("os.name").lowercase().contains("mac")
}

private fun intOption(fd: Int, level: Int, name: Int, value: Int): Int {
    val descriptor = JvmFileTable.descriptor(fd) as? JvmSocketDescriptor ?: return -88
    if (!descriptor.isOpen()) return -9
    return Arena.ofConfined().use { arena ->
        val state = arena.allocate(SocketOption.stateLayout)
        val option = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, value) }
        val result = LibcDowncall.SETSOCKOPT.invokeExact(SocketOption.at, state, descriptor.fd, level, name, option, 4) as Int
        if (result < 0) -state.get(ValueLayout.JAVA_INT, SocketOption.errnoOffset) else 0
    }
}

/** SO_REUSEADDR: a listener binds again while earlier connections on its port sit in TIME_WAIT. 0, or -errno. */
fun reuseAddress(fd: Int): Int = if (SocketOption.darwin) intOption(fd, 0xffff, 0x4, 1) else intOption(fd, 1, 2, 1)

/** TCP_NODELAY: segments leave without waiting to coalesce with later writes. 0, or -errno. */
fun noDelay(fd: Int): Int = intOption(fd, 6, 1, 1)
