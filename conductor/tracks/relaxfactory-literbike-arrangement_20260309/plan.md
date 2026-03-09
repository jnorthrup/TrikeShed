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
**Status:** [ ] open
**Owner:** master
**Corpus:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/`

**Deliverables:**
- capture the current red compile/test surfaces relevant to transport arrangement
- distinguish arrangement blockers from unrelated red debt
- keep all failing files/tests present

**Verification:** `./gradlew compileKotlinJvm` (evidence capture only; red is acceptable)

---

### arrange-03 — Universal Listener Failing Contracts
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/context/`, `src/commonMain/kotlin/borg/trikeshed/net/`, `src/jvmTest/kotlin/`

**Deliverables:**
- add failing TrikeShed tests/specs for:
  - protocol detection contract
  - prefixed-buffer preservation contract
  - HTTP transport detection plus model/API overlay classification
- no deletion of pre-existing red tests/code

**Verification:** focused JVM test command(s) to be defined by the assigned slice

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

- `arrange-02` red-ledger capture, then `arrange-03` failing contract materialization

---

## Evidence Log

- 2026-03-09: Cross-repo lineage inspected from `../RelaxFactory`, `../litebike`, `../literbike`, and `../2litebike`.
- 2026-03-09: Determined that `literbike`'s universal listener and QUIC/reactor separation are the useful salvage surfaces, not its CCEK posture.
- 2026-03-09: Determined that TrikeShed CCEK must remain a small typed service layer and should not own protocol architecture.
- 2026-03-09: Preserved current red build state as explicit debt instead of deleting red files/tests.
- 2026-03-09: Recorded `literbike` string-keyed CCEK/context wiring as negative evidence rather than target design.
- 2026-03-09: Recorded `QuadShed` parser lineage and `TrikeShedBridge` adapter lineage as follow-on salvage candidates.
- 2026-03-09: Recorded archival `old/v2superbikeshed/*` module families as parser/service/context reference corpus, with `trikeshed-ccek` and `trikeshed-quic` preserved mainly as anti-pattern evidence.
