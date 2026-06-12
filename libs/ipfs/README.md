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