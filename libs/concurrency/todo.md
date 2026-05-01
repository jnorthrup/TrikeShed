# libs/concurrency -- todo.md

## Boundary Audit

### Completed
- [x] MvccBlockStore with monotonic sequence counter
- [x] MvccSnapshot as point-in-time capture
- [x] BlockMeta tracking put/remove sequences
- [x] listAt/getAt visibility filtering by snapshot sequence
- [x] scanAt producing MiniCursor over visible blocks
- [x] Remove visibility (block removed after snapshot still visible at old snapshot)
- [x] Collection isolation (list/get filtered by collection name)
- [x] Concurrent snapshot independence (snapshots at different seq see different worlds)
- [x] JVM test coverage for all core MVCC invariants

### Boundary Issues

1. **Package mismatch between source and tests** -- Source is in
   `borg.trikeshed.miniduck.mvcc`.  Contract tests are in
   `borg.trikeshed.concurrency`.  The contract tests import nothing from the
   implementation -- they are pure stubs (`assertTrue(true)`) that document
   desired invariants but don't actually test anything.

2. **JVM-only source set** -- `MvccBlockStore.kt` lives in `jvmMain`.  There is
   no `commonMain` source.  If MVCC is needed on other platforms, the
   implementation must be duplicated or extracted to commonMain.

3. **No actual WAL integration** -- `MvccBlockStore` maintains its own
   `MutableList<BlockMeta>` as the version history.  It does NOT wrap
   `InMemoryBlockWal` or any other WAL.  The WAL module in miniduck
   (`InMemoryBlockWal`) is a separate, unconnected implementation.

4. **No write-write conflict detection** -- The contract tests specify
   "commit detects write-write conflicts" but MvccBlockStore has no transaction
   or commit concept.  `put()` and `remove()` are immediate -- no rollback,
   no conflict detection, no isolation levels beyond snapshot reads.

5. **No snapshot resource management** -- Snapshots are plain data classes.
   There is no reference counting, no cleanup, no close/释放.  The contract
   test mentions "snapshot releases when closed" but this is unimplemented.

6. **Scan collects all rows eagerly** -- `scanAt()` materializes all rows from
   all visible blocks into a `MutableList<MiniRowVec>` before returning.  This
   is fine for testing but will OOM on large datasets.  A lazy iterator/cursor
   would be more appropriate.

7. **BlockMeta is append-only** -- Blocks are never physically removed from the
   `blocks` list.  `remove()` adds a new BlockMeta with `removed=true`.  Over
   time the list grows without bound.  Needs compaction/GC.

8. **Cross-module dependency on miniduck types** -- The module imports
   BlockRowVec, MiniCursor, MiniRowVec, DocRowVec directly.  These are defined
   in `libs/miniduck`.  If miniduck changes its type hierarchy, concurrency
   breaks.  This coupling should be through an interface/abstraction.

### Integration Steps

1. **Extract commonMain interface** -- Create a commonMain `MvccStore`
   interface/skeleton that both the contract tests and JVM implementation
   can reference.  Move MvccBlockStore to commonMain or provide expect/actual.

2. **Wire WAL to MVCC** -- Make MvccBlockStore use InMemoryBlockWal (or a WAL
   interface) as its backing store instead of maintaining a parallel BlockMeta
   list.  The WAL already has monotonic sequence numbers.

3. **Implement real contract tests** -- Replace stub assertions in
   MvccBlockStoreContractTest with actual MvccBlockStore usage, or move the
   JVM tests to commonTest.

4. **Add transaction support** -- Introduce begin/commit/rollback with
   write-write conflict detection if needed by higher layers.

5. **Lazy scan** -- Implement scanAt() as a lazy cursor that yields rows
   on demand rather than materializing all rows upfront.

6. **Compaction** -- Add a compact() method that removes non-live BlockMeta
   entries older than the oldest active snapshot.

7. **Decouple from miniduck types** -- Define a BlockView interface in the
   concurrency module so it doesn't depend directly on BlockRowVec/MiniCursor.

## Path to Stable

- **v0.1** (current): Working MVCC snapshot isolation, JVM-only, no WAL integration,
  stub contract tests.
- **v0.2**: Extract commonMain interface, wire to WAL, real contract tests.
- **v0.3**: Lazy scan, compaction, transaction support.
- **v1.0**: Decoupled from miniduck types, multiplatform, full test coverage.
