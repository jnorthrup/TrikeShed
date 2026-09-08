@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.atomics.AtomicBoolean

/** Bounded, nonblocking userspace pipe. Both endpoints submit ordinary READ/WRITE/CLOSE SQEs. */
internal class UringPipe(capacity: Int = 65536) : Pipe() {
    private val backend = PipeBackend(capacity)
    private val source = Source()
    private val sink = Sink()
    override fun source(): SourceChannel = source
    override fun sink(): SinkChannel = sink

    private inner class Endpoint(private val fd: Int) {
        private val facade = FunctionalUringFacade(1, backend)
        private var token = 0L
        var open = true
            private set
        fun execute(op: UringOp, buffer: ByteBuffer? = null): Int {
            check(open) { "Pipe endpoint closed" }
            val id = token++
            facade.enqueue(UringSubmission(op, fd, 0, buffer?.remaining() ?: 0, -1,
                userData = id, buffer = buffer))
            facade.submit()
            val result = facade.wait().single { it.userData == id }.res
            if (result == -11) return 0
            if (result < 0) throw IOException("$op errno ${-result}")
            return if (op == UringOp.READ && result == 0 && buffer!!.hasRemaining()) -1 else result
        }
        fun close() { if (open) { execute(UringOp.CLOSE); open = false; facade.closeNow() } }
    }

    private inner class Source : SourceChannel(SelectorProvider.provider()) {
        private val endpoint = Endpoint(0)
        override fun read(dst: ByteBuffer): Int = endpoint.execute(UringOp.READ, dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
            require(offset >= 0 && length >= 0 && offset <= dsts.size - length)
            var total = 0L
            for (i in offset until offset + length) {
                if (!dsts[i].hasRemaining()) continue
                val n = read(dsts[i])
                if (n < 0) return if (total == 0L) -1 else total
                total += n
                if (dsts[i].hasRemaining()) break
            }
            return total
        }
        override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)
        override fun close() = endpoint.close()
        override fun isOpen(): Boolean = endpoint.open
        override fun implCloseSelectableChannel() = close()
        override fun provider(): SelectorProvider = SelectorProvider.provider()
        override fun validOps(): Int = SelectionKey.OP_READ
        override fun isRegistered(): Boolean = false
        override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("Not registered")
        override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("Pipe selection is unsupported")
        override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
        override fun isBlocking(): Boolean = false
        override fun blockingLock(): Any = this
        override fun configureBlocking(block: Boolean): SelectableChannel { implConfigureBlocking(block); return this }
        override fun implConfigureBlocking(block: Boolean) { require(!block) { "Userspace pipe is nonblocking" } }
        override fun begin() {}
        override fun end(completed: Boolean) {}
    }
    private inner class Sink : SinkChannel(SelectorProvider.provider()) {
        private val endpoint = Endpoint(1)
        override fun write(src: ByteBuffer): Int = endpoint.execute(UringOp.WRITE, src)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
            require(offset >= 0 && length >= 0 && offset <= srcs.size - length)
            var total = 0L
            for (i in offset until offset + length) {
                total += write(srcs[i])
                if (srcs[i].hasRemaining()) break
            }
            return total
        }
        override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)
        override fun close() = endpoint.close()
        override fun isOpen(): Boolean = endpoint.open
        override fun implCloseSelectableChannel() = close()
        override fun provider(): SelectorProvider = SelectorProvider.provider()
        override fun validOps(): Int = SelectionKey.OP_WRITE
        override fun isRegistered(): Boolean = false
        override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("Not registered")
        override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("Pipe selection is unsupported")
        override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
        override fun isBlocking(): Boolean = false
        override fun blockingLock(): Any = this
        override fun configureBlocking(block: Boolean): SelectableChannel { implConfigureBlocking(block); return this }
        override fun implConfigureBlocking(block: Boolean) { require(!block) { "Userspace pipe is nonblocking" } }
        override fun begin() {}
        override fun end(completed: Boolean) {}
    }
}

private class PipeBackend(capacity: Int) : UserspaceChannelBackend {
    private val bytes = ByteArray(capacity.also { require(it > 0) })
    private var head = 0
    private var length = 0
    private val source = AtomicBoolean(true)
    private val sink = AtomicBoolean(true)
    private val lock = Mutex()
    override val capabilities = UringOp.caps(UringOp.READ, UringOp.WRITE, UringOp.CLOSE)
    override val availability = "emulated: bounded userspace pipe"
    private fun execute(sqe: UringSubmission): Int {
        if (sqe.fd !in 0..1) return -9
        if (sqe.opcode == UringOp.CLOSE) {
            return if ((if (sqe.fd == 0) source else sink).compareAndSet(true, false)) 0 else -9
        }
        if (!lock.tryLock()) return -11
        try {
            if ((sqe.fd == 0 && !source.load()) || (sqe.fd == 1 && !sink.load())) return -9
            val buffer = sqe.buffer ?: return -22
            if (sqe.len < 0 || sqe.len > buffer.remaining()) return -22
            if (sqe.opcode == UringOp.READ && sqe.fd == 0) {
                if (buffer.isReadOnly()) return -22
                if (sqe.len == 0) return 0
                if (length == 0) return if (sink.load()) -11 else 0
                val count = minOf(sqe.len, length)
                buffer.put(bytes, head, count)
                head += count
                length -= count
                if (length == 0) head = 0
                return count
            }
            if (sqe.opcode == UringOp.WRITE && sqe.fd == 1) {
                if (!source.load()) return -32
                if (sqe.len == 0) return 0
                val count = minOf(sqe.len, bytes.size - length)
                if (count == 0) return -11
                if (head + length + count > bytes.size) { bytes.copyInto(bytes, 0, head, head + length); head = 0 }
                buffer.get(bytes, head + length, count)
                length += count
                return count
            }
            return -95
        } finally { lock.unlock() }
    }
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }
    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val result = Array(submissions.size) { val sqe = submissions[it]; UringCompletion(sqe.userData, execute(sqe), 0) }
        return result.size j { result[it] }
    }
}
