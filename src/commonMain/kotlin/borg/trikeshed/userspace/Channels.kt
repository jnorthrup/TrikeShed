package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram

/**
 * Channel factory — backed by expect/actual [ChannelsImpl].
 */
object Channels {
    fun open(scope: kotlinx.coroutines.CoroutineScope, entries: Int = 256,
             ebpfPrograms: List<UringEbpfProgram> = emptyList()): Channel =
        Channel(FunctionalUringFacade.create(scope, entries, ebpfPrograms = ebpfPrograms))

    fun open(entries: Int = 256, ebpfPrograms: List<UringEbpfProgram> = emptyList()): Channel =
        Channel(FunctionalUringFacade(entries, openUserspaceChannelBackend(entries), ebpfPrograms = ebpfPrograms))

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File.fromImpl(ChannelsImpl.socket(domain, type, protocol))
}

/**
 * Unified io_uring-style submission queue.
 *
 * Two APIs coexist:
 * 1. **Typed** — [read], [write], [accept], [connect], [close], [sync], [truncate], [map] + [submit]/[wait]/[peek]
 * 2. **Unified** — [enqueue] any [UringSubmission], then [submit]/[wait]/[peek]
 *
 * The typed API is sugar that creates [UringSubmission] internally.
 * New code should use the unified path exclusively.
 */
class Channel(
    private val facade: FunctionalUringFacade,
) {
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
    fun enqueue(submission: UringOp.Companion.UringSubmission) = facade.enqueue(submission)
    suspend fun drain() = facade.drain()
    suspend fun close() = facade.close()
    fun closeNow() = facade.closeNow()

    fun submit(): Int = facade.submit()

    suspend fun submitAwait() = facade.submitAwait()

    suspend fun batchEnqueue(submissions: borg.trikeshed.lib.Series<UringOp.Companion.UringSubmission>) =
        facade.batchEnqueue(submissions)

    fun wait(minComplete: Int = 1): List<SelectionResult> = facade.wait(minComplete)

    fun peek(): List<SelectionResult> = facade.peek()
}
