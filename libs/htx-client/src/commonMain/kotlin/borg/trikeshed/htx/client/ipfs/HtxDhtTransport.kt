package borg.trikeshed.htx.client.ipfs

/**
 * DHT Transport interface — abstracts network I/O for DHT operations.
 */
interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo>
}

/** Stub transport for testing without network. */
class StubDhtTransport : DhtTransport {
    private val registry = mutableMapOf<String, MutableSet<String>>()
    
    override suspend fun announceProviderRemote(cid: CID, address: String) {
        registry.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> =
        registry[cid.hex()]?.toList() ?: emptyList()

    override suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo> = emptyList()
}

/** Factory for DhtTransport. */
object DhtTransportFactory {
    fun createStub(): DhtTransport = StubDhtTransport()
}