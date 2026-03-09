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

## [ ] Track: CCEK Keyed Services Infrastructure

**Track ID:** `ccek-keyed-services_20260309`

**Branch:** `feat/ccek-keyed-services` (git worktree at `../TrikeShed-ccek`)

**Purpose:** Add minimal CCEK keyed service infrastructure to TrikeShed `commonMain`. Refactor 3 existing services + add 2 coexisting transport designs (ngSCTP + channelized QUIC, no AI/ML).

**Status:** 🆕 Open

**Slices:** `ccek-01` worktree+base · `ccek-02` transport designs · `ccek-03` HomeDirService · `ccek-04` SeekHandleService · `ccek-05` IndicatorContextService · `ccek-06` tests

**Plan:** `conductor/tracks/ccek-keyed-services_20260309/plan.md`

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
