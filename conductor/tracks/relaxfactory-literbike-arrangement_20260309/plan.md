# Track: RelaxFactory/Literbike Transport Arrangement & Red-TDD Preservation

**Track ID:** `relaxfactory-literbike-arrangement_20260309`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Repair TrikeShed's transport ownership after the merged CCEK experiment by using the actual lineage:

- `../RelaxFactory` for the zero-container-cost reactor and spartan protocol dispatch doctrine
- `../litebike` for the edge/runtime split and zero-cost abstraction pressure
- `../literbike` and `../2litebike` for the universal listener, prefixed-stream preservation, QUIC/reactor salvage, and conductor truth around those ports

This track is explicitly **TDD-first without purging red tests or red code**.
It uses both the active sibling repos and the archival `superbikeshed` module families as reference corpus, but does not treat either corpus as a wholesale import target.

---

## Source Evidence

- `../RelaxFactory/README.md`
  - reactor pattern with low resource cost
  - spartan protocol dispatch
  - explicit suitability for QUIC serverside work
- `../litebike/conductor/product.md`
  - edge utility/proxy role
  - companion relationship with `literbike`
- `../litebike/conductor/tracks.md`
  - control-plane/orchestration truth stays outside transport runtime details
- `../litebike/docs/literbike-roadmap.md`
  - preserve RelaxFactory lineage and migrate with bounded, auditable surfaces
- `../litebike/docs/betanet-densifier.md`
  - zero-cost abstractions, SIMD anchors, io_uring/eBPF remain primitive-level concerns
- `../literbike/conductor/tracks.md`
  - reactor and QUIC ports are separate completed tracks
- `../literbike/src/concurrency/ccek.rs`
  - string-keyed `ContextElement` map and `EmptyContext + Arc<dyn ContextElement>` composition are negative evidence, not TrikeShed target design
- `../literbike/src/reactor/context.rs`
  - reactor service is wired through the string-keyed CCEK layer; useful only as evidence of architectural overreach
- `../literbike/src/universal_listener.rs`
  - transport protocol detection
  - prefixed-stream preservation after peeking
  - model/API overlay classification layered on top of HTTP detection
- `../literbike/src/protocol_registry.rs`
  - registry is currently a placeholder shim for tests; do not mistake it for settled architecture
- `../literbike/src/model_serving_taxonomy.rs`
  - classifier/taxonomy corpus is a useful source for HTTP overlay parsing and model/API routing tests
- `../2litebike/src/universal_listener.rs`
  - same universal-listener shape in an earlier salvage line
- `../litebike/tests/modelmux/test_8888_cloaking.rs`
  - preserves the parser-combinator / single-port dispatch ambition as TDD evidence, even where implementation remains wishful
- `../QuadShed/README.md`
  - parser lineage: JSON indexer/reifier/path selector and flat-file import notes
- `../TrikeShedBridge/src/nativeMain/kotlin/Bridge.kt`
  - service-adapter lineage: narrow FFI ingestion bridge, useful as adapter evidence rather than transport ownership
- `/Users/jim/work/old/v2superbikeshed/trikeshed-http/src/commonMain/kotlin/borg/trikeshed/net/http/HttpParser.kt`
  - archival HTTP parser lineage with explicit module split
- `/Users/jim/work/old/v2superbikeshed/trikeshed-json/src/commonMain/kotlin/borg/trikeshed/core/SimpleJsonScanner.kt`
  - archival JSON scanner lineage
- `/Users/jim/work/old/v2superbikeshed/trikeshed-json/src/commonMain/kotlin/borg/trikeshed/core/FastJsonScanner.kt`
  - archival fast-path JSON parser/scanner lineage
- `/Users/jim/work/old/v2superbikeshed/trikeshed-context/src/commonMain/kotlin/borg/trikeshed/context/ContextDeck.kt`
  - archival context-deck experiment; useful as context-composition evidence but not as replacement for typed service keys
- `/Users/jim/work/old/v2superbikeshed/trikeshed-services/src/commonMain/kotlin/borg/trikeshed/services/ChannelizedServiceManager.kt`
  - archival service-manager lineage with clear stub/wishful surface area
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/CCEK.kt`
  - overgrown four-field-plus meta-orchestration CCEK; negative evidence
- `/Users/jim/work/old/v2superbikeshed/trikeshed-quic/src/commonMain/kotlin/borg/trikeshed/net/quic/QuicCCEK.kt`
  - QUIC-specific CCEK orchestration layer; negative evidence for transport ownership inside CCEK
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/ContextCompositionBehavior.kt`
  - useful as composition-scenario grammar for tests and coverage, not runtime truth
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/MetaCompositionPatterns.kt`
  - useful as staged block-composition vocabulary, though its endgame claims are not architecture evidence
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/META_COMPOSITION_ARCHITECTURE.md`
  - mostly request-shaped markdown theater, but still useful for recovering composition nouns and scenario patterns
- `src/commonMain/kotlin/borg/trikeshed/ccek/KeyedService.kt`
  - current in-repo typed service seam worth keeping
- `src/commonMain/kotlin/borg/trikeshed/ccek/CcekScope.kt`
  - current coroutine-scoped capability lookup seam
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/StreamTransport.kt`
  - current cross-transport coverage contract
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/QuicChannelService.kt`
  - current QUIC capability carrier and invariants holder, not runtime owner
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/NgSctpService.kt`
  - current SCTP-semantics capability carrier for parity coverage
- `src/commonMain/kotlin/borg/trikeshed/common/SeekHandle.kt`
  - current file-backed fixture/replay seam for protocol coverage
- `src/commonMain/kotlin/borg/trikeshed/context/Elements.kt`
  - current runtime hint elements for I/O capability coverage
- `src/commonMain/kotlin/borg/trikeshed/context/HandlerRegistry.kt`
  - current handler-overlay seam that needs reconciliation
- `src/commonMain/kotlin/borg/trikeshed/net/ProtocolRouter.kt`
  - current detector-to-handler seam that needs real contracts

---

## Invariants

- CCEK is a minimal typed coroutine service mechanism only.
- Universal listener ownership belongs to `context` + `net`, not to `ccek`.
- QUIC handshake, stream multiplexing, and reactor/runtime concerns stay outside CCEK.
- Red tests/code are evidence and must not be deleted just to create a false green state.
- New transport work enters through failing tests/specs first, then implementation.
- Existing unrelated red compile/test debt is tracked, not silently purged.
- Wishful or placeholder modules in sibling repos are evidence to triage through tests first, not code to anoint as architecture.
- Archived `superbikeshed` modules are reference corpus only; import bounded behaviors, not entire module theories.
- Conventional training bias toward servlet/handler/DI/socket patterns is treated as a distortion source; preserve repo-specific concepts instead of normalizing them away.
- Request-shaped markdown that merely satisfies the ask is not sufficient evidence; only code, failing tests, or bounded scenario grammar get preserved.
- Historical model limitations are part of the evidence: older LLMs often reproduced the composition prose without being able to code it or even follow the composition steps reliably.

---

## Current Red Ledger

As of 2026-03-09, `./gradlew compileKotlinJvm` is red. Relevant surfaced failures include:

- `src/commonMain/kotlin/borg/trikeshed/context/HandlerRegistry.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/ProtocolRouter.kt`
- `src/commonMain/kotlin/borg/trikeshed/grad/DrawdownDsel.kt`
- `src/commonMain/kotlin/one/xio/NetworkChannel.kt`
- `src/jvmMain/kotlin/rxf/server/CookieRfc6265Util.kt`

These failures remain in place as tracked debt. This track must not "solve" them by deleting the offending code or tests.

---

## Slice Schema

### arrange-01 — Lineage Truth Materialization
**Status:** [x] closed
**Owner:** master
**Corpus:** `conductor/tracks.md`, `conductor/tracks/relaxfactory-literbike-arrangement_20260309/`

**Deliverables:**
- create arrangement track in local conductor truth
- record actual lineage sources
- demote CCEK from transport owner to minimal service substrate

**Verification:** inspect referenced lineage files and local conductor truth

---

### arrange-02 — Red Ledger Capture
**Status:** [x] closed
**Owner:** master
**Corpus:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/`

**Deliverables:**
- capture the current red compile/test surfaces relevant to transport arrangement
- distinguish arrangement blockers from unrelated red debt
- keep all failing files/tests present

**Verification:** `./gradlew compileKotlinJvm` (evidence capture only; red is acceptable)

**Delivered:**
- Verified all five red files still present
- Classified by relevance:
  - **Arrangement blockers** (transport/protocol reconciliation):
    - `HandlerRegistry.kt` — CoroutineContext handler registry seam; needed for arrange-04
    - `ProtocolRouter.kt` — detector-to-handler dispatch seam; needed for arrange-03/04
    - `NetworkChannel.kt` — expect interface missing platform impls; transport-adjacent
  - **Unrelated red debt** (do not address in this track):
    - `DrawdownDsel.kt` — Kotlingrad DSEL (own track: kotlingrad-unified-dsel)
    - `CookieRfc6265Util.kt` — RxF legacy HTTP cookie utility; not arrangement concern

---

### arrange-03 — Universal Listener Failing Contracts
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/context/`, `src/commonMain/kotlin/borg/trikeshed/net/`, `src/jvmTest/kotlin/`

**Deliverables:**
- add failing TrikeShed tests/specs for:
  - protocol detection contract
  - prefixed-buffer preservation contract
  - HTTP transport detection plus model/API overlay classification
- no deletion of pre-existing red tests/code

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.UniversalListenerContractTest'`

**Delivered:**
- `UniversalListenerContractTest.kt` in jvmTest (`borg/trikeshed/net/`) with 10 tests covering:
  1. Protocol detection must use wire-format prefixes (not string search) — 1 test intentionally fails, documenting the current `detectProtocol()` bug
  2. SSH wire prefix detection (currently uses string search — intentional red)
  3. HTTP method prefix detection (currently passes)
  4. Minimum-bytes requirement (edge case)
  5. Prefixed-buffer preservation — documents missing `PeekableBuffer` abstraction
  6. Universal listener full-buffer contract — fails (no mechanism exists)
  7-10. HTTP endpoint classification (API/MODEL_SERVING/STATIC) — `HttpEndpointType` + `classifyHttpEndpoint()` stubs in test file
- 9/10 tests run; 1 intentional assertion failure (wire-format detection contract)

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.UniversalListenerContractTest'` → 9 pass, 1 intentionally fails (expected)

---

### arrange-04 — Handler/Router Reconciliation
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/context/HandlerRegistry.kt`, `src/commonMain/kotlin/borg/trikeshed/net/ProtocolRouter.kt`

**Deliverables:**
- reconcile the handler registry and protocol router to the arrangement in `arrangement.md`
- keep CCEK usage minimal and capability-oriented
- do not claim full compile green if unrelated red debt remains

**Verification:** focused compile/tests around `context` + `net`

---

### arrange-05 — Parser/Service Salvage Triage
**Status:** [ ] open
**Owner:** slave
**Corpus:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/`, `src/commonMain/kotlin/borg/trikeshed/net/`, `src/jvmTest/kotlin/`

**Deliverables:**
- catalog parser and service candidates from sibling bike/shed repos and archival `superbikeshed` module families with explicit owner boundaries
- preserve wishful or placeholder sources as evidence without importing them wholesale
- materialize new TrikeShed failing tests/specs for any selected parser/service salvage before implementation

**Verification:** inspect conductor truth plus focused failing test additions

---

## Next Slice

- `arrange-03` — universal listener failing contracts (slave)

---

## Evidence Log

- 2026-03-09: Cross-repo lineage inspected from `../RelaxFactory`, `../litebike`, `../literbike`, and `../2litebike`.
- 2026-03-09: Determined that `literbike`'s universal listener and QUIC/reactor separation are the useful salvage surfaces, not its CCEK posture.
- 2026-03-09: Determined that TrikeShed CCEK must remain a small typed service layer and should not own protocol architecture.
- 2026-03-09: Preserved current red build state as explicit debt instead of deleting red files/tests.
- 2026-03-09: Recorded `literbike` string-keyed CCEK/context wiring as negative evidence rather than target design.
- 2026-03-09: Recorded `QuadShed` parser lineage and `TrikeShedBridge` adapter lineage as follow-on salvage candidates.
- 2026-03-09: Recorded archival `old/v2superbikeshed/*` module families as parser/service/context reference corpus, with `trikeshed-ccek` and `trikeshed-quic` preserved mainly as anti-pattern evidence.
- 2026-03-09: Identified the in-repo CCEK code that helps future network/protocol coverage: typed capability lookup, stream-transport contract seams, fixture I/O seams, and I/O capability hints.
- 2026-03-09: Distinguished useful CCEK composition grammar from theatrical markdown; preserve the former as test/spec scripting material only.
- 2026-03-09: Recorded that historical model bias also limited execution fidelity; composition markdown from that era cannot be treated as proof that the design was implementable by the assisting model.
- 2026-03-09: arrange-02 closed — red ledger confirmed current; HandlerRegistry/ProtocolRouter/NetworkChannel classified as arrangement blockers; DrawdownDsel/CookieRfc6265Util classified as unrelated debt.
- 2026-03-09: arrange-03 closed — `UniversalListenerContractTest.kt` added with 10 TDD contracts for protocol detection, buffer preservation, and HTTP endpoint classification. One test intentionally fails documenting the string-search bug in `detectProtocol()`. No red files deleted.
