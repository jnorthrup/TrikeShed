# Empirical Shadow Backtest Task Plan

## Summary
Build the first milestone as a deterministic shadow-only Dreamer backtest, not live trading. The current blocker is compile failure in [DataModels.kt](/Users/jim/work/TrikeShed/libs/dreamer-kmm/src/commonMain/kotlin/borg/trikeshed/dreamer/DataModels.kt:230): duplicate `Genome.ORDINALS` plus stale enum references. Use Jules-style narrow task packets, informed by [google-labs-code/jules-awesome-list](https://github.com/google-labs-code/jules-awesome-list), so Gemini Flash/Pro agents get exact files, commands, and acceptance checks.

## Task Packets
- **Packet 0, Flash: compile hygiene**
  Fix only `Genome` ordinal metadata in `libs/dreamer-kmm`. Keep one `ORDINALS` map derived from `GenomeParam.byKey`, keep aliases working, and add/adjust a small test proving `Genome.DEFAULT_DOUBLES.size == Genome.WIDTH`, `ordinalOf(...)`, and alias lookup. Acceptance: `./gradlew :libs:dreamer-kmm:jvmTest` reaches test execution.

- **Packet 1, Flash: deterministic fixtures**
  Pin `generatedArchiveCsv`, `archiveInputs`, and `BinanceVisionKlineFeed.parseCachedCsv` as the only data source for the first proof. Add tests that same seed/config produces identical sealed `KlineBlock`s, exact row counts, and no network/OpenAPI dependency.

- **Packet 2, Pro: stochastic bag/extents**
  Treat `StochasticBag`, `KlineRowSpan`, and `PairSpanWindow` as the stochastic extent algebra. Add tests for same-seed repeatability, repeated-draw advancement, `spanLength > rowCount`, `maxWindows == 0`, empty-source filtering, and non-empty pair spans when cursors overlap. Remove silent/aspirational behavior; no swallowed cache/harness failures in code touched by this packet.

- **Packet 3, Pro: shadow backtest accounting**
  Tighten `simulateTicks`, `RealtimeHarness`, `TradingEngine`, and `SimWallet` around observable accounting. Tests must prove cycle `totalValue == cash + holdings` after engine update, wallet journal records mark-to-market and trade signals, `autoDrawdown()` is real or removed from the milestone surface, and no live `ApiClient` methods are called in `Mode.SHADOW`.

- **Packet 4, Flash: empirical report gate**
  Add a deterministic report test over uptrend/downtrend/chop fixtures. Assert no `NaN`, stable metrics for same seed, exact tick/window/span counts, and a buy-and-hold baseline comparison in `BacktestReport` or test-local helper. Do not require profit as proof; require reproducible metrics and visible baseline delta.

## Interfaces And Boundaries
- Public surface stays in `libs/dreamer-kmm` unless root algebra in `src/commonMain/kotlin/borg/trikeshed/...` is already the canonical type being used.
- OpenAPI/generated clients are adapter context only for this milestone. Do not hand-edit generated files or wire live order placement.
- Keep the core shape algebraic: `Cursor = Series<RowVec>`, sealed `KlineBlock.asCursor()`, lazy projection, no SQL-style builder, no eager table flattening.
- Any task packet that adds `TODO`, `Placeholder`, `not implemented`, `left as red`, silent `catch`, or “future work” as implementation fails review.

## Test Plan
- Required command after every packet: `./gradlew :libs:dreamer-kmm:jvmTest`.
- If root cursor/miniduck files are touched, also run the nearest root/common affected tests.
- If OpenAPI client boundaries are touched later, run `./gradlew :libs:openapi:jvmTest :libs:htx-client:jvmTest`.
- Final acceptance is a green dreamer test target plus a deterministic shadow backtest report over fixture data.

## Assumptions
- First milestone is **Shadow Backtest**.
- First data source is **Deterministic Fixtures**.
- Agent output should be **Jules-style task packets**, not a broad aspirational implementation brief.

