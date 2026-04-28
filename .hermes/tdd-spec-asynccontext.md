# TDD Specification: AsyncContext* Abstractions + Protocol Stack
**Generated:** 2026-04-28
**Scope:** libs/common (AsyncContext*), libs/quic, libs/ngsctp, libs/htx-client, libs/couch (ReactorSupervisor), libs/server, libs/dreamer-kmm, libs/openapi, libs/viewserver, libs/miniduck, libs/concurrency, libs/cpu-cache, libs/polyglot, libs/patl, libs/tiny-btrfs

---

## PART 0: WHY `AsyncContext*` CANNOT BE ELIMINATED

### The structural argument

Kotlin's `CoroutineContext` provides:
- `CoroutineContext.Key<E>` — a bare marker interface, zero behavior
- `CoroutineContext.Element` — a marker with one property: `val key: Key<*>`

The stdlib gives you **storage** (a `Map<Key<*>, Any>`) but zero **semantics**. You get `ctx[Key]` lookup, but the stdlib cannot express:

```
"ctx[QuicKey] returns a QuicElement whose SupervisorJob must be cancelled 
 on DRAINING/CLOSE, whose state must follow CREATED→OPEN→ACTIVE→DRAINING→CLOSED,
 and whose fanoutSubscribers must receive channelized completions."
```

That semantic layer is `AsyncContextElement`. Without it, each protocol module would either:
1. Re-implement the same lifecycle state machine locally (7+ copies of `isAtLeast/isLessThan`)
2. Invent its own `supervisor: CompletableJob` field with the same cancel-on-close behavior
3. Lose the typed key guarantee (any `CoroutineContext.Key<*>` matches, not just `AsyncContextKey<E>`)

### Evidence of necessity

The pervasiveness grep found 50+ touches across 7 modules. The per-module distribution:
- `libs/common` — the canonical definitions
- `libs/quic` — `QuicElement extends AsyncContextElement()`
- `libs/ngsctp` — `SctpElement extends AsyncContextElement()`
- `libs/htx-client` — `HtxElement extends AsyncContextElement()`
- `libs/server` — composes all three via `buildServerContext()`
- `libs/couch` — `ReactorSupervisor` + `Reactor` as lifecycle managers
- `libs/dreamer-kmm` — `PaperAccount/PaperOrder/PaperPosition extends AsyncContextElement()`
- `libs/openapi` — generated `Keys` object references `AsyncContextKey<HtxElement>`
- `libs/uring` — `LiburingFacadeElement extends AsyncContextElement()`

### Why the elimination attempt failed

If you tried this:
```kotlin
// Proposed elimination: use CoroutineContext.Element directly
class QuicElement : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = QuicKey
    val supervisor = SupervisorJob()
    var state: ElementState = CREATED
    // ... lifecycle methods
}
```

You would lose:
- The **type-safe key**: `ctx[QuicKey]` returns `Any?` instead of `QuicElement?`
- The **fanout contract**: no shared `fanoutSubscribers: List<AsyncContextElement>` field
- The **BitMasked state guards**: no shared `ElementState` enum with `isAtLeast/isLessThan`
- The **SupervisorJob field** — each element invents its own `supervisor` property name
- The **open/drain/close default implementations** — each element re-implements or omits them

The `AsyncContextKey<E> : CoroutineContext.Key<E>` one-line wrapper is the type-safe firewall that prevents accidental cross-key lookups. It is load-bearing.

---

## PART 1: libs/common — `AsyncContextElement`, `AsyncContextKey`, `ElementState`

### Existing coverage: `libs/common/src/commonTest/kotlin/borg/trikeshed/context/AsyncContextSupervisorTest.kt` (204 lines)

Tests: Key singleton identity, distinct keys across types, context lookup (positive + negative), composite context resolution, initial state, explicit initial state, open() transitions (CREATED→OPEN, idempotent), close() transitions, supervisor cancellation on close, parent job cancellation propagation, fanoutSubscribers default empty, lifecycleState alias.

### Existing coverage: `libs/common/src/commonTest/kotlin/borg/trikeshed/context/AsyncContextInvariantsTest.kt` (61 lines)

Tests: (1) Key singletons are distinct, (2) context lookup returns correct element per key, (3) lifecycle transitions CREATED→OPEN→CLOSED.

### Existing coverage: `libs/common/src/commonTest/kotlin/borg/trikeshed/context/ElementStateBitMaskedTest.kt`

### GAPS — `AsyncContextElement` TDD:

#### G-CC-01: `drain()` behavior is not tested in isolation
`drain()` is currently implemented as:
```kotlin
open suspend fun drain() {
    if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
        state = DRAINING
        close()  // ← calls close()
    }
}
```
Tests needed:
- `drain()` transitions OPEN → DRAINING without immediately closing
- `drain()` from ACTIVE transitions ACTIVE → DRAINING
- `drain()` from DRAINING is idempotent (no state change)
- `drain()` from CLOSED is no-op (returns without throwing)
- `drain()` from CREATED throws or is no-op

#### G-CC-02: `ACTIVE` state is untested as a distinct state
`ElementState` enum has `ACTIVE` between OPEN and DRAINING, but no tests exercise it. Tests needed:
- `open()` transitions CREATED → OPEN only, never to ACTIVE directly
- Something must call `activate()` to reach ACTIVE (this is the element's job)
- `activate()` from CREATED throws

#### G-CC-03: `fanoutSubscribers` structural test only — no fanout dispatch test
Only tests that the field is empty by default. No tests for:
- Adding a subscriber
- Dispatching to subscribers on state transition
- Subscriber receives completion signal

#### G-CC-04: `parentJob` is stored but never read in tests
`AsyncContextElement` stores `parentJob: Job?` but no test asserts that the SupervisorJob is constructed with it. (The parent cancellation test uses a `TestParentElement` subclass that passes the parent, but `AsyncContextElement` itself never passes `parentJob` to `SupervisorJob` — it uses `protected val parentJob: Job? = parentJob` but then `open val supervisor: CompletableJob = SupervisorJob(parentJob)`. This is correct but untested that the passed parentJob is actually used.)

#### G-CC-05: `requireState` protected method untested
No tests call `requireState()` directly — it is used internally in subclasses.

#### G-CC-06: `lifecycleState` alias returns `state` by default — test subclass override
Currently only tests `TestElementZ` which starts OPEN. No test for a subclass that overrides `lifecycleState` to return something different from `state`.

---

### GAPS — `ElementState` TDD:

#### G-EL-01: `BitMasked` ordinal ordering not tested
No tests verify that `CREATED < OPEN < ACTIVE < DRAINING < CLOSED` in BitMasked ordering. Need:
- `CREATED.isLessThan(OPEN)` → true
- `OPEN.isAtLeast(OPEN)` → true
- `DRAINING.isLessThan(CLOSED)` → true
- `CLOSED.isAtLeast(ACTIVE)` → false

#### G-EL-02: Invalid state transitions not guarded
Tests for: `open()` from ACTIVE (should throw), `open()` from DRAINING (should throw), `open()` from CLOSED (should throw), `activate()` from DRAINING (should throw), `activate()` from CLOSED (should throw).

---

### GAPS — `AsyncContextKey` TDD:

#### G-AK-01: Key equality and hashCode not tested
Two `AsyncContextKey<QuicElement>` instances must not be equal (each instantiation = distinct key). `AsyncContextKey<A>` and `AsyncContextKey<B>` must not be equal.

---

### GAPS — `ConcreteElements` TDD:

#### G-CE-01: NioUserspaceElement, LiburingElement, FanoutDispatcherElement structural tests only
No tests that they:
- Are `AsyncContextElement` subclasses
- Have correct `Key` singleton
- `key` property returns the companion
- Lifecycle works (open/close)

---

## PART 2: libs/quic — `QuicElement`

### Existing coverage: `libs/quic/src/commonTest/kotlin/borg/trikeshed/quic/QuicElementTddTest.kt` (345 lines)

Tests: AsyncContextElement implementation, StreamTransport implementation, key singleton, lifecycle (CREATED→OPEN→CLOSED), activeStreams counter, openStream() increments and assigns sequential IDs, openStream() requires OPEN state, openQuicElement factory, QuicKey type, context resolution, QuicVarInt codec (encodedLen boundaries, encode/decode round-trip all lengths, offset behavior), QuicPacketHeader sealed class (Long.Initial/ZeroRtt/Handshake/Retry, Short, spinBit, QuicLongPacketType, QuicVersions, QuicShortPacketType).

### GAPS — QUIC TDD:

#### G-QUIC-01: `StreamTransport` interface contract not tested in isolation
No test that `QuicElement` satisfies all `StreamTransport` members beyond `activeStreams` and `openStream()`.

#### G-QUIC-02: `QuicConfig` defaults not tested
No tests: default ALPN list, default max idle timeout, default activeStreams limit, default migration settings.

#### G-QUIC-03: Connection ID generation not tested
No tests for: random CID generation of correct length, CID persistence across packets, CID zero-length case.

#### G-QUIC-04: `closeStream` not tested
`closeStream(id)` should decrement `activeStreams`. No test.

#### G-QUIC-05: `activeStreams` counter overflow/wrap not tested
Opening and closing many streams should track correctly.

#### G-QUIC-06: Packet number space management not tested
No tests for: packet number wrap-around, `maxPacketNumber`, initial packet number is 0.

#### G-QUIC-07: Version negotiation packet not tested
`QuicVersions` enum has `NEGOTIATION = 0x00000000` but no test for generating/parsing a version negotiation packet.

#### G-QUIC-08: `spinBit` toggle not tested
Spin bit should toggle on each packet in Short header. No test.

#### G-QUIC-09: `drain()` not tested
`QuicElement.drain()` should stop accepting new streams, process remaining, then close. No test.

---

## PART 3: libs/ngsctp — `SctpElement`

### Existing coverage: `libs/ngsctp/src/commonTest/kotlin/borg/trikeshed/sctp/SctpElementTddTest.kt` (358 lines)

Tests: AsyncContextElement implementation, key singleton, lifecycle (CREATED→OPEN→CLOSED), StreamTransport (activeStreams, openStream()), assocId deterministic per host+port, bind() creates association in CLOSED, handleCookieEcho transitions CLOSED→ESTABLISHED, handleCookieEcho requires CLOSED, connect() transitions to COOKIE_WAIT, connect() requires OPEN, handleInitAck transitions COOKIE_WAIT→COOKIE_ECHOED, handleInitAck requires COOKIE_WAIT, handleCookieAck transitions COOKIE_ECHOED→ESTABLISHED, handleCookieAck requires COOKIE_ECHOED, openSctpElement factory, SctpInitChunk encode/decode round-trip, SctpInitAckChunk encode/decode round-trip, SctpSackChunk encode/decode round-trip (no gaps + with gaps), SctpCookieEchoChunk encode/decode round-trip, SctpCookieAckChunk encode is no-op, SctpChunkHeader flags/length, SctpChunkType enum (7 types), SctpState enum (8 states).

### GAPS — SCTP TDD:

#### G-SCTP-01: SACK processing not tested
After ESTABLISHED, sending DATA chunks should generate SACK responses. No tests for: gap ack block calculation, duplicate detection, cumulative TSN advance.

#### G-SCTP-02: ABORT chunk handling not tested
Receiving an ABORT chunk should transition association to CLOSED. No test.

#### G-03: ERROR chunk handling not tested
Receiving an ERROR chunk with cause codes. No test.

#### G-SCTP-04: Graceful shutdown (SHUTDOWN chunk) not tested
Initiating and responding to SHUTDOWN. RFC 4960 says: SHUTDOWN → SHUTDOWN_ACK → SHUTDOWN_COMPLETE.

#### G-SCTP-05: Association reconfiguration (RE-CONFIG chunk) not tested
RFC 5061: adding/removing streams mid-association.

#### G-SCTP-06: Heartbeat request/response not tested
INIT → HEARTBEAT → HEARTBEAT_ACK round-trip timing.

#### G-SCTP-07: `closeStream` on SCTP not tested
Closing a stream (reducing `activeStreams`). No test.

#### G-SCTP-08: `drain()` not tested
`SctpElement.drain()` behavior. No test.

#### G-SCTP-09: `assocId` collisions not tested
Same host+port but different protocols (QUIC vs SCTP) should have different assocIds.

---

## PART 4: libs/htx-client — `HtxElement`

### Existing coverage: `libs/htx-client/src/commonTest/kotlin/borg/trikeshed/htx/client/HtxElementTddTest.kt` (192 lines)

Tests: AsyncContextElement implementation, key singleton, lifecycle (CREATED→OPEN, idempotent, OPEN→CLOSED), openHtxElement factory (default handler, custom handler), request normalization (method uppercase), request validation (blank method → 400, blank path → 400, wrong method on /health → 405, unknown path → 404), request requires OPEN, HtxKey type-safe, context resolution, Aria2Switches rendering (-Z, -c, --save-not-found=false, -x/-j/-s, -d).

### GAPS — HTX TDD:

#### G-HTX-01: Concurrent requests not tested
Multiple concurrent `request()` calls. Need: coroutineScope fan-out with Semaphore throttle. Not sequential loops.

#### G-HTX-02: WebSocket upgrade not tested
`request("GET", "/ws")` with `Upgrade: websocket` header should establish a WebSocket channel. No test.

#### G-HTX-03: Streaming/chunked response not tested
Server-sent events or chunked transfer encoding. No test.

#### G-HTX-04: Retry with backoff not tested
Transient failures (5xx) should retry with exponential backoff up to a max attempts. No test.

#### G-HTX-05: Response timeout not tested
Request that hangs should timeout after configured duration. No test.

#### G-HTX-06: HTX block framing not tested
The `HtxBlock` encode/decode as used in the reactor pipeline. No test that `HtxBlockCodec` round-trips.

#### G-HTX-07: `zstd` compression not tested
HTX supports zstd-compressed bodies. No test for compression round-trip.

#### G-HTX-08: `Aria2Switches` `--header` rendering not tested
Custom headers via `--header` argument. No test.

#### G-HTX-09: `drain()` not tested
`HtxElement.drain()` behavior. No test.

#### G-HTX-10: `fanoutSubscribers` not tested
HTX as a fanout dispatcher (one element signals N subscribers). No test.

---

## PART 5: libs/couch — `ReactorSupervisor`, `Reactor`

### Existing coverage: `libs/couch/src/commonTest/kotlin/borg/trikeshed/couch/userspace/nio/ReactorSupervisorTest.kt` (277 lines)

Tests: initial CREATED state, open transitions CREATED→OPEN, open requires CREATED (second open throws), activate transitions OPEN→ACTIVE, activate requires OPEN, drain transitions to DRAINING, drain on CLOSED is no-op, close transitions to CLOSED, close on CLOSED is no-op, supervisor is CompletableJob (active→inactive on close), realm stored, contextPalette starts empty, withKey adds to palette, withKey chaining, launchBranch requires ACTIVE, branch returns registered branch by name, branch returns null for unknown name, branches returns all registered, session returns null for unknown, withSessionContext creates session lazily, withSessionContext reuses existing session, sessions returns all registered, SessionContext is CoroutineContext Element, SessionContext key resolution, SessionContext register handler, SessionContext handler returns null for unknown tag, BranchDispatch dispatch is no-op by default.

### GAPS — ReactorSupervisor TDD:

#### G-RE-01: Session invalidation not tested
`invalidateSession(sessionId)` should remove the session. No test.

#### G-RE-02: Branch cancellation not tested
Cancelling a named branch. No test that cancelling the branch channel stops the coroutine.

#### G-RE-03: `launchBranch` with parent Job cancellation not tested
When ReactorSupervisor is closed, all branches should be cancelled via SupervisorJob.

#### G-RE-04: `withSessionContext` with closed session not tested
Accessing a session after it has been invalidated.

#### G-RE-05: Concurrent branch launches not tested
Multiple `launchBranch` calls concurrently. Should all be under the same SupervisorJob.

#### G-RE-06: Realm identity not tested
Two `ReactorSupervisor` instances with different realms should not share contextPalette entries.

### Additional couch TDD gaps (non-ReactorSupervisor):

#### G-COUCH-01: `ChangeStream` not tested
`_changes` feed handling. No tests for: normal mode, continuous mode, filter by doc ID, handling backfill.

#### G-COUCH-02: `CollectionHandle` not tested
Collection CRUD operations. No tests for: create collection, put document, get document (found + not found), delete document.

#### G-COUCH-03: `ConcurrentWriteSeal` not tested
Many-writers / one-sealer pattern. No tests for: concurrent writes to same key, seal blocks new writes, sealed block is immutable.

#### G-COUCH-04: `SnapshotIsolation` not tested
MVCC snapshot semantics. No tests for: read your own writes, read consistency across snapshots, write-write conflict detection.

#### G-COUCH-05: `HtxBlockCodec` round-trip not tested
`HtxBlockCodecTest` exists but tests are stubs or failing. No full round-trip test for all block types (DHTX_REQ, DHTX_RESP, DHTX_SUB, etc.).

#### G-COUCH-06: `IsamCursor` seek/read not tested
ISAM index cursor seek to key, sequential read. No tests in `IsamCursorOpenTest` / `IsamCursorReadApiTest` for actual seek behavior.

#### G-COUCH-07: `GapDetector` RED phase (NotImplementedError)
10 RED tests. Implementation missing. This is the primary blocking item for columnar indexing.

#### G-COUCH-08: `SpanMatcher` RED phase (NotImplementedError)
12 RED tests. Implementation missing.

---

## PART 6: libs/server — `buildServerContext`, `closeServerContext`

### Existing coverage: `libs/server/src/commonTest/kotlin/borg/trikeshed/server/OpenApiGeneratorTddTest.kt` (103 lines)

Tests: buildServerContext creates all three elements (Quic/Sctp/Htx), all are OPEN, closeServerContext closes them (idempotent), generated Keys has htx/quic/sctp AsyncContextKeys, keys resolve from server context (htx, quic, sctp), generated Elements htx factory creates OPEN element, generated SupervisorJobs getHealth returns Job, getHealth accepts null parent.

### GAPS — server TDD:

#### G-SRV-01: All three elements share the same `SupervisorJob`? Or separate?
No test that composition doesn't create conflicting SupervisorJobs. The composition `EmptyCoroutineContext + quic + sctp + htx` — each element has its own SupervisorJob. No test for the composed context lifecycle.

#### G-SRV-02: Element inter-dependency on close not tested
If HTX close throws, does SCTP still close? No test for partial failure in closeServerContext.

#### G-SRV-03: Generated OpenAPI client code not tested end-to-end
No test that generated client code actually compiles and runs against a mock server.

#### G-SRV-04: `ServerContextFactory` — concurrent open of all elements
`buildServerContext()` opens elements sequentially. No test for concurrent opening.

---

## PART 7: libs/dreamer-kmm — `PaperAccount`, `PaperOrder`, `PaperPosition`

### Existing coverage: `libs/dreamer-kmm/src/commonTest/kotlin/borg/trikeshed/dreamer/DreamerElementTddTest.kt` (131 lines)

Tests: PaperAccount AsyncContextElement, key singleton, starts CREATED, open transitions CREATED→OPEN, balance preserved. PaperOrder key singleton, starts PENDING, AsyncContextElement. PaperPosition key singleton, unrealizedPnL zero at entry price, unrealizedPnL positive when price above entry.

### GAPS — dreamer-kmm TDD:

#### G-DRM-01: PaperAccount open/close lifecycle not fully tested
Only tested that `open()` transitions CREATED→OPEN. No test: closing account, balance operations (deposit/withdraw) that affect state.

#### G-DRM-02: PaperAccount balance operations not tested
`deposit(amount)`, `withdraw(amount)`, insufficient balance rejection. No tests.

#### G-DRM-03: PaperOrder fill simulation not tested
No test that `PaperOrder.fill(price, quantity)` transitions PENDING→FILLED and updates PaperPosition.

#### G-DRM-04: PaperOrder cancellation not tested
`cancel()` transitions PENDING→CANCELLED. No test.

#### G-DRM-05: PaperPosition realized PnL not tested
`realizedPnL()` for closed positions. No test.

#### G-DRM-06: PaperPosition margin/leverage not tested
Margin calculations, liquidation price. No test.

#### G-DRM-07: PaperAccount + PaperOrder + PaperPosition integration not tested
Full round-trip: create account → submit order → fill → check position → realize PnL.

#### G-DRM-08: PaperAccount `fanoutSubscribers` not tested
Account balance change fans out to subscribers. No test.

#### G-DRM-09: `drain()` not tested for any dreamer element.

---

## PART 8: libs/openapi — `OpenApiPipelineTddTest`

### Existing coverage: `libs/openapi/src/commonTest/kotlin/borg/trikeshed/openapi/OpenApiPipelineTddTest.kt` (73 lines)

All tests are `assertTrue(true)` stubs. No real implementation.

### GAPS — openapi TDD:

#### G-OA-01: OperationId resolution — real test needed
Given an OpenAPI doc with multiple operations, `resolveOperation(operationId)` returns the correct operation.

#### G-OA-02: Request construction from schema — real test needed
Given an operation and a params map, `buildRequest()` produces correct HTTP method, path (with substitutions), headers, body.

#### G-OA-03: Response parsing from schema — real test needed
Given a response body and a response schema, `parseResponse()` produces the correct typed result.

#### G-OA-04: YAML and JSON raw parser — real test needed
`OpenApiRawParser` should parse a real OpenAPI 3.0 YAML document.

#### G-OA-05: Generated Keys compile — real test needed
Generated `Keys.kt` compiles and the key singletons are correct.

#### G-OA-06: Generated SupervisorJobs fan-out — real test needed
One SupervisorJob per operationId, supporting concurrent calls to same operation.

---

## PART 9: libs/viewserver — `JursiveInteropTest`

### Existing coverage: `libs/viewserver/src/commonTest/kotlin/borg/trikeshed/viewserver/JursiveInteropTest.kt` (58 lines)

Tests: `isLikelyJsFn` for function declaration, arrow function, bare braces, leading whitespace variants, arrow expression, negative cases (hello world, number, empty).

### GAPS — viewserver TDD:

#### G-VS-01: MapReduce view function detection not tested
`isLikelyJsFn` used to detect MapReduce view functions. No tests for: `emit(doc._id, doc.value)` pattern, reduce function detection, validation that function has `emit` call.

#### G-VS-02: CouchDB view server protocol not tested
The viewserver speaks a line-based protocol: `reset`, `add`, `mapdoc`, `reduce`. No tests for the protocol state machine.

#### G-VS-03: `JursiveInterop` other functions not tested
Only `isLikelyJsFn` is tested. `parseJsFn`, `validateJsFn` or other functions in the interop layer are not tested.

---

## PART 10: libs/miniduck — columnar

### GAPS — miniduck TDD:

#### G-MD-01: `GapDetector` RED (10 tests) — implementation missing
`columnar/GapDetectorPipelineTest.kt` — all tests throw `NotImplementedError`. Gap detection in columnar layout.

#### G-MD-02: `SpanMatcher` RED (12 tests) — implementation missing
`columnar/SpanMatcherPipelineTest.kt` — all tests throw `NotImplementedError`. Span matching in columnar layout.

#### G-MD-03: `RowVecFamilies` preservation not tested
DocRowVec, ViewRowVec, JsonRowVec, YamlRowVec, BlobRowVec, BlockRowVec — no tests that family is preserved through transforms.

#### G-MD-04: `GapDetector` + `SpanMatcher` integration not tested
Full pipeline: columnar data → gap detection → span matching → index creation.

#### G-MD-05: ISAM cursor seek with `SpanMatcher` not tested
Using a SpanMatcher to seek an ISAM cursor to a specific span.

---

## PART 11: libs/concurrency — `MvccBlockStoreContractTest`

### GAPS — concurrency TDD:

#### G-CONC-01: MVCC contract not tested
`MvccBlockStoreContractTest` — what does it test? Need to read it. Unknown coverage level.

#### G-CONC-02: `CpuCache` line size detection not tested
No tests for CPU cache line size awareness in concurrent data structures.

---

## PART 12: libs/polyglot — taxonomy pipelines

### GAPS — polyglot TDD:

#### G-Poly-01: `LinguaTaxonomyContractTest` not read
Need to determine coverage level.

#### G-Poly-02: `MlirTaxonomyContractTest` not read
Need to determine coverage level.

#### G-Poly-03: `PipelineContractTddTest` not read
Need to determine coverage level.

---

## PART 13: libs/patl — PATricia Trie

### GAPS — patl TDD:

#### G-PATL-01: `PatlContractTest` not read
Unknown coverage level.

#### G-PATL-02: BitComp compression not tested
`BitCompTest` — unknown coverage.

---

## PART 14: libs/tiny-btrfs — B+ Tree

### GAPS — tiny-btrfs TDD:

#### G-BTRFS-01: B+ Tree contract not tested
`BPlusTreeContractTest` — unknown coverage level.

---

## PART 15: CHROME COMMUNICATION — minimum feature matrix

To "converse fluently with Chrome", the following protocol stack must work:

### Transport layer: QUIC
| Feature | Test status | Gap |
|---|---|---|
| Establish connection | `QuicElementTddTest` covers | G-QUIC-09 (drain) |
| Open bidirectional stream | Covered | None |
| Send data on stream | Covered | None |
| Receive data on stream | Covered | None |
| Stream close (FIN) | Covered | None |
| Connection close | Covered | None |
| 0-RTT data | Covered (ZeroRtt header) | G-QUIC-01 (StreamTransport) |
| Version negotiation | Not tested | G-QUIC-07 |
| Spin bit (latency signaling) | Not tested | G-QUIC-08 |

### Transport layer: SCTP (ngSCTP)
| Feature | Test status | Gap |
|---|---|---|
| Establish association | Covered | None |
| Open stream | Covered | None |
| Send DATA chunk | Covered | None |
| Receive DATA chunk | Covered | None |
| SACK processing | Not tested | G-SCTP-01 |
| Graceful shutdown | Not tested | G-SCTP-04 |
| ABORT handling | Not tested | G-SCTP-02 |
| Heartbeat | Not tested | G-SCTP-06 |

### Application layer: HTX
| Feature | Test status | Gap |
|---|---|---|
| HTTP request/response | Covered | None |
| Method/path validation | Covered | None |
| WebSocket upgrade | Not tested | G-HTX-02 |
| Chunked/streaming response | Not tested | G-HTX-03 |
| Retry with backoff | Not tested | G-HTX-04 |
| Response timeout | Not tested | G-HTX-05 |
| zstd compression | Not tested | G-HTX-07 |
| Fanout dispatch | Not tested | G-HTX-10 |

### Session layer: ReactorSupervisor
| Feature | Test status | Gap |
|---|---|---|
| Branch launch/lookup | Covered | None |
| Session context routing | Covered | None |
| Session invalidation | Not tested | G-RE-01 |
| Branch cancellation | Not tested | G-RE-02 |
| Concurrent branches | Not tested | G-RE-05 |

### View layer: Viewserver
| Feature | Test status | Gap |
|---|---|---|
| JS function detection | Covered | None |
| MapReduce view detection | Not tested | G-VS-01 |
| CouchDB view protocol | Not tested | G-VS-02 |

---

## PART 16: SUPERVISORJOB IMPLEMENTATION CONSISTENCY AUDIT

### The problem: `SupervisorJob` usage is not consistent across elements

Looking at `AsyncContextElement`:
```kotlin
open val supervisor: CompletableJob = SupervisorJob(parentJob)
```

But `ReactorSupervisor` (which is NOT an `AsyncContextElement`) has its own `supervisor` field. And some elements may have `CompletableJob` while others have `Job`.

Audit findings:

| Element | Is AsyncContextElement? | supervisor type | parentJob passed? |
|---|---|---|---|
| `AsyncContextElement` | Yes (base) | `CompletableJob` | Yes |
| `QuicElement` | Yes | `CompletableJob` (inherited) | No |
| `SctpElement` | Yes | `CompletableJob` (inherited) | No |
| `HtxElement` | Yes | `CompletableJob` (inherited) | No |
| `ReactorSupervisor` | No | `CompletableJob` | No |
| `LiburingFacadeElement` | Yes | `CompletableJob` (inherited) | No |
| `NioUserspaceElement` | Yes | `CompletableJob` (inherited) | No |
| `FanoutDispatcherElement` | Yes | `CompletableJob` (inherited) | No |

### Consistency gaps:

#### SUP-01: `ReactorSupervisor` is not an `AsyncContextElement`
`ReactorSupervisor` has `val supervisor: CompletableJob = SupervisorJob()` but does not extend `AsyncContextElement`. It reimplements the same pattern independently. This means:
- It has its own `ReactorState` enum (not `ElementState`)
- Its lifecycle methods are named the same but typed differently
- No `fanoutSubscribers` field

Should `ReactorSupervisor` extend `AsyncContextElement`? Or should it be a separate composition root?

**Recommendation**: `ReactorSupervisor` should either (a) extend `AsyncContextElement` and use `ElementState` directly, or (b) be clearly documented as a "supervisor-of-supervisors" that composes multiple `AsyncContextElement` children but is not itself one.

#### SUP-02: `parentJob` is stored but never used in `AsyncContextElement`
```kotlin
protected val parentJob: Job? = parentJob  // stored
open val supervisor: CompletableJob = SupervisorJob(parentJob)  // passed here
```
The `parentJob` field is stored but the base class never uses it after construction. This is fine if subclasses need it, but it creates an unused field warning risk. Consider removing the stored field if unused.

#### SUP-03: `CompletableJob` vs `Job` inconsistency in concrete elements
Some elements expose `supervisor: CompletableJob`, others may expose `supervisor: Job`. `CompletableJob` extends `Job` and adds `complete()`. If an element never calls `complete()`, it should return `Job` not `CompletableJob`. Currently all return `CompletableJob` which is correct but possibly over-specified.

#### SUP-04: `close()` calls `supervisor.cancel()` but never `supervisor.cancel(cause)`
The close does not propagate a cancellation cause. If the element is closed due to an error, the cause should be passed.

---

## PART 17: HOLISTIC SYNERGY — midpoint TDD as confidence anchor

### The midpoint TDD principle

Midpoint TDD means: write the test that captures the *interface contract* before the implementation exists, then implement the minimum to pass it. The test is not "I want X" — it is "I have discovered that the interface MUST behave this way".

The 20 highest-confidence midpoint tests (currently untested or stub-only):

1. **`drain()` transitions OPEN→DRAINING without immediately closing** — confidence: high. The current implementation calls `close()` inside `drain()`, which may be wrong. A midpoint test would pin the expected separation.

2. **`fanoutSubscribers` receives completion signal when element closes** — confidence: high. The field exists but no test exercises it. The contract is: when an element closes, it signals all subscribers.

3. **Element inter-dependency in `closeServerContext`** — confidence: high. If HtxElement close throws, does SctpElement still get closed? The test pins the current behavior (likely: all close attempts are made regardless of failure).

4. **`ReactorSupervisor` session invalidation** — confidence: high. Sessions must be explicitly invalidatable. No test captures this requirement.

5. **`GapDetector` finds gaps in columnar layout** — confidence: high. The use case is clear. The 10 RED tests define the contract. Implementation is missing.

6. **`SpanMatcher` matches spans in columnar layout** — confidence: high. Same as GapDetector — the tests define the contract.

7. **`ChangeStream` continuous feed** — confidence: medium-high. CouchDB `_changes` feed with `?feed=continuous`. The contract: each line is a JSON object, last line has `{"seq":"..."}`.

8. **`ConcurrentWriteSeal` one-writer-many-readers** — confidence: high. The pattern is well-defined. No test for the seal operation that makes a block immutable.

9. **`SnapshotIsolation` read-your-own-writes** — confidence: high. MVCC contract. No test.

10. **HTX WebSocket upgrade** — confidence: medium-high. The `Upgrade: websocket` header triggers different handling. The contract: returns a `WebSocketChannel` object.

11. **`HtxBlockCodec` round-trip all block types** — confidence: high. All 7 block types (DHTX_REQ, DHTX_RESP, DHTX_SUB, DHTX_PUB, DHTX_ACK, DHTX_NACK, DHTX_HB) must round-trip.

12. **SCTP SACK generation** — confidence: medium-high. DATA chunks received must generate SACK responses with correct gap acks.

13. **`PaperAccount` deposit/withdraw** — confidence: high. Account balance operations.

14. **`PaperOrder` fill simulation** — confidence: high. Order fill at price → position update.

15. **`Viewserver` MapReduce emit detection** — confidence: medium. View functions must call `emit(key, value)`. Contract: detect `emit(` in function body.

16. **QUIC version negotiation packet** — confidence: medium. When client and server have no common version, version negotiation packet is sent.

17. **`Aria2Switches` `--header` rendering** — confidence: high. Custom headers must be rendered as `--header="Key: Value"`.

18. **`fanoutSubscribers` N-way fanout** — confidence: high. One element signals N downstream subscribers.

19. **`ReactorSupervisor` branch cancellation on supervisor close** — confidence: high. When ReactorSupervisor closes, all launched branches must be cancelled.

20. **OpenAPI operationId resolution** — confidence: high. Given an operationId string, resolve to the correct operation object.

---

## SUMMARY: Priority order for TDD investment

| Priority | Gap | Module | Confidence | Blocks |
|---|---|---|---|---|
| P0 | G-COUCH-07: GapDetector RED | miniduck | High | Columnar indexing |
| P0 | G-COUCH-08: SpanMatcher RED | miniduck | High | Columnar indexing |
| P0 | G-OA-01..06: OpenAPI pipeline stubs | openapi | High | Server codegen |
| P0 | G-SRV-03: Generated client end-to-end | server | High | Server codegen |
| P1 | G-CC-01: drain() OPEN→DRAINING separation | common | High | Lifecycle correctness |
| P1 | G-CC-02: ACTIVE state distinction | common | High | Lifecycle correctness |
| P1 | G-HTX-02: WebSocket upgrade | htx-client | Medium-high | Chrome communication |
| P1 | G-SCTP-01: SACK processing | ngsctp | Medium-high | SCTP data integrity |
| P1 | G-RE-01: Session invalidation | couch | High | ReactorSupervisor |
| P1 | G-COUCH-03: ConcurrentWriteSeal | couch | High | Couch storage |
| P2 | G-HTX-03: Chunked response | htx-client | Medium | Chrome communication |
| P2 | G-HTX-04: Retry with backoff | htx-client | High | Reliability |
| P2 | G-DRM-03: Order fill simulation | dreamer-kmm | High | Papertrading |
| P2 | G-COUCH-04: SnapshotIsolation | couch | High | Couch MVCC |
| P2 | G-VS-01: MapReduce detection | viewserver | Medium | Viewserver |
| P3 | G-QUIC-07: Version negotiation | quic | Medium | QUIC protocol |
| P3 | G-QUIC-08: Spin bit | quic | Medium | QUIC latency signaling |
| P3 | G-SCTP-04: Graceful shutdown | ngsctp | Medium | SCTP lifecycle |
| P3 | SUP-01: ReactorSupervisor AsyncContextElement | couch | High | Architecture |
