# libs/ipfs — Content-Addressed Storage & DHT Primitives

## Overview

This library provides:
- **CID/BlockStore abstraction** — platform-neutral content-addressed storage
- **DHT Service** — in-process Kademlia skeleton (`DhtService`, `DhtTransport`)
- **IPFS Gateway Integration** — fetch/verify via public gateways
- **Console Verification App** — manufactures DHT keys, verifies IPFS assets

## Architecture

```
libs/ipfs/
├── commonMain/
│   ├── borg/trikeshed/ipfs/
│   │   ├── IpfsApi.kt           # CID, BlockStore, NameResolver interfaces
│   │   ├── IpfsElement.kt       # Coroutine context element (Pattern A)
│   │   ├── DhtService.kt        # In-process DHT provider registry
│   │   ├── DhtTransport.kt      # Network transport interface
│   │   └── console/
│   │       └── BigBuckBunnyVerifier.kt  # ← Console app entry point
│   └── borg/trikeshed/dht/      # Kademlia primitives (NUID, RoutingTable, NetMask)
└── jvmMain/
    ├── borg/trikeshed/ipfs/
    │   ├── DiskBlockStore.kt    # Filesystem CAS
    │   └── objectstore/         # S3/GCS/Alibaba adapters
    └── jvmTest/
        └── ...                  # Loopback DHT tests
```

## Console App: Big Buck Bunny Verifier

**Entry point:** `borg.trikeshed.ipfs.console.BigBuckBunnyVerifier`

### What it does:

1. **Manufactures DHT Identity**
   - Generates 160-bit NUID (IPFS/Kademlia standard)
   - Creates RoutingTable with 160 buckets
   - Adds bootstrap peer routes

2. **Announces Provider**
   - Registers as provider for target CID
   - Uses TrikeShed's `RoutingTable.addRoute()`

3. **Fetches & Verifies**
   - Tries multiple IPFS gateways (ipfs.io, dweb.link, cloudflare-ipfs.com, pinata)
   - Downloads CAR (Content Addressable aRchive) format
   - Verifies SHA-256, size, segment structure

### Target Asset:

```
CID: bafkreicysg23kiwv34eg2d7qweipxwosdo2py4ldv42nbauguluen5v6am
Asset: Big Buck Bunny (1080p, ~147MB, 9:56 duration)
Format: Dag-CBOR wrapped video in CAR
```

### Run:

```bash
./gradlew :libs:ipfs:run -PmainClass=borg.trikeshed.ipfs.console.BigBuckBunnyVerifier
```

Or from built JAR:
```bash
java -cp libs/ipfs/build/libs/ipfs-0.1.0-SNAPSHOT.jar \
     borg.trikeshed.ipfs.console.BigBuckBunnyVerifier
```

## DHT Primitives (from `src/commonMain/kotlin/borg/trikeshed/dht/`)

| Component | Purpose |
|-----------|---------|
| `NUID<TNum>` | Network Unique ID — generic over numeric type (Byte..BigInteger) |
| `NetMask<TNum>` | Bit-length + distance metric (XOR) |
| `RoutingTable<TNum>` | Kademlia buckets (array of Join<NUID, Address>) |
| `Route<TNum>` | `Join<NUID, Address>` — peer identity + location |
| `Agent<TNum>` | Composed NUID + RoutingTable |

### Distance Metric

```kotlin
// XOR distance in BigInteger space
distance(a, b) = (a xor b).bitLength()
bucket = min(distance, bucketCount).dec()
```

## Missing / TODO

- [ ] Real Kademlia routing (iterative FIND_NODE, FIND_VALUE)
- [ ] UDP/QUIC transport via `uring` facade
- [ ] libp2p peer connections (noise/secure transport)
- [ ] Bitswap block exchange
- [ ] CAR v1/v2 parser with DAG verification
- [ ] Dag-CBOR / Dag-PB codecs
- [ ] PubSub (gossipsub) integration

## Dependencies

- `libs:miniduck` — BlockRowVec, MiniDuckBlockCodec
- AWS SDK v2 (S3), Google Cloud Storage, Alibaba OSS (jvmMain)
- kotlinx-coroutines (structured concurrency for CCEK fanout)
## Build Configuration

The `:libs:ipfs` project uses `kmpJvm(rootMode: "api")` in `gradle/macros/trikeshed-lib.gradle`.
This means it depends on the root TrikeShed project for DHT types (NUID, NetMask, RoutingTable, Agent, BitOps).

The root project is the canonical source for DHT primitives. The `:libs:ipfs` project adds:
- IPFS-specific CID/BlockStore abstractions (`IpfsApi.kt`)
- DHT service with provider announce/find (`DhtService.kt`)
- Bitswap block exchange protocol engine (`bitswap/BitswapEngine.kt`)
- Transport interfaces and loopback implementation (`DhtTransport.kt`, `NioUringDhtTransport.kt`)
- Disk-backed block store (`jvmMain/DiskBlockStore.kt`)

### Excluded WIP Files

The following files were moved to `src/excluded/` because they reference APIs that don't exist yet:
- `car/CarParser.kt` — CAR archive parser (uses buildByteArray DSL + Long/Int type mismatches)
- `codec/DagCodecs.kt` — Dag-CBOR codec (uses java.math.BigInteger instead of borg.trikeshed.num.BigInt)
- `console/BigBuckBunnyVerifier.kt` — Demo console app (depends on CarParser/DagCodecs)

These will be re-enabled when the kotlinx-io buildByteArray DSL is added or the code is rewritten.

### Build Commands

```bash
./gradlew :libs:ipfs:jvmTest --offline --no-configuration-cache
# 8 tests, 0 failures, 0 errors
```
