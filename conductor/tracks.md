# TrikeShed Tracks

Active development tracks for TrikeShed.

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

**Status:** 🔄 Open

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

## [ ] Track: Stream Transport Implementation

**Track ID:** `stream-transport-contracts_20260310`

**Branch:** `master`

**Purpose:** Implement `openStream()` in `QuicChannelService` and `NgSctpService` to green 6 failing `StreamTransportContractTest` contract tests.

**Status:** 🔄 Open — stream-01 contracts written (6 red tests); implementation pending

**Slices:** `stream-01` ✅ failing contracts · `stream-02` QUIC stream factory · `stream-03` SCTP stream factory

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
