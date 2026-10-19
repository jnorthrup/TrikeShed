package borg.trikeshed.userspace

import borg.trikeshed.collections.LongTable
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Identities one ring may hold between prep and reap. Deferred watches (one or two per idle socket) are bounded by
 * this, not by the SQ depth: two directions per descriptor at Linux's default nr_open (1,048,576).
 */
const val URING_IN_FLIGHT_LIMIT: Int = 1 shl 21

/** Identity states besides an in-flight [EmulatedRing] record. */
private enum class RingMark { STAGED, SETTLED, SEEN, REPEATED }

/** Common bounded SQ/CQ coordinator. Platform backends execute effects only.
 * Synchronous callers serialize access; coroutine callers use FunctionalUringFacade.
 *
 * Two bounds, kept apart. The opened entries bound admission: prep is refused while the SQ and the unreaped CQ
 * together hold that many, so one submit dispatches at most entries SQEs. [inFlightLimit] bounds identities: one
 * table holds every userData from prep until its CQE is reaped (staged + in flight + unreaped <= [inFlightLimit]),
 * so a ring of 256 entries carries 64,000 deferred watches. Deferred work completes when it completes, so more than
 * entries CQEs, up to [inFlightLimit], can wait unreaped; like a kernel ring's NODROP overflow, that refuses new
 * submissions until the caller reaps. Admission, duplicate rejection and settlement are O(1) whatever the number of
 * deferred watches.
 */
internal class EmulatedRing(
    private val backend: UserspaceChannelBackend,
    private val inFlightLimit: Int = URING_IN_FLIGHT_LIMIT,
) : LiburingFacade {
    /** An SQE between dispatch and its terminal CQE, with the effect the backend executes for it. */
    private class Flight(val submission: UringSubmission, val effect: UringSubmission)
    private val sq = ArrayDeque<UringSubmission>()
    private val cq = ArrayDeque<UringCompletion>()
    /** userData to [RingMark.STAGED], a [Flight], or [RingMark.SETTLED]. */
    private val identities = LongTable<Any>()
    private var flights = 0
    /** Duplicate detection within one backend result list; emptied before [settle] returns. */
    private val seen = LongTable<RingMark>()
    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()
    private var capacity = 0
    private var draining = false
    private var closed = false
    private var executing = false
    private var failure: Throwable? = null
    /** Staging for fixed transfers the backend cannot address in place: recycled, never cleared. */
    private var blocks = EmulatedBlocks(0)
    internal val isClosed: Boolean get() = closed

    /** The separator between renameat's two paths, by code point so the source stays ASCII. */
    private val nul: String = 0.toChar().toString()

    private fun stage(submission: UringSubmission): Result<Unit> = runCatching {
        check(capacity > 0 && !closed && !draining) { "ring is not accepting submissions" }
        check(!executing) { "ring is executing" }
        failure?.let { throw it }
        check(sq.size + cq.size < capacity) { "submission queue full" }
        check(identities.size < inFlightLimit) { "in-flight limit $inFlightLimit reached" }
        require(submission.len >= 0) { "negative transfer length" }
        require(submission.userData !in identities) { "duplicate outstanding userData" }
        identities.put(submission.userData, RingMark.STAGED)
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
        blocks = EmulatedBlocks(entries)
    }

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.READ, fd, bufAddress, len, offset, 0, userData))

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.WRITE, fd, bufAddress, len, offset, 0, userData))

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.ACCEPT, fd, 0L, 0, 0L, 0, userData))

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.CONNECT, fd, addrPtr, addrLen, 0L, 0, userData))

    override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.SOCKET, domain, 0L, protocol, type.toLong(), 0, userData))

    override fun prepBind(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.BIND, fd, addrPtr, addrLen, 0L, 0, userData))

    override fun prepListen(fd: Int, backlog: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.LISTEN, fd, 0L, backlog, 0L, 0, userData))

    override fun prepPollAdd(fd: Int, pollMask: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.POLL_ADD, fd, 0L, 0, 0L, userData = userData, operationFlags = pollMask))

    override fun prepPollRemove(targetUserData: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.POLL_REMOVE, -1, targetUserData, 0, 0L, 0, userData))

    override fun prepSend(fd: Int, bufAddress: Long, len: Int, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.SEND, fd, bufAddress, len, 0L, userData = userData, operationFlags = flags))

    override fun prepRecv(fd: Int, bufAddress: Long, len: Int, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.RECV, fd, bufAddress, len, 0L, userData = userData, operationFlags = flags))

    override fun prepCancel(targetUserData: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.ASYNC_CANCEL, -1, targetUserData, 0, 0L, 0, userData))

    override fun prepShutdown(fd: Int, how: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.SHUTDOWN, fd, 0L, how, 0L, 0, userData))

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
        runCatching { UringOp.Companion.Submissions.renameat(oldDfd, oldPath, newDfd, newPath, flags, userData) }.mapCatching { stage(it).getOrThrow() }

    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        runCatching { UringOp.Companion.Submissions.unlinkat(dfd, path, flags, userData) }.mapCatching { stage(it).getOrThrow() }

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
        var executingFixed = false
        identities.forEach { _, state -> if (state is Flight && state.submission.bufferIndex >= 0) executingFixed = true }
        check(!executingFixed) { "registered buffers still have executing requests" }
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
        runCatching { UringOp.Companion.Submissions.mkdirat(dfd, path, mode, userData) }.mapCatching { stage(it).getOrThrow() }

    override fun submit(): Result<Int> = runCatching {
        check(capacity > 0 && !closed && !executing) { "ring is not available for submission" }
        failure?.let { throw it }
        if (sq.isEmpty()) return@runCatching 0
        val batch = Array(sq.size) { sq.removeFirst() }
        var dispatched = false
        executing = true
        try {
            // List is the platform compatibility boundary: the effects array itself, viewed, not copied.
            val effects = Array(batch.size) { index ->
                val submission = batch[index]
                if (submission.bufferIndex < 0 || (kernelRegistered && submission.memory != null &&
                        backend.supportsFixedBuffer(submission.fd))) submission
                else {
                    // Staged through a recycled block, not a new array: the write fills [0, len) before dispatch, and
                    // complete() copies a read out only up to its completion, so the block's earlier bytes stay here.
                    val memory = submission.memory
                    val buffer = submission.buffer ?: ByteBuffer.wrap(blocks.acquire(submission.len), 0, submission.len).also {
                        if (submission.opcode == UringOp.WRITE_FIXED) {
                            requireNotNull(memory).read(0, it.array(), 0, submission.len)
                        }
                    }
                    submission.copy(opcode = if (submission.opcode == UringOp.READ_FIXED) UringOp.READ else UringOp.WRITE,
                        buffer = buffer, bufferIndex = -1, addr = 0L)
                }
            }
            for (i in batch.indices) identities.put(batch[i].userData, Flight(batch[i], effects[i]))
            flights += batch.size
            dispatched = true
            val results = backend.submitBatch(effects.asList())
            settle(results)
            val deferredMask = backend.deferredCapabilities
            for (submission in batch) check(!inFlight(submission.userData) || deferred(submission, deferredMask)) {
                "backend did not settle every submission"
            }
            failure?.let { throw it }
            batch.size
        } catch (error: Throwable) {
            // A failed call settles its synchronous borrows. Deferred work remains owned until
            // a terminal CQE arrives, including cancellation during drain. Never retry effects.
            val deferredMask = backend.deferredCapabilities
            for (submission in batch) {
                if (!dispatched) settled(UringCompletion(submission.userData, -5, 0))
                else if (inFlight(submission.userData) && !deferred(submission, deferredMask)) {
                    complete(SelectionResult(-5, submission.userData))
                }
            }
            failed(error)
            throw error
        } finally {
            executing = false
        }
    }

    private fun inFlight(userData: Long): Boolean = identities[userData] is Flight

    private fun deferred(submission: UringSubmission, deferredMask: Long = backend.deferredCapabilities): Boolean =
        deferredMask and ((identities[submission.userData] as? Flight)?.effect?.opcode ?: submission.opcode).mask != 0L

    /** A terminal CQE enters the CQ; its identity stays held until the CQE is reaped. */
    private fun settled(completion: UringCompletion) {
        identities.put(completion.userData, RingMark.SETTLED)
        cq.addLast(completion)
    }

    private fun failed(error: Throwable) {
        val previous = failure
        if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
    }

    private fun complete(result: SelectionResult) {
        val flight = requireNotNull(identities[result.userData] as? Flight)
        identities.put(result.userData, RingMark.SETTLED)
        flights--
        val submission = flight.submission
        val effect = flight.effect
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
        } finally {
            if (submission.buffer == null && effect !== submission) effect.buffer?.let { blocks.release(it.array()) }
        }
        cq.addLast(UringCompletion(result.userData, res, 0))
    }

    private fun settle(results: List<SelectionResult>) {
        if (results.isEmpty()) return
        for (result in results) {
            val repeated = seen.put(result.userData, RingMark.SEEN) != null
            if (repeated) seen.put(result.userData, RingMark.REPEATED)
            if (!inFlight(result.userData) || repeated) {
                failed(IllegalStateException("backend completion identity mismatch"))
            }
        }
        for (result in results) {
            if (inFlight(result.userData)) {
                complete(if (seen[result.userData] === RingMark.REPEATED) SelectionResult(-5, result.userData) else result)
            }
        }
        for (result in results) seen.remove(result.userData)
    }

    private fun reap(minComplete: Int, timeoutNanos: Long = -1L): Result<UringCompletion?> = runCatching {
        check(capacity > 0 && !closed) { "ring is not open" }
        check(!executing) { "ring is executing" }
        if (cq.isEmpty() && flights > 0) {
            executing = true
            try {
                settle(if (timeoutNanos < 0L) backend.reapCompletions(minComplete) else backend.reapCompletions(minComplete, timeoutNanos))
            }
            catch (error: Throwable) { failed(error); throw error }
            finally { executing = false }
        }
        val completion = cq.removeFirstOrNull() ?: return@runCatching null
        identities.remove(completion.userData)
        if (handlers.isNotEmpty()) handlers[completion.userData]?.toTypedArray()?.forEach { handler ->
            // Observers cannot turn an executed transfer into another errno or byte count.
            runCatching { handler(completion) }
        }
        completion
    }

    override fun waitCqe(): Result<UringCompletion?> = reap(1)
    override fun peekCqe(): Result<UringCompletion?> = reap(0)
    override fun waitCqeTimeout(timeoutNanos: Long): Result<UringCompletion?> =
        if (timeoutNanos < 0L) Result.failure(IllegalArgumentException("negative timeout")) else reap(1, timeoutNanos)

    override fun counters(): Map<String, Long> = linkedMapOf(
        "ring.sq" to sq.size.toLong(), "ring.flights" to flights.toLong(), "ring.cq" to cq.size.toLong(),
        "ring.identities" to identities.size.toLong(),
    ) + backend.counters()
    override val availability: String get() = backend.availability
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
            while (sq.isNotEmpty()) settled(UringCompletion(sq.removeFirst().userData, -5, 0))
        }
        if (flights > 0) {
            executing = true
            try {
                backend.cancelPending()
                settle(backend.reapCompletions(0))
                check(flights == 0) { "backend did not settle deferred submissions during drain" }
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
        if (flights > 0) return Result.failure(requireNotNull(error))
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
        identities.remove(completion.userData)
        handlers[completion.userData]?.toTypedArray()?.forEach { handler -> runCatching { handler(completion) } }
    }
}
