# libs/couch — Boundary Audit TODO

## Static / Factories / Enums -> Key Candidates

These are pure routing identities that should be AsyncContextKey<K> singletons
if they gain element counterparts. Currently they are mixed patterns:

- [ ] **ReactorSupervisorKey** — already a singleton `object`, but ReactorSupervisor
  extends AbstractCoroutineContextElement directly rather than the Key/Element
  split. Evaluate: extract AsyncContextKey<ReactorSupervisor> and make
  ReactorSupervisor extend AsyncContextElement.

- [ ] **ParseSupervisorKey** — same pattern as ReactorSupervisorKey.

- [ ] **SessionContextKey** — same pattern. SessionContext carries mutable handler
  registry; should follow Key/Element split.

- [ ] **ngSCTPChannel.ngSCTPChannelKey** — same pattern. The channel is stateful
  (rendezvous channels); Key/Element split is appropriate.

- [ ] **QuicChannelService.Companion.Key** / **NgSctpService.Companion.Key** —
  These use companion object as Key directly. Should follow the canonical pattern.

- [ ] **HtxSlFlags** / **HtxFlags** — correctly implement BitMasked. No change needed.

- [ ] **HtxBlockType** — enum with UByte codes. Not BitMasked. Consider if
  a BitMasked version is needed for block-type filtering.

- [ ] **HandleState** — enum {OPEN, SEALED, CLOSED}. Consider mapping to the
  canonical ElementState {CREATED, OPEN, ACTIVE, DRAINING, CLOSED} or at
  minimum implementing BitMasked for consistency.

- [ ] **ReactorState** — enum {CREATED, OPEN, ACTIVE, DRAINING, CLOSED}.
  Should implement BitMasked to match the ElementState convention.

- [ ] **ParseState** — duplicates ReactorState exactly. Unify into a shared
  LifecycleState enum or reuse ReactorState.

- [ ] **KlineBlock.State** — {MUTABLE, SEALED}. Different lifecycle than
  ElementState. Document why it diverges or align.

- [ ] **Change.Kind** — {INSERT, REMOVE, SEAL}. Not BitMasked.

- [ ] **PathState** (in NgSctpService) — {ACTIVE, INACTIVE, UNKNOWN}. Not BitMasked.

## Stateful -> Element Candidates

These hold mutable state and should follow the AsyncContextElement lifecycle
pattern (CREATED->OPEN->ACTIVE->DRAINING->CLOSED with SupervisorJob):

- [ ] **CollectionHandle** — mutable row list with OPEN/SEALED/CLOSED states.
  Thread-unsafe (documented). Does NOT extend AsyncContextElement. Should it?

- [ ] **AdmissionControl** — mutable permit counter with seal/close. Not a
  CoroutineContext.Element. If used inside reactor branches, should become one.

- [ ] **ChangeEmitter** — mutable listener map + sealed flag. Synchronous
  fanout only. Not an Element. Used by tests; may not need Element status.

- [ ] **KlineBlock** — MUTABLE->SEALED state machine. Mutable row list. Not an
  Element. If blocks need to live in coroutine contexts, consider conversion.

- [ ] **ngSCTPMultiplexer** — mutable channel map. Not a CoroutineContext.Element.
  Should be an Element if injected into branch scopes.

- [ ] **CouchRuntime** — thin wrapper around Reactor + Transport. Not an Element.

- [ ] **ProtocolDetector** — mutable StringBuilder + tlsDetected flag. JVM-only.
  Stateful but scoped to a single branch lifetime; probably fine as-is.

- [ ] **InstrumentedHandle** (jvmMain) — wraps CollectionHandle with ReentrantLock
  + AtomicLong probes. Test infrastructure, not production Element.

## Boundary Issues

### Code Duplication

- [ ] **ccek/QuicChannelService.kt** and **ccek/NgSctpService.kt** duplicate
  libs/quic and libs/ngsctp respectively. These should be removed from couch
  and replaced with proper `implementation libPeer("quic")` / `libPeer("ngsctp")`
  dependencies. The couch build.gradle.kts does NOT currently depend on quic
  or ngsctp — the code is copy-pasted into the ccek package.

- [ ] **UrlEncoding.kt** exists in two places: `couch/internal/UrlEncoding.kt`
  (urlEncode function) and `couch/UrlEncode.kt` (package-level urlencode).
  Consolidate into one.

- [ ] **isLikelyJsFn** exists in two places: `couch/viewserver/JursiveHeuristics.kt`
  (commonMain, simple heuristic) and `viewserver/commonMain/JursiveInterop.kt`
  (uses kursive). The couch one doesn't use kursive; the viewserver one does.
  Clarify which is canonical and eliminate the duplicate.

### Lifecycle Consistency

- [ ] **BtrfsWal** extends AsyncContextElement but calls `requireState(ElementState.OPEN)`
  — it never transitions through CREATED/ACTIVE/DRAINING/CLOSED. The `open()`
  call is manual (in BtrfsHarness). Verify the lifecycle actually matches
  AsyncContextElement expectations.

- [ ] **BtrfsSandboxElement** extends AsyncContextElement but has no domain methods
  beyond the BPlusTree reference. The lifecycle is managed externally by
  BtrfsHarness (which calls sandbox.close()). Verify SupervisorJob completion
  is correct.

- [ ] **ReactorSupervisor._state** is a bare `var` with no thread-safety.
  Comment says "single-threaded access". If branches can call drain()/close()
  from different coroutines, this is a race. Consider AtomicReference or
  restricting state transitions to the supervisor's own dispatcher.

- [ ] **ParseSupervisor._state** has the same thread-safety concern.

### Missing Pieces

- [ ] **HTX HTTP/2 frame parsing** — HtxMessage.normalizeToHtx has a TODO for
  HTTP/2 frame parsing. Currently returns empty HtxMessage for HTTP/2.

- [ ] **HtxBlock.payloadBytes()** — returns zeros (placeholder). The block store
  integration is not implemented.

- [ ] **LSMRWal.compact()** — BtrfsWal.compact returns a stub CompactionResult(1).

- [ ] **BtrfsTableSource** — openSuspend/open return hardcoded "test_data" cursors.
  Not production-ready.

- [ ] **HtxCryptoWasm** / **CryptoPrimitivesWasm** — platform implementations
  need verification. wasmJs is the newest target.

- [ ] **CryptoPrimitivesJs** — may lack real X25519 DH on JS. Check if WebCrypto
  is used.

### Transport Integration

- [ ] **HtxBackedCouchTransport** — takes a Reactor parameter but never uses it
  (the constructor param is unused in the current methods). Wire up the reactor
  for actual async transport.

- [ ] **ngSCTPChannel** — rendezvous channels are created but never connected
  to an underlying SCTP association. The multiplexer is purely in-memory.

- [ ] **CouchServiceCompiler** — JVM-only (kotlin-reflect). Cannot be used from
  commonMain. Consider a compile-time annotation processor (KSP) for multiplatform.

## Integration Steps

1. **Eliminate ccek/ duplication** — remove QuicChannelService and NgSctpService
   from couch, add `implementation libPeer("quic")` and `libPeer("ngsctp")` to
   build.gradle.kts couch case. Update imports across the codebase.

2. **Unify URL encoding** — keep `internal/UrlEncoding.kt`, delete `UrlEncode.kt`,
   update all callers.

3. **Unify lifecycle enums** — extract a shared `LifecycleState` or map
   HandleState/KlineBlock.State/ParseState/ReactorState to a canonical enum.

4. **Key/Element split** — for ReactorSupervisor, ParseSupervisor, SessionContext,
   ngSCTPChannel: extract AsyncContextKey singletons and verify Element lifecycle.

5. **Thread-safety audit** — ReactorSupervisor._state and ParseSupervisor._state
   need atomic access or a confined dispatcher.

6. **Transport wiring** — connect HtxBackedCouchTransport to the reactor's
   branch/session infrastructure for real async I/O.

7. **KSP for RelaxFactory** — replace kotlin-reflect CouchServiceCompiler with
   a KSP processor for multiplatform view compilation.

## Path to Stable

- All 28+ test files are RED-phase (test-first). Continue filling in implementations.
- Stabilize the HTX serialize/deserialize round-trip (HtxBlockCodecTest, HtxSearchRedTest).
- Lock down the ReactorSupervisor lifecycle (ReactorSupervisorLifecycleTest).
- Verify crypto primitives across all 4 platforms (HtxTicketDhRedTest).
- Once transport is wired, run integration-scratch end-to-end against a real CouchDB.
- Remove ccek/ duplication before any public API commitment.
