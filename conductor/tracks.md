# TrikeShed Tracks

Active development tracks for TrikeShed.

---

## [x] Track: Native Grad Analytics

**Track ID:** `native-grad-analytics_20260314`

**Branch:** `master`

**Purpose:** Reimplement the deleted kotlingrad layer in plain Kotlin `Double` forward-mode autodiff — `Dual` number type, `GradOps`, `SeriesGrad`, `DrawdownDsel`, and `DiffDuckCursor` (emaFold/macdFold/softPnlFold) — with no external autodiff dependency.

**Status:** ✅ Completed — 4 tests green

**Slices:** `grad-01` ✅ Dual + GradOps · `grad-02` ✅ SeriesGrad · `grad-03` ✅ DrawdownDsel · `grad-04` ✅ DiffDuckCursor · `grad-05` ✅ contract tests

**Plan:** `conductor/tracks/native-grad-analytics_20260314/plan.md`

---

## [x] Track: Freqtrade Retirement and Feature Extraction

**Track ID:** `freqtrade-retirement-and-extraction_20260303`

**Purpose:** Decommission freqtrade by extracting beneficial logic into TrikeShed algebra and moneyfan DSEL contracts.

**Status:** ✅ Completed

**Summary:**

- Implemented Williams%R indicator (Stochastic & ADX already existed)
- Extracted ROI/Stoploss rules into StrategyRules.kt DSEL contracts
- Ported SampleStrategy signal logic into SignalGenerator.kt
- All tasks completed with tests

---

## [x] Track: CCEK Keyed Services Infrastructure

**Track ID:** `ccek-keyed-services_20260309`

**Branch:** `master` (source branch merged; detached worktree remains at `../TrikeShed-ccek`)

**Purpose:** Keep only the minimal typed coroutine-key service substrate in TrikeShed `commonMain`. Transport architecture ownership is being moved out to a separate arrangement track.

**Status:** ✅ Completed

**Summary:**

- Base `KeyedService`/`coroutineService` and minimal service wrappers were merged.
- The original track overstated CCEK as a transport architecture owner.
- The retained CCEK value for network/protocol work is coverage-oriented: typed capability lookup, transport contract seams, and fixture/runtime hint injection.
- `ccek/transport/*` now remains only as capability-carrier scaffolding until the transport arrangement track lands.

**Slices:** `ccek-01` worktree+base · `ccek-02` transport designs · `ccek-03` HomeDirService · `ccek-04` SeekHandleService · `ccek-05` IndicatorContextService · `ccek-06` tests · `ccek-07` coverage inventory

**Plan:** `conductor/tracks/ccek-keyed-services_20260309/plan.md`

---

## [x] Track: RelaxFactory/Literbike Transport Arrangement & Red-TDD Preservation

**Track ID:** `relaxfactory-literbike-arrangement_20260309`

**Branch:** `master`

**Purpose:** Course-correct TrikeShed transport architecture using the real lineage from `../RelaxFactory`, `../litebike`, `../literbike`, and `../2litebike`. Preserve red tests/code as evidence; do not greenwash by deletion.

**Status:** ✅ Completed

**Summary:**

- `RelaxFactory` contributes the reactor/no-container-cost dispatch lineage.
- `literbike` contributes the universal listener shape, prefixed-stream preservation, QUIC/reactor split, and practical transport salvage.
- `litebike` contributes the edge-vs-heavy-runtime arrangement and zero-cost abstraction emphasis.
- `QuadShed` contributes parser lineage; `TrikeShedBridge` contributes service-adapter lineage.
- Archived `old/v2superbikeshed/*` modules extend the parser/service corpus and preserve earlier transport/context splits.
- String-keyed CCEK experiments in the bike line are recorded as negative evidence, not canonical direction.
- CCEK is demoted to minimal typed service injection, not the protocol architecture center.
- RFC 7230 request-line and header parsing salvaged from archival lineage as failing TDD contracts; implementation deferred to next track.

**Slices:** `arrange-01` ✅ · `arrange-02` ✅ · `arrange-03` ✅ universal-listener contracts · `arrange-04` ✅ handler/router reconciliation · `arrange-05` ✅ parser/service salvage triage

**Plan:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/plan.md`

**Arrangement:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/arrangement.md`

---

## [x] Track: Channelized Blackboard Platform

**Track ID:** `channelized-blackboard-platform_20260309`

**Branch:** `master`

**Purpose:** Build the smallest viable channelized assembly/graph/job/block platform around Cursor/RowVec and CCEK without letting future model passes skip ahead into generic DI/reactor/socket abstractions.

**Status:** ✅ Completed

**Summary:**

- Treat `Cursor` as dataframe substrate and `RowVec` as row/x-projection.
- Keep CCEK assemblies as keyed attraction points, not library end-state.
- Keep NIO as a backend projection/shim, not public architecture.
- Add explicit phase gates so future LLM passes cannot jump to a fake end-state.
- Preserve a separate blackboard overlay track from `TypeMemento`/`IOMemento`.

**Slices:** `phase-00` ✅ · `phase-01` ✅ · `phase-02a` ✅ · `phase-02b` ✅ · `phase-02c` ✅ · `phase-02d` ✅ · `phase-03` ✅ blackboard overlay core · `phase-04` ✅ graph/job surface · `phase-05` ✅ HTTP ingress slice · `phase-06` ✅ backend tightening

**Plan:** `conductor/tracks/channelized-blackboard-platform_20260309/plan.md`

**Progress:**

- Phase-03 Blackboard Overlay Core completed with full implementation and tests
- Added overlay types: `OverlayRole`, `Provenance`, `Evidence`, `DependencyHandle`, `CellOverlay<T>`, `ColumnOverlay`, `BlackboardContext`
- Added 25+ unit tests covering all overlay types and extension functions
- Implementation is additive and preserves backward compatibility with existing cursor/ISAM semantics
- Phase-04 Graph and Job Surface completed with full implementation and tests
- Added graph/job types: `ChannelGraph`, `ChannelJob`, `WorkerKey`, `GraphFact`, `ActivationRule`, `ChannelGraphService`
- Added 40+ unit tests covering graph/job lifecycle, activation rules, and integration with channelization planner
- Graph/job layer is minimal without scheduler/actor framework complexity; jobs activate from facts without transport details leaking upward

---

## [x] Track: JVM Compile Repair

**Track ID:** `jvm-compile-repair_20260310`

**Branch:** `master`

**Purpose:** Restore default JVM compilation in bounded slices by repairing one compile blocker at a time.

**Status:** ✅ Completed

**Slices:** `jvmfix-01` ✅ `HttpMethod.kt` syntax repair · `jvmfix-02` ✅ `BrcDuckDbJvm.kt` Series access repair · `jvmfix-03` ✅ `HttpHeaders.kt` typed map initializer repair · `jvmfix-04a` ✅ `CookieRfc6265Util.kt` JVM ByteBuffer realignment · `jvmfix-05` ✅ `HttpMethod.kt` killswitch JVM signature clash

**Plan:** `conductor/tracks/jvm-compile-repair_20260310/plan.md`

---

## [x] Track: JVM Test Compile Repair

**Track ID:** `jvm-test-compile-repair_20260310`

**Branch:** `master`

**Purpose:** Restore JVM test compilation in bounded slices after default JVM compilation is green.

**Status:** ✅ Completed

**Slices:** `jvmtest-01` ✅ `SmMsgPackTest.kt` MsgPack dependency repair

**Plan:** `conductor/tracks/jvm-test-compile-repair_20260310/plan.md`

---

## [ ] Track: JSON Runtime Stack Overflow Repair

**Track ID:** `json-runtime-stack-overflow-repair_20260310`

**Branch:** `master`

**Purpose:** Restore JVM JSON-path/parser runtime behavior by repairing the `Series` recursion causing `StackOverflowError` in JSON tests.

**Status:** 🔄 Open

**Slices:** `jsonso-01` ✅ `Series.kt` iterable-map recursion repair · `jsonso-02` `Json.kt` segment boundary pair repair

**Plan:** `conductor/tracks/json-runtime-stack-overflow-repair_20260310/plan.md`

---

## [ ] Track: JSON Scan Autovec Shaping

**Track ID:** `json-scan-autovec-shaping_20260311`

**Branch:** `master`

**Purpose:** Reshape TrikeShed's JSON structural scan so the hot loop is embarrassingly vectorizable: contiguous indexed access, explicit `Int` induction, no iterator/polymorphic traversal overhead, and a clean split between scan and reify.

**Status:** 🔄 Open

**Slices:** `autovec-00` ✅ truth + gate capture · `autovec-01` JSON scan/reify red contracts · `autovec-02` `JsonBitmap.kt` induction-shape repair · `autovec-03` `Json.kt` scan extraction · `autovec-04` `Json.kt` reify consumption tightening · `autovec-05` `CsvBitmap.kt` parity follow-on

**Plan:** `conductor/tracks/json-scan-autovec-shaping_20260311/plan.md`

---

## [ ] Track: Manifold Semantic Layer

**Track ID:** `manifold-semantic-layer_20260311`

**Branch:** `master`

**Purpose:** Add the minimum working manifold surface to TrikeShed itself in pure Kotlin/commonMain with semantic coordinates first and dense lowered views second.

**Status:** 🔄 Open

**Summary:**

- TrikeShed needs the manifold locally, not as cppfort narration.
- The first slice is pure Kotlin and independent of Kotlingrad.
- The semantic-vs-dense split is explicit so the same type does not pretend to be both.
- `Manifold.kt` and `ManifoldTest.kt` are complete and verified (4 passing tests).

**Slices:** `manifold-01` ✅ semantic coordinates + dense separation · `manifold-02` ✅ chart + atlas lookup · `manifold-03` ✅ transition/reprojection contracts · `manifold-04` tangent/jacobian follow-on

**Plan:** `conductor/tracks/manifold-semantic-layer_20260311/plan.md`

---

## [ ] Track: Cpp2 Surface Transition

**Track ID:** `cpp2-surface-transition_20260311`

**Branch:** `master`

**Purpose:** Port Kotlin text into an expanded cpp2 spec locally, using TrikeShed as the reference surface while refusing unverified sibling-repo completion claims.

**Status:** 🔄 Open

**Summary:**

- Imported Grok material captured useful architecture signal but also speculative generated code and unverified sibling-repo claims.
- User direction is now explicit: Kotlin is the ideal surface and cpp2 needs to be brought up to it.
- This track is local spec work first, not sibling-repo implementation theater.
- The current slice is the manifold/coordinates/atlas text port in [expanded_cpp2_spec.md](/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md).

**Slices:** `cpp2surf-01` ✅ transcript intake + course correction · `cpp2surf-02` ✅ Kotlin-first posture capture · `cpp2surf-03` ✅ expanded cpp2 manifold spec + dogfood · `cpp2surf-04` broader Kotlin text port · `cpp2surf-05` external dogfood verification only after a real slice

**Plan:** `conductor/tracks/cpp2-surface-transition_20260311/plan.md`

---

## [x] Track: Stream Transport Implementation

**Track ID:** `stream-transport-contracts_20260310`

**Branch:** `master`

**Purpose:** Implement `openStream()` in `QuicChannelService` and `NgSctpService` to green 6 failing `StreamTransportContractTest` contract tests.

**Status:** ✅ Completed

**Slices:** `stream-01` ✅ failing contracts · `stream-02` ✅ QUIC stream factory · `stream-03` ✅ SCTP stream factory

**Summary:**

- Implemented `openStream()` in `QuicChannelService` and `NgSctpService`
- Both services now allocate non-negative stream ids and open buffered send/recv channels
- Default service instances record opened streams so `activeStreams` increments as contracted
- `StreamTransportContractTest` is fully green under the focused transport slice

**Plan:** `conductor/tracks/stream-transport-contracts_20260310/plan.md`

---

## [x] Track: HTTP Parser Implementation

**Track ID:** `http-parser-implementation_20260310`

**Branch:** `master`

**Purpose:** Implement RFC 7230 HTTP request-line and header parsing contracts in `commonMain` to green the 9 failing `HttpParserContractTest` tests established in arrange-05.

**Status:** ✅ Completed

**Slices:** `http-01` ✅ request-line + header implementation

**Plan:** `conductor/tracks/http-parser-implementation_20260310/plan.md`

---

## [x] Track: Unified Kotlingrad DSEL for Pretesting + Paper Testing Drawdown

**Track ID:** `kotlingrad-unified-dsel-pretest-paper-dd_20260302`

**Purpose:** Build a stable, testable Kotlingrad DSEL layer for drawdown-related pretesting and paper-testing contracts.

**Status:** ✅ Completed

**Summary:**

- Restored TrikeShed as Gradle source-of-truth
- Cleared duplicated library code from sibling repos
- Documented Gradle consumption logic
- Synchronized boundaries in product/tech-stack docs
- Added deterministic drawdown/max-drawdown DSEL contract tests in `DselBenchmarkTest`
- Moved `DrawdownDsel.kt` from commonMain to jvmMain (JVM-only Kotlingrad dependency)
- Fixed SeriesGrad.kt conflicting overloads and unresolvable min/max calls
- Added `minOf`/`maxOf` symbolic infix extensions to GradOps.kt
- All 3 DselBenchmarkTest tests pass: drawdown fraction, max-drawdown running-min, throughput benchmark

---

## [ ] Track: Userspace Kernel Emulation Port

**Track ID:** `userspace-port_20260314`

**Branch:** `master`

**Purpose:** Import architectural patterns from `../userspace` Rust library for Kotlin structured concurrency, kernel bypass (io_uring), eBPF JIT, and transport primitives while adapting to Kotlin's coroutine infrastructure.

**Status:** 🔄 Open

**Summary:**

- userspace provides Rust structured concurrency patterns (Job/Scope/Dispatcher/Deferred/Cancel) applicable to Kotlin coroutines
- io_uring kernel bypass requires Kotlin/Native Linux target
- Network adapter patterns (HTTP/QUIC/SSH) align with TrikeShed protocol routing
- All ports must follow TDD: failing Kotlin tests before implementation
- Kotlin/Native target infrastructure needs setup before kernel bypass features

**Slices:** `userspace-01` [x] architectural survey · `userspace-02` [SKIP] · `userspace-03` [ ] Kotlin/Native setup · `userspace-04` [ ] syscall interface design · `userspace-05` [ ] io_uring bindings prototype

**Plan:** `conductor/tracks/userspace-port_20260314/plan.md`

**Arrangement:** `conductor/tracks/userspace-port_20260314/arrangement.md`

---

## [ ] Track: 1BRC Benchmark & DuckDB Test Integration

**Track ID:** `brc-benchmark-duckdb-test_20260314`

**Branch:** `master`

**Purpose:** Ensure One Billion Row Challenge (1BRC) benchmarks run correctly and DuckDB dependencies are confined to test scope only. Fix BRC test configuration issues and verify all variants compile and execute.

**Status:** 🔄 Open

**Summary:**

- DuckDB JDBC dependency in jvmMain (production) scope violates test-only requirement
- BrcHarnessTest references missing variants (BrcFixedPoint, BrcIsamJvm) and has package mismatch
- DuckDB integration code exists in jvmMain and posixMain source sets
- enableBrcTests Gradle property correctly gates heavy BRC tests
- Test resources (measurements_test.txt, expected_output.txt) are in place

**Slices:** `brc-01` ✅ DuckDB scope audit · `brc-02` [ ] test import fix · `brc-03` [ ] missing variants · `brc-04` [ ] test execution · `brc-05` [ ] native execution

**Plan:** `conductor/tracks/brc-benchmark-duckdb-test_20260314/plan.md`

**Arrangement:** `conductor/tracks/brc-benchmark-duckdb-test_20260314/arrangement.md`

---

## [x] Track: 1BRC CommonTest IO Matrix

**Track ID:** `brc-commontest-io-matrix_20260314`

**Branch:** `master`

**Purpose:** `commonTest` contract suite for the full IO access matrix using 1BRC as forcing function.

**Status:** ✅ Completed — 51 tests across 6 slices, all green

**Plan:** `conductor/tracks/brc-commontest-io-matrix_20260314/plan.md`

---

## [ ] Track: BRC Slab Gateway Sort

**Track ID:** `brc-slab-gateway-sort_20260314`

**Branch:** `master`

**Purpose:** Test all existing BRC Cursor IO varieties (BrcCursor, BrcPure, BrcDiscoveryOrder, BrcHashArray, BrcHeapBisect, BrcMmap, BrcParallel, BrcDuckDbJvm) in sequence — Cursor variants first — verifying each produces identical output against a `/tmp` fixture. Nothing written to repo.

**Status:** 🔄 Open

**Slices:** `gatewayseq-01` [ ] Cursor IO varieties (Cursor/Pure/DiscoveryOrder) · `gatewayseq-02` [ ] remaining JVM (HashArray/HeapBisect/Mmap/Parallel) · `gatewayseq-03` [ ] DuckDB gateway · `gatewayseq-04` [ ] full sequence all-match

**Plan:** `conductor/tracks/brc-slab-gateway-sort_20260314/plan.md`
