package borg.trikeshed.ipfs

/**
 * Minimal in-process DHT service for prototype/testing.
 * Provides a simple provider registry keyed by CID bytes hex.
 */
class DhtService {
    private val providers: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun announceProvider(cid: CID, address: String) {
        val key = hex(cid.bytes)
        providers.computeIfAbsent(key) { mutableSetOf() }.add(address)
    }

    fun findProviders(cid: CID): List<String> = providers[hex(cid.bytes)]?.toList() ?: emptyList()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
