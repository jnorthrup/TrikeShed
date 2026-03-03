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

## [~] Track: Unified Kotlingrad DSEL for Pretesting + Paper Testing Drawdown

**Track ID:** `kotlingrad-unified-dsel-pretest-paper-dd_20260302`

**Purpose:** Build a stable, testable Kotlingrad DSEL layer for drawdown-related pretesting and paper-testing contracts.

**Status:** 🔄 In Progress

**Summary:**
- Restored TrikeShed as Gradle source-of-truth
- Cleared duplicated library code from sibling repos
- Documented Gradle consumption logic
- Synchronized boundaries in product/tech-stack docs
