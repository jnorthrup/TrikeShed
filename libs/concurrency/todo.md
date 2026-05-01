# libs/concurrency — TODO

## Intent
MVCC block store — snapshot-isolated reads over WAL-backed BlockRowVec. Donor: CouchDB MVCC, PostgreSQL xmin/xmax. JVM-only (plainJvm).

## Status: ALPHA

## Pure boundary audit

### Keys (static — need creation)
- [ ] `MvccBlockStoreKey : CoroutineContext.Key<MvccBlockStore>` — the MVCC store is stateful (sequence counter, block list) and needs lifecycle. Currently a bare class with no context integration.

### Elements (stateful → AsyncContextElement)
- [ ] `MvccBlockStore` → extend `AsyncContextElement` with CREATED→OPEN→ACTIVE→DRAINING→CLOSED lifecycle
  - `open()`: validate WAL integrity
  - `close()`: flush pending snapshots, mark CLOSED
  - `snapshot()`: returns `MvccSnapshot` — already correct

### Statics that should stay static
- `MvccSnapshot` data class — pure value, correct
- `BlockMeta` data class — pure value, correct

### Enums → unify with root
- `MvccBlockStore` has no enum, but its lifecycle mirrors `ElementState` exactly. Use `ElementState` directly instead of ad-hoc state vars.

## Integration partners
- **miniduck**: `MvccBlockStore` wraps `BlockRowVec` from miniduck. Ensure import path is `borg.trikeshed.miniduck.BlockRowVec`.
- **couch**: couch MVCC semantics should delegate here, not re-implement.

## Path to stable
1. Create `MvccBlockStoreKey` object
2. Make `MvccBlockStore` extend `AsyncContextElement`
3. Replace ad-hoc state with `ElementState` transitions
4. Add `commonTest` lifecycle tests
5. Wire into couch `ReactorSupervisor` as a context palette entry
