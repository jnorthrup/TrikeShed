# libs/tiny-btrfs -- todo.md

## Boundary Audit

### Completed
- [x] BPlusTree<K,V> with configurable order, binary search, dense-array nodes
- [x] Leaf node linked list for sequential scan
- [x] Internal node split with pivot extraction
- [x] DiskAdapter SPI (read/write/allocate/free)
- [x] InMemoryDiskAdapter for testing
- [x] FileDiskAdapter (JVM, one-file-per-node)
- [x] ChunkedFileDiskAdapter (JVM, single-file chunk allocator with RAF)
- [x] UserspaceMemoryBuffer -- AsyncContextElement + DiskAdapter + free-list
- [x] UserspaceBtrfsBuffer -- AsyncContextElement + DiskAdapter + btrfs node format
- [x] BtrfsTreeRebuilder -- lifecycle-managed validation pass
- [x] BtrfsByteKey -- lexicographic Series<Byte> comparison
- [x] Basic put/get test coverage

### Boundary Issues

1. **No delete operation** -- BPlusTree has put/get/findLeaf/insert but no remove.
   The contract tests (BPlusTreeContractTest) specify merge-on-underflow and
   rebalancing, but none of this exists.  All contract test assertions are stubs
   (`assertTrue(true)`).

2. **No range query** -- Contract tests specify `rangeQuery(start, end)` but there
   is no implementation.  Leaf nodes are linked (`next: LeafNode?`) so a range
   scan is architecturally possible, just not exposed.

3. **No snapshot / copy-on-write** -- BPlusTree is fully mutable.  Contract tests
   specify COW behavior ("insert never mutates existing nodes", "snapshot preserves
   old root after modification") but the tree mutates nodes in place during
   `insertAt`, `splitAt`, `setChildAt`, etc.

4. **No CRC32C implementation** -- UserspaceBtrfsBuffer mentions CRC32C checksums
   in its doc comments and has `encodeLeaf`/`decodeLeaf`/`encodeInternal`/
   `decodeInternal` calls, but the actual checksum logic is not visible in the
   source (may be inlined or TODO).  BtrfsTreeRebuilder only validates magic
   bytes and generation, not checksums.

5. **BtrfsLeaf / BtrfsInternal types are referenced but not defined in this module**
   -- `UserspaceBtrfsBuffer.writeLeaf(nodeId, leaf: BtrfsLeaf, generation)` calls
   `encodeLeaf(leaf, buf, generation)`, and `readLeaf()` calls `decodeLeaf(bytes)`.
   But BtrfsLeaf, BtrfsInternal, encodeLeaf, decodeLeaf, encodeInternal,
   decodeInternal are not defined in any file in this module.  They may be in
   another module or are TODO stubs.

6. **ChunkedFileDiskAdapter free-list is in-memory only** -- Freed chunk offsets
   are tracked in a `ConcurrentLinkedQueue<Long>` that is lost on process restart.
   The class doc acknowledges: "a production allocator should persist metadata."

7. **ChunkedFileDiskAdapter.close() is not called automatically** -- The RAF is
   opened in the constructor but `close()` must be called manually.  No
   Closeable/AutoCloseable implementation.

8. **No node serialization** -- BPlusTree operates on in-memory nodes only.
   There is no code that serializes a BPlusTree node to bytes for DiskAdapter
   persistence.  The DiskAdapter is SPI-level but never wired into BPlusTree.

9. **BtrfsTreeRebuilder lifecycle is incomplete** -- Uses `ElementState` enum
   but only CREATED/OPEN/CLOSED.  No DRAINING or ACTIVE state.  Not an
   AsyncContextElement -- manages its own `var state`.

10. **rebuildFromSorted is O(n*log(n))** -- The helper does individual inserts
    instead of bulk-loading.  The doc comment acknowledges this: "A more
    sophisticated bulk-loading algorithm could be implemented here."

### Integration Steps

1. **Implement BPlusTree.remove()** -- Add delete with rebalancing and merge to
   pass contract tests.  This is the most visible gap.

2. **Implement range query** -- Add `range(start: K, end: K): Sequence<Join<K,V>>`
   leveraging the leaf linked list.

3. **Add COW semantics** -- Make insert/delete produce new node copies instead
   of mutating in place.  This enables snapshot isolation.

4. **Define BtrfsLeaf/BtrfsInternal** -- Either add these types to this module
   or document where they come from.  Without them, UserspaceBtrfsBuffer cannot
   compile standalone.

5. **Wire DiskAdapter to BPlusTree** -- Add a persistent BPlusTree mode that
   serializes/deserializes nodes through DiskAdapter on access.

6. **Implement node serialization** -- Add a Codec interface for
   `Node<K,V> -> ByteArray` and back, so the tree can be persisted.

7. **Add AutoCloseable to ChunkedFileDiskAdapter** -- Implement Closeable so
   the RAF is managed properly in try-with-resources blocks.

8. **Persist free-list metadata** -- For production use, the chunk allocator
   needs to survive restarts.

9. **Bulk-loading** -- Implement bottom-up B+Tree construction from sorted input
   for O(n) initial build.

## Path to Stable

- **v0.1** (current): BPlusTree insert/lookup, DiskAdapter SPI, userspace buffers
  with AsyncContextElement lifecycle, JVM file adapters.  No delete, no COW,
  no persistence.
- **v0.2**: BPlusTree delete + rebalance, range query, node serialization.
- **v0.3**: COW snapshots, DiskAdapter-integrated tree, bulk-loading.
- **v1.0**: Full btrfs-format persistence with checksums, free-list recovery,
  all contract tests passing.
