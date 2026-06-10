package borg.trikeshed.ipfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LoopbackDhtTransport : DhtTransport {
    companion object {
        private val registry: MutableMap<String, MutableSet<String>> = mutableMapOf()
        private val mutex = Mutex()
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun announceProviderRemote(cid: CID, address: String) {
        val key = hex(cid.bytes)
        mutex.withLock {
            registry.computeIfAbsent(key) { mutableSetOf() }.add(address)
        }
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> {
        val key = hex(cid.bytes)
        return mutex.withLock { registry[key]?.toList() ?: emptyList() }
    }

    override fun close() {}
}
