# libs/patl — TODO

## Boundary Audit

### Current State

All production types are pure data structures with no lifecycle, no state
machines, and no async context involvement. The module correctly uses root
kernel types (`Series`, `TwInt`, `packInts`, `PackedIntBuf`) as building
blocks without introducing any Key/Element patterns of its own.

### Key/Element Classification

| Symbol | Classification | Action |
|---|---|---|
| `IntNodeStore` | Mutable value — **not** a Key/Element candidate | Keep as-is |
| `IntNodeStore.NULL` | Static sentinel constant | Keep as companion const |
| `IntNodeStore.PARENT_MASK` | Static mask constant | Keep as companion const |
| `BitComp<K>` | Pure function holder | Keep as-is |
| `BitComp.ALL_MATCH` | Static sentinel (UInt.MAX_VALUE) | Keep as companion val |
| `AutoIntNodeStore` | Stateless singleton object | Keep as object — no Key needed |
| `CompressedNodeStore` | Read-only frozen value | Keep as-is |
| `CompressedNodeStore.empty()` | Static factory | Keep as companion fun |

### Findings

1. **PatlContractTest is entirely placeholder.** All 7 test methods contain
   `assertTrue(true)` with no real assertions. These are contract stubs that
   need real implementations once a `PatriciaTrieMap` facade is built on top
   of `IntNodeStore` + `BitComp`.
2. **No `PatriciaTrieMap` exists yet.** The building blocks (`IntNodeStore`,
   `BitComp`, `CompressedNodeStore`) are in place, but the trie map itself
   (insert/lookup/delete using BitComp to navigate IntNodeStore) has not been
   implemented. The contract tests describe this future API.
3. **`CompressedNodeStore` has no `getParentRaw()` accessor** — only
   `getParent()` which strips the LSB. If callers need the raw parent field
   (including child-id bit), they would need a new accessor.
4. **`BitComp` uses LSB-first bit numbering** — documented but worth calling
   out: bit 0 is the LSB of byte 0, not MSB. Patricia trie consumers must
   align with this convention.
5. **No `delete` support on `IntNodeStore`** — only `append`. Trie deletion
   would require either tombstones or compaction.

## Integration Steps

1. **PatriciaTrieMap facade**: Build a `PatriciaTrieMap<K, V>` on top of
   `IntNodeStore` (or `CompressedNodeStore` for read-only) and `BitComp<K>`.
   This is the missing consumer-facing API.
2. **PatlContractTest population**: Replace `assertTrue(true)` stubs with real
   assertions against `PatriciaTrieMap`.
3. **Integration with `libs/kursive`**: Patricia tries can serve as the
   index structure for kursive's radix/trie collections. Add `libs/patl` as
   a dependency of kursive when ready.
4. **Integration with `libs/miniduck`**: Patricia trie indices could accelerate
   miniduck's document lookups. Wire through couch → miniduck → patl.
5. **Frozen trie serialization**: `CompressedNodeStore` fields are already
   `Series<Int>` backed by `PackedIntSeries` — these can be memory-mapped or
   serialized directly for persistent trie storage.

## Path to Stable

- [ ] Implement `PatriciaTrieMap<K, V>` with insert/lookup/delete
- [ ] Replace all `PatlContractTest` stubs with real assertions
- [ ] Add property-based tests: random insert sequences, consistency with
      reference implementation (e.g., stdlib `Map`)
- [ ] Benchmark `IntNodeStore` hot-path accessors (verify inline elision)
- [ ] Add `delete` support to `IntNodeStore` (or document as append-only)
- [ ] Add `getParentRaw()` to `CompressedNodeStore` if needed by consumers
- [ ] Consider making `BitComp` a `fun interface` or `typealias` for simpler
      lambda usage
- [ ] Document LSB-first bit numbering convention prominently
- [ ] Once `PatriciaTrieMap` exists, mark module as stable candidate
