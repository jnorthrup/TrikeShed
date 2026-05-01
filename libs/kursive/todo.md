# libs/kursive — TODO

## Intent
NARS-inspired parser combinator / IKR reasoning VM. Jursive parse grammar, NAL logic levels, Narsive element kinds, NARS3 machine with channelized atoms. KMP full.

## Status: ALPHA (complex domain, unique Key pattern)

## Pure boundary audit

### Keys (interesting pattern — already CoroutineContext.Key!)
- `NarsiveElementKind` enum — **each enum entry IS a `CoroutineContext.Key<NarsiveElement>`**
  - This is a clever use: parser element kinds double as context keys for typed lookup
  - ✓ Keep this pattern, it's correct for the domain

### Elements (stateful)
- `NarsiveElement` (in Narsive.kt) — extends `AbstractCoroutineContextElement` keyed by `NarsiveElementKind`
  - ✓ Correct shape: each parsed element carries its kind as its Key
- `Nars3Machine` — has `CoroutineScope` + `SupervisorJob` but is NOT an AsyncContextElement
  - [ ] Make `Nars3Machine` extend `AsyncContextElement` with lifecycle (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)
  - [ ] Use `coroutineScope { launch { } }` fan-out for channelized atom processing

### Statics → refactor
- `NarsiveOperator` enum — operator taxonomy. Stays enum ✓ (domain taxonomy)
- `NarsiveRenderMode` enum — rendering mode. Stays enum ✓
- `NALLevel` enum — logic level taxonomy. Stays enum ✓
- `Narsive` object — top-level parser factory. [ ] Consider: is this a Key or a pure factory? It's a factory — stays object ✓
- `std` object — standard library of parsers. Pure factory ✓

### Nars3SupervisorJob
- `NarsiveSupervisorJob` class wraps a SupervisorJob — ad-hoc, not using AsyncContextElement base
  - [ ] Replace with `AsyncContextElement` base class, get lifecycle for free

## Integration partners
- **polyglot**: depends on kursive for parser types + Nars3 bridge
- **couch**: couch has `JursiveHeuristics` using kursive parsers for viewserver
- **miniduck**: miniduck has `NarsBag` — IKR budget integration
- **dreamer-kmm**: uses NARS concepts for IKR stochastic search

## Path to stable
1. Make `Nars3Machine` extend `AsyncContextElement`
2. Replace `NarsiveSupervisorJob` with `AsyncContextElement` base
3. Use `ElementState` instead of ad-hoc state management
4. Add lifecycle tests for Nars3Machine (open → activate → feed atoms → drain → close)
5. Integration test: Jursive parse → NarsiveElement → Nars3Machine atom dispatch
