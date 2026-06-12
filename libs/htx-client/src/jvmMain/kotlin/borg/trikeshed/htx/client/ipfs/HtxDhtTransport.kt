package borg.trikeshed.htx.client.ipfs

interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo>
}

class StubDhtTransport : DhtTransport {
    private val registry = mutableMapOf<String, MutableSet<String>>()
    
    override suspend fun announceProviderRemote(cid: CID, address: String) {
        registry.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> =
        registry[cid.hex()]?.toList() ?: emptyList()

    override suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo> = emptyList()
}

object DhtTransportFactory {
    fun createStub(): DhtTransport = StubDhtTransport()
}