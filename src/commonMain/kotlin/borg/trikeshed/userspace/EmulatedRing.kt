package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

/** Common bounded SQ/CQ coordinator. Platform backends execute effects only.
 * Synchronous callers serialize access; coroutine callers use FunctionalUringFacade.
 */
internal class EmulatedRing(private val backend: UserspaceChannelBackend) : LiburingFacade {
    private val sq = ArrayDeque<UringSubmission>()
    private val cq = ArrayDeque<UringCompletion>()
    private val inFlight = mutableMapOf<Long, Twin<UringSubmission>>()
    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()
    private var capacity = 0
    private var draining = false
    private var closed = false
    private var executing = false
    private var failure: Throwable? = null
    internal val isClosed: Boolean get() = closed

    /** The separator between renameat's two paths, by code point so the source stays ASCII. */
    private val nul: String = 0.toChar().toString()

    private fun stage(submission: UringSubmission): Result<Unit> = runCatching {
        check(capacity > 0 && !closed && !draining) { "ring is not accepting submissions" }
        check(!executing) { "ring is executing" }
        failure?.let { throw it }
        check(sq.size + cq.size + inFlight.size < capacity) { "submission queue full" }
        require(submission.len >= 0) { "negative transfer length" }
        require(submission.userData !in inFlight && sq.none { it.userData == submission.userData } &&
            cq.none { it.userData == submission.userData }) {
            "duplicate outstanding userData"
        }
        sq.addLast(submission)
    }

    /**
     * A path rides in the submission buffer. renameat carries both halves NUL-separated: an SQE
     * has one address field, and a second channel for the second path would be a shape only this
     * backend understands.
     */
    private fun paths(vararg parts: String): ByteBuffer =
        ByteBuffer(parts.joinToString(nul).encodeToByteArray())

    override fun open(entries: Int, flags: Int): Result<Unit> = runCatching {
        check(capacity == 0 && !closed) { "ring is already open or closed" }
        require(entries > 0) { "entries must be positive" }
        require(flags == 0) { "setup flags are unsupported by the common facade" }
        capacity = entries
    }

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.READ, fd, bufAddress, len, offset, 0, userData))

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.WRITE, fd, bufAddress, len, offset, 0, userData))

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.ACCEPT, fd, 0L, 0, 0L, 0, userData))

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.CONNECT, fd, addrPtr, addrLen, 0L, 0, userData))

    override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.CLOSE, fd, 0L, 0, 0L, 0, userData))

    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        stage(UringSubmission(UringOp.FSYNC, fd, 0L, 0, 0L, userData = userData, operationFlags = if (datasync) 1 else 0))

    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.FTRUNCATE, fd, 0L, 0, size, 0, userData))

    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0L, userData = userData, operationFlags = flags))

    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0L, userData = userData, operationFlags = flags))

    override fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.OPENAT, dfd, 0L, path.encodeToByteArray().size, flags.toLong(), userData = userData, buffer = paths(path), operationFlags = mode))

    override fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.STATX, dfd, bufAddress, 256, mask.toLong(), userData = userData, buffer = paths(path), operationFlags = flags))

    override fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> = runCatching {
        require(len in 0..Int.MAX_VALUE.toLong()) { "allocation length exceeds submission representation" }
        stage(UringSubmission(UringOp.FALLOCATE, fd, 0L, len.toInt(), offset, userData = userData, operationFlags = mode)).getOrThrow()
    }

    override fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.FADVISE, fd, 0L, len, offset, userData = userData, operationFlags = advice))

    override fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MADVISE, -1, addr, length, 0L, userData = userData, operationFlags = advice))

    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.RENAMEAT, oldDfd, 0L, oldPath.length, newDfd.toLong(), userData = userData, buffer = paths(oldPath, newPath), operationFlags = flags))

    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.UNLINKAT, dfd, 0L, path.length, 0L, userData = userData, buffer = paths(path), operationFlags = flags))

    private var registeredBuffers: Series<ByteBuffer>? = null
    private var registeredMemory: Series<MemoryMapping>? = null
    private var kernelRegistered = false

    private fun registrationAvailable() {
        failure?.let { throw it }
        check(capacity > 0 && !closed && !draining && !executing) { "ring is not open for registration" }
        check(registeredBuffers == null && registeredMemory == null) { "buffers already registered" }
    }

    /** Compatibility storage for emulated heap transfers; no native pinning is claimed. */
    override fun registerBuffers(buffers: List<ByteBuffer>): Result<Int> = runCatching {
        registrationAvailable()
        require(buffers.isNotEmpty()) { "empty buffer registration" }
        registeredBuffers = buffers.toTypedArray().toSeries()
        buffers.size
    }

    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = runCatching {
        registrationAvailable()
        require(buffers.size > 0) { "empty buffer registration" }
        val snapshot = Array(buffers.size) { buffers[it] }.toSeries()
        var retained = 0
        try {
            for (i in 0 until snapshot.size) {
                val memory = snapshot[i]
                require(memory.length in 1..Int.MAX_VALUE.toLong()) { "registered mapping length exceeds transfer representation" }
                memory.retain()
                retained++
            }
            val registration = backend.registerBuffers(snapshot)
            if (registration.isFailure && registration.exceptionOrNull() !is UnsupportedOperationException) registration.getOrThrow()
            kernelRegistered = registration.isSuccess
            registeredMemory = snapshot
        } catch (error: Throwable) {
            for (i in 0 until retained) snapshot[i].release()
            throw error
        }
    }

    override fun unregisterBuffers(): Result<Unit> = runCatching {
        check(capacity > 0 && !closed && !executing) { "ring is not available for unregistration" }
        check(sq.none { it.bufferIndex >= 0 }) { "registered buffers still have staged requests" }
        check(inFlight.values.none { it.a.bufferIndex >= 0 }) { "registered buffers still have executing requests" }
        val memory = registeredMemory
        check(memory != null || registeredBuffers != null) { "no buffers registered" }
        if (memory != null) {
            if (kernelRegistered) backend.unregisterBuffers().getOrThrow()
            kernelRegistered = false
            for (i in 0 until memory.size) memory[i].release()
            registeredMemory = null
        }
        registeredBuffers = null
    }

    override fun registerFiles(fds: IntArray): Result<Int> = unsupported()
    override fun unregisterFiles(): Result<Unit> = unsupported()

    override fun prepReadFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        fixed(UringOp.READ_FIXED, fd, bufIndex, len, offset, userData)

    override fun prepWriteFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        fixed(UringOp.WRITE_FIXED, fd, bufIndex, len, offset, userData)

    private fun fixed(op: UringOp, fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> = runCatching {
        require(bufIndex >= 0 && len >= 0) { "invalid fixed buffer range" }
        val memory = registeredMemory
        if (memory != null) {
            require(bufIndex < memory.size) { "buffer index is not registered" }
            val owner = memory[bufIndex]
            require(len.toLong() <= owner.length) { "fixed transfer exceeds registered mapping" }
            stage(UringSubmission(op, fd, owner.address, len, offset, userData = userData,
                bufferIndex = bufIndex, memory = owner)).getOrThrow()
        } else {
            val buffers = checkNotNull(registeredBuffers) { "no buffers registered" }
            require(bufIndex < buffers.size) { "buffer index is not registered" }
            val buffer = buffers[bufIndex]
            require(len <= buffer.remaining()) { "fixed transfer exceeds registered buffer" }
            stage(UringSubmission(op, fd, 0L, len, offset, userData = userData,
                buffer = buffer, bufferIndex = bufIndex)).getOrThrow()
        }
    }

    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MKDIRAT, dfd, 0L, path.length, 0L, userData = userData, buffer = paths(path), operationFlags = mode))

    override fun submit(): Result<Int> = runCatching {
        check(capacity > 0 && !closed && !executing) { "ring is not available for submission" }
        failure?.let { throw it }
        if (sq.isEmpty()) return@runCatching 0
        val batch = Array(sq.size) { sq.removeFirst() }.toSeries()
        var dispatched = false
        executing = true
        try {
            // List is the platform compatibility boundary; the owned snapshot remains Series.
            val effects = Array(batch.size) { index ->
                val submission = batch[index]
                if (submission.bufferIndex < 0 || (kernelRegistered && submission.memory != null &&
                        backend.supportsFixedBuffer(submission.fd))) submission
                else {
                    val memory = submission.memory
                    val buffer = submission.buffer ?: ByteBuffer(submission.len).also {
                        if (submission.opcode == UringOp.WRITE_FIXED) {
                            requireNotNull(memory).read(0, it.array(), 0, submission.len)
                        }
                    }
                    submission.copy(opcode = if (submission.opcode == UringOp.READ_FIXED) UringOp.READ else UringOp.WRITE,
                        buffer = buffer, bufferIndex = -1, addr = 0L)
                }
            }.toSeries()
            for (i in 0 until batch.size) inFlight[batch[i].userData] = batch[i] j effects[i]
            dispatched = true
            val results = backend.submitBatch(List(effects.size) { effects[it] })
            settle(results)
            check((0 until batch.size).none {
                val submission = batch[it]
                submission.userData in inFlight && !deferred(submission)
            }) { "backend did not settle every submission" }
            failure?.let { throw it }
            batch.size
        } catch (error: Throwable) {
            // A failed call settles its synchronous borrows. Deferred work remains owned until
            // a terminal CQE arrives, including cancellation during drain. Never retry effects.
            for (i in 0 until batch.size) {
                val submission = batch[i]
                if (!dispatched) cq.addLast(UringCompletion(submission.userData, -5, 0))
                else if (submission.userData in inFlight && !deferred(submission)) {
                    complete(SelectionResult(-5, submission.userData))
                }
            }
            failed(error)
            throw error
        } finally {
            executing = false
        }
    }

    private fun deferred(submission: UringSubmission): Boolean =
        backend.deferredCapabilities and submission.opcode.mask != 0L

    private fun failed(error: Throwable) {
        val previous = failure
        if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
    }

    private fun complete(result: SelectionResult) {
        val (submission, effect) = requireNotNull(inFlight.remove(result.userData))
        val res = try {
            if (effect.opcode == UringOp.READ && submission.opcode == UringOp.READ_FIXED &&
                submission.memory != null && result.res > 0) {
                check(result.res <= submission.len) { "backend read exceeds requested length" }
                submission.memory.write(0, requireNotNull(effect.buffer).array(), 0, result.res)
            }
            result.res
        } catch (error: Throwable) {
            failed(error)
            -5 // EIO: the completed read could not be delivered to its registered memory.
        }
        cq.addLast(UringCompletion(result.userData, res, 0))
    }

    private fun settle(results: List<SelectionResult>) {
        val identities = mutableSetOf<Long>()
        val duplicates = mutableSetOf<Long>()
        for (result in results) {
            if (!identities.add(result.userData)) duplicates.add(result.userData)
            if (result.userData !in inFlight || result.userData in duplicates) {
                failed(IllegalStateException("backend completion identity mismatch"))
            }
        }
        for (result in results) {
            if (result.userData in inFlight) {
                complete(if (result.userData in duplicates) SelectionResult(-5, result.userData) else result)
            }
        }
    }

    private fun reap(minComplete: Int): Result<UringCompletion?> = runCatching {
        check(capacity > 0 && !closed) { "ring is not open" }
        check(!executing) { "ring is executing" }
        if (cq.isEmpty() && inFlight.isNotEmpty()) {
            executing = true
            try { settle(backend.reapCompletions(minComplete)) }
            catch (error: Throwable) { failed(error); throw error }
            finally { executing = false }
        }
        val completion = cq.removeFirstOrNull() ?: return@runCatching null
        handlers[completion.userData]?.toTypedArray()?.forEach { handler ->
            // Observers cannot turn an executed transfer into another errno or byte count.
            runCatching { handler(completion) }
        }
        completion
    }

    override fun waitCqe(): Result<UringCompletion?> = reap(1)
    override fun peekCqe(): Result<UringCompletion?> = reap(0)
    override fun cqAdvance(count: Int) { require(count >= 0) }

    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        check(!closed) { "ring is closed" }
        handlers.getOrPut(token) { mutableListOf() }.add(handler)
    }
    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        handlers[token]?.remove(handler)
        if (handlers[token].isNullOrEmpty()) handlers.remove(token)
    }

    override fun drain(): Result<Unit> {
        if (closed) return Result.success(Unit)
        if (executing) return Result.failure(IllegalStateException("ring is executing"))
        draining = true
        var error = submit().exceptionOrNull()
        if (error != null) {
            while (sq.isNotEmpty()) cq.addLast(UringCompletion(sq.removeFirst().userData, -5, 0))
        }
        if (inFlight.isNotEmpty()) {
            executing = true
            try {
                backend.cancelPending()
                settle(backend.reapCompletions(0))
                check(inFlight.isEmpty()) { "backend did not settle deferred submissions during drain" }
            } catch (drainError: Throwable) {
                failed(drainError)
                if (error == null) error = drainError
            } finally { executing = false }
        }
        return (error ?: failure)?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override fun close(): Result<Unit> {
        if (closed) return Result.success(Unit)
        if (executing) return Result.failure(IllegalStateException("ring is executing"))
        var error = drain().exceptionOrNull()
        if (inFlight.isNotEmpty()) return Result.failure(requireNotNull(error))
        if (registeredMemory != null || registeredBuffers != null) {
            unregisterBuffers().exceptionOrNull()?.let { if (error == null) error = it }
        }
        try {
            // Even after a failed submission, backend return settled its borrows. Teardown
            // must still run; a successful close also settles failed unregistration.
            backend.close()
            registeredMemory?.let { memory -> for (i in 0 until memory.size) memory[i].release() }
            registeredMemory = null
            registeredBuffers = null
            kernelRegistered = false
            closed = true
            while (cq.isNotEmpty()) reapBeforeClose()
            handlers.clear()
        } catch (closeError: Throwable) {
            if (error == null) error = closeError else if (error !== closeError) error?.addSuppressed(closeError)
        }
        return error?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun reapBeforeClose() {
        val completion = cq.removeFirst()
        handlers[completion.userData]?.toTypedArray()?.forEach { handler -> runCatching { handler(completion) } }
    }
}
