package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/** JNI boundary. The shared facade owns admission, cancellation and completion delivery. */
internal object JvmUring {
    external fun abiVersion(): Int
    external fun open(entries: Int): Long
    external fun supports(handle: Long, opcode: Int): Boolean
    external fun execute(handle: Long, opcode: Int, fd: Int, bytes: ByteArray?, start: Int,
                         length: Int, offset: Long, userData: Long, address: Long,
                         operationFlags: Int, bufferIndex: Int): Int
    /**
     * [count] SQEs in one kernel submission, reaped before return: [control] holds [BATCH_FIELDS] longs per SQE (opcode,
     * fd, start, length, offset, address, operationFlags | bufferIndex shl 32, unused), [arrays] the heap window of each
     * (or null), [results] receives each CQE's res. 0, or -errno when the ring refused the whole batch.
     */
    external fun executeBatch(handle: Long, count: Int, control: LongArray, arrays: Array<ByteArray?>, results: IntArray): Int
    const val BATCH_FIELDS = 8
    external fun registerBuffers(handle: Long, addresses: LongArray, lengths: LongArray): Int
    external fun unregisterBuffers(handle: Long): Int
    external fun close(handle: Long)
    external fun closeFd(fd: Int): Int
    external fun size(fd: Int): Long
}

/** An operating-system descriptor, opened by the kernel ring or by [JvmFileSyscalls]. */
internal class JvmNativeDescriptor(val fd: Int) : JvmDescriptor {
    @Volatile private var open = true
    @Synchronized
    override fun close() {
        if (open) { open = false; JvmFileSyscalls.close(fd) }
    }
    fun completedClose() { open = false }
    override fun isOpen(): Boolean = open
    override fun size(): Long = if (open) JvmFileSyscalls.size(fd) else -1L
}

internal fun jvmNativeChannelBackend(handle: Long): UserspaceChannelBackend = JvmNativeChannelBackend(handle)

/** Ops a kernel ring runs through the POSIX emulation: the JNI bridge has no encoder for them. */
private val legacyOps = UringOp.caps(UringOp.SOCKET, UringOp.UNLINKAT, UringOp.MKDIRAT, UringOp.RENAMEAT,
    UringOp.GETDENTS, UringOp.POLL_REMOVE, UringOp.ASYNC_CANCEL, UringOp.URING_CMD)

/** [JvmNativeChannelBackend.batchable]'s answer for an SQE that names no descriptor (NOP, MADVISE). */
private val NO_DESCRIPTOR = JvmNativeDescriptor(-1)

/** The data-path ops [JvmUring.executeBatch] carries (uring_jni.c `batched`). */
private val batchOps = UringOp.caps(UringOp.NOP, UringOp.FSYNC, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
    UringOp.FALLOCATE, UringOp.READ, UringOp.WRITE, UringOp.FADVISE, UringOp.MADVISE, UringOp.FTRUNCATE)

/** SQEs per JNI call; the bridge splits a run into enters of at most its SQ depth. */
private const val BATCH_LIMIT = 64

/** Native and POSIX-emulated primitives use the same OS descriptors in the JNI bridge. */
private class JvmNativeChannelBackend(private var handle: Long) : UserspaceChannelBackend {
    override val capabilities: Long get() = jvmUringOperations
    override val deferredCapabilities: Long get() = legacy.deferredCapabilities
    override val nativeCapabilities: Long = UringOp.entries.fold(0L) { mask, op ->
        // Socket commands are emulated; cancellation addresses the emulation's
        // deferred admissions, not the separate synchronous JNI ring's tokens.
        if (op != UringOp.URING_CMD && op != UringOp.ASYNC_CANCEL && capabilities and op.mask != 0L && op.code >= 0 && JvmUring.supports(handle, op.code))
            mask or op.mask else mask
    }
    override val availability = "io_uring: JNI setup and operation probe succeeded; unsupported kernel ops use POSIX emulation"
    private val legacy = JvmUserspaceChannelBackend()
    /** The ops one kernel submission carries: [batchOps] the probe found. */
    private val batched = batchOps and nativeCapabilities
    /** The run being gathered for one kernel submission, with each SQE's descriptor (null for NOP and MADVISE). */
    private val run = ArrayList<UringSubmission>(BATCH_LIMIT)
    private val runDescriptors = ArrayList<JvmNativeDescriptor?>(BATCH_LIMIT)
    private val locks = ArrayList<JvmNativeDescriptor>()
    private val control = LongArray(BATCH_LIMIT * JvmUring.BATCH_FIELDS)
    private val arrays = arrayOfNulls<ByteArray>(BATCH_LIMIT)
    private val results = IntArray(BATCH_LIMIT)

    override fun supportsFixedBuffer(fd: Int): Boolean = JvmFileTable.descriptor(fd) is JvmNativeDescriptor

    @Synchronized
    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = runCatching {
        check(handle != 0L) { "ring is closed" }
        val addresses = LongArray(buffers.size) { buffers[it].address }
        val lengths = LongArray(buffers.size) { buffers[it].length }
        val result = JvmUring.registerBuffers(handle, addresses, lengths)
        check(result == 0) { "io_uring_register_buffers failed: $result" }
    }

    @Synchronized
    override fun unregisterBuffers(): Result<Unit> = runCatching {
        check(handle != 0L) { "ring is closed" }
        val result = JvmUring.unregisterBuffers(handle)
        check(result == 0) { "io_uring_unregister_buffers failed: $result" }
    }

    @Synchronized
    private fun execute(sub: UringSubmission): Int {
        if (handle == 0L) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        if (sub.opcode == UringOp.OPENAT && sub.fd != -100) return -95
        if (sub.len < 0) return -22
        if (legacyOps and sub.opcode.mask != 0L) return legacy.execute(sub)
        // MADVISE addresses the process VM and remains valid after the originating fd closes.
        if (sub.opcode == UringOp.MADVISE) return nativeExecute(sub, -1)
        // A STATX path resolves from AT_FDCWD; only AT_EMPTY_PATH reads a descriptor.
        val pathStatx = sub.opcode == UringOp.STATX && sub.fd == UringOp.AT_FDCWD && sub.len > 0
        val descriptor = JvmFileTable.descriptor(sub.fd)
        if (descriptor is JvmChannelDescriptor || descriptor is JvmSocketDescriptor) return legacy.execute(sub)
        if (sub.opcode != UringOp.NOP && sub.opcode != UringOp.OPENAT && !pathStatx && descriptor !is JvmNativeDescriptor) return -9
        val buffer = sub.buffer
        if (sub.opcode == UringOp.STATX && (buffer == null || buffer.isReadOnly() || sub.addr != 0L || sub.offset !in 0..0xffffffffL ||
                buffer.remaining() - sub.len < 1 + UringOp.STATX_SIZE ||
                buffer.array()[buffer.arrayOffset() + buffer.position() + sub.len] != 0.toByte())) return -22
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE || sub.opcode == UringOp.OPENAT) {
            if (sub.opcode == UringOp.OPENAT && buffer == null) return -22
            if (buffer != null && sub.len > buffer.remaining()) return -22
            if (sub.opcode == UringOp.READ && buffer?.isReadOnly() == true) return -22
            if (sub.opcode != UringOp.OPENAT && sub.offset < -1) return -22
        }
        val result = if (descriptor is JvmNativeDescriptor) synchronized(descriptor) {
            if (!descriptor.isOpen()) return -9
            nativeExecute(sub, descriptor.fd).also {
                if (sub.opcode == UringOp.CLOSE && it >= 0) descriptor.completedClose()
            }
        } else {
            nativeExecute(sub, sub.fd)
        }
        if (result >= 0) {
            if (sub.opcode == UringOp.OPENAT) {
                return JvmFileTable.register(JvmNativeDescriptor(result))
            }
            if (sub.opcode == UringOp.CLOSE) {
                (descriptor as JvmNativeDescriptor).completedClose()
                JvmFileTable.close(sub.fd)
            }
            if (result > 0 && buffer != null && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE)) {
                buffer.position(buffer.position() + result)
            }
        }
        return result
    }

    private fun nativeExecute(sub: UringSubmission, fd: Int): Int = JvmUring.execute(
        handle, sub.opcode.code, fd, sub.buffer?.array(),
        sub.buffer?.let { it.arrayOffset() + it.position() } ?: 0,
        sub.len, sub.offset, sub.userData, sub.addr, sub.operationFlags, sub.bufferIndex,
    )

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = synchronized(this) {
        val out = ArrayList<SelectionResult>(submissions.size)
        for (sub in submissions) {
            if ((sub.opcode == UringOp.POLL_ADD || sub.opcode == UringOp.CONNECT) && handle != 0L) {
                flush(out)
                out.addAll(legacy.submitBatch(listOf(sub)))
                continue
            }
            val descriptor = if (handle != 0L) batchable(sub) else null
            if (descriptor == null) {
                flush(out)
                out.add(SelectionResult(execute(sub), sub.userData))
                continue
            }
            // A kernel submission runs its SQEs in any order: an SQE that orders after one in the run starts the next.
            if (run.size == BATCH_LIMIT || run.any { dependsOn(sub, it) }) flush(out)
            run.add(sub)
            runDescriptors.add(descriptor.takeIf { it !== NO_DESCRIPTOR })
        }
        flush(out)
        out
    }

    /**
     * [sub]'s descriptor when it passes execute's checks unchanged and a kernel batch can carry it ([NO_DESCRIPTOR] for
     * NOP and MADVISE), else null: execute settles it alone.
     */
    private fun batchable(sub: UringSubmission): JvmNativeDescriptor? {
        if (sub.flags != 0 || batched and sub.opcode.mask == 0L || sub.len < 0) return null
        if (sub.opcode == UringOp.NOP || sub.opcode == UringOp.MADVISE) return NO_DESCRIPTOR
        val descriptor = JvmFileTable.descriptor(sub.fd) as? JvmNativeDescriptor ?: return null
        if (!descriptor.isOpen()) return null
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) {
            if (sub.offset < -1) return null
            val buffer = sub.buffer ?: return descriptor
            if (sub.len > buffer.remaining() || (sub.opcode == UringOp.READ && buffer.isReadOnly())) return null
        }
        return descriptor
    }

    /**
     * Runs the gathered run as one kernel submission under its descriptors' locks (taken in fd order), appending one
     * result per SQE in order. A descriptor closed since it joined sends the run through execute, one SQE at a time.
     */
    private fun flush(out: MutableList<SelectionResult>) {
        val count = run.size
        if (count == 0) return
        locks.clear()
        for (descriptor in runDescriptors) if (descriptor != null && locks.none { it === descriptor }) locks.add(descriptor)
        locks.sortBy { it.fd }
        val submitted = locked(0) {
            if (locks.any { !it.isOpen() }) return@locked false
            for (index in 0 until count) {
                val sub = run[index]
                val field = index * JvmUring.BATCH_FIELDS
                val buffer = if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) sub.buffer else null
                control[field] = sub.opcode.code.toLong()
                control[field + 1] = (runDescriptors[index]?.fd ?: -1).toLong()
                control[field + 2] = if (buffer == null) 0L else (buffer.arrayOffset() + buffer.position()).toLong()
                control[field + 3] = sub.len.toLong()
                control[field + 4] = sub.offset
                control[field + 5] = sub.addr
                control[field + 6] = (sub.operationFlags.toLong() and 0xffffffffL) or (sub.bufferIndex.toLong() shl 32)
                arrays[index] = buffer?.array()
            }
            val refused = JvmUring.executeBatch(handle, count, control, arrays, results)
            if (refused < 0) results.fill(refused, 0, count)
            true
        }
        for (index in 0 until count) {
            val sub = run[index]
            arrays[index] = null
            if (!submitted) {
                out.add(SelectionResult(execute(sub), sub.userData))
                continue
            }
            val result = results[index]
            val buffer = sub.buffer
            if (result > 0 && buffer != null && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE))
                buffer.position(buffer.position() + result)
            out.add(SelectionResult(result, sub.userData))
        }
        run.clear()
        runDescriptors.clear()
    }

    private fun locked(index: Int, block: () -> Boolean): Boolean =
        if (index == locks.size) block() else synchronized(locks[index]) { locked(index + 1, block) }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> = legacy.reapCompletions(minComplete)

    override fun cancelPending() = legacy.cancelPending()

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val outstanding = HashSet<Long>(submissions.size)
        for (index in 0 until submissions.size) outstanding.add(submissions[index].userData)
        val results = submitBatch(List(submissions.size) { submissions[it] }).toMutableList()
        results.forEach { outstanding.remove(it.userData) }
        while (outstanding.isNotEmpty()) {
            val settled = reapCompletions(0)
            settled.forEach { outstanding.remove(it.userData) }
            results.addAll(settled)
            if (outstanding.isNotEmpty()) kotlinx.coroutines.delay(1)
        }
        return results.size j { UringCompletion(results[it].userData, results[it].res, 0) }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        // Closing a ring does not close descriptors returned by its operations.
        legacy.close()
        JvmUring.close(handle)
        handle = 0L
    }
}
