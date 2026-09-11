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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.posix.*

private class PosixUserspaceChannelBackend(private val entries: Int) : UserspaceChannelBackend {
    private val lock = SynchronizedObject()
    private val native = NativeUringAdapter(entries)
    private val descriptors = mutableSetOf<Int>()
    private var closed = false
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE, UringOp.MADVISE, UringOp.READ_FIXED, UringOp.WRITE_FIXED) or native.capabilities
    override val nativeCapabilities: Long get() = native.capabilities
    override val availability: String get() = native.availability

    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = synchronized(lock) {
        if (closed) Result.failure(IllegalStateException("ring is closed")) else native.registerBuffers(buffers)
    }

    override fun unregisterBuffers(): Result<Unit> = synchronized(lock) { native.unregisterBuffers() }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = synchronized(lock) {
        require(submissions.size <= entries) { "submission queue full" }
        submissions.map { execute(it).let { c -> SelectionResult(c.res, c.userData) } }
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
        withContext(Dispatchers.Default + NonCancellable) {
            synchronized(lock) {
                require(submissions.size <= entries) { "submission queue full" }
                val completions = Array(submissions.size) { execute(submissions[it]) }
                completions.size j { completions[it] }
            }
        }

    private fun execute(sub: UringSubmission): UringCompletion {
        fun result(code: Int) = UringCompletion(sub.userData, code, 0)
        if (closed) return result(-9)
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return result(-95)
        if (sub.len < 0) return result(-22)
        val buffer = sub.buffer
        if (sub.opcode in bufferOps) {
            if (buffer == null) return result(-22)
            if (sub.len > buffer.remaining()) return result(-22)
            if ((sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV || sub.opcode == UringOp.STATX) && buffer.isReadOnly()) return result(-22)
        }
        if ((sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) && sub.offset < -1) return result(-22)
        if (sub.opcode == UringOp.STATX && (sub.len < 24 || sub.operationFlags != 0 || sub.offset != 0L || sub.addr != 0L)) return result(-22)
        if (sub.opcode == UringOp.FTRUNCATE && sub.offset < 0) return result(-22)
        if (sub.opcode == UringOp.OPENAT) {
            if (sub.len == 0 || sub.offset < 0 || sub.offset > Int.MAX_VALUE || sub.offset.toInt() and OPEN_FLAGS.inv() != 0) return result(-22)
            val flags = sub.offset.toInt()
            if (flags and 3 == 3 || flags and 128 != 0 && flags and 64 == 0 ||
                flags and (64 or 128 or 512) != 0 && flags and 3 == 0) return result(-22)
            val start = buffer!!.arrayOffset() + buffer.position()
            if ((start until start + sub.len).any { buffer.array()[it] == 0.toByte() }) return result(-22)
        }
        val completion = if (native.capabilities and sub.opcode.mask != 0L) native.execute(sub)
        else result(emulate(sub))
        if (completion.res > 0 && sub.opcode in transferOps) {
            check(completion.res <= sub.len) { "completion exceeds submitted buffer window" }
            buffer!!.position(buffer.position() + completion.res)
        }
        if (sub.opcode == UringOp.OPENAT && completion.res >= 0) descriptors.add(completion.res)
        if (sub.opcode == UringOp.CLOSE) descriptors.remove(sub.fd)
        return completion
    }

    private fun emulate(sub: UringSubmission): Int {
        val buffer = sub.buffer
        return when (sub.opcode) {
            UringOp.NOP -> 0
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
                        send(sub.fd, ptr, sub.len.convert(), 0).toInt()
                    else recv(sub.fd, ptr, sub.len.convert(), 0).toInt())
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

    override fun close() = synchronized(lock) {
        if (!closed) {
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
