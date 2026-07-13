package borg.trikeshed.userspace.ebpf

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import kotlin.coroutines.CoroutineContext

/**
 * eBPF Channelizer JIT wrapper in Userspace NIO.
 * Intercepts I/O operations through an eBPF JIT or interpreter.
 */
class EbpfChannelizer(
    private val delegate: ChannelOperations,
    private val rxProgram: EbpfProgram? = null,
    private val txProgram: EbpfProgram? = null
) : ChannelOperations {

    override val key: CoroutineContext.Key<*> get() = ChannelOperations.Key

    // Fast-path evaluators
    private val rxInterpreter = rxProgram?.let { EbpfInterpreter(it) }
    private val txInterpreter = txProgram?.let { EbpfInterpreter(it) }

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle {
        val handle = delegate.openChannel(entries)
        return EbpfChannelHandle(handle)
    }

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        return delegate.socket(domain, type, protocol)
    }

    override fun bind(fd: Int, port: Int): Int = delegate.bind(fd, port)

    override fun listen(fd: Int, backlog: Int): Int = delegate.listen(fd, backlog)

    override fun accept(fd: Int): Int = delegate.accept(fd)

    override fun connect(fd: Int, host: String, port: Int): Int = delegate.connect(fd, host, port)

    override fun close(fd: Int): Int = delegate.close(fd)

    private inner class EbpfChannelHandle(
        private val underlying: ChannelOperations.ChannelHandle
    ) : ChannelOperations.ChannelHandle {
        override val id: Int get() = underlying.id

        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val result = underlying.read(buffer, offset)
            if (result > 0 && rxInterpreter != null) {
                // Apply eBPF Rx program filter
                val bytes = ByteArray(result)
                val startPos = buffer.position()
                for (i in 0 until result) {
                    // For offset reads, position does not advance, but we read from the updated range.
                    // Assuming buffer provides relative reading based on position.
                    bytes[i] = buffer.get(startPos + i)
                }
                val action = rxInterpreter.execute(bytes)

                // Usually an action of 0 means DROP or failure, while != 0 means PASS
                if (action == 0L) {
                    return 0 // Dropped by eBPF filter
                }

                // Write back modified bytes if allowed (in place transformation)
                for (i in 0 until result) buffer.put(startPos + i, bytes[i])
            }
            return result
        }

        override fun write(buffer: ByteBuffer, offset: Long): Int {
            if (txInterpreter != null) {
                val len = buffer.limit() - buffer.position()
                val bytes = ByteArray(len)
                for (i in 0 until len) bytes[i] = buffer.get(buffer.position() + i)

                // Apply eBPF Tx program filter
                val action = txInterpreter.execute(bytes)
                if (action == 0L) {
                    return len // Dropped but acknowledge to caller
                }

                // Put transformed bytes back
                for (i in 0 until len) buffer.put(buffer.position() + i, bytes[i])
            }
            return underlying.write(buffer, offset)
        }

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            // Async SQE - we cannot filter inline here easily as completion is async.
            // A fully implemented JIT channelizer for io_uring would chain SQEs or BPF prog attach.
            return underlying.readv(fd, buffer, userData)
        }

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            if (txInterpreter != null) {
                val len = buffer.limit() - buffer.position()
                val bytes = ByteArray(len)
                for (i in 0 until len) bytes[i] = buffer.get(buffer.position() + i)
                val action = txInterpreter.execute(bytes)
                if (action == 0L) {
                    return 0 // Dropped
                }
                for (i in 0 until len) buffer.put(buffer.position() + i, bytes[i])
            }
            return underlying.writev(fd, buffer, userData)
        }

        override fun prepAccept(serverFd: Int, userData: Long): Int = underlying.prepAccept(serverFd, userData)

        override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = underlying.sendmsg(fd, msgHdrPtr, userData)

        override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = underlying.recvmsg(fd, msgHdrPtr, userData)

        override fun submit(): Int = underlying.submit()

        override fun wait(minComplete: Int): List<ChannelResult> {
            val results = underlying.wait(minComplete)
            // Rx filtering for async completion would require accessing the buffer again.
            return results
        }
    }
}
