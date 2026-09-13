@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteOrder
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.posix.*

private class PosixUserspaceChannelBackend(private val entries: Int) : UserspaceChannelBackend {
    private val lock = SynchronizedObject()
    private val native = NativeUringAdapter(entries)
    private val descriptors = mutableSetOf<Int>()
    private val queued = ArrayDeque<UringSubmission>()
    private val pending = mutableMapOf<Long, UringSubmission>()
    private val completed = ArrayDeque<UringCompletion>()
    private val results = mutableMapOf<Long, CompletableDeferred<UringCompletion>>()
    private var accepting = true
    private var closed = false
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.FSYNC, UringOp.FTRUNCATE,
        UringOp.CLOSE, UringOp.FADVISE, UringOp.MADVISE, UringOp.READ_FIXED, UringOp.WRITE_FIXED)
    override val nativeCapabilities: Long get() = native.capabilities
    override val deferredCapabilities: Long get() = capabilities
    override val availability: String get() = native.availability

    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = synchronized(lock) {
        if (closed) Result.failure(IllegalStateException("ring is closed")) else native.registerBuffers(buffers)
    }

    override fun unregisterBuffers(): Result<Unit> = synchronized(lock) { native.unregisterBuffers() }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = synchronized(lock) {
        admit(submissions)
        takeCompletions()
    }

    private fun admit(
        submissions: List<UringSubmission>,
        receivers: Map<Long, CompletableDeferred<UringCompletion>> = emptyMap(),
    ) {
        require(queued.size + pending.size + submissions.size <= entries) { "submission queue full" }
        val tokens = (queued.map { it.userData } + pending.keys).toMutableSet()
        require(submissions.all { tokens.add(it.userData) }) { "duplicate outstanding userData" }
        results.putAll(receivers)
        for (submission in submissions) {
            val invalid = validate(submission)
            if (invalid != null) publish(UringCompletion(submission.userData, invalid, 0))
            else queued.add(submission)
        }
        dispatch()
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> {
        require(minComplete >= 0)
        val results = mutableListOf<SelectionResult>()
        do {
            val outstanding = synchronized(lock) {
                poll()
                results.addAll(takeCompletions())
                queued.isNotEmpty() || pending.isNotEmpty()
            }
            if (results.size >= minComplete || !outstanding) break
            usleep(1000u)
        } while (true)
        return results
    }

    override fun cancelPending() = synchronized(lock) {
        accepting = false
        cancel(null)
    }

    private fun cancel(tokens: Set<Long>?) {
        // These requests have not reached the kernel. Submitted requests keep
        // their buffers until their own terminal CQEs are reaped.
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val submission = iterator.next()
            if (tokens == null || submission.userData in tokens) {
                iterator.remove()
                publish(UringCompletion(submission.userData, -125, 0))
            }
        }
        native.cancelPending(tokens)
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        require(submissions.size <= entries) { "submission queue full" }
        val ordered = Array(submissions.size) { submissions[it] }
        val receivers = ordered.associate { it.userData to CompletableDeferred<UringCompletion>() }
        require(receivers.size == ordered.size) { "duplicate userData in batch" }
        suspend fun settle() {
            while (receivers.values.any { !it.isCompleted }) {
                synchronized(lock) { poll() }
                if (receivers.values.any { !it.isCompleted }) delay(1)
            }
        }
        try {
            while (true) {
                val admitted = synchronized(lock) {
                    if (queued.size + pending.size + ordered.size <= entries) {
                        admit(ordered.asList(), receivers)
                        true
                    } else {
                        poll()
                        false
                    }
                }
                if (admitted) break
                delay(1)
            }
            settle()
            val completions = Array(ordered.size) { receivers.getValue(ordered[it].userData).await() }
            return completions.size j { completions[it] }
        } catch (failure: Throwable) {
            val admitted = synchronized(lock) {
                receivers.any { (token, receiver) -> results[token] === receiver }
            }
            if (admitted) try {
                withContext(NonCancellable) {
                    synchronized(lock) {
                        val tokens = receivers.filter { (token, receiver) -> results[token] === receiver }.keys
                        if (tokens.isNotEmpty()) cancel(tokens)
                    }
                    settle()
                }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun poll() {
        if (closed) return
        for (completion in native.reapCompletions()) {
            val submission = checkNotNull(pending.remove(completion.userData)) {
                "unknown native completion ${completion.userData}"
            }
            complete(submission, completion)
        }
        dispatch()
    }

    private fun validate(sub: UringSubmission): Int? {
        if (closed || !accepting) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        if (sub.len < 0) return -22
        val buffer = sub.buffer
        if (sub.opcode in bufferOps) {
            if (buffer == null) return -22
            if (sub.len > buffer.remaining()) return -22
            if ((sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV || sub.opcode == UringOp.STATX) && buffer.isReadOnly()) return -22
        }
        if (sub.opcode in positioned && sub.offset < -1) return -22
        if (sub.opcode == UringOp.STATX && (sub.len < 24 || sub.operationFlags != 0 || sub.offset != 0L || sub.addr != 0L)) return -22
        if (sub.opcode == UringOp.FTRUNCATE && sub.offset < 0) return -22
        if (sub.opcode == UringOp.OPENAT) {
            if (sub.len == 0 || sub.offset < 0 || sub.offset > Int.MAX_VALUE || sub.offset.toInt() and OPEN_FLAGS.inv() != 0) return -22
            val flags = sub.offset.toInt()
            if (flags and 3 == 3 || flags and 128 != 0 && flags and 64 == 0 ||
                flags and (64 or 128 or 512) != 0 && flags and 3 == 0) return -22
            val start = buffer!!.arrayOffset() + buffer.position()
            if ((start until start + sub.len).any { buffer.array()[it] == 0.toByte() }) return -22
        }
        return null
    }

    private fun dispatch() {
        val nativeBatch = mutableListOf<UringSubmission>()
        val blocked = mutableListOf<UringSubmission>()
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val submission = iterator.next()
            if (pending.values.any { dependsOn(submission, it) } || blocked.any { dependsOn(submission, it) }) {
                blocked.add(submission)
                continue
            }
            if (submission.buffer?.remaining()?.let { submission.len > it } == true) {
                iterator.remove()
                complete(submission, UringCompletion(submission.userData, -22, 0))
                continue
            }
            if (native.capabilities and submission.opcode.mask != 0L) {
                iterator.remove()
                pending[submission.userData] = submission
                nativeBatch.add(submission)
            } else {
                val result = emulate(submission)
                if (result == -11 && (submission.opcode == UringOp.SEND || submission.opcode == UringOp.RECV)) {
                    blocked.add(submission)
                } else {
                    iterator.remove()
                    complete(submission, UringCompletion(submission.userData, result, 0))
                }
            }
        }
        if (nativeBatch.isNotEmpty()) try {
            native.submit(nativeBatch)
        } catch (failure: Throwable) {
            accepting = false
            for (submission in nativeBatch) {
                if (!native.isPending(submission.userData)) {
                    pending.remove(submission.userData)
                    publish(UringCompletion(submission.userData, -5, 0))
                }
            }
            throw failure
        }
    }

    private fun dependsOn(next: UringSubmission, prior: UringSubmission): Boolean {
        val nextBuffer = next.buffer
        val priorBuffer = prior.buffer
        if (nextBuffer != null && priorBuffer != null) {
            if (nextBuffer === priorBuffer) return true
            if (nextBuffer.array() === priorBuffer.array()) {
                val start = nextBuffer.arrayOffset() + nextBuffer.position()
                val previous = priorBuffer.arrayOffset() + priorBuffer.position()
                if (start.toLong() < previous.toLong() + prior.len && previous.toLong() < start.toLong() + next.len) return true
            }
        }
        if (next.addr != 0L && prior.addr != 0L && next.len > 0 && prior.len > 0 &&
            next.opcode in memoryOps && prior.opcode in memoryOps &&
            next.addr.toULong() < prior.addr.toULong() + prior.len.toULong() &&
            prior.addr.toULong() < next.addr.toULong() + next.len.toULong()) return true
        if (next.fd != prior.fd || next.opcode == UringOp.NOP || prior.opcode == UringOp.NOP ||
            next.opcode == UringOp.MADVISE || prior.opcode == UringOp.MADVISE) return false
        if (next.opcode in barriers || prior.opcode in barriers) return true
        if (next.opcode == UringOp.OPENAT || prior.opcode == UringOp.OPENAT) return false
        if (next.opcode in positioned && prior.opcode in positioned) {
            if (next.offset == -1L || prior.offset == -1L) return true
            if (next.opcode in reads && prior.opcode in reads) return false
            return if (next.offset <= prior.offset) prior.offset - next.offset < next.len
                else next.offset - prior.offset < prior.len
        }
        return (next.opcode in reads && prior.opcode in reads) ||
            (next.opcode in writes && prior.opcode in writes)
    }

    private fun complete(sub: UringSubmission, completion: UringCompletion) {
        if (completion.res > 0 && sub.opcode in transferOps) {
            check(completion.res <= sub.len) { "completion exceeds submitted buffer window" }
            sub.buffer!!.position(sub.buffer.position() + completion.res)
        }
        if (sub.opcode == UringOp.OPENAT && completion.res >= 0) descriptors.add(completion.res)
        if (sub.opcode == UringOp.CLOSE && completion.res != -125) descriptors.remove(sub.fd)
        publish(completion)
    }

    private fun publish(completion: UringCompletion) {
        val receiver = results.remove(completion.userData)
        if (receiver == null) completed.add(completion) else receiver.complete(completion)
    }

    private fun takeCompletions(): List<SelectionResult> = buildList {
        while (completed.isNotEmpty()) completed.removeFirst().let { add(SelectionResult(it.res, it.userData)) }
    }

    private fun emulate(sub: UringSubmission): Int {
        val buffer = sub.buffer
        return when (sub.opcode) {
            UringOp.NOP -> 0
            UringOp.FADVISE -> native.fadvise(sub.fd, sub.offset, sub.len, sub.operationFlags)
            UringOp.MADVISE -> adviseMemory(sub.addr, sub.len.toLong(), sub.operationFlags)
            UringOp.OPENAT -> {
                val start = buffer!!.arrayOffset() + buffer.position()
                val path = buffer.array().decodeToString(start, start + sub.len)
                val flags = sub.offset.toInt()
                val mode = when (flags and 3) { 0 -> O_RDONLY; 1 -> O_WRONLY; 2 -> O_RDWR; else -> return -22 }
                val hostFlags = mode or (if (flags and 64 != 0) O_CREAT else 0) or
                    (if (flags and 128 != 0) O_EXCL else 0) or (if (flags and 512 != 0) O_TRUNC else 0) or
                    (if (flags and 1024 != 0) O_APPEND else 0)
                if (sub.fd != -100) return -95
                posixCompletion(open(path, hostFlags, 438u))
            }
            UringOp.READ, UringOp.WRITE -> {
                val bytes = buffer!!.array()
                val start = buffer.arrayOffset() + buffer.position()
                if (sub.opcode == UringOp.READ) PosixUringIO.readAt(sub.fd, bytes, start, sub.len, sub.offset)
                else PosixUringIO.writeAt(sub.fd, bytes, start, sub.len, sub.offset)
            }
            UringOp.SEND, UringOp.RECV -> {
                buffer!!.array().usePinned { pinned ->
                    val ptr = if (sub.len == 0) null else pinned.addressOf(buffer.arrayOffset() + buffer.position())
                    posixCompletion(if (sub.opcode == UringOp.SEND)
                        send(sub.fd, ptr, sub.len.convert(), MSG_DONTWAIT).toInt()
                    else recv(sub.fd, ptr, sub.len.convert(), MSG_DONTWAIT).toInt())
                }
            }
            UringOp.STATX -> memScoped {
                val metadata = alloc<stat>()
                val status = posixCompletion(fstat(sub.fd, metadata.ptr))
                if (status < 0) status else {
                    val kind = when (metadata.st_mode.toInt() and S_IFMT.toInt()) {
                        S_IFREG.toInt() -> 1L
                        S_IFDIR.toInt() -> 2L
                        else -> 0L
                    }
                    // The portable payload is size, mtime millis (unavailable here), and file kind.
                    buffer!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(metadata.st_size).putLong(0L).putLong(kind)
                    24
                }
            }
            UringOp.FSYNC -> PosixUringIO.fsync(sub.fd)
            UringOp.FTRUNCATE -> PosixUringIO.ftruncate(sub.fd, sub.offset)
            UringOp.CLOSE -> PosixUringIO.closeFd(sub.fd)
            else -> -95
        }
    }

    override fun close() {
        synchronized(lock) { accepting = false }
        while (synchronized(lock) {
            poll()
            queued.isNotEmpty() || pending.isNotEmpty()
        }) usleep(1000u)
        synchronized(lock) {
            if (closed) return
            native.close()
            descriptors.forEach { platform.posix.close(it) }
            descriptors.clear()
            closed = true
        }
    }

    private companion object {
        const val OPEN_FLAGS = 3 or 64 or 128 or 512 or 1024
        val transferOps = setOf(UringOp.READ, UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX)
        val bufferOps = setOf(UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX)
        val barriers = setOf(UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.STATX)
        val positioned = setOf(UringOp.READ, UringOp.WRITE, UringOp.READ_FIXED, UringOp.WRITE_FIXED)
        val memoryOps = positioned + UringOp.MADVISE
        val reads = setOf(UringOp.READ, UringOp.READ_FIXED, UringOp.RECV)
        val writes = setOf(UringOp.WRITE, UringOp.WRITE_FIXED, UringOp.SEND)
    }
}

/** Translate host errno values to Linux CQE values, including Darwin's differing numbers. */
internal fun posixCompletion(result: Int): Int = if (result >= 0) result else -when (errno) {
    EPERM -> 1; ENOENT -> 2; EINTR -> 4; EIO -> 5; EBADF -> 9; EAGAIN -> 11
    ENOMEM -> 12; EACCES -> 13; EFAULT -> 14; EBUSY -> 16; EEXIST -> 17
    ENOTDIR -> 20; EISDIR -> 21; EINVAL -> 22; ENFILE -> 23; EMFILE -> 24
    EFBIG -> 27; ENOSPC -> 28; ESPIPE -> 29; EROFS -> 30; EPIPE -> 32
    ENOSYS -> 38; ENOTEMPTY -> 39; ELOOP -> 40; EOPNOTSUPP -> 95
    ECONNRESET -> 104; ENOTCONN -> 107; ETIMEDOUT -> 110; ECONNREFUSED -> 111
    else -> 5
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend {
    require(entries > 0)
    return PosixUserspaceChannelBackend(entries)
}

actual class FileImpl actual constructor(actual val id: Int) {
    private var closed = id < 0
    actual fun isOpen(): Boolean = !closed && fcntl(id, F_GETFD) >= 0
    actual fun close() {
        if (!closed) { closed = true; PosixUringIO.closeFd(id) }
    }
    actual fun size(): Long = if (closed) -1L else PosixUringIO.fileSize(id)
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val fd = open(path, if (readOnly) O_RDONLY else O_RDWR or O_CREAT, 438u)
        check(fd >= 0) { "open failed: ${posixCompletion(fd)}" }
        return FileImpl(fd)
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(platform.posix.socket(domain, type, protocol))
}
