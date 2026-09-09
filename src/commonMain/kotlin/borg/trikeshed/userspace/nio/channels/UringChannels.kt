package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ChannelsImpl
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * Channel factory — backed by expect/actual [ChannelsImpl].
 */
object UringChannels {
    fun open(entries: Int = 256, ebpfPrograms: List<UringEbpfProgram> = emptyList()): UringChannel =
        UringChannel(FunctionalUringFacade(entries, openUserspaceChannelBackend(entries), ebpfPrograms = ebpfPrograms))

    fun open(scope: CoroutineScope, entries: Int = 256, ebpfPrograms: List<UringEbpfProgram> = emptyList()): UringChannel {
        require(entries > 0) { "entries must be positive" }
        requireNotNull(scope.coroutineContext[Job]) { "Uring requires an owning Job" }.ensureActive()
        val backend = openUserspaceChannelBackend(entries)
        try {
            return UringChannel(FunctionalUringFacade.create(scope, entries, backend, ebpfPrograms))
        } catch (failure: Throwable) {
            runCatching { backend.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File(ChannelsImpl.socket(domain, type, protocol))
}
