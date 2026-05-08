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

### QuicElement
**`libs/quic/src/commonMain/.../quic/QuicElement.kt`** — 505 lines, Pattern A.

Protocol types are complete:
- `QuicVarInt` — RFC 9000 variable-length integer codec (1/2/4/8 byte)
- `QuicPacketHeader` — sealed class with Initial, ZeroRtt, Handshake, Retry, Short
- `QuicConfig`, `QuicConnectionId`, `QuicVersions`
- `QuicErrorException` — full sealed error hierarchy (Connection, Stream, Protocol, Transport, FlowControl, CongestionControl)

Transport is in-memory only:
- `openStream()` → `Channel<ByteArray>` pairs
- `connect(host, port)` → stub calling `openStream()`
- `QuicChannelService` — legacy duplicate with `ioUringFd: Int = -1`, `xdpProg: String?` placeholders

**Next:** Wire to `FunctionalUringFacade` for real UDP. Eliminate `QuicChannelService` duplicate. Stream IDs → userData tokens for fanout.

### HtxClient
**`libs/htx-client/src/commonMain/.../HtxElement.kt`** — 105 lines, Pattern A.

Aria2c-compatible `Aria2Switches` data class. Pluggable `HtxRequestHandler` typealias. Generated client code for `HtxGeneralApi`. Serialization via `Cbor`.

**Next:** Wire HtxRequestHandler through `FunctionalUringFacade` instead of raw `Channel` I/O.

### DreamerElement
**`libs/dreamer-kmm/src/commonMain/.../dreamer/engine/DreamerElement.kt`** — Pattern A.

ISAM3 columnar reader via `platformSeekHandle()` → `openColumnarIsam()`. Cursor-based row access. `Genome` configuration. `HtxTransport` uses raw `Channel` I/O (socket → connect → write → read → close).

**Next:** Push `HtxTransport` I/O through `FunctionalUringFacade` path. The dreamer-kmm `HtxTransport` already implements `SelectableChannelOps` — bridge to facade.

### CombinedClient
**`libs/combined-client/src/commonMain/.../CombinedClientElement.kt`** — Pattern A.

String-based command routing: `"http"`, `"htmx"`, `"reactor"`, `"ipfs"`. Composes `HtxElement` + `IpfsElement` together.

**Next:** Unified uring path for all command transports. Single ring, single CQE loop, fanout by command token.

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

## Uring Primacy — The One Convergence

### Current State

The uring facade plumbing exists (`ChannelImpl` → `FunctionalUringFacade` → `PosixUringIO` → `Liburing` → `LiburingImpl`). **No upper-layer consumer calls through it.** Every protocol element uses raw `Channel` I/O or is stubbed.

### The Target

```
                         LiburingImpl (platform actual)
                              │
                   io_uring_wait_cqe loop (Linux cinterop)
                              │
                  publish(userData → fanout handlers)
                    ┌─────────┼─────────┐──────────┐───────────┐
              QuicElement   HtxElement  TlsElement  IpfsElement  SctpElement
              (stream mux)  (HTTP/1.1)  (handshake) (DHT UDP)    (SCTP conn)
                    │            │           │           │            │
              stream 0..N   request     encrypt/    DHT query    association
                            dispatch     decrypt     /response
```

### Step 1: Wire CQE Loop to FanoutDispatcher

`LiburingImpl.waitCqe()` already calls `publish(completion)` which fans to handlers by `userData`. `FanoutDispatcherElement` (already in `ConcreteElements.kt`) becomes the dispatch point. `io_uring_wait_cqe` blocks; on CQE receipt, `publish()` resumes suspended coroutines via `Continuation` instead of just callback lambdas.

### Step 2: Element Registration

Each protocol element, on `open()`:
- Obtains userData tokens from `LiburingElement` in context
- Registers fanout handlers: `Liburing.registerFanoutHandler(token) { completion → resume continuation }`
- Uses `AsyncContextKey` for type-safe context lookup

### Step 3: Generalize ChannelRunner to FanoutDispatcher

`ChannelRunner` (`UserspaceWrappers.kt`) pattern — poll completions, resume deferred futures — generalizes to `FanoutDispatcher`:
- One ring, one wait loop, one dispatch surface
- All elements share the ring
- No `SimpleReactor` (deleted). No separate polling layer like epoll/kqueue

### Step 4: TlsEngine Over uring

`TlsEngine.wrap(plaintext: ByteRegion): ByteRegion` and `unwrap(ciphertext: ByteRegion): ByteRegion` operate on `ByteRegion` → `ByteBuffer` → `ByteArray` → native pin → `FunctionalUringFacade.write/read`. Encrypted bytes flow through the same uring ring as application data. No separate SSL BIO.

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
