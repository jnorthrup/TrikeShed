# libs/integration-scratch

Binance OHLCV kline data pipeline + stochastic indicator cache. JVM-only
integration test harness that exercises the TrikeShed kernel algebra (Join,
Series, Cursor, `j` infix) against real market data from Binance's public
data-vision API.

## What It Is (Mechanically)

A **fetch-parse-collect-cache** pipeline:

```
Binance data-vision ZIP archives (1 CSV per day per symbol)
  -> BinanceKlineSource.fetchAll()           // coroutineScope fan-out, Semaphore throttle
  -> ZipInputStream -> CSV string
  -> parseCsv() -> List<Kline>
  -> Channel<Kline> -> KlineCollector        // blocks sealed at capacity
  -> List<KlineBlock>
  -> blocksToCursor() via `n j { idx -> }`   // lazy indexed MiniCursor
  -> MiniCursor (DocRowVec rows)
```

On top of this, `BinanceStochasticKlineCache` adds a process-local
`Mutex`-guarded cache that computes `Stochastic` indicators on the cursor.

## Source Layout

### src/main

| File | Role |
|------|------|
| `BinanceKlineSource.kt` | Core fetch pipeline. `BinanceKlineSource` accepts symbol/interval/dateRange, fans out HTTP fetches via `coroutineScope` + `Semaphore`, parses CSV into `Kline`, drains via `Channel<Kline>` + `KlineCollector`, converts to `MiniCursor` using `j` infix. Includes `BinanceKlineFetchException`. |
| `BinanceStochasticKlineCache.kt` | `BinanceKlineKey` / `BinanceStochasticKey` value classes. `BinanceKlineProvider` interface + `BinanceKlineSourceProvider` adapter. `ProcessLocalBinanceStochasticCache` (object with `Mutex`). `computeStochastic()` extracts OHLC Series from cursor. |
| `BinanceCsvParser.kt` | Standalone CSV line parser for Binance kline format (12 fields, first 6 consumed). Maps interval strings to `TimeSpan` enum. |
| `BinanceCursor.kt` | `BinanceCursor` wraps sealed `KlineBlock` list as a `Cursor` (positioned block/row indices). `MiniRowVecRowAccessor` adapts `DocRowVec` to `RowAccessor`. |
| `BinanceTableSource.kt` | `BinanceTableSource` implements `TableSource` (MiniDuck SQL engine integration). Provides `TableSchema`, `SchemaManager`, `Cursor` for the OHLCV columns. Lazily fetches and caches blocks. |
| `ZipUtils.kt` | `object ZipUtils` — extracts a named entry from a ZIP `InputStream`. |
| `RunBinanceStochasticKlineCache.kt` | CLI entry point: `main(args)` parses `--symbol`, `--interval`, `--start`, `--end` flags, runs the stochastic cache pipeline. |
| `RunSqlIntegration.kt` | CLI demo: seeds an in-memory LSMR database, parses a SQL `SELECT`, runs it through the MiniDuck planner, prints results. Exercises `SqlParser` + `LsmrDatabase`. |

### src/test

| Test | What it exercises |
|------|-------------------|
| `BinanceKlineTddTest` | Full draw-through: CSV line parsing, multi-line CSV, ZIP extraction via `ZipUtils`, `KlineBlock` -> `MiniCursor` -> `DocRowVec`, `KlineCollector` channel drain, `BinanceCursor` positioned iteration, `BinanceKlineSource` URL building, fan-out concurrency throttling, total-failure exception, partial-failure tolerance. |
| `BinanceStochasticKlineCacheTest` | Process-local cache: same-key dedup, different-symbol separation, concurrent-load coalescing (10 concurrent `getOrLoad` calls -> 1 load), CLI arg parsing, `runBinanceStochasticKlineCache` end-to-end. |

## Key/Element/Reactor Status

### Keys (AsyncContextKey)

- **Not yet extracted.** `BinanceKlineKey` and `BinanceStochasticKey` are plain
  `data class` value objects. They are used as map keys in
  `ProcessLocalBinanceStochasticCache` but are not wired into the
  `AsyncContextKey<K>` routing system.
- **Migration path:** `BinanceKlineKey` -> `AsyncContextKey<BinanceKlineElement>`
  singleton in a `Keys` object. `BinanceStochasticKey` -> similar.

### Elements (AsyncContextElement)

- **Not yet extracted.** `ProcessLocalBinanceStochasticCache` is a global
  `object` with `Mutex` + `MutableMap` — it is stateful but not lifecycle-managed.
  `BinanceKlineSource` holds network config and is effectively stateless after
  construction.
- **Migration path:** Wrap cache + provider as `AsyncContextElement<K>` with
  `CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED` lifecycle. The `Mutex`
  becomes the element's internal state guard.

### ReactorSupervisor

- **Partially present.** `BinanceKlineSource.fetchAll()` uses `coroutineScope`
  with structured fan-out, but creates no explicit `SupervisorJob` branch per
  fetch — individual fetch failures are caught and logged, not routed through
  a supervisor hierarchy.

## Dependencies

- `borg.trikeshed.couch.kline` — `Kline`, `KlineBlock`, `KlineCollector`, `TimeSpan`
- `borg.trikeshed.lib` — `j` infix, `Series`, `size`
- `borg.trikeshed.miniduck` — `MiniCursor`, `DocRowVec`, `at`, `emptyMiniCursor`
- `borg.trikeshed.miniduck.exec` — `Cursor`, `RowAccessor`, `ExecutionContext`, `TableSource`
- `borg.trikeshed.miniduck.schema` — `TableSchema`, `ColumnSchema`, `SchemaManager`
- `borg.trikeshed.miniduck.sql` — `PlannerContext`, `PlannerConfig`, `transformSelect`
- `borg.trikeshed.parse.kursive.sql` — `SqlParser`
- `borg.trikeshed.userspace.concurrency` — `Channel`, `ChannelCapacity`
- `borg.trikeshed.userspace.database` — `LsmrDatabase`, `LsmrConfig`
- `borg.trikeshed.indicator` — `Stochastic`
- `kotlinx.coroutines` — `Semaphore`, `Mutex`, `coroutineScope`, `async`, `launch`
- JVM: `java.net.HttpURLConnection`, `java.util.zip.ZipInputStream`, `java.time.LocalDate`

## Kernel Algebra Usage

- **`j` infix (Join):** `totalRows j { rowIdx -> ... }` in `BinanceKlineSource.blocksToCursor()` — constructs a lazy `Series<RowVec>` indexed over multiple `KlineBlock`s.
- **`at` operator:** `cur at remaining` — indexed access into a `MiniCursor`.
- **`Series<Double>`:** Extracted from cursor columns for Stochastic computation (`ohlcSeries`).
- **`Cursor` interface:** `BinanceCursor` implements positioned `next()/row/close()` over block boundaries.

## Duplicated Code

`intervalToTimeSpan()` is duplicated verbatim in three files:
- `BinanceKlineSource.kt`
- `BinanceCsvParser.kt`
- `BinanceTableSource.kt`

Should be extracted to a single location (e.g., a `TimeSpan` companion or a
top-level function in the couch.kline package).
