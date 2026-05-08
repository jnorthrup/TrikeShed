# PLAN — TrikeShed IO Primacy & Choreography Consolidation

Audit date: 2026-05-07. Derived from cross-module explorer analysis of src/, libs/, and ../relaxfactory.

---

## Architecture Overview

```
commonMain IO Surface
  │
  ├─ nio/channels/           JDK NIO compatibility stubs (COMPAT SURFACE ONLY — do not wire)
  │    UringSocketChannel, UringFileChannel, UringServerSocketChannel
  │
  ├─ userspace/Channel       Operation queue (read/write/accept/connect/close)
  │    userspace/File         Handle lifecycle (open/close/isOpen/id)
  │    userspace/ChannelImpl  expect → actual (posix/linux/jvm/js/wasm)
  │         │
  │         └─ FunctionalUringFacade  Enqueues PreparedChannelOp, drains to backend on submit()
  │              │
  │              └─ PosixUserspaceChannelBackend  → PosixUringIO → Liburing → LiburingImpl
  │                   │                                           │
  │                   │                                          (posix: uring-or-pread/pwrite)
  │                   │                                          (linux: cinterop io_uring syscalls)
  │                   │
  │                   └─ publish(completion) → channelized fanout handlers
  │
  └─ context/                Coroutine choreography
       AsyncContextElement   { SupervisorJob, fanoutSubscribers, CREATED→OPEN→DRAINING→CLOSED }
       AsyncContextKey<E>    Type-safe key for Element lookup
       ConcreteElements      NioUserspaceElement, LiburingElement, FanoutDispatcherElement
```

## Data Model

```
Join<A, B>                          Pair interface (from lib/Join.kt)
  │
  └─ Series<T> = Join<Int, (Int)→T> Lazy indexed sequence (size + getter function)
       │                             Always cold — re-evaluable, no subscription state
       ├─ ByteSeries                Series<Byte> with NIO position/limit/mark + 4K byte cache
       ├─ CharSeries                Series<Char> for parsing
       └─ LongSeries<T>             Series<T> with Long index

ByteBuffer (nio/ByteBuffer.kt)       Backed by ByteArray, NIO position/limit/mark
  ↔ ByteRegion (userspace/ByteRegion.kt)  Sub-range view of ByteBuffer
       ↔ ByteSeries                       Lazy Series<Byte> over ByteRegion (zero copy)

For native IO:
  ByteArray → usePinned { addressOf() } → Long native address → Liburing.prepRead/prepWrite
```

---

## Settled (Above Par)

### Confix — Universal Parser
**`src/commonMain/.../parse/confix/Confix.kt`** — 1700 lines.

One tokenizer per syntax (JSON/YAML/CBOR/CSV), one reifier, one `ConfixElement` + `ConfixSource` with SupervisorJob CCEK orchestration. Uses `JsElement` = `Join<Twin<Int>, Series<Int>>` for bracket/brace/quote tracking.

```
ConfixElement (Pattern A, AsyncContextKey companion)
  └─ ParseScope / ParseLifecycle with CCEK SupervisorJob
       ├─ ConfixJsonTest, ConfixYamlTest, ConfixCborTest, ConfixCsvTest
       ├─ ConfixEscapeTest, ConfixYamlIndentTest, ConfixYamlMultilineTest
       └─ Isam3FileReader → ISAM file integration
```

### Couch HTX Protocol Internals
**`libs/couch/src/commonMain/.../couch/htx/`** — 11 files, ~850 lines.

Full HAProxy `htx_blk` model:
- `HtxBlock` — bit-packed info field (block type + size)
- `HtxBlockData` — sealed class discriminated union of block types
- `HtxStartLine` — HAProxy-compatible request/response start-line struct
- `HtxMessage.parseHttp1()` — HTTP/1.x text parser with trie-based method detection
- `HtxAlgebraRed` — RED-phase serialization/deserialization, CRC32 framing
- `HtxCrc32` — pure Kotlin IEEE 802.3 CRC32
- `Htx` — ticket verification via HMAC-SHA256, X25519 DH TODO
- `HtxFlags`, `HtxSlFlags` — `BitMasked<UInt>` enums
- `DHTX_REQ`/`DHTX_RES` extensions for non-HTTP block types

Plus per-platform crypto: `HtxCryptoJvm`, `HtxCryptoJs`, `HtxCryptoPosix`, `HtxCryptoWasm`.

### OpenAPI Codegen
**`libs/openapi/`** — 17 files, ~1700 lines.

```
OpenApiRawParser → OpenApiReactorResolver → OpenApiClientGenerator / OpenApiServerGenerator
                         │
                         └─ OpenApiCallPipeline (isolated SupervisorJob per call)
```

Generates `Keys.kt`, `Elements.kt`, `SupervisorJobs.kt` per API spec. `x-trikeshed-context` bindings map operations to AsyncContextKeys. Feeds generated clients:
- `libs/htx-client/generated/` — HtxGeneralApi
- `libs/cmc-generated/` — CoinMarketCap Pro
- `libs/robinhood-generated/` — Robinhood Crypto
- `libs/krak/generated/openapi/` — Kraken Spot, Futures, Embed, Custody

### MiniDuck
**`libs/miniduck/`** — 50+ files.

| File | Lines | Purpose |
|------|-------|---------|
| `MiniDuckBlockCodec.kt` | 501 | Block encode/decode, NDJSON streaming |
| `RowVecFamilies.kt` | 437 | All RowVec family types (Json, Doc, View, Yaml, Blob, Keyed, Block, Gcs, S3, Alibaba) |
| `CursorOps.kt` | 369 | Cursor algebra operations |
| `columnar/ColumnarStubs.kt` | 220 | Columnar file layout |
| `tablespace/TablespaceStubs.kt` | 145 | Tablespace WAL |
| `sql/SqlToMiniDuck.kt` | 137 | SQL-to-MiniDuck translation |
| `KernelStochastic.kt` | 135 | Stochastic kernel |

`ManifoldElement` with 3 SupervisorJob branches: `shapeBranch`, `timeBranch`, `accessBranch`.
Couch imports heavily from miniduck (`CouchDb11RowSet`, `CollectionHandle`, `MiniCursorFinanceExtensions`).

### Channel → Liburing Facade (IO Substrate)
**`src/commonMain/.../userspace/`** + **`src/posixMain/`** + **`src/linuxMain/`**

Clean handle-body mutable state, immutability at rest:

```
Opaque (at rest)              Mutable body (coarse, grepable)
════════════════              ═══════════════════════════════
Channel                       ChannelImpl → FunctionalUringFacade
  .read(file, buf, off, tok)    .pending = ArrayDeque<PreparedChannelOp>
  .submit()                     .completions = ArrayDeque<SelectionResult>
  .wait(min)                  submit():
File                            drain pending → backend.read/write/accept/connect/close
  .id, .isOpen()                → completions queue
  .close()                    wait():
                                drain completions → List<SelectionResult>
                              UserData token → CompletableDeferred map (ChannelRunner)
                              OR userData → fanout handlers map (LiburingImpl)
```

Platform wiring:
```
Posix:  PosixUringIO → Liburing (singleton) → LiburingImpl (expect)
          readAt → usePinned { addressOf } → prepRead → submit → waitCqe
          fallback: pread/pwrite if uring unavailable || ESPIPE/EINVAL
Linux:  LiburingImpl → cinterop zlinux_uring
          io_uring_queue_init → io_uring_prep_read/write → io_uring_submit
          io_uring_wait_cqe → publish(completion) → channelized fanout
macOS:  UnsupportedOperationException (POSIX fallback only)
JVM:    ServiceLoader → LiburingFacadeSpi (pluggable)
JS/Wasm: UnsupportedOperationException
```

---

## In Progress (On Par)

### htx-general-client — Multi-Transport HTTP Client
**`libs/htx-client/`** — `HtxElement` (105L, Pattern A) + generated `HtxGeneralApi`.

HTX as the version-agnostic tokenizer powers all HTTP versions. The htx-general-client
targets three transports under one Element:

```
HtxElement (htx-general-client)
  │
  ├─ TCP        → Channels.socket(SOCK_STREAM) → FunctionalUringFacade
  ├─ QUIC       → QuicElement openStream() → UDP via uring
  └─ ngSCTP     → SctpElement association → SCTP via uring
  │
  └─ IPFS       → IpfsElement DHT + content routing
       └─ context access to CouchElement (collection state, views)
```

The client serializes requests to HTX blocks via `HtxAlgebraRed`. Transport selection
is per-URI scheme: `https://` → TCP+TLS, `h3://` → QUIC, `sctp://` → ngSCTP.
The same `HtxMessage` flows through any transport without re-tokenization.

Aria2c-compatible `Aria2Switches` for download parallelism. Pluggable `HtxRequestHandler`
typealias allows swapping in mock/fake/cache transports without changing the client.

**Next:** Wire all three transports through `FunctionalUringFacade`. Connect
`QuicElement` and `SctpElement` stream handles to the channelized fanout.

### QuicElement
**`libs/quic/src/commonMain/.../quic/QuicElement.kt`** — 505 lines, Pattern A.

Protocol types are complete: `QuicVarInt`, `QuicPacketHeader` (sealed: Initial/ZeroRtt/Handshake/Retry/Short),
`QuicConfig`, `QuicConnectionId`, `QuicVersions`, `QuicErrorException`.

**Next:** Wire `connect()` to `FunctionalUringFacade` for real UDP. Map stream IDs to userData
tokens for htx-general-client fanout. Eliminate legacy `QuicChannelService` duplicate.

### Couch — HTX over Shared Reactor
**`libs/couch/`** — 93 files. HTX protocol internals (~850L) are rich. Transport layer is thin.

HTX is couch's HTTP substrate. Couch serializes CouchDB operations (DocFetch, ViewFetch,
DbCreate, etc.) as `HtxMessage` blocks, then dispatches through the same `Channel` surface
as htx-general-client. Couch uses HTX as its tokenizer; htx-general-client uses couch's
HTX parser.

**Next:** Wire `HtxBackedCouchTransport` through `FunctionalUringFacade`. Couch's
`BtrfsSandboxElement` and `BtrfsWal` → Pattern A key convergence. `CollectionHandle`
lifecycle → `ElementState` enum.

### IPFS — Same Reactor, Cross-Context Access
**`libs/ipfs/`** — 10 files, 200 lines.

`IpfsElement` (Pattern A) shares the same uring ring and can access couch context
Elements via `AsyncContextKey`:

```kotlin
val couch = currentCoroutineContext()[CouchElement.Key]
val knownPeers = couch?.collections?.get("peers")?.activeDocuments()
```

CombinedClient already composes `IpfsElement` + `HtxElement` as CCEK siblings.
IPFS DHT queries and content routing use the same `Channel` surface as HTTP traffic.

**Next:** Flesh out `DhtService` with Kademlia routing. Wire `DhtTransport` through
`FunctionalUringFacade` for UDP.

---

## Under Par / Stubbed

### TLS
**`libs/tls/`** — 5 files, 65 lines.

| File | Status |
|------|--------|
| `TlsElement.kt` | Pattern A, open/close lifecycle. Clean |
| `TlsEngine.kt` | Abstract interface (wrap/unwrap/close). Clean |
| `TlsEngineJdk.kt` | Passthrough stub — no real TLS |
| `OpenSslNative.kt` | TODO placeholder for JNR/JNI |

**Missing:** X.509 certificate store, key management, TLS handshake state machine, ALPN negotiation, session resumption. No uring wiring.

**Path:** Implement `TlsEngine` on Linux via OpenSSL/BoringSSL cinterop. JDK `SSLEngine` on JVM fallback. Wire through `FunctionalUringFacade` — encrypt/decrypt in-place via `ByteRegion` → native pin → uring write.

### IPFS
**`libs/ipfs/`** — 10 files, 200 lines.

| File | Status |
|------|--------|
| `IpfsElement.kt` | Pattern A. Clean |
| `DhtService.kt` | Interface only — Kademlia DHT skeleton |
| `DhtTransport.kt` | Interface only |
| `IpfsApi.kt` | Type definitions |
| `DiskBlockStore.kt` | JVM on-disk block storage |

**Missing:** libp2p peer connections, NAT traversal, stream multiplexing, content routing, provider records. Loopback test transport only.

**Path:** Flesh out DhtService with real Kademlia routing. Wire through uring for UDP.

### M2M Replication
**Zero implementation.** Design doc prose in `libs/couch/WAL_DESIGN.md` only.

**Path:** Couch-to-couch replication over HTX + uring + QUIC. Model after CouchDB `_replicate` protocol but with QUIC transport for multiplexed streams, 0-RTT resumption at large scale.

### Couch Transport Layer
**`libs/couch/src/commonMain/.../couch/transport/htx/`** — 4 files, 68 lines.

`HtxBackedCouchTransport` — 14 lines, prior `Reactor` parameter (removed) was unused. `HtxRequestFactoryBridge` parses HTTP text → InvocationPlan.

**Path:** Wire to `FunctionalUringFacade` via `LiburingImpl`.

### SCTP (ngsctp)
**`libs/ngsctp/`** — Pattern A choreography. No IO wiring.

**Path:** Wire through uring facade.

### Root NIO Stubs
**`src/commonMain/.../userspace/nio/channels/`** — 65+ stub files.

All `TODO("NIO common stub")`. Explicitly marked: "COMPATIBILITY SURFACE ONLY. Do not route new IO through these stubs. Implement UserspaceChannelBackend instead."

**Verdict:** Leave as JDK compatibility surface. Never wire. The real path is `userspace.Channel` → `ChannelImpl` → `FunctionalUringFacade`.

---

## Pattern Convergence — Choreography Consistency

### Current State (17 production Elements)

| Pattern | Count | Detail |
|---------|-------|--------|
| **A** — `companion object Key : AsyncContextKey<Self>()` | 9 | HtxElement, TlsElement, IpfsElement, CombinedClientElement, CombinedClientApp, DreamerElement, QuicElement, SctpElement, LiburingFacadeElement |
| **B** — `companion object Key : CoroutineContext.Key<Self>` | 5 | BtrfsSandboxElement, BtrfsWal, UserspaceBtrfsBuffer, UserspaceMemoryBuffer, QuicChannelService |
| **D** — external key object | 1 | ManifoldElement (miniduck) |

**Canonical rate: 53%** (9/17).

### Required Refactors

| # | Class | Module | From | To |
|---|-------|--------|------|-----|
| 1 | `BtrfsSandboxElement` | couch | `CoroutineContext.Key` | `AsyncContextKey` companion |
| 2 | `BtrfsWal` | couch | `CoroutineContext.Key` | `AsyncContextKey` companion |
| 3 | `UserspaceBtrfsBuffer` | tiny-btrfs | `CoroutineContext.Key` | `AsyncContextKey` companion |
| 4 | `UserspaceMemoryBuffer` | tiny-btrfs | `CoroutineContext.Key` | `AsyncContextKey` companion |
| 5 | `QuicChannelService` | quic | `CoroutineContext.Key` | Eliminate (legacy duplicate) |
| 6 | `ManifoldElement` | miniduck | External `ManifoldSupervisorKey` | `AsyncContextKey` companion |
| 7 | `UnifyCodec` | miniduck | Manual `state: ElementState` | `extends AsyncContextElement` |
| 8 | `CollectionHandle` | couch | Custom OPEN/SEALED/CLOSED | `ElementState` enum |
| 9 | `isLikelyJsFn()` | couch + viewserver | Duplicate | One canonical, Confix-aware |

Also: root `context/ConcreteElements.kt` — `NioUserspaceElement`, `LiburingElement`, `FanoutDispatcherElement` use `CoroutineContext.Key` or sealed `AsyncContextKey` hierarchy. Converge to canonical `AsyncContextKey` open class.

---

## Cross-Module Reactor Convergence

### Current State

The uring facade plumbing exists (`ChannelImpl` → `FunctionalUringFacade` → `PosixUringIO` → `Liburing` → `LiburingImpl`).
Upper-layer consumers use raw `Channel` I/O directly rather than going through the facade.
Cross-module context access between Elements is not wired.

### The Target — One Reactor, All Protocols

```
                              LiburingImpl (platform actual)
                                       │
                            io_uring_wait_cqe loop
                                       │
                           publish(userData → fanout handlers)
         ┌─────────────────────────────┼─────────────────────────────┐
         │                             │                             │
    htx-general-client           couch (CouchElement)            ipfs (IpfsElement)
    (HtxElement)                 │                               │
    │                            │                               │
    │  ┌─────────────────────────┤                               │
    │  │                         │                               │
    ├──┤  TCP                    │   HtxMessage tokenizer        │   DHT queries
    ├──┤  QUIC                   │   DocFetch/ViewFetch/DbCreate│   content routing
    └──┤  ngSCTP                 │   via same Channel facade    │   peer store
         │                       │                               │
    HTTP 1-3 + DHTX              └─ context access ←─────────────┤
    one tokenizer,                                        IpfsElement reads
    all transports                                    CouchElement collections

         ┌─────────────────────────────┼─────────────────────────────┐
         │                             │                             │
    TlsElement                   DreamerElement              CombinedClient
    (handshake wrap/unwrap)      (ISAM3 columnar reader)    (command routing)
```

HTX is the universal tokenizer. Any protocol element producing `HtxMessage` blocks
can dispatch through any transport (TCP, QUIC, SCTP) without changing its message
format. IPFS can access couch's `AsyncContextElement`s via `coroutineContext[Key]`,
enabling DHT queries that introspect couch collections and vice versa.

### Step 1: Wire CQE Loop to FanoutDispatcher

`LiburingImpl.waitCqe()` already calls `publish(completion)` which fans to handlers
by `userData`. `FanoutDispatcherElement` (already in `ConcreteElements.kt`) becomes
the single dispatch point. On CQE receipt, `publish()` resumes suspended coroutines
via `Continuation` — one loop, one ring, all protocol elements.

### Step 2: Element Registration + Cross-Context

Each protocol element, on `open()`:
- Obtains userData tokens from `LiburingElement` in context
- Registers fanout handlers: `Liburing.registerFanoutHandler(token) { completion → resume continuation }`
- Exposes itself via `AsyncContextKey` for cross-module lookup:
  ```kotlin
  // IpfsElement can reach couch:
  val couch = coroutineContext[CouchElement.Key]
  // HtxClient can reach IPFS:
  val ipfs = coroutineContext[IpfsElement.Key]
  ```

### Step 3: Generalize ChannelRunner to FanoutDispatcher

`ChannelRunner` (`UserspaceWrappers.kt`) pattern — poll completions, resume deferred
futures — generalizes to `FanoutDispatcher`. One ring, one wait loop, one dispatch
surface. All elements share the ring. No `SimpleReactor` (deleted). No separate
polling layer like epoll/kqueue.

### Step 4: HTX as Transport-Agnostic Tokenizer

HtxMessage block model is identical whether bytes arrived via TCP, QUIC stream, or
SCTP association. `HtxMessage.normalizeToHtx()` detects the wire format and routes
to the correct parser. The htx-general-client selects transport per URI scheme:

```
https://host/api  →  TCP + TlsElement.wrap/unwrap  →  Channel socket
h3://host/api    →  QuicElement.openStream()        →  Channel UDP
sctp://host/api  →  SctpElement.connect()           →  Channel SCTP
```

### Step 5: TlsEngine Over uring

`TlsEngine.wrap/unwrap` operate on `ByteRegion` → `ByteBuffer` → `ByteArray` →
native pin → `FunctionalUringFacade.write/read`. Encrypted bytes flow through the
same uring ring as application data. No separate SSL BIO.

---

## Tooling Maturation

| Tool | Purpose | When |
|------|---------|------|
| Key/Element linter | Detect Pattern B/D → flag for Pattern A. Detect manual `ElementState` outside `AsyncContextElement`. | After refactor #1-8 completed |
| Uring wiring check | Verify every IO-capable `AsyncContextElement` calls through `FunctionalUringFacade`, not raw `Channel`. | After Step 2 |
| Generated client template | OpenAPI generator already produces Pattern A. Extend to produce `FanoutDispatcher` registration boilerplate. | After Step 3 |
| Confix + HTX unification | HTX blocks are `ByteRegion` slices. Confix reifier operates on `ByteRegion` → `ByteSeries` → `Series<Char>`. Avoid intermediate `String`/`ByteArray` allocations between protocol layer and parser. | After HTX wired through uring |
| CCEK visualizer | Given coroutine context, show the full element→key→supervisor→fanout graph. Helps verify no isolated elements, no orphan supervisors. | After Step 1 |

---

## relaxfactory (Java Reference) — Gap Summary

`../relaxfactory` is the Maven/Java 8 reference being ported. It has a complete HTTP/1.1 reactor with CouchDB driver and WebSocket, using Java NIO selector loop (1xio). No io_uring, no coroutines, no Series, no choreography. TLS is stubbed. No Confix/miniduck/IPFS/m2m — these are Kotlin-original features with no Java precedent.

The port mapping from `conductor/tracks.md`:
```
rxf-couch (CouchMetaDriver, CouchServiceFactory)    → libs/couch (HTX protocol internals)
rxf-rpc (GwtRequestFactoryVisitor, RelaunchFactoryServerImpl) → libs/couch (RequestFactoryHT)
rxf-core (C10k Reactor, NIO selector)               → src/userspace (Channel, FunctionalUringFacade)
rxf-pouch (PouchDB sync)                            → (future: couch m2m replication)
```

The C10k reactor from rxf-core maps to `FunctionalUringFacade` — the Java NIO selector becomes a single io_uring ring. All remaining relaxfactory features (view dispatch, service factory, routing) map to elements in couch.
