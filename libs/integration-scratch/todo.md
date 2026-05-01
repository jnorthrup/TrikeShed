# libs/integration-scratch — TODO (Boundary Audit)

## Key/Element Migration

### Keys (Static Routing Identity)

- [ ] **`BinanceKlineKey` -> `AsyncContextKey<BinanceKlineElement>`**
      Currently a plain `data class` used as a `Map` key. Should become a
      singleton `AsyncContextKey<BinanceKlineElement>` in an `object Keys`.
      The key's identity is the (symbol, interval, startDate, endDate) tuple —
      this maps cleanly to a context key.
- [ ] **`BinanceStochasticKey` -> `AsyncContextKey<BinanceStochasticElement>`**
      Adds kPeriod/dPeriod to the routing identity. Same migration pattern.
- [ ] **`BinanceKlineProvider` interface** should be replaced by or wired
      through the `AsyncContextElement` open function, not passed as a
      constructor parameter.

### Elements (Stateful Lifecycle)

- [ ] **`ProcessLocalBinanceStochasticCache` -> `AsyncContextElement<BinanceStochasticKey>`**
      This `object` with `Mutex` + `MutableMap` is the clearest Element candidate:
      - Has state (cached `BinanceStochasticKline` values)
      - Has lifecycle (create -> load -> active -> drain -> close)
      - Currently global — should be scoped to a coroutine context branch
      - The `Mutex.withLock` pattern maps to `ElementState.ACTIVE` guarded access
- [ ] **`BinanceKlineSource` -> element or factory** — It holds configuration
      (symbol, interval, dates, blockCapacity, maxConcurrentFetches) and
      produces cursors. Could be an Element whose `open()` method is the
      lifecycle entry point, or a pure factory if config is moved to the Key.

### Factories/Statics -> Keys

- [ ] **`BinanceKlineSourceProvider`** — Currently a class implementing
      `BinanceKlineProvider`. This is effectively a factory. Should become
      a context element or be replaced by `AsyncContextKey`-routed lookup.
- [ ] **`BinanceKlineSource.OHLCV_KEYS`** — Static string list. Should become
      a `Cursor` schema constant or a Key if it routes to a schema provider.
- [ ] **`BinanceKlineSource.defaultFetchCsv`** — Static companion function.
      Should be injected, not hardcoded. Could be an Element's transport method.

### Enums -> Keys

- [ ] **`TimeSpan` enum** (from couch.kline, used heavily here) — Each TimeSpan
      value is effectively a routing key for interval-specific data. If
      interval-specific caching/routes are needed, map TimeSpan values to
      `AsyncContextKey` instances. Low priority.

---

## Code Hygiene

- [ ] **Deduplicate `intervalToTimeSpan()`** — Identical implementation in
      `BinanceKlineSource`, `BinanceCsvParser`, and `BinanceTableSource`.
      Extract to a single top-level function or `TimeSpan` companion.
- [ ] **Deduplicate CSV parsing** — `BinanceKlineSource.parseCsv()` and
      `BinanceTableSource.parseCsv()` are nearly identical. `BinanceCsvParser`
      exists but is not used by `BinanceKlineSource` (which has its own inline
      `parseCsv`). Unify on `BinanceCsvParser`.
- [ ] **Deduplicate HTTP fetch** — `BinanceKlineSource.defaultFetchCsv()` and
      `BinanceTableSource.fetchCsv()` are structurally identical ZIP/CSV
      fetchers. Extract to a shared HTTP utility.
- [ ] **Deduplicate URL building** — `BinanceKlineSource.buildUrl()` appends
      `.zip`, `BinanceTableSource.buildUrl()` appends `.csv`. Different
      endpoints but same pattern. Unify with a parameter.
- [ ] **`RunSqlIntegration.kt` is a standalone demo** — Not related to Binance.
      Should either move to a separate test/demo module or get a Binance SQL
      integration test.

---

## ReactorSupervisor Integration

- [ ] **`BinanceKlineSource.fetchAll()` should use explicit `SupervisorJob` branches** —
      Currently uses `coroutineScope` + `Semaphore` + `async` per date.
      Individual failures are caught as exceptions but don't route through a
      supervisor. Should create a `SupervisorJob` branch per fetch group so
      that partial failures can be observed without cancelling the whole scope.
- [ ] **`ProcessLocalBinanceStochasticCache` should be scoped** — Currently a
      global singleton. Should be installed into a coroutine context as an
      `AsyncContextElement` managed by `ReactorSupervisor`.

---

## Integration Steps

1. Extract `intervalToTimeSpan()` to a single location.
2. Unify CSV parsing on `BinanceCsvParser`; remove inline `parseCsv` from
   `BinanceKlineSource` and `BinanceTableSource`.
3. Create `object BinanceIntegrationKeys` with `AsyncContextKey` singletons
   for kline and stochastic contexts.
4. Wrap `ProcessLocalBinanceStochasticCache` as an `AsyncContextElement` with
   `ElementState` lifecycle.
5. Wire `BinanceKlineSourceProvider` through the context element's open function.
6. Add `SupervisorJob` branch to `fetchAll()` for per-date failure isolation.
7. Move `RunSqlIntegration.kt` to a more appropriate module.

---

## Path to Stable

1. Deduplicate the three copies of `intervalToTimeSpan()` and `parseCsv()`.
2. Convert `BinanceKlineKey`/`BinanceStochasticKey` to `AsyncContextKey` singletons.
3. Convert `ProcessLocalBinanceStochasticCache` to `AsyncContextElement` with lifecycle.
4. Wire fetch pipeline under `SupervisorJob` branches.
5. Add integration test that runs the full pipeline with a mock HTTP layer
   and validates cursor content + stochastic output.
