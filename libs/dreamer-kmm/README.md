# libs/dreamer-kmm

Trading/backtesting domain model for TrikeShed. Pure Kotlin Multiplatform — no
platform-specific code. Implements a genome-driven paper trading engine with
evolutionary optimization over Binance kline archive data.

## What It Is (Mechanically)

A closed-loop simulation system:

1. **Ingest** Binance Vision CSV archives into `KlineBlock` sealed cursors.
2. **Project** kline bars through `TradingEngine` cycles driven by a `Genome`
   (fixed-width `DoubleArray` of 45 strategy parameters).
3. **Record** per-tick `CycleResult` and aggregate `BacktestMetrics`
   (Sharpe, Sortino, max drawdown, total return, harvest totals).
4. **Evolve** genomes via one-point crossover + additive mutation, ranked by
   stochastic fitness (return + Sharpe + Sortino - maxDrawdown).
5. **Train** populations via `StochasticBagSpanTrainer` (multi-symbol stochastic
   window/span selection) and `GenomeTrainer` (single-symbol one-dimensional
   parameter sweep).

The engine operates in two modes: `SHADOW` (pure simulation) and `LIVE` (routes
through `ApiClient` for real exchange orders — interface only, no
implementation in this module).

## Source Layout

```
src/commonMain/kotlin/borg/trikeshed/dreamer/
  BacktestModels.kt        — PortfolioInput, CycleResult, BacktestMetrics,
                              BacktestResult, simulateTicks(), simulateMultiSymbolTicks(),
                              computeBacktestMetrics(), closesFromCursor(),
                              allSymbolsAtBar(), klineBarToPortfolioInput(),
                              multiSymbolKlineToPortfolioInput(), toBacktestReport()
  KlineModels.kt           — Kline, ExtendedKline, KlineBlock (MUTABLE→SEALED),
                              TimeSpan enum, asCursor()/asColumnarCursor()
  DataModels.kt            — Mode, GenomeParam (45 params), Genome (DoubleArray +
                              per-symbol overrides + backing map), PortfolioRow,
                              Holding, EngineResult
  OrderStatus.kt           — OrderStatus enum (PENDING/FILLED/CANCELLED/REJECTED)
  Evolution.kt             — GenomeEvaluation, crossoverGenome(), mutateGenome(),
                              evolvePopulation(), evaluatePopulation()
  KlineCsvParser.kt        — klinesFromCsv(): Binance 12-column CSV → Series<ExtendedKline>
  BinanceVisionKlineFeed.kt— KlineFeed interface, BinanceVisionKlineFeed (URL planning,
                              CSV parsing), TradingPair/KlineSeriesKey type aliases
  PaperAccount.kt          — PaperAccount: AsyncContextElement (balance only)
  PaperOrder.kt            — PaperOrder: AsyncContextElement (symbol/qty/price/status)
  PaperPosition.kt         — PaperPosition: AsyncContextElement (unrealized PnL)
  TradingEngine.kt         — Core engine: harvest logic, rebalance scheduling,
                              genome parameter resolution with per-symbol overrides
  SimWallet.kt             — Full simulation wallet: balances, locked funds, pending
                              orders, fill processing, cost basis tracking, journal
  SimulationReplay.kt      — SimulationReplay: CSV → KlineBlock → simulateTicks
  ControlHarness.kt        — ControlHarness: AsyncContextElement, frame projection
                              with horizon OHLCV + pancake flattening, wallet routing
  RealtimeHarness.kt       — RealtimeHarness: multi-symbol replay with DreamerAgent,
                              StochasticBag integration, wallet mark-to-market
  StochasticTraining.kt    — StochasticBagSpanTrainer: evolutionary population trainer
                              with generated archive CSV, per-generation snapshots
  StochasticBag.kt         — StochasticBag: random window/span selection from
                              KlineSeriesSource cursors, SpanMatcher integration
  GenomeTraining.kt        — GenomeTrainer: 1D and pair-bag parameter sweep
  PairGraph.kt             — BFS shortest-path finder for trade pair routing
  CursorBacktestAdapters.kt— Backward-compat shim (MiniCursor → Cursor)
  TrikeAdapterLocal.kt     — In-package RowVec adapter helpers (cells→RowVec, Kline→RowVec)
  ApiClient.kt             — ApiClient interface (placeBuy/placeSell/getBalance/etc.)
  TweezeArchive.kt         — Binance symbol parser (rawSymbol → base/quote pair)
  adapter/TrikeAdapterLocal.kt — Public adapter helpers for DocRowVec/Kline → RowVec

src/commonTest/kotlin/borg/trikeshed/dreamer/
  DreamerElementTddTest.kt    — PaperAccount/PaperOrder/PaperPosition Key/Element TDD
  EvolutionTest.kt            — Crossover/mutation/population evolution tests
  SimWalletTest.kt            — Wallet order lifecycle, fill processing, PnL
  SimulationReplayTest.kt     — CSV replay end-to-end
  ControlHarnessTest.kt       — Frame projection, horizon indexing, pancake
  BacktestIntegrationTest.kt  — Full backtest with metrics computation
  RunCycleTest.kt             — Single tick cycle tests
  RunCycleRedTest.kt          — 25-test regression suite for full backtest pipeline
  RealtimeHarnessTest.kt      — Multi-symbol harness replay + GenomeTrainer/StochasticBagSpanTrainer
  BinanceVisionKlineFeedTest.kt — Feed planning and CSV parsing
  RebalanceExecutionTest.kt   — Engine rebalance execution
```

## Key/Element Pattern Status

| Element           | AsyncContextKey     | AsyncContextElement | ElementState lifecycle | SupervisorJob |
|--------------------|---------------------|---------------------|------------------------|---------------|
| PaperAccount       | PaperAccount.Key    | yes — balance       | CREATED→OPEN (tested)  | not wired     |
| PaperOrder         | PaperOrder.Key      | yes — order state   | CREATED (default)      | not wired     |
| PaperPosition      | PaperPosition.Key   | yes — PnL tracking  | CREATED (default)      | not wired     |
| ControlHarness     | ControlHarness.Key  | yes — frame driver  | OPEN→ACTIVE (enforced) | fanoutSubscribers list |

PaperAccount, PaperOrder, PaperPosition extend AsyncContextElement with
singleton companion Key. They carry data but do not yet manage a full
CREATED→OPEN→ACTIVE→DRAINING→CLOSED lifecycle. ControlHarness enforces
OPEN|ACTIVE for frame projection and carries a fanoutSubscribers list.

## Dependencies

- **TrikeShed core**: `cursor` (Cursor, RowVec, ColumnMeta, joins, at),
  `lib` (Series, Join, Twin, j infix, alpha projection, toSeries),
  `miniduck` (DocRowVec, toRowVec), `isam.meta` (IOMemento),
  `context` (AsyncContextElement, AsyncContextKey, ElementState),
  `collections` (columnar.SpanMatcher)
- **kotlinx.coroutines** (coroutineScope, async, awaitAll)
- **kotlin.time.Clock** (system clock for timestamps)
- Build: `../../gradle/macros/trikeshed-lib.gradle`

No external dependencies. Pure KMP common code.
