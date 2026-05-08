package borg.trikeshed.ipfs

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Minimal in-process DHT service for prototype/testing.
 * Provides a simple provider registry keyed by CID bytes hex.
 */
class DhtService(private val transport: DhtTransport? = null) {
    private val providers: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun announceProvider(cid: CID, address: String) {
        val key = hex(cid.bytes)
        providers.computeIfAbsent(key) { mutableSetOf() }.add(address)

        transport?.let { t ->
            try {
                kotlinx.coroutines.GlobalScope.launch {
                    t.announceProviderRemote(cid, address)
                }
            } catch (_: Throwable) {
                // ignore transport errors in prototype
            }
        }
    }

    suspend fun findProviders(cid: CID): List<String> {
        val key = hex(cid.bytes)
        val local = providers[key]?.toList() ?: emptyList()
        if (local.isNotEmpty()) return local
        return transport?.findProvidersRemote(cid) ?: emptyList()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
