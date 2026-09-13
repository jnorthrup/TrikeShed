package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Unified io_uring-style submission queue.
 *
 * Two APIs coexist:
 * 1. **Typed** — [read], [write], [accept], [connect], [close], [sync], [truncate] + [submit]/[wait]/[peek]
 * 2. **Unified** — [enqueue] any [UringSubmission], then [submit]/[wait]/[peek]
 *
 * The typed API is sugar that creates [UringSubmission] internally.
 * New code should use the unified path exclusively.
 *
 * Lifecycle: every channel is an [AsyncContextElement]. A channel built with a
 * parent Job rides the caller's supervision (the scoped facade owns its
 * consumer under that Job); a scopeless channel owns its own supervisor, so
 * [drain] settles in-flight work through the facade instead of abandoning it.
 */
class UringChannel private constructor(
    private val facade: FunctionalUringFacade,
    parentJob: Job?,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    constructor(facade: FunctionalUringFacade) : this(facade, null)

    companion object Key : AsyncContextKey<UringChannel>() {
        /** Scoped channel: supervision flows from the caller's owning Job. */
        fun open(scope: CoroutineScope, facade: FunctionalUringFacade): UringChannel =
            UringChannel(facade, scope.coroutineContext[Job])
    }

    override val key: CoroutineContext.Key<*> get() = Key

    fun enqueue(submission: UringSubmission) = facade.enqueue(submission)

    fun read(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        facade.read(file.impl, buffer, offset, userData)

    fun write(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        facade.write(file.impl, buffer, offset, userData)

    fun accept(file: File, userData: Long) =
        facade.accept(file.impl, userData)

    fun connect(file: File, address: String, port: Int, userData: Long) =
        facade.connect(file.impl, address, port, userData)

    fun close(file: File, userData: Long) =
        facade.close(file.impl, userData)

    fun sync(file: File, userData: Long, metaData: Boolean) =
        facade.sync(file.impl, userData, metaData)

    fun truncate(file: File, size: Long, userData: Long) =
        facade.truncate(file.impl, size, userData)

    val capabilities: Long get() = facade.capabilities
    val nativeCapabilities: Long get() = facade.nativeCapabilities
    val availability: String get() = facade.availability
    fun closeNow() = facade.closeNow()

    /** FSM drain: stop admission, settle in-flight CQEs, join, then CLOSED. */
    override suspend fun drain() {
        open()
        facade.drain()
        super.drain()
    }

    override suspend fun close() {
        open()
        facade.close()
        super.close()
    }

    fun submit(): Int = facade.submit()

    suspend fun submitAwait() = facade.submitAwait()

    suspend fun batchEnqueue(submissions: borg.trikeshed.lib.Series<borg.trikeshed.userspace.UringOp.Companion.UringSubmission>) =
        facade.batchEnqueue(submissions)

    fun wait(minComplete: Int = 1): List<SelectionResult> = facade.wait(minComplete)

    fun peek(): List<SelectionResult> = facade.peek()
}
