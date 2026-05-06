# libs/patl

## What It Is

`libs/patl` implements a **dense Long-packed patricia trie** data structure
for integer-keyed tries. It provides three layers:

1. **`IntNodeStore`** — mutable trie node storage. Each node is two `Long`
   values (16 bytes) packed via `TwInt` into parallel `LongSeries` arrays:
   `links[i] = packInts(leftChild, rightChild)` and
   `meta[i] = packInts(parent|childId, skip)`. Supports `append`, `initRoot`,
   and inline hot-path field accessors.
2. **`BitComp<K>`** — generic byte-level bit comparator. Given a key-to-bytes
   extraction function, finds the first mismatching bit position between two
   keys. Returns `ALL_MATCH` (UInt.MAX_VALUE) for identical keys.
3. **`AutoIntNodeStore`** — freeze/compress layer. Takes a mutable
   `IntNodeStore` and produces a read-only `CompressedNodeStore` where each
   field is bit-packed into the minimal `PackedIntSeries` width.

The module is pure data structure code — no I/O, no coroutines, no lifecycle
management. All types are value-oriented with `inline` hot-path accessors.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/patl/
    IntNodeStore.kt        -- mutable 16-byte/node trie storage (LongSeries-backed)
    BitComp.kt             -- generic bit-mismatch comparator for arbitrary keys
    AutoIntNodeStore.kt    -- freeze() → CompressedNodeStore (bit-packed fields)

  commonTest/kotlin/borg/trikeshed/patl/
    IntNodeStoreTest.kt    -- node CRUD, parent/child field accessors
    BitCompTest.kt         -- mismatch position, ALL_MATCH, prefix edge cases
    AutoIntNodeStoreTest.kt -- freeze then read-back round-trip
    PatlContractTest.kt    -- placeholder contract tests (all assertTrue(true))
```

## Key/Element Status

This module has **no Key/Element types**. It is a pure data structure library.

| Symbol | Classification | Notes |
|---|---|---|
| `IntNodeStore` | Mutable value object | No lifecycle; no Key needed |
| `IntNodeStore.Companion` | Statics (NULL, PARENT_MASK) | Constants, not routable |
| `BitComp<K>` | Pure function container | Stateless comparator; no Key |
| `BitComp.Companion.ALL_MATCH` | Static sentinel | UInt.MAX_VALUE constant |
| `AutoIntNodeStore` (object) | Stateless singleton | Pure `freeze()` transformation |
| `CompressedNodeStore` | Read-only value object | Frozen output of `AutoIntNodeStore.freeze()` |

Nothing here should become an `AsyncContextKey` or `AsyncContextElement`. The
module is fully `inline`-friendly, zero-allocation data structure code.

## Dependencies

- **Root project** (`org.bereft:TrikeShed`) — `api` dependency via
  `trikeshed-lib.gradle` (`kmpHost()` with default `rootMode="api"`).
  Consumed symbols: `LongSeries`, `Series`, `TwInt`, `packInts`, `Series2`,
  `PackedIntBuf`, `PackedIntSeries`, `zip`, `j` infix, `emptySeries`.
- **kotlinx-coroutines-core** — brought in by shared gradle macro (not used
  in production code).
- **kotlinx-coroutines-test** — test dependency (not used in current tests).

## Build

Host KMP module (`kmpHost()`) — JVM, JS, wasmJs, one native target.
Same target matrix as `libs/common`.
