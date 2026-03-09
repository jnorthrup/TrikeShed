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

## [~] Track: CCEK Keyed Services Infrastructure

**Track ID:** `ccek-keyed-services_20260309`

**Branch:** `master` (source branch merged; detached worktree remains at `../TrikeShed-ccek`)

**Purpose:** Keep only the minimal typed coroutine-key service substrate in TrikeShed `commonMain`. Transport architecture ownership is being moved out to a separate arrangement track.

**Status:** 🔄 Course-correcting after merge

**Summary:**
- Base `KeyedService`/`coroutineService` and minimal service wrappers were merged.
- The original track overstated CCEK as a transport architecture owner.
- The retained CCEK value for network/protocol work is coverage-oriented: typed capability lookup, transport contract seams, and fixture/runtime hint injection.
- `ccek/transport/*` now remains only as capability-carrier scaffolding until the transport arrangement track lands.

**Slices:** `ccek-01` worktree+base · `ccek-02` transport designs · `ccek-03` HomeDirService · `ccek-04` SeekHandleService · `ccek-05` IndicatorContextService · `ccek-06` tests · `ccek-07` coverage inventory

**Plan:** `conductor/tracks/ccek-keyed-services_20260309/plan.md`

---

## [ ] Track: RelaxFactory/Literbike Transport Arrangement & Red-TDD Preservation

**Track ID:** `relaxfactory-literbike-arrangement_20260309`

**Branch:** `master`

**Purpose:** Course-correct TrikeShed transport architecture using the real lineage from `../RelaxFactory`, `../litebike`, `../literbike`, and `../2litebike`. Preserve red tests/code as evidence; do not greenwash by deletion.

**Status:** 🆕 Open

**Summary:**
- `RelaxFactory` contributes the reactor/no-container-cost dispatch lineage.
- `literbike` contributes the universal listener shape, prefixed-stream preservation, QUIC/reactor split, and practical transport salvage.
- `litebike` contributes the edge-vs-heavy-runtime arrangement and zero-cost abstraction emphasis.
- `QuadShed` contributes parser lineage; `TrikeShedBridge` contributes service-adapter lineage.
- Archived `old/v2superbikeshed/*` modules extend the parser/service corpus and preserve earlier transport/context splits.
- String-keyed CCEK experiments in the bike line are recorded as negative evidence, not canonical direction.
- CCEK is demoted to minimal typed service injection, not the protocol architecture center.

**Slices:** `arrange-01` lineage truth materialization · `arrange-02` red-ledger capture · `arrange-03` universal-listener failing contracts · `arrange-04` handler/router reconciliation · `arrange-05` parser/service salvage triage

**Plan:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/plan.md`

**Arrangement:** `conductor/tracks/relaxfactory-literbike-arrangement_20260309/arrangement.md`

---

## [ ] Track: Channelized Blackboard Platform

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

**Slices:** `phase-00` vocabulary freeze · `phase-01` minimal channelization planner · `phase-02a` session identity · `phase-02b` block exchange · `phase-02c` planner projection · `phase-02d` HTTP-like proof · `phase-03` blackboard overlay core · `phase-04` graph/job surface · `phase-05` first protocol slice · `phase-06` backend tightening

**Plan:** `conductor/tracks/channelized-blackboard-platform_20260309/plan.md`

---

## [~] Track: Unified Kotlingrad DSEL for Pretesting + Paper Testing Drawdown

**Track ID:** `kotlingrad-unified-dsel-pretest-paper-dd_20260302`

**Purpose:** Build a stable, testable Kotlingrad DSEL layer for drawdown-related pretesting and paper-testing contracts.

**Status:** 🔄 In Progress

**Summary:**
- Restored TrikeShed as Gradle source-of-truth
- Cleared duplicated library code from sibling repos
- Documented Gradle consumption logic
- Synchronized boundaries in product/tech-stack docs
- Added deterministic drawdown/max-drawdown DSEL contract tests in `DselBenchmarkTest`
- Focused JVM verification currently blocked by Gradle wrapper distribution download under network-restricted runtime
- Next slice: `kg-dd-test-contract-02` (execute focused JVM test once local Gradle distribution is available and capture evidence)
