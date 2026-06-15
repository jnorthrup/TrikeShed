# HTX Client IPFS Adjacency

## Overview

The `libs/htx-client` IPFS module provides content-addressable storage, DHT routing,
and CAR archive support for the TrikeShed HTX client. It implements the IPFS adjacency
pattern: content-addressable keys (CAK) as the primary indexing layer over the BlockStore.

## Architecture

### Source Layout

```
libs/htx-client/src/
├── commonMain/kotlin/borg/trikeshed/htx/client/
│   ├── HtxElement.kt          # Base HTTP client element (open class)
│   └── ipfs/
│       ├── CidAndStore.kt     # CID, BlockStore, MemoryBlockStore, NodeId, NodeInfo, DhtTransport
│       └── package.kt         # Package documentation
├── jvmMain/kotlin/borg/trikeshed/htx/client/
│   ├── HtxElementWithIpfs.kt  # JVM extension: IPFS put/get/pin/CAR via CakManager
│   └── ipfs/
│       ├── DhtService.kt      # Kademlia DHT (160-bit NodeId, routing table, provider discovery)
│       ├── CakManager.kt      # CAK Manager: unified put/get/pin/provide/verify/importCar/exportCar
│       ├── CarIntegration.kt  # CAR v1/v2 parser + writer (CarParser, CarWriter, CarBlock, CarParseResult)
│       ├── BitswapEngine.kt   # Bitswap message encode/decode (WantBlock, WantHave, Block, Have, Cancel)
│       ├── HtxBitswapTransport.kt  # Bitswap transport over coroutine channels
│       ├── HtxDhtTransport.kt      # Stub DHT transport + factory
│       └── ...
└── jvmTest/kotlin/borg/trikeshed/htx/client/ipfs/
    └── IpfsIntegrationDemo.kt # Integration test: put/get/pin/DHT/CAR/verify round-trip
```

### Core Types (commonMain)

- **CID** — Content Identifier (SHA-256 digest). Content-equal (overrides equals/hashCode).
- **BlockStore** — Content-addressable storage interface (put/get/has).
- **MemoryBlockStore** — In-memory BlockStore with mutex-protected map.
- **NodeId** — 160-bit Kademlia node identifier with XOR distance metric.
- **NodeInfo** — DHT contact (NodeId + InetSocketAddress + lastSeen).
- **DhtTransport** — Network I/O abstraction for DHT operations.

### JVM Implementation (jvmMain)

- **CakManager** — Unified CAK interface. Extends AsyncContextElement.
  Methods: put, get, has, fetch, provide, findProviders, findClosestNodes,
  importCar, exportCar, pin, sync, verify.
- **DhtService** — Kademlia DHT with 160-bucket routing table (k=20).
  Methods: announceProvider, findProviders, findNode.
- **CarParser/CarWriter** — CAR v1/v2 archive format with varint encoding.
- **BitswapEngine** — Bitswap protocol message encoding/decoding.

### HtxElementWithIpfs

JVM-only subclass of HtxElement that adds IPFS operations:
- `ipfsPut(data): CID` — Store data, return content-addressable key
- `ipfsGet(cid): ByteArray?` — Retrieve by CID
- `ipfsPin(cid, address)` — Pin content and announce as provider
- `ipfsImportCar(data): CarParseResult` — Parse CAR archive
- `ipfsExportCar(roots, version): ByteArray` — Export blocks as CAR

## Testing

Run the integration test:

```bash
./gradlew :libs:htx-client:jvmTest --offline
```

The `IpfsIntegrationDemo` test exercises the full pipeline:
1. Content-addressable put/get with SHA-256 CID verification
2. Multiple block storage and pinning
3. DHT provider announcement and discovery
4. CAR v2 archive export/import round-trip
5. Integrity verification (CID re-hash matching)

## Build Configuration

The `:libs:ipfs` subproject uses `kmpJvm(rootMode: "none")` via the
`gradle/macros/trikeshed-lib.gradle` switch — JVM-only, no root project dependency.

The `:libs:htx-client` subproject also uses `kmpJvm(rootMode: "none")` with
additional dependencies on `:libs:tls` and `:libs:quic`.
