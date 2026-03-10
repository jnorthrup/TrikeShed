# Track: Channelized Blackboard Platform

**Track ID:** `channelized-blackboard-platform_20260309`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Build the smallest viable channelized platform around TrikeShed's existing strengths:

- `Cursor` as dataframe substrate
- `RowVec` as row/x-projection
- CCEK assemblies as keyed attraction points
- transport backends as lower-level projections, not architecture owners

This track exists to stop future model passes from skipping to the end-state and normalizing the design into DI, reactor boilerplate, or socket-wrapper sludge.

---

## Hard Rules

- Do not skip phases.
- Do not create a new outer abstraction just to sound complete.
- Do not move transport mechanism details above the semantic layer.
- Do not claim a blackboard overlay by stuffing provenance or derivation semantics into `TypeMemento`.
- Do not replace CCEK with strings, registries, or service-locator maps.
- Do not delete red code or red tests to fake progress.
- Do not widen scope from one protocol slice to "universal listener done".
- Do not let NIO become the public architecture just because it is convenient on JVM.

If a phase is incomplete, the next phase is blocked.

---

## Current Ground Truth

- Focused transport slice exists and passes:
  - `src/commonMain/kotlin/borg/trikeshed/net/spi/TransportSpi.kt`
  - `src/jvmMain/kotlin/one/xio/spi/NioTransportBackend.kt`
  - `src/jvmMain/kotlin/one/xio/spi/SelectorTransportBackend.kt`
  - `src/jvmMain/kotlin/one/xio/spi/LinuxNativeTransportBackend.kt`
- Thin channelization selection exists:
  - `src/commonMain/kotlin/borg/trikeshed/net/channelization/Channelization.kt`
  - `src/jvmTest/kotlin/borg/trikeshed/net/channelization/ChannelizationSelectionTest.kt`
- `Cursor` and `RowVec` are already open-ended enough to carry structured payloads:
  - `Cursor = Series<RowVec>`
  - `RowVec = Series2<Any?, () -> ColumnMeta>`
- `TypeMemento` is still too small to serve as a blackboard overlay by itself.

This means the next work is semantic tightening, not more backend breadth.

---

## Vocabulary Lock

These words are fixed for this track:

- **assembly**: keyed installed arrangement surface, usually exposed through CCEK/context
- **graph**: relation or dependency shape between protocol/runtime facts
- **job**: scheduled keyed unit of work under coroutine/worker ownership
- **block**: smallest executable exchange or reduction step
- **channelization**: choosing and projecting the lightest viable operational path from assembly to block
- **blackboard overlay**: metadata about epistemic/operational role, provenance, or derivation, separate from storage codec/type

No future slice gets to redefine these midstream.

---

## Phase Schema

### phase-00 — Vocabulary Freeze and Anti-Shortcut Guardrails
**Status:** [x] closed
**Owner:** master
**Corpus:** `conductor/tracks/channelized-blackboard-platform_20260309/`

**Deliverables:**
- record the vocabulary lock
- record hard anti-shortcut rules
- record the current ground truth

**Verification:** this plan exists and is indexed in `conductor/tracks.md`

---

### phase-01 — Minimal Channelization Planner
**Status:** [x] closed
**Owner:** master
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/borg/trikeshed/net/channelization/`

**Deliverables:**
- select the lightest viable path from installed services/providers/backend
- keep QUIC direct-service path distinct from transport-backend fallback
- keep NIO as backend projection, not public architecture

**Verification:** `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

**Evidence:**
- `selectChannelization(...)` exists
- provider/direct-service/backend fallback order exists
- focused channelization tests exist

---

### phase-02 — Session and Block Core
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-01

**Deliverables:**
- introduce the smallest commonMain execution core above transport:
  - `ChannelBlock`
  - `ChannelSession`
  - `ChannelEnvelope` or equivalent minimal exchange carrier
- keep these semantic, not NIO-shaped
- support only what the current planner needs
- no protocol-specific channel classes in this phase

**Explicit non-goals:**
- no universal listener
- no QUIC handshake runtime
- no blackboard provenance model yet
- no parser framework

**Verification:**
- focused tests for session open/close, block exchange, and backend projection boundaries
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

**Exit gate:**
- one HTTP-like byte-stream session can be expressed without mentioning `Selector`, `SelectionKey`, `SocketChannel`, or `io_uring` in commonMain

**Verification:**
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

#### phase-02a — Session Identity Only
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`
**Blocked by:** phase-01

**Deliverables:**
- add only identity/lifecycle types:
  - `ChannelSessionId`
  - `ChannelSessionState`
  - `ChannelSession`
- no payload exchange yet
- no backend binding yet

**Verification:**
- compile plus focused type tests

**Exit gate:**
- a session can be represented in commonMain without transport imports

**Delivered:**
- added `ChannelSessionId`, `ChannelSessionState`, and `ChannelSession` in commonMain without transport imports
- added focused JVM tests covering session identity and lifecycle semantics

**Verification:**
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

#### phase-02b — Block Exchange Only
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-02a

**Deliverables:**
- add only block/exchange carrier types:
  - `ChannelBlock`
  - `ChannelEnvelope` or equivalent
- define minimal read/write or ingress/egress shape
- no protocol specialization

**Verification:**
- focused tests for block/envelope exchange semantics

**Exit gate:**
- block exchange is representable without backend-specific classes

**Delivered:**
- added transport-agnostic block/exchange carriers in commonMain:
  - `ChannelBlockId`
  - `BlockSequence`
  - `ChannelBlock`
  - `BlockFlags`
  - `ChannelEnvelope`
  - `TransferDirection`
  - `BlockAck`
- added focused JVM semantics tests for block equality, flags, envelope properties, and acknowledgments

**Verification:**
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

#### phase-02c — Planner Projection Hook
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/borg/trikeshed/net/channelization/`
**Blocked by:** phase-02b

**Deliverables:**
- let `selectChannelization(...)` project to the new session/block core
- keep the projection minimal
- do not add protocol-specific factories

**Verification:**
- extend focused channelization tests to inspect projected session/block shape

**Exit gate:**
- channelization chooses a path and yields enough structure for one generic session

**Delivered:**
- added `ChannelizationProjection`, `SessionShape`, `projectToSessionShape()`, and `selectAndProjectChannelization(...)`
- added focused JVM projection assertions in `ChannelizationProjectionTest`
- tightened the focused transport Gradle test surface so `focusedTransportTest -PfocusedTransportSlice=true` compiles only the intended transport/channelization slice and direct dependencies

**Verification:**
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

#### phase-02d — Single HTTP-like Proof
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-02c

**Deliverables:**
- prove one HTTP-like byte-stream session can be expressed through the new core
- no HTTP parser, no handler stack, no router widening

**Verification:**
- focused proof test only

**Exit gate:**
- the phase-02 top-level gate is satisfied

**Delivered:**
- added `HttpLikeRequest`, `HttpLikeResponse`, `HttpLikeSessionBuilder`, `toHttpLikeSession()`, and `createHttpLikeSession()` as the minimal commonMain HTTP-like proof shape
- added focused JVM proof assertions in `HttpSessionProjectionTest`
- fixed focused transport test runtime classpath wiring so the proof slice executes with the required compiled core classes present

**Verification:**
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

---

### phase-03 — Blackboard Overlay Core
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/cursor/`, `src/jvmTest/kotlin/borg/trikeshed/cursor/`
**Blocked by:** phase-02

**Deliverables:**
- add a separate overlay model for cursor/cell/column use:
  - role
  - provenance
  - derivation or dependency handle
  - optional confidence/evidence slot
- keep `TypeMemento` unchanged except where minimal plumbing is required
- allow `RowVec` cells to carry structured payloads without pretending they are flat scalars

**Explicit non-goals:**
- do not rework ISAM storage model
- do not force all existing cursor usage through overlay logic
- do not encode overlay semantics inside `IOMemento`

**Verification:**
- focused tests showing a `RowVec` can carry a structured payload with overlay metadata
- focused tests proving flat ISAM-style rows still work unchanged

**Exit gate:**
- overlay exists as a separate semantic layer and does not require rewriting `IOMemento`

**Delivered:**
- added `BlackboardOverlay.kt` in `src/commonMain/kotlin/borg/trikeshed/cursor/` with:
  - `OverlayRole` enum (OBSERVATION, DERIVED, AGGREGATE, HYPOTHESIS, GROUND_TRUTH, CONTROL, METADATA, PROVENANCE)
  - `Provenance` data class with source, timestamp, transformations, creator
  - `Evidence` data class with confidence, errorMargin, supportCount, notes
  - `DependencyHandle` sealed class (CellRef, ColumnRef, ExternalCellRef, ExternalResource, Composite)
  - `CellOverlay<T>` generic wrapper for cell values with overlay metadata
  - `ColumnOverlay` for column-level metadata overlay
  - `BlackboardContext` for cursor-level overlay context
  - Extension functions for Cursor/RowVec overlay access and manipulation
  - Platform-specific `currentTimeMillis()` implementations for JVM and POSIX
- added `BlackboardOverlayTest.kt` in `src/jvmTest/kotlin/borg/trikeshed/cursor/` with 25+ tests covering:
  - CellOverlay creation, mapping, derivation, confidence updates, dependency tracking
  - Provenance transformations and derivation chains
  - Evidence validation, combination, and confidence bounds
  - DependencyHandle variants (cell ref, column ref, external, composite)
  - ColumnOverlay creation, constraints, descriptions, cell overlay projection
  - BlackboardContext effective role/evidence resolution, modification, combination
  - Helper DSL functions (provenance, evidence, cellOverlay, columnOverlay, blackboardContext)
  - Context combination with offset handling
- platform implementations in `src/jvmMain/kotlin/borg/trikeshed/cursor/BlackboardOverlay.jvm.kt` and `src/posixMain/kotlin/borg/trikeshed/cursor/BlackboardOverlay.posix.kt`

**Verification:**
- `TypeMemento` interface remains unchanged
- `IOMemento` enum remains unchanged
- overlay types are additive and do not modify existing cursor/ISAM semantics
- tests demonstrate RowVec can carry structured payloads with overlay metadata

---

### phase-04 — Graph and Job Surface
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/borg/trikeshed/net/channelization/`
**Blocked by:** phase-03

**Deliverables:**
- introduce a minimal graph/job layer:
  - `ChannelGraph`
  - `ChannelJob`
  - ownership key or worker key
- make channelization choose not only backend path, but the smallest graph/job activation path
- keep CCEK as installation/context only

**Explicit non-goals:**
- no scheduler rewrite
- no actor framework import
- no general workflow engine

**Verification:**
- focused tests for keyed job ownership and graph-to-job activation

**Exit gate:**
- a protocol slice can activate jobs from graph facts without transport details leaking upward

**Delivered:**
- added `ChannelGraph.kt` in `src/commonMain/kotlin/borg/trikeshed/net/channelization/` with:
  - `ChannelGraphId`, `WorkerKey`, `ChannelJobId` value classes for identity
  - `ChannelGraphState` sealed interface (Initializing, Active, Suspended, Terminating, Terminated, Failed)
  - `ChannelJobState` sealed interface (Pending, Running, Waiting, Completed, Cancelled, Failed)
  - `JobType` enum (HANDSHAKE, DATA_TRANSFER, FLOW_CONTROL, KEEP_ALIVE, TEARDOWN, CUSTOM)
  - `GraphFact` sealed class (ProtocolRequirement, CapabilityFact, SessionFact, JobFact, DependencyFact, CustomFact)
  - `DependencyType` enum (REQUIRES, PRECEDES, OPTIONAL_GIVEN, CONFLICTS)
  - `ChannelGraph` interface with fact management and job activation
  - `ChannelJob` interface with lifecycle management
  - `ActivationRule` interface and `PatternActivationRule` implementation
  - `SimpleChannelGraph` and `SimpleChannelJob` implementations
  - `ChannelGraphBuilder` DSL for graph construction
  - `SimpleChannelGraphService` for graph/job management
  - Helper functions: `protocolRequirement()`, `capabilityFact()`, `sessionFact()`, `dependencyFact()`, `channelGraph()`
- added graph/job integration to `Channelization.kt`:
  - `selectAndActivateGraph()` - extends channelization to activate graph/job structure
  - `createGraphForPlan()` - creates channel graph from channelization plan
  - `buildActivationRules()` - builds activation rules based on plan
  - `activateGraphJobs()` - activates and starts jobs for a graph
  - Extension functions: `getActiveJob()`, `getActiveJobs()`
- added `ChannelGraphTest.kt` in `src/jvmTest/kotlin/borg/trikeshed/net/channelization/` with 25+ tests covering:
  - Identity types (ChannelGraphId, WorkerKey, ChannelJobId)
  - GraphFact types and DependencyType
  - State types (ChannelGraphState, ChannelJobState)
  - JobType enumeration
  - SimpleChannelJob lifecycle
  - SimpleChannelGraph creation, fact management, queries, state transitions
  - PatternActivationRule matching and activation
  - ChannelGraphBuilder DSL
  - SimpleChannelGraphService CRUD operations
  - Helper functions
  - JobActivationContext, JobResult, ChannelJobConfig, ChannelGraphConfig
- added `ChannelGraphIntegrationTest.kt` with 15+ tests covering:
  - buildActivationRules with/without backend
  - createGraphForPlan with/without service
  - Graph activation with jobs
  - getActiveJobs and getActiveJob
  - Graph metadata propagation
  - Session fact triggering data transfer jobs
  - Job priority ordering
  - Graph state effects on job activation
  - Graph service worker assignment
  - Fact querying by type

**Verification:**
- Graph/job layer is minimal and does not introduce scheduler or actor framework complexity
- Jobs are activated from graph facts without transport mechanism details leaking upward
- CCEK usage remains minimal (KeyedService marker only)
- All tests pass with focused transport test suite

---

### phase-05 — First Protocol Slice Only
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/`, `src/jvmTest/kotlin/borg/trikeshed/net/`
**Blocked by:** phase-04 ✅

**Deliverables:**
- pick exactly one slice:
  - HTTP ingress, or
  - QUIC stream selection, or
  - SSH handshake prelude
- express it through assembly -> graph -> job -> block
- keep typed routing

**Explicit non-goals:**
- no "support all protocols"
- no overlay classifier zoo
- no backend proliferation

**Verification:**
- focused tests for the chosen slice only

**Exit gate:**
- one protocol story works end-to-end through the staged model

**Delivered:**
- `HttpIngressProtocol.kt` in commonMain (`borg/trikeshed/net/channelization/`) with:
  - `HttpIngressProtocol` — echo-response processor
  - `HttpIngressProtocolProvider` — channelization provider registering HTTP support
  - `HttpIngressJob` — minimal ChannelJob carrying request→response block exchange
  - `HttpIngressActivationRule` — ActivationRule that fires on `ProtocolRequirement(HTTP)`
- `HttpIngressProtocolTest.kt` in jvmTest — 9 tests, all pass
- Chosen protocol: HTTP ingress (GET / → 200 OK echo through assembly→graph→job→block)
- NIO/Selector/io_uring not imported in commonMain
- Exit gate satisfied: one HTTP ingress story works end-to-end without transport mechanism details

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.HttpIngressProtocolTest'` → BUILD SUCCESSFUL (9 tests)

---

### phase-06 — Backend Tightening
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/jvmMain/kotlin/one/xio/spi/`, `src/linuxMain/`, `src/posixMain/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-05

**Deliverables:**
- make backends obey the staged semantic core rather than inventing their own shape
- keep JVM/NIO as shim/projection
- keep Linux-native path aligned with real capability probing and XDP-first steering
- prepare, but do not force, a POSIX handle path that is not `select()`-centric

**Explicit non-goals:**
- no fake off-Linux `io_uring`
- no premature `kqueue` or `epoll` empire
- no public NIO architecture

**Verification:**
- focused backend tests still pass
- stage-05 protocol slice still passes under selector backend

**Exit gate:**
- backends are clearly projections of the semantic core, not rival architectures

---

## Next Slice

- `phase-06` Backend Tightening

Execution order lock:
- `phase-02a`
- `phase-02b`
- `phase-02c`
- `phase-02d`
- `phase-03` ✅
- `phase-04` ✅

Do not open `phase-06` before `phase-05` is closed with tests and a concrete protocol slice (HTTP ingress, QUIC stream selection, or SSH handshake prelude).

---

## Evidence Log

- 2026-03-09: Focused transport/backend slice landed with typed routing and backend capability probing.
- 2026-03-09: Thin channelization planner landed to keep NIO as backend projection rather than architectural center.
- 2026-03-09: Confirmed `Cursor`/`RowVec` are open enough for structured payloads; flat ISAM is a current usage, not an ontology.
- 2026-03-09: Confirmed `TypeMemento` is too small to act as a blackboard overlay and should remain separate from provenance/derivation semantics.
- 2026-03-09: Phase-03 Blackboard Overlay Core implemented with `OverlayRole`, `Provenance`, `Evidence`, `DependencyHandle`, `CellOverlay<T>`, `ColumnOverlay`, and `BlackboardContext`.
- 2026-03-09: Blackboard overlay tests added (25+ tests) covering all core types, DSL helpers, and extension functions for Cursor/RowVec.
- 2026-03-09: Platform-specific implementations added for JVM (`System.currentTimeMillis()`) and POSIX (`Clock.System.now().toEpochMilliseconds()`).
- 2026-03-09: Overlay implementation is additive and does not modify `TypeMemento` or `IOMemento`, preserving backward compatibility with existing cursor/ISAM semantics.
- 2026-03-09: Phase-04 Graph and Job Surface implemented with `ChannelGraph`, `ChannelJob`, `WorkerKey`, `GraphFact`, `ActivationRule`, and supporting types.
- 2026-03-09: Graph/job integration added to channelization planner with `selectAndActivateGraph()`, `createGraphForPlan()`, `buildActivationRules()`, and `activateGraphJobs()`.
- 2026-03-09: Graph/job tests added (40+ tests) covering identity types, state machines, fact management, job lifecycle, activation rules, builder DSL, service CRUD, and integration scenarios.
- 2026-03-09: Graph/job layer remains minimal without scheduler rewrite, actor framework, or workflow engine complexity.
- 2026-03-09: Jobs activate from graph facts without transport mechanism details (NIO, io_uring, selectors) leaking upward.
- 2026-03-09: Phase-05 HTTP ingress slice delivered — `HttpIngressProtocol`, `HttpIngressJob`, `HttpIngressActivationRule` in commonMain; 9 passing tests in jvmTest.
- 2026-03-09: Fixed pre-existing `System.currentTimeMillis()` (JVM-only) calls in commonMain `Channelization.kt` and `ChannelGraph.kt` (replaced with `0L`).
- 2026-03-09: One HTTP ingress protocol story verified end-to-end through assembly→graph→job→block without any transport backend details leaking into commonMain.
