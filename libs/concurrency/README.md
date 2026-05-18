# libs/concurrency

MVCC snapshot isolation over WAL-backed block storage.

## What It Is

The concurrency module provides multi-version concurrency control (MVCC) for
MiniDuck's block store.  Each read captures a point-in-time snapshot; writers
produce new versions without blocking readers.

Mechanically:
- `MvccBlockStore` wraps a WAL-backed block store with monotonic sequence numbers
- `MvccSnapshot(seq)` captures the MVCC clock at creation time
- `BlockMeta` tracks put/remove sequence per block for visibility filtering
- `listAt(snapshot, collection)` / `getAt(snapshot, collection, blockId)` return
  only blocks visible at that snapshot's sequence
- `scanAt(snapshot, collection)` merges visible blocks into a `MiniCursor`

Donor patterns: CouchDB MVCC (`_rev`), PostgreSQL MVCC (`xmin/xmax`), Raft snapshot.

## Source Layout

### jvmMain
```
concurrency/
  MvccBlockStore.kt   -- MvccBlockStore, MvccSnapshot, BlockMeta
                         (package: borg.trikeshed.miniduck.mvcc)
```

### commonTest
```
concurrency/
  MvccBlockStoreContractTest.kt  -- TDD spec: snapshot invariants (all stubs)
                                    (package: borg.trikeshed.concurrency)
```

### jvmTest
```
concurrency/
  MvccSnapshotTest.kt  -- RED tests: snapshot isolation, concurrent snapshots,
                          remove visibility, collection isolation, scan cursor
                          (package: borg.trikeshed.miniduck.mvcc)
```

## Key/Element/Reactor Status

| Component | Status |
|---|---|
| MvccBlockStore | Implemented (JVM only, depends on miniduck types) |
| AsyncContextKey | Not used -- MvccBlockStore manages its own sequence counter |
| AsyncContextElement | Not used -- snapshots are plain data classes |
| ReactorSupervisor | Not used -- no coroutine branching here |

The module does NOT follow the Key/Element/Reactor pattern. MvccBlockStore is a
plain class with mutable state. Snapshots are `data class MvccSnapshot(seq: Long)`.

## Dependencies

- `borg.trikeshed.miniduck` -- BlockRowVec, MiniCursor, MiniRowVec, DocRowVec
- `borg.trikeshed.miniduck.tablespace` -- BlockStore (SPI, but not directly used)
- `borg.trikeshed.lib` -- Series, j infix

### Cross-module coupling

This module lives in `libs/concurrency` but its source code resides in package
`borg.trikeshed.miniduck.mvcc` and directly imports `borg.trikeshed.miniduck.*`.
It is architecturally a sub-module of miniduck, not an independent concurrency
library.  The contract tests in `commonTest` use package `borg.trikeshed.concurrency`
and contain only stub assertions -- they are placeholders for a future
commonMain migration.
