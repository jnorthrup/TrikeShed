package borg.trikeshed.ipfs

import borg.trikeshed.userspace.nio.channel.Channels
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.openUserspaceChannelBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NioUringDhtTransport(entries: Int = 256) : DhtTransport {
    private val facade = FunctionalUringFacade(entries, openUserspaceChannelBackend(entries))

    companion object {
        private val registry: MutableMap<String, MutableSet<String>> = mutableMapOf()
        private val mutex = Mutex()
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun announceProviderRemote(cid: CID, address: String) {
        val key = hex(cid.bytes)

        // Simulate networking with uring NOP to ensure pipeline functions
        facade.enqueue(UringOp.Companion.Submissions.nop(1L))
        facade.submit()
        facade.wait(1)

        mutex.withLock {
            registry.computeIfAbsent(key) { mutableSetOf() }.add(address)
        }
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> {
        val key = hex(cid.bytes)

        // Simulate networking with uring NOP
        facade.enqueue(UringOp.Companion.Submissions.nop(2L))
        facade.submit()
        facade.wait(1)

        return mutex.withLock { registry[key]?.toList() ?: emptyList() }
    }

    override fun close() {
        // No-op or cleanup if needed in future
    }
}
