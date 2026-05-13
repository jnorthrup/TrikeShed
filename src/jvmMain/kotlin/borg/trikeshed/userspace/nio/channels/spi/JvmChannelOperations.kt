package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.Channels
import borg.trikeshed.userspace.File
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * JVM ChannelOperations — all IO through FunctionalUringFacade / UserspaceChannelBackend.
 * Zero JDK imports. JDK is isolated in UserspaceIO.jvm.kt only.
 */
class JvmChannelOperations : ChannelOperations {
    override val key get() = ChannelOperations.Key

    private val files = mutableMapOf<Int, File>()
    private val ring = Channels.open(entries = 256)

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        JvmChannelHandle(Channels.open(entries))

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        val file = Channels.socket(domain, type, protocol)
        files[file.id] = file
        return file.id
    }

    override fun bind(fd: Int, port: Int): Int = -1   // server-side: not yet

    override fun listen(fd: Int, backlog: Int): Int = -1

    override fun accept(fd: Int): Int = -1

    override fun connect(fd: Int, host: CharSequence, port: Int): Int {
        val file = files[fd] ?: return -1
        val hostBuf = ByteBuffer.wrap(host.toString().encodeToByteArray())
        ring.enqueue(UringSubmission(UringOp.CONNECT, file.id, 0L, hostBuf.remaining(), port.toLong(), 0, fd.toLong(), hostBuf))
        ring.submit()
        return ring.wait(1).firstOrNull()?.res ?: -1
    }

    override fun close(fd: Int): Int {
        files.remove(fd)?.close()
        return 0
    }

    fun hasPending(fd: Int): Boolean = ring.hasPending(fd)

    fun send(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        val file = files[fd] ?: return -1
        ring.enqueue(UringSubmission(UringOp.SEND, file.id, 0L, buffer.remaining(), 0L, 0, userData, buffer))
        ring.submit()
        return ring.wait(1).firstOrNull()?.res ?: -1
    }

    fun recv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        val file = files[fd] ?: return -1
        ring.enqueue(UringSubmission(UringOp.RECV, file.id, 0L, buffer.remaining(), 0L, 0, userData, buffer))
        ring.submit()
        return ring.wait(1).firstOrNull()?.res ?: -1
    }

    private inner class JvmChannelHandle(
        private val ch: borg.trikeshed.userspace.Channel
    ) : ChannelOperations.ChannelHandle {
        override val id: Int = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int = -1
        override fun write(buffer: ByteBuffer, offset: Long): Int = -1
        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int = -1
        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int = -1
        override fun submit(): Int = ch.submit()
        override fun wait(minComplete: Int): List<ChannelResult> =
            ch.wait(minComplete).map { ChannelResult(it.res, it.res, it.userData) }
    }
}
