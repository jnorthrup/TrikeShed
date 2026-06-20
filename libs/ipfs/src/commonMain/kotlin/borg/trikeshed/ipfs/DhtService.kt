package borg.trikeshed.ipfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.collections.mutableSetOf

/**
 * Minimal in-process DHT service for prototype/testing.
 * Provides a simple provider registry keyed by CID bytes hex.
 */
class DhtService(private val transport: DhtTransport? = null) {
    private val providers: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val scope = CoroutineScope(SupervisorJob())

    fun announceProvider(cid: CID, address: String) {
        val key = hex(cid.bytes)
        providers.getOrPut(key) { mutableSetOf() }.add(address)

        transport?.let { t ->
            scope.launch {
                try {
                    t.announceProviderRemote(cid, address)
                } catch (_: Throwable) {
                    // ignore transport errors in prototype
                }
            }
        }
    }

    suspend fun findProviders(cid: CID): List<String> {
        val key = hex(cid.bytes)
        val local = providers[key]?.toList() ?: emptyList()
        if (local.isNotEmpty()) return local
        return transport?.findProvidersRemote(cid) ?: emptyList()
    }

    fun close() {
        scope.cancel()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
