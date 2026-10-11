@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
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
    private val queued = ArrayDeque<UringSubmission>()
    private val pending = mutableMapOf<Long, UringSubmission>()
    private val completed = ArrayDeque<UringCompletion>()
    private val results = mutableMapOf<Long, CompletableDeferred<UringCompletion>>()
    private var accepting = true
    private var closed = false
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.FSYNC, UringOp.FTRUNCATE,
        UringOp.CLOSE, UringOp.FADVISE, UringOp.MADVISE, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
        UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT, UringOp.GETDENTS)
    /** Directory streams GETDENTS continues, by descriptor; CLOSE of the descriptor ends one. */
    private val directories = mutableMapOf<Int, CPointer<DIR>>()
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
            if ((sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV || sub.opcode == UringOp.STATX ||
                    sub.opcode == UringOp.GETDENTS) && buffer.isReadOnly()) return -22
        }
        if (sub.opcode in positioned && sub.offset < -1) return -22
        if (sub.opcode == UringOp.STATX) {
            // The path's len bytes, its NUL, then the struct statx this call writes.
            val start = buffer!!.arrayOffset() + buffer.position()
            if (sub.addr != 0L || sub.offset !in 0L..0xffffffffL || buffer.remaining() - sub.len < 1 + UringOp.STATX_SIZE ||
                buffer.array()[start + sub.len] != 0.toByte() || (start until start + sub.len).any { buffer.array()[it] == 0.toByte() } ||
                sub.operationFlags and (UringOp.AT_SYMLINK_NOFOLLOW or UringOp.AT_EMPTY_PATH or 0x800).inv() != 0) return -22
        }
        if (sub.opcode in pathOps && (sub.len == 0 || sub.addr != 0L)) return -22
        if (sub.opcode == UringOp.FTRUNCATE && sub.offset < 0) return -22
        if (sub.opcode == UringOp.OPENAT) {
            if (sub.len == 0 || sub.offset < 0 || sub.offset > Int.MAX_VALUE || sub.offset.toInt() and OPEN_FLAGS.inv() != 0) return -22
            val flags = sub.offset.toInt()
            if (flags and 3 == 3 || sub.operationFlags !in 0..4095) return -22
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
                val path = sub.paths().single()
                val flags = sub.offset.toInt()
                val mode = when (flags and 3) { 0 -> O_RDONLY; 1 -> O_WRONLY; 2 -> O_RDWR; else -> return -22 }
                fun bit(flag: Int, host: Int) = if (flags and flag != 0) host else 0
                val hostFlags = mode or bit(UringOp.O_CREAT, O_CREAT) or bit(UringOp.O_EXCL, O_EXCL) or bit(UringOp.O_TRUNC, O_TRUNC) or
                    bit(UringOp.O_APPEND, O_APPEND) or bit(UringOp.O_NONBLOCK, O_NONBLOCK) or bit(UringOp.O_DIRECTORY, O_DIRECTORY) or
                    bit(UringOp.O_NOFOLLOW, O_NOFOLLOW) or bit(UringOp.O_CLOEXEC, O_CLOEXEC)
                if (sub.fd != UringOp.AT_FDCWD) return -95
                posixCompletion(open(path, hostFlags, sub.operationFlags.toUInt()))
            }
            // Path syscalls resolve from the working directory (AT_FDCWD); renameat's two paths are
            // NUL-separated in the one buffer, as an SQE has one address.
            UringOp.MKDIRAT -> if (sub.fd != UringOp.AT_FDCWD) -95 else if (sub.operationFlags !in 0..4095) -22
                else posixCompletion(mkdir(sub.paths().single(), sub.operationFlags.convert()))
            UringOp.UNLINKAT -> if (sub.fd != UringOp.AT_FDCWD) -95 else when (sub.operationFlags) {
                0 -> posixCompletion(unlink(sub.paths().single()))
                UringOp.AT_REMOVEDIR -> posixCompletion(rmdir(sub.paths().single()))
                else -> -22
            }
            UringOp.RENAMEAT -> {
                val paths = sub.paths()
                if (paths.size != 2) -22 else if (sub.fd != UringOp.AT_FDCWD || sub.offset != UringOp.AT_FDCWD.toLong()) -95
                else if (sub.operationFlags != 0) -22 else posixCompletion(rename(paths[0], paths[1]))
            }
            UringOp.GETDENTS -> getdents(sub.fd, buffer!!.array(), buffer.arrayOffset() + buffer.position(), sub.len)
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
                // statx(2) where the host has it is the kernel ring's; here fstat, stat or lstat
                // fill struct statx in host order, without timestamps (stx_mask says which fields).
                val metadata = alloc<stat>()
                val path = if (sub.len == 0) "" else sub.paths().single()
                val status = when {
                    path.isEmpty() && sub.operationFlags and UringOp.AT_EMPTY_PATH == 0 -> -2
                    path.isEmpty() -> posixCompletion(fstat(sub.fd, metadata.ptr))
                    sub.fd != UringOp.AT_FDCWD -> -95
                    sub.operationFlags and UringOp.AT_SYMLINK_NOFOLLOW != 0 -> posixCompletion(lstat(path, metadata.ptr))
                    else -> posixCompletion(stat(path, metadata.ptr))
                }
                if (status < 0) status else {
                    val dev = metadata.st_dev.toLong()
                    val rdev = metadata.st_rdev.toLong()
                    val statx = buffer!!.array().let { ByteBuffer.wrap(it, buffer.arrayOffset() + buffer.position() + sub.len + 1, UringOp.STATX_SIZE) }
                        .order(ByteOrder.nativeOrder())
                    for (at in 0 until UringOp.STATX_SIZE step 8) statx.putLong(at, 0L)
                    statx.putInt(0x00, 0x71f)
                    statx.putInt(0x04, metadata.st_blksize.toInt())
                    statx.putInt(0x10, metadata.st_nlink.toInt())
                    statx.putInt(0x14, metadata.st_uid.toInt())
                    statx.putInt(0x18, metadata.st_gid.toInt())
                    statx.putShort(0x1c, metadata.st_mode.toShort())
                    statx.putLong(0x20, metadata.st_ino.toLong())
                    statx.putLong(0x28, metadata.st_size)
                    statx.putLong(0x30, metadata.st_blocks)
                    statx.putInt(0x80, major(rdev))
                    statx.putInt(0x84, minor(rdev))
                    statx.putInt(0x88, major(dev))
                    statx.putInt(0x8c, minor(dev))
                    0
                }
            }
            UringOp.FSYNC -> PosixUringIO.fsync(sub.fd)
            UringOp.FTRUNCATE -> PosixUringIO.ftruncate(sub.fd, sub.offset)
            UringOp.CLOSE -> {
                directories.remove(sub.fd)?.let { closedir(it) }
                PosixUringIO.closeFd(sub.fd)
            }
            else -> -95
        }
    }

    /** dev_t halves: Darwin's 8-bit major over 24-bit minor, glibc's split 12/20 and 32/32 layout. */
    private fun major(dev: Long): Int = if (Platform.osFamily == OsFamily.LINUX)
        (((dev ushr 8) and 0xfff) or ((dev ushr 32) and 0xfffff000L)).toInt()
    else ((dev ushr 24) and 0xff).toInt()

    private fun minor(dev: Long): Int = if (Platform.osFamily == OsFamily.LINUX)
        ((dev and 0xff) or ((dev ushr 12) and 0xffffff00L)).toInt()
    else (dev and 0xffffff).toInt()

    /**
     * getdents64 over the host's directory stream: linux_dirent64 records (d_ino, d_off, d_reclen,
     * d_type, d_name) into [length] bytes at [start]; 0 at the end. An entry that does not fit is
     * read again by the next call.
     */
    private fun getdents(fd: Int, bytes: ByteArray, start: Int, length: Int): Int {
        val dir = directories[fd] ?: run {
            val copy = dup(fd)
            if (copy < 0) return posixCompletion(copy)
            fdopendir(copy)?.also { directories[fd] = it } ?: return posixCompletion(-1).also { close(copy) }
        }
        val records = ByteBuffer.wrap(bytes, start, length).order(ByteOrder.nativeOrder())
        var written = 0
        while (true) {
            val position = telldir(dir)
            set_posix_errno(0)
            val entry = readdir(dir)?.pointed ?: return if (posix_errno() != 0) posixCompletion(-1) else written
            val name = entry.d_name.toKString().encodeToByteArray()
            val size = (19 + name.size + 1 + 7) and 7.inv()
            if (written + size > length) {
                seekdir(dir, position)
                return if (written == 0) -22 else written
            }
            records.putLong(written, entry.d_ino.toLong())
            records.putLong(written + 8, 0L)
            records.putShort(written + 16, size.toShort())
            records.put(written + 18, entry.d_type.toByte())
            name.copyInto(bytes, start + written + 19)
            for (pad in written + 19 + name.size until written + size) records.put(pad, 0.toByte())
            written += size
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
            // Descriptors returned to callers are closed by their owners, independently of this ring.
            closed = true
        }
    }

    private companion object {
        const val OPEN_FLAGS = 3 or UringOp.O_CREAT or UringOp.O_EXCL or UringOp.O_TRUNC or UringOp.O_APPEND or UringOp.O_NONBLOCK or
            UringOp.O_DIRECTORY or UringOp.O_NOFOLLOW or UringOp.O_CLOEXEC
        val transferOps = setOf(UringOp.READ, UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.GETDENTS)
        val pathOps = setOf(UringOp.OPENAT, UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT)
        val bufferOps = pathOps + setOf(UringOp.READ, UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.GETDENTS)
        val barriers = setOf(UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.STATX)
        val positioned = setOf(UringOp.READ, UringOp.WRITE, UringOp.READ_FIXED, UringOp.WRITE_FIXED)
        val memoryOps = positioned + UringOp.MADVISE
        val reads = setOf(UringOp.READ, UringOp.READ_FIXED, UringOp.RECV)
        val writes = setOf(UringOp.WRITE, UringOp.WRITE_FIXED, UringOp.SEND)
    }
}

/** The submission's NUL-separated paths, each without a NUL of its own. */
internal fun UringSubmission.paths(): List<String> {
    val bytes = requireNotNull(buffer)
    val start = bytes.arrayOffset() + bytes.position()
    return bytes.array().decodeToString(start, start + len).split('\u0000')
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
