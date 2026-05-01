# libs/tiny-btrfs

B+Tree + userspace buffer layer for TrikeShed.

## What It Is

tiny-btrfs provides:

1. **BPlusTree<K,V>** -- A generic in-memory B+Tree with dense-array-backed nodes.
   Supports insert, lookup, and automatic splitting at configurable order.
   Leaf nodes are linked for sequential scan.  Uses TrikeShed algebra
   (Series, Join, Twin, `j` infix) throughout.

2. **DiskAdapter** -- Platform-neutral SPI for node persistence (read/write/allocate/free).
   InMemoryDiskAdapter provided for tests.

3. **Userspace buffer layer** -- Two lifecycle-managed DiskAdapter implementations
   that extend `AsyncContextElement`:
   - `UserspaceMemoryBuffer` -- in-memory fixed-size chunk store with free-list
   - `UserspaceBtrfsBuffer` -- btrfs-format node storage (4 KiB blocks) with
     CRC32C checksums, leaf/internal node encode/decode

4. **BtrfsTreeRebuilder** -- Validates node magic/generation headers during tree rebuild

5. **JVM disk adapters** -- `FileDiskAdapter` (one file per node) and
   `ChunkedFileDiskAdapter` (single-file chunk allocator with RandomAccessFile)

The module is designed as a portable starting point for a userspace btrfs
implementation.  All B+Tree logic is in commonMain; I/O is behind DiskAdapter.

## Source Layout

### commonMain
```
tinybtrfs/
  BPlusTree.kt       -- BPlusTree<K,V>: order-configurable B+Tree with
                         LeafNode, InternalNode, split, binary search.
                         Public surface: put(), get(), size(), findLeaf(),
                         insert().  Node.keySeries exposes Series<K>.
  DiskAdapter.kt     -- DiskAdapter interface + InMemoryDiskAdapter

userspace/btrfs/
  UserspaceMemoryBuffer.kt  -- AsyncContextElement + DiskAdapter.
                                Fixed-size chunks, free-list, nodeSnapshot().
  UserspaceBtrfsBuffer.kt   -- AsyncContextElement + DiskAdapter.
                                Btrfs-format nodes (BtrfsLeaf, BtrfsInternal),
                                4 KiB blocks, CRC32C, writeLeaf/readLeaf.
  BtrfsTreeRebuilder.kt     -- BtrfsTreeRebuilder (lifecycle: CREATED->OPEN->CLOSED),
                                BtrfsByteKey, rebuildFromSorted() helper.
```

### jvmMain
```
tinybtrfs/
  FileDiskAdapter.kt       -- File-backed DiskAdapter (one file per node)
  ChunkedFileDiskAdapter.kt -- Single-file chunk allocator (RandomAccessFile,
                                AtomicLong offset, ConcurrentLinkedQueue freeList)
```

### commonTest
```
tinybtrfs/
  BPlusTreeContractTest.kt  -- TDD spec for B+Tree invariants (all stubs):
                                COW, lookup, range query, split, merge,
                                balance, snapshot, fanout bounds
  BPlusTreeTest.kt          -- Working test: put/get/size, replace, order=3 split
```

## Key/Element/Reactor Status

| Component | Key (Identity) | Element (Lifecycle) | Reactor | Status |
|---|---|---|---|---|
| UserspaceMemoryBuffer | Companion Key : CoroutineContext.Key | AsyncContextElement (CREATED->OPEN->DRAINING->CLOSED) | -- | Implemented |
| UserspaceBtrfsBuffer | Companion Key : CoroutineContext.Key | AsyncContextElement (CREATED->OPEN->DRAINING->CLOSED) | -- | Implemented |
| BtrfsTreeRebuilder | -- | Own ElementState lifecycle (CREATED->OPEN->CLOSED) | -- | Implemented |
| BPlusTree | -- | -- | -- | No lifecycle, pure data structure |
| DiskAdapter | -- | -- | -- | SPI interface |

Both userspace buffers properly participate in the TrikeShed Key/Element pattern:
- `companion object Key : CoroutineContext.Key<Self>` provides singleton routing identity
- `AsyncContextElement` base class provides CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED lifecycle
- All operations check `state.isAtLeast(ElementState.OPEN)` before proceeding
- `close()` transitions through DRAINING (clears store/freeList) then CLOSED

## Dependencies

- `borg.trikeshed.lib` -- Series, Join, Twin, j infix, ReifiedSplitSeries2, Series2
- `borg.trikeshed.context` -- AsyncContextElement, ElementState
- kotlinx.coroutines -- CoroutineScope, launch (used in UserspaceMemoryBuffer import only)
- JDK (jvmMain only) -- java.io.File, java.io.RandomAccessFile, java.util.UUID,
  java.util.concurrent.AtomicLong, java.util.concurrent.ConcurrentLinkedQueue

## Kernel Algebra Usage

- `LeafNode.keySeries: Series<K>` -- `keysCount j { _keys[it] as K }`
- `LeafNode.entries: Series2<K, V?>` -- `ReifiedSplitSeries2(keySeries, valueSeries)`
- `InternalNode.splitAt(mid)` returns `Join<K, InternalNode>` -- `pivotKey j right`
- `BPlusTree.insert()` returns `Join<K, Twin<Node<K,V>>>?` -- `pivot j Twin(left, right)`
- `BtrfsByteKey` wraps `Series<Byte>` for lexicographic comparison
- `rebuildFromSorted()` takes `Series<Join<Series<Byte>, Series<Byte>>>`
