package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ChannelsImpl
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import borg.trikeshed.userspace.containment.ContainmentPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive

/**
 * Channel factory — backed by expect/actual [ChannelsImpl].
 */
object UringChannels {
    /**
     * Scopeless channel: the channel itself is the Element and owns the
     * supervisor its facade runs under, so drain() settles in-flight work.
     */
    fun open(entries: Int = 256, ebpfPrograms: List<UringEbpfProgram> = emptyList()): UringChannel {
        require(entries > 0) { "entries must be positive" }
        val backend = openUserspaceChannelBackend(entries)
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        return try {
            UringChannel.open(scope, FunctionalUringFacade.create(scope, entries, backend, ebpfPrograms))
        } catch (failure: Throwable) {
            owner.complete()
            runCatching { backend.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    fun open(scope: CoroutineScope, entries: Int = 256, ebpfPrograms: List<UringEbpfProgram> = emptyList(),
             containmentPolicy: ContainmentPolicy = ContainmentPolicy.MAXIMUM): UringChannel {
        require(entries > 0) { "entries must be positive" }
        requireNotNull(scope.coroutineContext[Job]) { "Uring requires an owning Job" }.ensureActive()
        val raw = openUserspaceChannelBackend(entries)
        val backend = scope.coroutineContext[borg.trikeshed.userspace.UringTrace]?.let {
            borg.trikeshed.userspace.UringTraceBackend(raw, it)
        } ?: raw
        try {
            return UringChannel(FunctionalUringFacade.create(scope, entries, backend, ebpfPrograms, containmentPolicy))
        } catch (failure: Throwable) {
            runCatching { backend.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File(ChannelsImpl.socket(domain, type, protocol))
}
