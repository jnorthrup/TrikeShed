# libs/integration-scratch — TODO

## Intent
Integration test harness + standalone app. Exercises couch + miniduck + kursive in real scenarios. Has Binance stochastic kline cache pipeline. Plain JVM with `application` plugin.

## Status: ALPHA (working integration, not a library)

## Pure boundary audit

### Keys (none — this is a test harness, not a library)
### Elements (none — orchestrates other modules' elements)

### Statics
- `ProcessLocalBinanceStochasticCache` object — caches kline data locally ✓
- `ZipUtils` object — zip extraction utility ✓
- `RunSqlIntegrationKt` — main entry point for SQL integration test
- `RunBinanceStochasticKlineCacheKt` — main entry point for binance pipeline

### Refactoring
- [ ] Uses `CoroutineScope(Dispatchers.Default).launch { }` — should use `ReactorSupervisor.launchBranch()` or `AsyncContextElement.supervisor`-scoped launch
- [ ] `fetchAll` uses `coroutineScope { async { } }` — correct fan-out pattern ✓

## Integration partners
- **couch**: main dependency for transport + kline types
- **miniduck**: for cursor operations
- **kursive**: for parser types
- **dreamer-kmm**: indirectly via shared data models

## Path to stable
1. Replace bare `CoroutineScope(Dispatchers.Default).launch` with ReactorSupervisor branches
2. Add integration test that runs the full pipeline end-to-end
3. This module is a test harness — stability means "runs reliably", not "published API"
