# HTX Client IPFS Integration

IPFS (InterPlanetary File System) support in `htx-client` provides content-addressable storage, DHT-based provider discovery, and CAR archive import/export.

## Architecture

```
borg.trikeshed.htx.client.ipfs
├── CID              — Content Identifier (SHA-256 based)
├── BlockStore       — content-addressable storage interface
├── MemoryBlockStore — in-memory implementation
├── CakManager       — unified CAK (Content-Addressable Keys) manager
├── DhtService       — Kademlia DHT with 160-bit NodeId
├── CarParser/CarWriter — CAR (Content Addressable Repository) v1/v2
├── BitswapEngine    — block exchange protocol
└── HtxBitswapTransport / HtxDhtTransport — network transports
```

## Core Types

### CID (Content Identifier)

```kotlin
data class CID(val bytes: ByteArray) {
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        fun sha256(data: ByteArray): CID {
            val digest = MessageDigest.getInstance("SHA-256")
            return CID(digest.digest(data))
        }
    }
}
```

CIDv1 (SHA-256, 32-byte multihash) is used exclusively. The `hex()` method returns a hex string for logging/debugging.

### BlockStore

```kotlin
interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}
```

Implement `BlockStore` to connect to any storage backend (in-memory, file-system, IPFS daemon, etc.).

## CakManager

`CakManager` is the main entry point for IPFS operations.

```kotlin
class CakManager(
    private val blockStore: BlockStore,
    private val dht: DhtService? = null,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob)
```

### Construction

```kotlin
// Basic — in-memory storage only
val cak = CakManager(MemoryBlockStore())

// With DHT for provider discovery
val dht = DhtService()
val cak = CakManager(MemoryBlockStore(), dht)

// Via factory
val cak = CakManagerFactory.create(blockStore, dht)
```

### Storage Operations

```kotlin
// Store data — returns CID
val cid: CID = cak.put(data)

// Retrieve by CID
val data: ByteArray? = cak.get(cid)

// Check if CID is stored
val hasIt: Boolean = cak.has(cid)

// Delete (zero-out)
cak.delete(cid)
```

### DHT Operations

Requires a `DhtService` instance passed to the constructor.

```kotlin
// Announce this node provides the CID
suspend fun provide(cid: CID, address: String = "local")

// Find providers for a CID
suspend fun findProviders(cid: CID): List<String>

// Find k closest nodes to target CID
suspend fun findClosestNodes(target: CID, k: Int = 20): List<DhtService.NodeInfo>
```

**Note**: `findClosestNodes` converts the target `CID` to a `DhtService.NodeId` via XOR-spreading (`NodeId.fromCID(target)`).

### Pinning and Sync

```kotlin
// Pin a CID (fetch if missing, then announce provider)
suspend fun pin(cid: CID, address: String = "local")

// Sync multiple CIDs
suspend fun sync(cids: List<CID>, address: String = "local")
```

### CAR Archive Operations

CAR (Content Addressable Repository) archives bundle multiple blocks with roots.

```kotlin
// Import CAR archive — parses and stores all blocks
suspend fun importCar(data: ByteArray): CarParseResult

// Export blocks as CAR archive (v2) for given roots
suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray
```

**CAR v2 format**: Header with roots, data blocks, optional index, and a sentinel `0xFFFFFFFF` marker.

## DHT Service

Full Kademlia DHT implementation with iterative routing.

```kotlin
class DhtService(
    localNodeId: NodeId = NodeId.random(),
    transport: DhtTransport? = null,
)
```

### NodeId

160-bit Kademlia node identifier, derived from a 32-byte CID via XOR-spreading:

```kotlin
NodeId.fromCID(cid: CID): NodeId  // deterministic from CID
NodeId.random(): NodeId            // random
```

Distance is XOR-based (`xorDistance()`), bucketed into 160 buckets by bit prefix (`bucketIndex()`).

### Routing Table

- 160 buckets (k-buckets)
- `k = 20` contacts per bucket
- `findClosest(target, count)` returns contacts sorted by XOR distance

### Provider Registry

```kotlin
// Local provider announcements (in-memory)
suspend fun announceProvider(cid: CID, address: String)
suspend fun findProviders(cid: CID): List<String>

// Via transport for network announcements
// (HtxDhtTransport implements the remote protocol)
```

## CAR Format

### CAR v2 Structure

```
[4-byte magic: 0xC5D1 + 0x71]
[version: varint]
[header_length: varint]
[header: CBOR-like dag-cbor]
  roots: array of CIDs
  version: integer
[data blocks...]
  [block_length: varint]
  [cid_bytes]
  [block_data]
[index (optional)]
[0xFFFFFFFF sentinel — ends data section in v2]
[index_length: varint]
[index_data]
```

### CarParseResult

```kotlin
data class CarParseResult(
    val roots: List<CID>,      // root CIDs from header
    val blockCount: Int,        // number of blocks parsed
    val version: Int,           // 1 or 2
    val dataCid: CID,           // CID of the whole dataset (all blocks hashed)
)
```

**Note**: `CarParseResult` is defined in `commonMain` for cross-platform use. The JVM definition in `CarIntegration.kt` includes additional helper types (`CarBlock`, `CarIndex`) that are JVM-only.

## Bitswap Protocol

Bitswap is the block exchange protocol used for wanted/has communication between IPFS nodes.

### BitswapEngine

```kotlin
class BitswapEngine(
    private val blockStore: BlockStore,
    private val sendMessage: (ByteArray) -> Unit,
)
```

**Message types**: `WantBlock`, `WantHave`, `Block`, `Have`, `DontHave`, `Cancel`

```kotlin
// Request a block (blocks until received or cancelled)
suspend fun wantBlock(cid: CID): ByteArray

// Cancel an outstanding want
fun cancelWant(cid: CID)

// Handle incoming message from peer
fun handleMessage(message: BitswapEngine.BitswapMessage)
```

### HtxBitswapTransport

JVM transport adapter using coroutine channels:

```kotlin
class HtxBitswapTransport(
    scope: CoroutineScope = CoroutineScope(Job()),
) : AutoCloseable {

    val messages: ReceiveChannel<BitswapEngine.BitswapMessage>

    suspend fun send(message: BitswapEngine.BitswapMessage)
    suspend fun registerWant(cid: CID): CompletableDeferred<ByteArray>
    fun completeWant(cid: CID, data: ByteArray)
}
```

## HTX Reactor Integration

`HtxElementWithIpfs` extends `HtxElement` (HTTP client) with IPFS operations for unified HTX protocol handling:

```kotlin
class HtxElementWithIpfs(
    baseUrl: String = "http://127.0.0.1",
    private val blockStore: HtxElementWithIpfsBlockStore? = null,
    tlsConfig: TlsConfig? = null,
) : HtxElement(baseUrl, tlsConfig) {

    val cak: CakManager  // lazy-initialized

    suspend fun ipfsPut(data: ByteArray): CID
    suspend fun ipfsGet(cid: CID): ByteArray?
    suspend fun ipfsPin(cid: CID, address: String = "local")
    suspend fun ipfsImportCar(data: ByteArray): CarParseResult
    suspend fun ipfsExportCar(roots: List<CID>, version: Int = 2): ByteArray
}
```

The CAK manager acts as the IPFS protocol handler within the HTX reactor pattern, alongside HTTP/TLS handlers.

## Testing

```kotlin
class IpfsIntegrationDemo {
    @Test
    fun demoContentAddressableStorage() = runBlocking {
        val blockStore = MemoryBlockStore()
        val cak = CakManager(blockStore)
        val dht = DhtService()

        // Put and get
        val cid = cak.put("Hello, IPFS!".toByteArray())
        val retrieved = cak.get(cid)

        // Pin and DHT
        cak.pin(cid)
        val providers = dht.findProviders(cid)

        // CAR export/import
        val carData = cak.exportCar(listOf(cid))
        val result = cak.importCar(carData)
    }
}
```

## Known Limitations

1. **No network transport**: `HtxDhtTransport` is a stub; network DHT communication requires a real Kubo/go-ipfs node.
2. **CAR header parsing**: `parseRootsFromHeader` returns empty list; real CAR archives have proper CBOR-encoded DAG-CBOR headers.
3. **Bitswap**: `send()` is a stub; requires integration with a real network layer (QUIC, TCP).
4. **Performance benchmarks**: 1GB dataset benchmarks (10% overhead target vs raw Kubo) not yet implemented.
5. **CID equivalence**: `CID.equals()` defaults to `ByteArray.equals()` — use `bytes.contentEquals()` for reliable comparison across instances.

## File Locations

| File | Purpose |
|------|---------|
| `libs/htx-client/src/commonMain/.../ipfs/CidAndStore.kt` | CID, BlockStore, NodeId, CarParseResult (common) |
| `libs/htx-client/src/jvmMain/.../ipfs/CakManager.kt` | CAK Manager implementation |
| `libs/htx-client/src/jvmMain/.../ipfs/CarIntegration.kt` | CAR parser/writer, CarBlock, CarIndex |
| `libs/htx-client/src/jvmMain/.../ipfs/DhtService.kt` | Kademlia DHT implementation |
| `libs/htx-client/src/jvmMain/.../ipfs/BitswapEngine.kt` | Bitswap protocol engine |
| `libs/htx-client/src/jvmMain/.../ipfs/HtxDhtTransport.kt` | DHT transport interface + stub |
| `libs/htx-client/src/jvmMain/.../ipfs/HtxBitswapTransport.kt` | JVM Bitswap channel transport |
| `libs/htx-client/src/jvmMain/.../HtxElementWithIpfs.kt` | HTTP client + IPFS unified element |
| `libs/htx-client/src/jvmTest/.../IpfsIntegrationDemo.kt` | Integration tests |
