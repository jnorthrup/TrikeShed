package borg.trikeshed.ipfs

/**
 * Optional network transport hooks for DHT operations. Implementations may be
 * networked (UDP/TCP) or no-op for in-process testing.
 */
interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
}
