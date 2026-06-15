package borg.trikeshed.htx.client.ipfs

/**
 * Stub DHT Transport — in-process registry for testing and local-only operation.
 * DhtTransport interface is in commonMain (CidAndStore.kt).
 */
class StubDhtTransport : DhtTransport {
    private val registry = mutableMapOf<String, MutableSet<String>>()

    override suspend fun announceProviderRemote(cid: CID, address: String) {
        registry.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> =
        registry[cid.hex()]?.toList() ?: emptyList()

    override suspend fun findNodeRemote(target: NodeId): List<NodeInfo> = emptyList()
}

object DhtTransportFactory {
    fun createStub(): DhtTransport = StubDhtTransport()
}
