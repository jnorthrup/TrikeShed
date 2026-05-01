# libs/dreamer-kmm — TODO

## Intent
Trading/backtesting domain model. Kline parsing, paper trading, stochastic optimization, evolutionary genome search, backtest metrics. KMP full.

## Status: ALPHA (domain models solid, no Element/Key integration)

## Pure boundary audit

### Keys (need creation)
- `OrderStatus` enum (PENDING/FILLED/CANCELLED/REJECTED) — order lifecycle state.
  - [ ] Consider: is this a routing key or just domain metadata? It's domain metadata — stays enum. But if orders are dispatched via coroutine context, it could be a Key.

### Elements (stateful — need AsyncContextElement)
- `PaperAccount` — stateful (cash balance, positions map). [ ] AsyncContextElement with lifecycle.
- `TradingEngine` — stateful (portfolio, rebalance schedule). [ ] AsyncContextElement.
- `SimulationReplay` — drives tick replay. [ ] AsyncContextElement or use `ReactorSupervisor.launchBranch`.
- `ControlHarness` / `RealtimeHarness` — orchestration. [ ] Should use ReactorSupervisor branches.

### Statics that should stay static
- `BacktestMetrics`, `CycleResult`, `PortfolioInput` — pure data ✓
- `GenomeEvaluation` — pure value ✓
- `computeStochasticFitness()`, `fitnessFromResult()` — pure functions ✓
- `crossoverGenome()`, `mutateGenome()` — pure genetic operators ✓
- `KlineCsvParser` — pure parser ✓
- `BinanceVisionKlineFeed` — data source descriptor ✓
- `DataModels`, `KlineModels` — pure domain values ✓

### Enums
- `OrderStatus` — domain lifecycle, stays enum ✓

## Integration partners
- **miniduck**: uses MiniCursor, cursor `at()`, BlockRowVec for kline data representation.
- **couch**: uses couch KlineBlock, finance extensions.
- **kursive**: no direct dependency but NARS bag/atom types are used for IKR budget.
- **integration-scratch**: the test runner that exercises the full pipeline.

## Path to stable
1. Create `TradingEngineKey : AsyncContextKey<TradingEngine>` — make engine a lifecycle element
2. Create `PaperAccountKey : AsyncContextKey<PaperAccount>` — same
3. Wire backtest execution into ReactorSupervisor as named branches (one branch per symbol)
4. Use `coroutineScope { async { } }` fan-out with `Semaphore` throttle for multi-symbol simulation (per user preference)
5. Integration test: KlineCsvParser → KlineBlock → Cursor → simulateTicks → BacktestResult → BacktestMetrics
