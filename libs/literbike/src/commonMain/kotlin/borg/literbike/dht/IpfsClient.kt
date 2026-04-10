package borg.literbike.dht

/**
 * IPFS Block and Storage interfaces.
 * Ported from literbike/src/dht/client.rs (IpfsBlock, IpfsStorage, InMemoryStorage).
 */

/**
 * IPFS link to another block.
 */
data class IpfsLink(
    val name: String,
    val cid: CID,
    val size: Long
)

/**
 * IPFS Block with content-addressed data.
 */
data class IpfsBlock(
    val cid: CID,
    val data: ByteArray,
    val links: List<IpfsLink> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpfsBlock) return false
        return cid == other.cid && data.contentEquals(other.data) && links == other.links
    }

    override fun hashCode(): Int = cid.hashCode() xor data.contentHashCode()
}

/**
 * IPFS Storage interface - trait for block persistence.
 * Port of IpfsStorage trait.
 */
interface IpfsStorage {
    fun putBlock(block: IpfsBlock)
    fun getBlock(cid: CID): IpfsBlock?
    fun hasBlock(cid: CID): Boolean
    fun deleteBlock(cid: CID): Boolean
    fun pin(cid: CID)
    fun unpin(cid: CID): Boolean
    fun isPinned(cid: CID): Boolean
    fun listPins(): List<CID>
}

/**
 * In-memory storage implementation for IPFS blocks.
 * Port of InMemoryStorage.
 */
class InMemoryStorage : IpfsStorage {
    private val blocks = mutableMapOf<String, IpfsBlock>()
    private val pins = mutableSetOf<CID>()

    override fun putBlock(block: IpfsBlock) {
        blocks[block.cid.encode()] = block
    }

    override fun getBlock(cid: CID): IpfsBlock? = blocks[cid.encode()]

    override fun hasBlock(cid: CID): Boolean = blocks.containsKey(cid.encode())

    override fun deleteBlock(cid: CID): Boolean {
        if (isPinned(cid)) return false
        return blocks.remove(cid.encode()) != null
    }

    override fun pin(cid: CID) {
        pins.add(cid)
    }

    override fun unpin(cid: CID): Boolean = pins.remove(cid)

    override fun isPinned(cid: CID): Boolean = pins.contains(cid)

    override fun listPins(): List<CID> = pins.toList()
}

/**
 * IPFS Client for DHT operations.
 * Port of IpfsClient from client.rs.
 */
class IpfsClient(
    private val localPeerId: PeerId,
    private val storage: IpfsStorage,
    private val routingTable: RoutingTable
) {
    constructor(localPeerId: PeerId, storage: IpfsStorage) : this(
        localPeerId,
        storage,
        RoutingTable(localPeerId, 20)
    )

    fun getRoutingTable(): RoutingTable = routingTable

    /** Add content to IPFS */
    suspend fun add(data: ByteArray): CID {
        val hash = computeHash(data)
        val multihash = Multihash(HashType.Sha256, hash)
        val cid = CID(1, Codec.Raw, multihash)

        val block = IpfsBlock(cid, data, emptyList())
        storage.putBlock(block)
        announceBlock(cid)

        return cid
    }

    /** Get content from IPFS */
    suspend fun get(cid: CID): ByteArray? {
        // Check local storage
        storage.getBlock(cid)?.let { return it.data }

        // Find providers via DHT
        val providers = findProviders(cid)
        if (providers.isEmpty()) return null

        // Request block from first provider (stubbed)
        val provider = providers[0]
        val block = requestBlock(provider, cid)
        if (block != null && verifyBlock(block)) {
            storage.putBlock(block)
            return block.data
        }

        return null
    }

    /** DHT: announce block availability */
    private suspend fun announceBlock(cid: CID) {
        // In real implementation, would announce to DHT network
    }

    /** DHT: find providers for a CID */
    private suspend fun findProviders(cid: CID): List<PeerInfo> {
        // In real implementation, would query DHT
        return emptyList()
    }

    /** Request block from peer via QUIC (stubbed) */
    private suspend fun requestBlock(provider: PeerInfo, cid: CID): IpfsBlock? {
        // Would use QUIC to request block from peer
        return null
    }

    /** Verify block integrity */
    private fun verifyBlock(block: IpfsBlock): Boolean {
        val hash = computeHash(block.data)
        return hash.contentEquals(block.cid.multihash.digest)
    }

    /** Compute SHA256 hash */
    private fun computeHash(data: ByteArray): ByteArray {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data)
    }
}
