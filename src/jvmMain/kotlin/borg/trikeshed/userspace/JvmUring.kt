package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/** JNI boundary. The shared facade owns admission, cancellation and completion delivery. */
internal object JvmUring {
    external fun abiVersion(): Int
    external fun open(entries: Int): Long
    external fun supports(handle: Long, opcode: Int): Boolean
    external fun execute(handle: Long, opcode: Int, fd: Int, bytes: ByteArray?, start: Int,
                         length: Int, offset: Long, userData: Long, address: Long,
                         operationFlags: Int, bufferIndex: Int): Int
    /** Eight control longs per SQE: opcode, fd, start, len, offset, address, operation flags/buffer index, user_data.
     * Int.MIN_VALUE marks an admitted request, -EAGAIN leaves it unsubmitted for the next free SQ slot. */
    external fun submitBatch(handle: Long, count: Int, control: LongArray, arrays: Array<ByteArray?>, results: IntArray): Int
    external fun reapBatch(handle: Long, tokens: LongArray, results: IntArray): Int
    external fun await(handle: Long, descriptors: IntArray, masks: IntArray): Int
    external fun wake(handle: Long)
    external fun cancel(handle: Long, token: Long, all: Boolean): Int
    const val BATCH_FIELDS = 8
    external fun registerBuffers(handle: Long, addresses: LongArray, lengths: LongArray): Int
    external fun unregisterBuffers(handle: Long): Int
    external fun close(handle: Long): Int
    external fun closeFd(fd: Int): Int
    external fun size(fd: Int): Long
}

/** An operating-system descriptor, opened by the kernel ring or by [JvmFileSyscalls]. */
internal class JvmNativeDescriptor(val fd: Int) : JvmDescriptor {
    @Volatile private var open = true
    private var fdOpen = true
    private var retained = 0
    @Synchronized
    fun retain(): Boolean {
        if (!open) return false
        retained++
        return true
    }
    @Synchronized
    fun release() {
        check(retained > 0)
        retained--
        if (!open && retained == 0 && fdOpen) { fdOpen = false; JvmFileSyscalls.close(fd) }
    }
    @Synchronized
    override fun close() {
        open = false
        if (retained == 0 && fdOpen) { fdOpen = false; JvmFileSyscalls.close(fd) }
    }
    @Synchronized
    fun completedClose() { open = false; fdOpen = false }
    override fun isOpen(): Boolean = open
    override fun size(): Long = synchronized(this) { if (open) JvmFileSyscalls.size(fd) else -1L }
}

internal fun jvmNativeChannelBackend(handle: Long): UserspaceChannelBackend = JvmNativeChannelBackend(handle)

/** POSIX operations, including cancellation when its target belongs to the emulated backend. */
private val legacyOps = UringOp.caps(UringOp.SOCKET, UringOp.UNLINKAT, UringOp.MKDIRAT, UringOp.RENAMEAT,
    UringOp.GETDENTS, UringOp.POLL_REMOVE, UringOp.ASYNC_CANCEL, UringOp.URING_CMD)

/** A native SQE without a retained file descriptor. */
private val NO_DESCRIPTOR = JvmNativeDescriptor(-1)

/** Every SQE the JNI encoder can prepare; unsupported kernel operations retain their POSIX implementation. */
private val batchOps = UringOp.caps(UringOp.NOP, UringOp.FSYNC, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
    UringOp.OPENAT, UringOp.CLOSE, UringOp.STATX, UringOp.ASYNC_CANCEL, UringOp.FALLOCATE, UringOp.READ, UringOp.WRITE,
    UringOp.FADVISE, UringOp.MADVISE, UringOp.FTRUNCATE)

/** SQEs per JNI enter; a full SQ leaves excess admissions queued for the next completion. */
private const val BATCH_LIMIT = 64

/** Native and POSIX-emulated primitives use the same OS descriptors in the JNI bridge. */
private class JvmNativeChannelBackend(private var handle: Long) : UserspaceChannelBackend {
    override val capabilities: Long get() = jvmUringOperations
    override val deferredCapabilities: Long get() = capabilities
    override val nativeCapabilities: Long = UringOp.entries.fold(0L) { mask, op ->
        // JNI supports() intersects the kernel probe with its SQE encoder; legacy-only opcodes stay emulated.
        if (op != UringOp.URING_CMD && capabilities and op.mask != 0L && op.code >= 0 && JvmUring.supports(handle, op.code))
            mask or op.mask else mask
    }
    override val availability = "io_uring: JNI setup and operation probe succeeded; unsupported kernel ops use POSIX emulation"
    private val legacy = JvmUserspaceChannelBackend()
    private val batched = batchOps and nativeCapabilities
    private class Admission(val submission: UringSubmission, val descriptor: JvmNativeDescriptor?, val native: Boolean)
    private val pending = ArrayList<UringSubmission>()
    private val active = LinkedHashMap<Long, Admission>()
    private val ready = ArrayDeque<SelectionResult>()
    private val control = LongArray(BATCH_LIMIT * JvmUring.BATCH_FIELDS)
    private val arrays = arrayOfNulls<ByteArray>(BATCH_LIMIT)
    private val results = IntArray(BATCH_LIMIT)
    private val tokens = LongArray(BATCH_LIMIT)
    private var draining = false
    // The common owner may fan in several unscoped callers; cancellation wakes exactly one OS waiter at a time.
    private val completionWait = Mutex()
    // Retain the JNI handle before dispatching an OS wait, including the interval before C registers its waiter.
    private var nativeWaiters = 0

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Synchronized
    private fun releaseWait() {
        check(nativeWaiters > 0)
        nativeWaiters--
        (this as java.lang.Object).notifyAll()
    }

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

    @Synchronized
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        for (sub in submissions) {
            require(sub.userData !in active && pending.none { it.userData == sub.userData }) { "Duplicate outstanding userData" }
            if (handle == 0L || draining) ready.addLast(SelectionResult(if (draining) -125 else -9, sub.userData))
            else pending.add(sub)
        }
        dispatch()
        if (handle != 0L) JvmUring.wake(handle)
        return takeReady()
    }

    /** Dependency barriers cover previous calls as well as this batch; independent requests share the next enter. */
    private fun dispatch() {
        if (pending.isEmpty() || handle == 0L || draining) return
        val admissions = ArrayList(pending)
        pending.clear()
        val run = ArrayList<Admission>(BATCH_LIMIT)
        fun flush() {
            if (run.isEmpty()) return
            for ((index, admission) in run.withIndex()) {
                val sub = admission.submission
                val field = index * JvmUring.BATCH_FIELDS
                val buffer = sub.buffer
                control[field] = sub.opcode.code.toLong()
                control[field + 1] = (admission.descriptor?.fd ?: sub.fd).toLong()
                control[field + 2] = if (buffer == null) 0L else (buffer.arrayOffset() + buffer.position()).toLong()
                control[field + 3] = sub.len.toLong()
                control[field + 4] = sub.offset
                control[field + 5] = sub.addr
                control[field + 6] = (sub.operationFlags.toLong() and 0xffffffffL) or (sub.bufferIndex.toLong() shl 32)
                control[field + 7] = sub.userData
                arrays[index] = buffer?.array()
            }
            val status = JvmUring.submitBatch(handle, run.size, control, arrays, results)
            for ((index, admission) in run.withIndex()) {
                arrays[index] = null
                val sub = admission.submission
                val result = if (status < 0) status else results[index]
                if (result == Int.MIN_VALUE) active[sub.userData] = admission
                else {
                    admission.descriptor?.release()
                    if (result == -11) pending.add(sub) else ready.addLast(SelectionResult(result, sub.userData))
                }
            }
            run.clear()
        }
        for (sub in admissions) {
            if (sub.opcode == UringOp.ASYNC_CANCEL) {
                flush()
                val target = pending.firstOrNull { it.userData == sub.addr }
                if (target != null && sub.flags == 0 && sub.operationFlags == 0 && sub.len == 0 && sub.offset == 0L) {
                    pending.remove(target)
                    ready.addLast(SelectionResult(-125, target.userData))
                    ready.addLast(SelectionResult(0, sub.userData))
                    continue
                }
            }
            if (run.any { dependsOn(sub, it.submission) }) flush()
            if (active.values.any { dependsOn(sub, it.submission) } || pending.any { dependsOn(sub, it) }) {
                pending.add(sub)
                continue
            }
            val descriptor = batchable(sub)
            if (descriptor == null) {
                flush()
                // Flush can introduce an active predecessor on this descriptor.
                if (active.values.any { dependsOn(sub, it.submission) } || pending.any { dependsOn(sub, it) }) {
                    pending.add(sub)
                    continue
                }
                val descriptor = JvmFileTable.descriptor(sub.fd)
                val completion = if (descriptor is JvmSocketDescriptor || descriptor is JvmChannelDescriptor ||
                    sub.opcode of legacyOps || sub.opcode == UringOp.POLL_ADD || sub.opcode == UringOp.CONNECT)
                    legacy.submitBatch(listOf(sub)) else listOf(SelectionResult(execute(sub), sub.userData))
                ready.addAll(completion)
                if (completion.isEmpty()) active[sub.userData] = Admission(sub, null, false)
            } else {
                val retained = descriptor.takeIf { it !== NO_DESCRIPTOR }
                if (retained != null && !retained.retain()) ready.addLast(SelectionResult(-9, sub.userData))
                else run.add(Admission(sub, retained, true))
                if (run.size == BATCH_LIMIT) flush()
            }
        }
        flush()
    }

    /**
     * [sub]'s descriptor when it passes execute's checks unchanged and a kernel batch can carry it ([NO_DESCRIPTOR] for
     * NOP and MADVISE), else null: execute settles it alone.
     */
    private fun batchable(sub: UringSubmission): JvmNativeDescriptor? {
        if (sub.flags != 0 || batched and sub.opcode.mask == 0L || sub.len < 0) return null
        if (sub.opcode == UringOp.NOP || sub.opcode == UringOp.MADVISE) return NO_DESCRIPTOR
        if (sub.opcode == UringOp.ASYNC_CANCEL) return if (active[sub.addr]?.native == true) NO_DESCRIPTOR else null
        val path = sub.opcode == UringOp.OPENAT ||
            (sub.opcode == UringOp.STATX && sub.fd == UringOp.AT_FDCWD && sub.len > 0)
        if (sub.opcode == UringOp.OPENAT && sub.fd != UringOp.AT_FDCWD) return null
        val descriptor = if (path) NO_DESCRIPTOR else JvmFileTable.descriptor(sub.fd) as? JvmNativeDescriptor ?: return null
        if (descriptor !== NO_DESCRIPTOR && !descriptor.isOpen()) return null
        val buffer = sub.buffer
        if (sub.opcode == UringOp.STATX && (buffer == null || buffer.isReadOnly() || sub.addr != 0L ||
                sub.offset !in 0..0xffffffffL || buffer.remaining().toLong() - sub.len < 1L + UringOp.STATX_SIZE ||
                buffer.array()[buffer.arrayOffset() + buffer.position() + sub.len] != 0.toByte())) return null
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE || sub.opcode == UringOp.OPENAT) {
            if (sub.opcode == UringOp.OPENAT && buffer == null) return null
            if (buffer != null && sub.len > buffer.remaining()) return null
            if (sub.opcode == UringOp.READ && buffer?.isReadOnly() == true) return null
            if (sub.opcode != UringOp.OPENAT && sub.offset < -1) return null
        }
        return descriptor
    }

    private fun takeReady(): List<SelectionResult> = ArrayList<SelectionResult>(ready.size).also { out ->
        while (ready.isNotEmpty()) out.add(ready.removeFirst())
    }

    private fun completed(result: SelectionResult) {
        val admission = active.remove(result.userData) ?: error("Foreign or duplicate native CQE ${result.userData}")
        val sub = admission.submission
        var value = result.res
        try {
            if (value >= 0 && sub.opcode == UringOp.OPENAT)
                value = JvmFileTable.register(JvmNativeDescriptor(value))
            if (value >= 0 && sub.opcode == UringOp.CLOSE) {
                admission.descriptor?.completedClose()
                JvmFileTable.close(sub.fd)
            }
            if (value > 0 && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE))
                sub.buffer?.let { it.position(it.position() + value) }
            ready.addLast(SelectionResult(value, sub.userData))
        } finally { admission.descriptor?.release() }
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> {
        require(minComplete >= 0)
        while (true) {
            val wait = synchronized(this) {
                if (handle != 0L) {
                    var count: Int
                    do {
                        count = JvmUring.reapBatch(handle, tokens, results)
                        check(count >= 0) { "io_uring reap failed: $count" }
                        for (index in 0 until count) completed(SelectionResult(results[index], tokens[index]))
                    } while (count == BATCH_LIMIT)
                }
                for (completion in legacy.reapCompletions(0)) {
                    active.remove(completion.userData)
                    ready.addLast(completion)
                }
                dispatch()
                if (draining && active.values.any { it.native } && handle != 0L) JvmUring.cancel(handle, 0, true)
                if (ready.size >= minComplete || active.isEmpty() && pending.isEmpty() || minComplete == 0)
                    return takeReady()
                if (active.values.any { !it.native } && legacy.completionReady()) null
                else {
                    val pollSet = legacy.completionPollSet()
                    nativeWaiters++
                    handle to pollSet
                }
            }
            // Release the backend monitor while the OS sleeps: cancelPending can wake this synchronous waiter.
            if (wait != null) try {
                JvmUring.await(wait.first, wait.second.first, wait.second.second)
            } finally { releaseWait() }
        }
    }

    override suspend fun awaitCompletions() = completionWait.withLock {
        val (ring, pollSet) = synchronized(this) {
            if (ready.isNotEmpty() || handle == 0L || active.isEmpty() ||
                active.values.any { !it.native } && legacy.completionReady()) return@withLock
            val pollSet = legacy.completionPollSet()
            nativeWaiters++
            handle to pollSet
        }
        val done = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(this@JvmNativeChannelBackend) {
                        if (handle == ring) JvmUring.wake(ring)
                    }
                }
                try { Dispatchers.IO.dispatch(continuation.context, Runnable {
                    val outcome = runCatching {
                        if (continuation.isActive) {
                            val result = JvmUring.await(ring, pollSet.first, pollSet.second)
                            check(result >= 0 || result == -4) { "io_uring readiness failed: $result" }
                        }
                    }
                    // Cleanup must precede dispatch back to an owner that may be synchronously closing this ring.
                    releaseWait()
                    done.complete(Unit)
                    continuation.resumeWith(outcome)
                }) } catch (failure: Throwable) {
                    releaseWait()
                    done.complete(Unit)
                    continuation.resumeWith(Result.failure(failure))
                }
            }
        } finally { withContext(NonCancellable) { done.await() } }
    }

    @Synchronized
    override fun cancelPending() {
        draining = true
        for (sub in pending) ready.addLast(SelectionResult(-125, sub.userData))
        pending.clear()
        legacy.cancelPending()
        if (handle != 0L) {
            if (active.values.any { it.native }) JvmUring.cancel(handle, 0, true)
            JvmUring.wake(handle)
        }
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val outstanding = HashSet<Long>(submissions.size)
        for (index in 0 until submissions.size) outstanding.add(submissions[index].userData)
        val completed = submitBatch(List(submissions.size) { submissions[it] }).toMutableList()
        completed.forEach { outstanding.remove(it.userData) }
        while (outstanding.isNotEmpty()) {
            val settled = reapCompletions(0)
            settled.forEach { outstanding.remove(it.userData) }
            completed.addAll(settled)
            if (outstanding.isNotEmpty()) awaitCompletions()
        }
        return completed.size j { UringCompletion(completed[it].userData, completed[it].res, 0) }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Synchronized
    override fun close() {
        if (handle == 0L) return
        cancelPending()
        while (active.isNotEmpty()) reapCompletions(1)
        legacy.close()
        // Draining may consume the earlier wake; include leases whose OS wait has not entered C yet.
        repeat(nativeWaiters) { JvmUring.wake(handle) }
        var interrupted = false
        try {
            while (nativeWaiters != 0) try { (this as java.lang.Object).wait() }
            catch (_: InterruptedException) { interrupted = true }
            check(JvmUring.close(handle) == 0) { "Native ring still owns outstanding requests" }
            handle = 0L
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
