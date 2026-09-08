package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JNI boundary. The shared facade owns admission, cancellation and completion delivery. */
internal object JvmUring {
    external fun abiVersion(): Int
    external fun open(entries: Int): Long
    external fun supports(handle: Long, opcode: Int): Boolean
    external fun execute(handle: Long, opcode: Int, fd: Int, bytes: ByteArray?, start: Int,
                         length: Int, offset: Long, userData: Long): Int
    external fun close(handle: Long)
    external fun closeFd(fd: Int): Int
    external fun size(fd: Int): Long
}

private class JvmNativeDescriptor(val fd: Int) : JvmDescriptor {
    @Volatile private var open = true
    @Synchronized
    override fun close() {
        if (open) { open = false; JvmUring.closeFd(fd) }
    }
    fun completedClose() { open = false }
    override fun isOpen(): Boolean = open
    override fun size(): Long = if (open) JvmUring.size(fd) else -1L
}

internal fun jvmNativeChannelBackend(handle: Long): UserspaceChannelBackend = JvmNativeChannelBackend(handle)

/** Native and POSIX-emulated primitives use the same OS descriptors in the JNI bridge. */
private class JvmNativeChannelBackend(private var handle: Long) : UserspaceChannelBackend {
    override val capabilities: Long get() = jvmUringOperations
    override val nativeCapabilities: Long = UringOp.entries.fold(0L) { mask, op ->
        if (capabilities and op.mask != 0L && op.code >= 0 && JvmUring.supports(handle, op.code))
            mask or op.mask else mask
    }
    override val availability = "io_uring: JNI setup and operation probe succeeded; unsupported kernel ops use POSIX emulation"
    private val owned = mutableSetOf<Int>()
    private val legacy = JvmUserspaceChannelBackend()

    @Synchronized
    private fun execute(sub: UringSubmission): Int {
        if (handle == 0L) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        if (sub.opcode == UringOp.OPENAT && sub.fd != -100) return -95
        val descriptor = JvmFileTable.descriptor(sub.fd)
        if (descriptor is JvmChannelDescriptor) return legacy.execute(sub)
        if (sub.opcode != UringOp.NOP && sub.opcode != UringOp.OPENAT && descriptor !is JvmNativeDescriptor) return -9
        val buffer = sub.buffer
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE || sub.opcode == UringOp.OPENAT) {
            if (buffer == null || sub.len < 0 || sub.len > buffer.remaining()) return -22
            if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return -22
            if (sub.opcode != UringOp.OPENAT && sub.offset < -1) return -22
        }
        val result = if (descriptor is JvmNativeDescriptor) synchronized(descriptor) {
            if (!descriptor.isOpen()) return -9
            JvmUring.execute(handle, sub.opcode.code,
            (descriptor as? JvmNativeDescriptor)?.fd ?: sub.fd,
            buffer?.array(), buffer?.let { it.arrayOffset() + it.position() } ?: 0,
            sub.len, sub.offset, sub.userData).also {
                if (sub.opcode == UringOp.CLOSE && it >= 0) descriptor.completedClose()
            }
        } else {
            JvmUring.execute(handle, sub.opcode.code,
            (descriptor as? JvmNativeDescriptor)?.fd ?: sub.fd,
            buffer?.array(), buffer?.let { it.arrayOffset() + it.position() } ?: 0,
            sub.len, sub.offset, sub.userData)
        }
        if (result >= 0) {
            if (sub.opcode == UringOp.OPENAT) {
                return JvmFileTable.register(JvmNativeDescriptor(result)).also { owned.add(it) }
            }
            if (sub.opcode == UringOp.CLOSE) {
                (descriptor as JvmNativeDescriptor).completedClose()
                JvmFileTable.close(sub.fd)
                owned.remove(sub.fd)
            }
            if (result > 0 && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE)) {
                buffer!!.position(buffer.position() + result)
            }
        }
        return result
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
        withContext(Dispatchers.IO) {
            val results = Array(submissions.size) { index ->
                val sub = submissions[index]
                UringCompletion(sub.userData, execute(sub), 0)
            }
            results.size j { results[it] }
        }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        owned.forEach { JvmFileTable.close(it) }
        owned.clear()
        legacy.close()
        JvmUring.close(handle)
        handle = 0L
    }
}
