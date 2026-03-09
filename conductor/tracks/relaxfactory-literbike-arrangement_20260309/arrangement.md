# Arrangement: RelaxFactory -> Litebike/Literbike -> TrikeShed

## Intent

Import the useful transport lineage without repeating the architectural overreach around CCEK.

## Owner Matrix

- **Reactor / no-container-cost dispatch**
  - **Lineage source:** `../RelaxFactory/README.md`, `../literbike/conductor/tracks.md`
  - **TrikeShed owner:** future runtime/reactor surface (`one/xio` or dedicated reactor module), not `ccek`
  - **Reason:** reactor is a runtime/event-loop concern, not a coroutine key concern

- **Universal listener / protocol combinators**
  - **Lineage source:** `../literbike/src/universal_listener.rs`, `../2litebike/src/universal_listener.rs`
  - **TrikeShed owner:** `src/commonMain/kotlin/borg/trikeshed/net/` plus `src/commonMain/kotlin/borg/trikeshed/context/`
  - **Reason:** protocol detection, handler lookup, and prefix preservation are routing/combinator concerns

- **Prefixed stream / preserved peek buffer**
  - **Lineage source:** `PrefixedStream` in the universal listener
  - **TrikeShed owner:** `net`
  - **Reason:** it is transport-routing machinery and should compose with protocol handlers directly

- **HTTP transport detection + model/API overlay classification**
  - **Lineage source:** `../literbike/src/universal_listener.rs`, `../literbike/src/model_serving_taxonomy.rs`
  - **TrikeShed owner:** `net` with optional `context` lookup support
  - **Reason:** model/API overlay enriches HTTP handling; it is not its own transport

- **Parser salvage from `*shed` line**
  - **Lineage source:** `../QuadShed/README.md`
  - **TrikeShed owner:** future parser/indexing surface adjacent to `net` or data-ingest modules, not `ccek`
  - **Reason:** `QuadShed` contributes JSON index/reify/path parsing lineage that can be pulled in through bounded tests

- **Parser salvage from archival `superbikeshed` modules**
  - **Lineage source:** `/Users/jim/work/old/v2superbikeshed/trikeshed-http/src/commonMain/kotlin/borg/trikeshed/net/http/HttpParser.kt`, `/Users/jim/work/old/v2superbikeshed/trikeshed-json/src/commonMain/kotlin/borg/trikeshed/core/SimpleJsonScanner.kt`, `/Users/jim/work/old/v2superbikeshed/trikeshed-json/src/commonMain/kotlin/borg/trikeshed/core/FastJsonScanner.kt`
  - **TrikeShed owner:** `net` for protocol parsing and future bounded parser/indexing surfaces for JSON scanning
  - **Reason:** the archived module split already isolated HTTP and JSON parser concerns in ways worth mining through tests instead of folklore

- **Service-adapter bridges from `*shed` line**
  - **Lineage source:** `../TrikeShedBridge/src/nativeMain/kotlin/Bridge.kt`
  - **TrikeShed owner:** adapter/interop surfaces only
  - **Reason:** narrow ingestion bridges are service adapters, not transport arrangement centers

- **Service-manager salvage from archival `superbikeshed` modules**
  - **Lineage source:** `/Users/jim/work/old/v2superbikeshed/trikeshed-services/src/commonMain/kotlin/borg/trikeshed/services/ChannelizedServiceManager.kt`
  - **TrikeShed owner:** bounded service-adapter or broker surfaces only
  - **Reason:** the service-manager split is useful lineage, but the current implementation is stub-heavy and should arrive only via failing specs

- **CCEK typed services**
  - **Lineage source:** TrikeShed merge experiment and `literbike` QA claims
  - **TrikeShed owner:** `src/commonMain/kotlin/borg/trikeshed/ccek/`
  - **Reason:** use CCEK only for injected capabilities such as `HomeDirService`, `SeekHandleService`, indicator/service handles, and transport capability carriers

- **Context composition experiments**
  - **Lineage source:** `/Users/jim/work/old/v2superbikeshed/trikeshed-context/src/commonMain/kotlin/borg/trikeshed/context/ContextDeck.kt`
  - **TrikeShed owner:** `context` experiments only
  - **Reason:** deck-style context composition is a useful exploration artifact, but it must not replace typed coroutine-key services or routing ownership boundaries

- **QUIC transport runtime**
  - **Lineage source:** `../literbike/conductor/tracks.md`, `../literbike/QUIC_TEST_STATUS.md`
  - **TrikeShed owner:** future transport/runtime modules, with `ccek/transport` limited to capability wrappers
  - **Reason:** handshake state, stream multiplexing, and reactor integration exceed the remit of typed context keys

## Negative Decisions

- Do **not** make `ccek` the owner of the universal listener.
- Do **not** import `literbike`'s string-keyed `ContextElement` / `EmptyContext` map semantics into TrikeShed Kotlin.
- Do **not** treat placeholder shims like `../literbike/src/protocol_registry.rs` as settled architecture.
- Do **not** import archival `CcekContext` / `QuicCCEK` meta-orchestrators into current TrikeShed as if they were proven runtime design.
- Do **not** "fix" the repo by deleting failing tests or broken compile surfaces.
- Do **not** collapse edge/runtime arrangement from `litebike` into a single undifferentiated transport blob inside TrikeShed.

## Negative Evidence

- `../literbike/src/concurrency/ccek.rs` implements CCEK as a string-keyed runtime map. That is useful only as a record of overreach and test scaffolding pressure.
- `../literbike/src/reactor/context.rs` shows reactor ownership being pushed through that CCEK layer via `"ReactorService"` string lookup. TrikeShed should not repeat that mistake.
- `../litebike/tests/modelmux/test_8888_cloaking.rs` preserves the parser-combinator direction as red/aspirational TDD evidence. Keep the intent; do not pretend the test prose is already a finished architecture.
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/CCEK.kt` expands CCEK into a four-field-plus meta-context with rules, constraints, validators, and transport adjuncts. That is exactly the scope creep this arrangement rejects.
- `/Users/jim/work/old/v2superbikeshed/trikeshed-quic/src/commonMain/kotlin/borg/trikeshed/net/quic/QuicCCEK.kt` pushes full QUIC control/context/environment/knowledge orchestration into CCEK-named structures. Preserve it as history, not target architecture.
- A repeated historical failure mode is model/training bias toward conventional frameworks. When the lineage says reactor, universal listener, context deck, or parser combinator, do not silently rewrite it into servlet, handler registry, DI container, or direct socket folklore.

## TDD Stance

- Preserve current red code/tests as debt inventory.
- Add new failing contract tests for universal-listener behavior before implementation.
- Route parser/service salvage from sibling repos through failing specs first; do not bulk-port wishful modules.
- Treat archival `superbikeshed` modules the same way: extract bounded contracts, never bulk-revive the whole module graph.
- Claim only local progress on the bounded slice under test; unrelated red debt remains tracked until explicitly reconciled.

## Immediate Follow-On

1. Capture the current red ledger in conductor truth.
2. Add failing TrikeShed contracts for protocol detection and prefix preservation.
3. Reconcile `HandlerRegistry` and `ProtocolRouter` to that contract.
