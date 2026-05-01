# libs/dreamer-kmm — Boundary Audit & Path to Stable

## Boundary Audit

### Clean boundaries (no issues)
- **KlineModels**: KlineBlock MUTABLE→SEALED state machine is clean. asCursor()
  enforces sealed invariant. No side-channel mutations after seal.
- **KlineCsvParser**: Pure function klinesFromCsv(Series<Char>) → Series<ExtendedKline>.
  No global state. Deterministic for same input.
- **Evolution**: Pure functions — crossoverGenome, mutateGenome, evolvePopulation.
  All produce new Genome instances; no mutation of inputs.
- **DataModels.Genome**: Fixed-width DoubleArray with stable ordinals.
  Per-symbol overrides via NaN-fallback DoubleArray. Copy methods are defensive.
- **SimulationReplay**: Thin orchestrator — CSV → block → engine → result.
  No retained state between calls.
- **PairGraph**: Pure BFS on adjacency list. No I/O.
- **TweezeArchive**: Static parser, no state.
- **ApiClient**: Pure interface — no implementation in this module.

### Boundary concerns (needs attention)

1. **TradingEngine mutable state explosion**
   - `cashBalance`, `holdings`, `baselines`, `lastActionTimestamps`,
     `rebalanceState`, `totalHarvested` — all mutable var/val on a single class.
   - `update()` both reads AND writes all of these in one call.
   - `injectSimulationState()` and `loadPersistedState()` bypass constructors.
   - Risk: concurrent callers corrupt state; test isolation requires fresh instances.
   - **Fix**: Extract an immutable `EngineState` data class. Make update() return
     a new state (or a sealed result). Keep TradingEngine as a stateless orchestrator.

2. **SimWallet is not thread-safe**
   - All mutable maps (balances, locked, pending, realized, costBasis, journal).
   - No synchronization. Used inside coroutineScope in ControlHarness.
   - Risk: concurrent markToMarket / processBar from parallel pair frames.
   - **Fix**: Either make SimWallet use concurrent collections, or guarantee
     single-threaded access (document the contract).

3. **PaperAccount/PaperOrder/PaperPosition: ElementState lifecycle incomplete**
   - They extend AsyncContextElement but only use CREATED→OPEN.
   - No DRAINING/CLOSED transitions. No resource cleanup.
   - fanoutSubscribers is empty by default.
   - **Fix**: Either complete the lifecycle (what does "closing" a PaperAccount mean?)
     or downgrade to plain data classes if lifecycle management is not needed here.

4. **BacktestModels: dual location for RowVec extensions**
   - stringValue/longValue/doubleValue/intValue defined in BacktestModels.kt
     (extension on RowVec from cursor package). This is a cross-package
     extension that should live in cursor or a shared utilities module.
   - **Fix**: Move these to a shared RowVec accessor module or into cursor itself.

5. **TrikeAdapterLocal: duplicate files**
   - `TrikeAdapterLocal.kt` exists at both `dreamer/` and `dreamer/adapter/`
     with overlapping but not identical helpers.
   - **Fix**: Consolidate into `adapter/` package. Remove the root-level copy.

6. **CursorBacktestAdapters: dead shim**
   - `miniCursorToColumnar` is an identity function. `simulateTicksMiniCursor`
     just delegates. This exists only for backward compatibility.
   - **Fix**: Migrate callers to `simulateTicks` directly, then delete this file.

7. **StochasticTraining: generated archive CSV couples test and production**
   - `generatedArchiveCsv()` uses deterministic random with hardcoded base prices.
     This is both the production training data source AND the test data source.
   - **Fix**: Separate synthetic data generation from the trainer. Allow real
     archive CSV to be injected.

8. **GenomeTraining.maxDrawdown duplicated across files**
   - Identical `HarnessRunResult.maxDrawdown()` defined in both
     StochasticTraining.kt and GenomeTraining.kt.
   - Identical `fitness()` defined in both files with different formulas.
   - **Fix**: Extract to a shared metrics module.

## Integration Steps

1. **Stabilize TradingEngine state model**: Extract immutable EngineState. Make
   all update paths return new state. This is prerequisite for parallel backtests.
2. **Consolidate adapter layer**: Merge TrikeAdapterLocal duplicates. Move
   RowVec extensions to cursor module. Delete CursorBacktestAdapters shim.
3. **Complete Element lifecycle or downgrade**: Decide whether Paper* classes
   need full lifecycle. If not, simplify to data classes and remove
   AsyncContextElement inheritance.
4. **Thread-safety audit**: Document concurrency contract for SimWallet and
   TradingEngine. Either add synchronization or enforce single-threaded use.
5. **Extract shared metrics**: Deduplicate maxDrawdown/fitness across training
   files into a shared BacktestMetricsUtils.
6. **Separate synthetic data**: Make StochasticBagSpanTrainer accept arbitrary
   List<HarnessReplayInput> instead of coupling to generatedArchiveCsv.

## Path to Stable

- [ ] TradingEngine state immutability pass
- [ ] Adapter consolidation (remove duplicates, move extensions)
- [ ] Paper* lifecycle decision (complete or downgrade)
- [ ] SimWallet concurrency contract
- [ ] Shared metrics extraction
- [ ] Synthetic data decoupling
- [ ] Full test coverage for multi-symbol simulateMultiSymbolTicks edge cases
- [ ] Integration test: generated CSV → feed → block → engine → metrics → evolution loop
