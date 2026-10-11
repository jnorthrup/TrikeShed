@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import borg.trikeshed.platform.loadPlatformHost
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

private class PosixUserspaceChannelBackend(private val entries: Int, mode: UringBackendMode?) : UserspaceChannelBackend {
    private val lock = SynchronizedObject()
    private val native = NativeUringAdapter(entries, mode)
    private val queued = ArrayDeque<UringSubmission>()
    private val pending = mutableMapOf<Long, UringSubmission>()
    private val completed = ArrayDeque<UringCompletion>()
    private val results = mutableMapOf<Long, CompletableDeferred<UringCompletion>>()
    private var accepting = true
    private var closed = false
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.FSYNC, UringOp.FTRUNCATE,
        UringOp.CLOSE, UringOp.FADVISE, UringOp.MADVISE, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
        UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT, UringOp.GETDENTS, UringOp.SOCKET, UringOp.BIND,
        UringOp.LISTEN, UringOp.ACCEPT, UringOp.CONNECT, UringOp.SHUTDOWN, UringOp.POLL_ADD, UringOp.POLL_REMOVE,
        UringOp.URING_CMD)
    /** Directory streams GETDENTS continues, by descriptor; CLOSE of the descriptor ends one. */
    private val directories = mutableMapOf<Int, CPointer<DIR>>()
    /** CONNECTs whose connect(2) is in progress: writable or failed, SO_ERROR settles them. */
    private val connecting = mutableSetOf<Long>()
    override val nativeCapabilities: Long get() = native.capabilities
    override val deferredCapabilities: Long get() = capabilities
    override val availability: String get() = native.availability
    /** A kernel ring whose setup failed: the errno, in Linux numbering. Null when no setup failed. */
    override val probeReport: UringProbeReport? get() = native.setupErrno?.let {
        UringProbeReport(loadPlatformHost().descriptor, uringErrnoState(it), UringProbePhase.SETUP, null, availability, it)
    }

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
            else if (submission.opcode == UringOp.POLL_REMOVE) publish(UringCompletion(submission.userData, removePoll(submission.addr), 0))
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
                connecting.remove(submission.userData)
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
                if (result == -11 && submission.opcode in waits) {
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
            UringOp.SOCKET -> socket(sub)
            UringOp.BIND -> address(sub) { addr, size -> posixCompletion(bind(sub.fd, addr, size.convert())) }
            UringOp.LISTEN -> posixCompletion(listen(sub.fd, sub.len))
            UringOp.ACCEPT -> accept(sub)
            UringOp.CONNECT -> connect(sub)
            UringOp.SHUTDOWN -> if (sub.len !in 0..2) -22 else posixCompletion(shutdown(sub.fd, sub.len))
            // Ready events of the mask, as the kernel's poll wakeup reports them; none yet waits.
            UringOp.POLL_ADD -> if (sub.len != 0 || sub.operationFlags and 0x3f.inv() != 0) -95 else
                poll(sub.fd, sub.operationFlags).let { if (it == 0) -11 else if (it > 0 && it and POLLNVAL != 0) -9 else it }
            UringOp.URING_CMD -> socketCommand(sub)
            UringOp.SEND, UringOp.RECV -> {
                buffer!!.array().usePinned { pinned ->
                    val ptr = if (sub.len == 0) null else pinned.addressOf(buffer.arrayOffset() + buffer.position())
                    posixCompletion(if (sub.opcode == UringOp.SEND)
                        send(sub.fd, ptr, sub.len.convert(), MSG_DONTWAIT or if (linux) MSG_NOSIGNAL else 0).toInt()
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

    /** IORING_OP_SOCKET: the domain in fd, the type with Linux's SOCK_NONBLOCK and SOCK_CLOEXEC in offset, the protocol in len. */
    private fun socket(sub: UringSubmission): Int {
        if (sub.offset !in 0L..Int.MAX_VALUE.toLong() || sub.offset.toInt() and (0xf or SOCKET_FLAGS).inv() != 0) return -22
        val domain = hostFamily(sub.fd) ?: return -97
        val fd = platform.posix.socket(domain, sub.offset.toInt() and 0xf, sub.len)
        if (fd < 0) return posixCompletion(fd)
        return configure(fd, sub.offset.toInt()).also { if (it < 0) close(fd) }
    }

    /**
     * accept4/socket flags of a new descriptor: O_NONBLOCK exactly as SOCK_NONBLOCK says (Darwin's accept inherits the
     * listener's), FD_CLOEXEC for SOCK_CLOEXEC, and on Darwin SO_NOSIGPIPE, as the JVM backend configures its sockets.
     */
    private fun configure(fd: Int, flags: Int): Int {
        val status = fcntl(fd, F_GETFL)
        val blocking = if (flags and 0x800 != 0) status or O_NONBLOCK else status and O_NONBLOCK.inv()
        if (status < 0 || fcntl(fd, F_SETFL, blocking) < 0) return posixCompletion(-1)
        if (flags and 0x80000 != 0 && fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) return posixCompletion(-1)
        if (!linux) memScoped {
            val enabled = alloc<IntVar>().apply { value = 1 }
            if (setsockopt(fd, SOL_SOCKET, 0x1022, enabled.ptr, sizeOf<IntVar>().convert()) < 0) return posixCompletion(-1)
        }
        return fd
    }

    /**
     * The facade's Linux sockaddr in [sub]'s window as the host's, to [action]: Darwin leads with sa_len and an 8-bit
     * family, and its sun_path ends at the NUL.
     */
    private inline fun address(sub: UringSubmission, action: (CPointer<sockaddr>, Int) -> Int): Int {
        val buffer = sub.buffer!!
        val start = buffer.arrayOffset() + buffer.position()
        val bytes = buffer.array().copyOfRange(start, start + sub.len)
        if (bytes.size < 2) return -22
        val family = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
        var size = bytes.size
        when (family) {
            2 -> if (size != 16) return -22
            10 -> if (size != 28) return -22
            1 -> {
                if (size !in 3..110) return -22
                if (!linux) {
                    if (bytes[2] == 0.toByte()) return -95 // Darwin has no abstract UNIX namespace.
                    val end = (2 until size).firstOrNull { bytes[it] == 0.toByte() } ?: size
                    if (end > 105) return -36
                    size = minOf(end + 1, size)
                }
            }
            else -> return -97
        }
        if (!linux) {
            bytes[0] = size.toByte()
            bytes[1] = hostFamily(family)!!.toByte()
        }
        return bytes.usePinned { action(it.addressOf(0).reinterpret(), size) }
    }

    /** IORING_OP_ACCEPT waits for a connection, as the kernel's poll-armed accept does; accept4's flags in operationFlags. */
    private fun accept(sub: UringSubmission): Int {
        if (sub.operationFlags and SOCKET_FLAGS.inv() != 0) return -22
        val ready = poll(sub.fd, POLLIN)
        if (ready <= 0) return if (ready == 0) -11 else ready
        if (ready and POLLNVAL != 0) return -9
        val fd = platform.posix.accept(sub.fd, null, null)
        if (fd < 0) return posixCompletion(fd)
        return configure(fd, sub.operationFlags).also { if (it < 0) close(fd) }
    }

    /** IORING_OP_CONNECT settles once the connection is made or refused, also for a non-blocking socket. */
    private fun connect(sub: UringSubmission): Int {
        if (sub.userData in connecting) {
            val ready = poll(sub.fd, POLLOUT)
            if (ready == 0) return -11
            connecting.remove(sub.userData)
            return if (ready < 0) ready else if (ready and POLLNVAL != 0) -9 else memScoped {
                val error = alloc<IntVar>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
                if (getsockopt(sub.fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr) < 0) posixCompletion(-1)
                else -linuxErrno(error.value)
            }
        }
        val result = address(sub) { addr, size -> posixCompletion(platform.posix.connect(sub.fd, addr, size.convert())) }
        if (result != -115 && result != -4) return result
        connecting.add(sub.userData)
        return -11
    }

    /** poll(2) without waiting: the ready events among [mask], POLLERR, POLLHUP and POLLNVAL, 0 when none, or -errno. */
    private fun poll(fd: Int, mask: Int): Int = memScoped {
        val record = alloc<pollfd>()
        record.fd = fd
        record.events = mask.toShort()
        record.revents = 0
        val ready = platform.posix.poll(record.ptr, 1.convert(), 0)
        if (ready < 0) posixCompletion(ready) else record.revents.toInt() and 0xffff
    }

    /** POLL_REMOVE: a POLL_ADD of [target] still waiting here or in the kernel ring settles -ECANCELED; -ENOENT otherwise. */
    private fun removePoll(target: Long): Int {
        val waiting = queued.any { it.userData == target && it.opcode == UringOp.POLL_ADD } ||
            pending[target]?.opcode == UringOp.POLL_ADD
        if (!waiting) return -2
        cancel(setOf(target))
        return 0
    }

    /**
     * IORING_OP_URING_CMD's socket commands, the command in offset: SETSOCKOPT with level and optname in addr's halves
     * and the value in the window; GETSOCKNAME (getpeername when operationFlags is 1) writing the facade sockaddr into
     * the window. The kernel probe reports URING_CMD, not which socket commands a kernel has, so every target emulates them.
     */
    private fun socketCommand(sub: UringSubmission): Int {
        val buffer = sub.buffer!!
        val start = buffer.arrayOffset() + buffer.position()
        return when (sub.offset) {
            UringOp.SOCKET_URING_OP_SETSOCKOPT.toLong() -> {
                val level = (sub.addr and 0xffffffffL).toInt()
                val name = (sub.addr ushr 32).toInt()
                if (level != UringOp.SOL_SOCKET || name != UringOp.SO_REUSEADDR) return -92
                buffer.array().usePinned {
                    posixCompletion(setsockopt(sub.fd, SOL_SOCKET, SO_REUSEADDR, if (sub.len == 0) null else it.addressOf(start), sub.len.convert()))
                }
            }
            UringOp.SOCKET_URING_OP_GETSOCKNAME.toLong() -> memScoped {
                if (buffer.isReadOnly() || sub.operationFlags !in 0..1) return -22
                val storage = alloc<sockaddr_storage>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
                val status = if (sub.operationFlags == 0) getsockname(sub.fd, storage.ptr.reinterpret(), length.ptr)
                    else getpeername(sub.fd, storage.ptr.reinterpret(), length.ptr)
                if (status < 0) return posixCompletion(status)
                val bytes = storage.ptr.reinterpret<ByteVar>().readBytes(length.value.toInt())
                if (!linux) {
                    bytes[0] = when (bytes[1].toInt()) { AF_INET -> 2; AF_INET6 -> 10; AF_UNIX -> 1; else -> return -97 }.toByte()
                    bytes[1] = 0
                }
                bytes.copyInto(buffer.array(), start, 0, minOf(bytes.size, sub.len))
                0
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
        val bufferOps = pathOps + setOf(UringOp.READ, UringOp.WRITE, UringOp.SEND, UringOp.RECV, UringOp.STATX, UringOp.GETDENTS,
            UringOp.BIND, UringOp.CONNECT, UringOp.URING_CMD)
        /** Operations the kernel ring completes only when ready: their emulation leaves them queued while it reports -EAGAIN. */
        val waits = setOf(UringOp.SEND, UringOp.RECV, UringOp.ACCEPT, UringOp.CONNECT, UringOp.POLL_ADD)
        /** Linux SOCK_NONBLOCK | SOCK_CLOEXEC. */
        const val SOCKET_FLAGS = 0x800 or 0x80000
        val linux = Platform.osFamily == OsFamily.LINUX
        /** Linux MSG_NOSIGNAL: a send to a closed peer reports EPIPE without SIGPIPE. */
        const val MSG_NOSIGNAL = 0x4000

        /** A Linux address family as the host's. */
        fun hostFamily(family: Int): Int? = when (family) { 2 -> AF_INET; 10 -> AF_INET6; 1 -> AF_UNIX; else -> null }
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
internal fun posixCompletion(result: Int): Int = if (result >= 0) result else -linuxErrno(errno)

/** A host errno in Linux numbering. */
internal fun linuxErrno(value: Int): Int = when (value) {
    0 -> 0
    EPERM -> 1; ENOENT -> 2; EINTR -> 4; EIO -> 5; EBADF -> 9; EAGAIN -> 11
    ENOMEM -> 12; EACCES -> 13; EFAULT -> 14; EBUSY -> 16; EEXIST -> 17
    ENOTDIR -> 20; EISDIR -> 21; EINVAL -> 22; ENFILE -> 23; EMFILE -> 24
    EFBIG -> 27; ENOSPC -> 28; ESPIPE -> 29; EROFS -> 30; EPIPE -> 32
    ENAMETOOLONG -> 36; ENOSYS -> 38; ENOTEMPTY -> 39; ELOOP -> 40
    ENOTSOCK -> 88; EDESTADDRREQ -> 89; EMSGSIZE -> 90; EPROTOTYPE -> 91; ENOPROTOOPT -> 92; EPROTONOSUPPORT -> 93
    EOPNOTSUPP -> 95; EAFNOSUPPORT -> 97; EADDRINUSE -> 98; EADDRNOTAVAIL -> 99; ENETDOWN -> 100; ENETUNREACH -> 101
    ECONNABORTED -> 103; ECONNRESET -> 104; ENOBUFS -> 105; EISCONN -> 106; ENOTCONN -> 107; ETIMEDOUT -> 110
    ECONNREFUSED -> 111; EHOSTUNREACH -> 113; EALREADY -> 114; EINPROGRESS -> 115
    else -> 5
}

actual fun openUserspaceChannelBackend(entries: Int, mode: UringBackendMode?): UserspaceChannelBackend {
    require(entries > 0)
    return PosixUserspaceChannelBackend(entries, mode)
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
