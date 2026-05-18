# TrikeShed Algebra Deep Dive

**Complexity, JIT Behavior, and LLVM Tradeoffs**
Companion to [bikeshed-you0a0haskell.md](./bikeshed-you0a0haskell.md).
That doc covers the lambda-calculus motivation and Join/Series/Cursor thesis.
This one maps the concrete type surface through the lens of performance:
complexity theory, JIT behavior, and LLVM tradeoffs — with SOTA analogs
to show where TrikeShed deliberately stays cold.

---

## Kernel Algebra: Why Join?

### The Allocation Question

`Join<A, B>` is not a philosophical choice. It is a complexity statement.

A product type with two properties and no methods, no equals, no hashCode,
no toString — the minimum viable representation — has a lower memory footprint
and a better cache working set than any JVM data class. Kotlin's `object : Join<A,B>`
anonymous class has exactly two fields (header + two OOPs). A data class with
two properties carries metadata, componentN, copy, equals, hashCode, toString:
~6× the surface area.

**Contemporary analog**: Arrow's `Field` is a product type with name + type +
nullability + dictionary encoding + custom metadata. TrikeShed's `ColumnMeta =
Join<CharSequence, TypeMemento>` is the same idea stripped to the minimum.
The metadata lives elsewhere (in `IOMemento`) so `ColumnMeta` stays cold.

**Complexity**: Construction is O(1). The `a`/`b` properties are final fields
with no indirection. Allocation rate is the only cost — which is why dense
packed twins (see below) are the hot-path response.

### Why Not Pair?

`Pair<A, B>` carries an identical structure but is stdlib. TrikeShed's `Join`
is KMP-common, meaning both JVM and Native share the same definition. Pair
lives in kotlin.* on JVM and is not available in commonMain for Kotlin/Native
without an explicit import. `Join` is zero-dependency.

**LLVM tradeoff**: For Native targets, Join's anonymous object is a two-field
struct in SSA form. LLVM's escape analysis can scalar-replace it into register
slots when the Join is only used within a single function scope. This eliminates
the heap allocation entirely — equivalent to C's stack-allocated struct. Pair
does not have this guarantee because its equals/hashCode methods prevent
scalar replacement.

### Twin<T>

```kotlin
typealias Twin<T> = Join<T, T>
fun <T> Twin(a: T, b: T): Twin<T> = autoTwin(a, b)
```

Same-type pair. `autoTwin` routes to the densest representation available.

---

## Dense-Packed Twins: The Hot-Path Response

### The Tradeoff Matrix

| Implementation | Allocation | Per-Access Cost | When |
|---|---|---|---|
| `Join.invoke(a, b)` | 1 heap obj + header | 1 load + 1 checkcast | cold |
| `PairJoin<A,B>` | 1 heap obj | 1 load (no checkcast) | warm |
| `TwInt(Long)` (inline) | 0 (in register) | 1 shift + 1 mask (ALU) | hot |

The dense forms are value classes (JVM `inline class`, Kotlin/Native `value class`).
When stored as a field or in an array slot, the wrapper vanishes — the bits live
directly in the containing reference. This is the core tradeoff: 2 ALU ops vs
1–2 heap allocations per access.

**Contemporary analog**: DuckDB's `Value` variant uses a 64-bit union for
primitive types (int16/int32/int64/float/double) with a type tag byte.
The boxing overhead is eliminated by keeping primitives unboxed in the union.
Simultaneously, Arrow's `PrimitiveArray<T>` stores primitives in a contiguous
`Buffer` without boxing. TrikeShed's dense twins are the same idea as the
`TwInt` level — they eliminate boxing at the Pair level, not the Array level.

### JIT Behavior for Dense Twins

C2 (Server JIT) treats `TwInt` as an `inline class` with a single Long field.
When `TwInt.a` is accessed in a tight loop, the following happens:

1. **Inline caching**: the call site `twin.a` is monomorphic — the JIT sees
   only `TwInt` at this call site and inlines the accessor. The Long field
   load is hoisted out of the loop.
2. **BCE (Bounds Check Elimination)**: if the loop index `i` is proved
   `0 ≤ i < size` by the surrounding logic, the bounds check on `series[i]`
   is eliminated. The resulting inner loop is two loads (size + element) +
   one shift + one mask — roughly 4 cycles per iteration on Zen 3.
3. **Vectorization (SIMD)**: if `TwInt` values are processed in a batch,
   LLVM can emit AVX2 `vperm2i128` / AVX-512 `vpsrlq` for the unpack step
   if the surrounding loop has enough ILP (instruction-level parallelism).
   This requires trip count to be known at JIT time — which is why
   `ChunkedMutableSeries` with fixed chunk size enables better vectorization
   than a linked-list Series.

**Graal-specific**: Graal's partial escape analysis can stack-allocate a `TwInt`
even when it escapes the immediate function, if the escape is only through
a final field read (no aliasing). This is tighter than C2's escape analysis.

**Tradeoff**: The 2-ALU cost is paid on every access. If the twin is accessed
infrequently (cold path), the ALU cost dominates and boxing would have been
cheaper. This is why `TwInt` is gated to hot paths only — `zipWithNext`
in cursor scans, tensor construction, cursor α-chains.

### AutoTwinContext: Megamorphic Lock

```kotlin
inline class AutoTwinContext<T>(private var locked: ((T,T) -> Twin<T>)? = null)
```

The probe in `autoTwin` is megamorphic when called from a generic context
like `Series.zipWithNext` where the element type is `T` at the call site.
The JIT sees multiple receiver classes and falls back to a megamorphic
cache lookup (inline cache miss → polymorphic inline cache → 3+ targets).

`AutoTwinContext` fixes this: first call probes once, stores the packer as
a captured lambda, all subsequent calls go through a single monomorphic
call site. The lock is one compare-and-branch (null check) vs the megamorphic
3-target lookup.

**Contemporary analog**: This is the same pattern as V8's IC (Inline Cache)
monomorphism threshold — after 4 different receiver types, the IC becomes
megamorphic and all lookups go through the generic path. `AutoTwinContext`
manually achieves monomorphism by locking the first successful probe.

---

## MetaSeries<I, T>: The Index Function

### Complexity of Indexing

```kotlin
typealias Series<T> = MetaSeries<Int, T>
typealias MetaSeries<I, T> = Join<I, (I) -> T>

val <I,T> MetaSeries<I,T>.size: I get() = a
operator fun <L,T> MetaSeries<L,T>.get(i: L): T = b(i)
```

`get` is O(1). The index function `b` is invoked directly — no bounds check
delegation, no interface dispatch on a `List` implementation class, no
iterator creation. The call is `invoke` on a captured function reference.

**JIT behavior**: When the Series is constructed as `N j { i -> expr }`
and the calling context is inlined (small N, tight loop), the Kotlin JIT
compiles the lambda body directly into the loop body. The `get` call
disappears entirely — it becomes a load from the captured local array at
a constant offset. This is equivalent to a raw array access after the JIT
has proven the Series is a simple anonymous object with a captured lambda.

### Bounds Check Elimination

Graal and C2 both perform BCE on array-like accesses when:
- The index is derived from a loop counter with provable bounds
- The array is provably non-null
- The access is within a method that cannot be partially跑了 (no side effects
  that could alter the array between the bounds check and the access)

In TrikeShed's `α` operator:
```kotlin
inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }
```

The `size` is captured from `this.a` at construction time. If the Series
is provably stable (not concurrently mutated), the JIT can eliminate
the bounds check on `this[i]` entirely. `CowSeriesHandle` mutation swaps
the body, but the `α` lambda captures the old size at construction time
— BCE still applies because the captured size is constant for that lambda's
lifetime.

### The Staircase Problem

Series can be stair-shaped: a Series of Series. This is the `combine` result
when inputs are not flattened. The staircase is lazy — no materialization
until indexed.

**Complexity**: Stair access is O(1) if the outer index is known, but
requires two index function invocations: `stairs[i]` → `outerSeries[i]`
→ `innerSeries[j]`. The lambda nesting can inhibit JIT inlining if the
closure captures are too many (register pressure) or too large (requires
heap allocation for the closure).

**ReificationContext** controls when staircases are flattened. When
`maxDepth == 0`, all combines materialize immediately. At higher depths,
the staircase is preserved until the depth budget is exhausted. The
`CacheTopology`-derived budget ensures materialization happens before
L2 cache pressure from the staircase becomes worse than the copy cost.

**Contemporary analog**: Polars' `LazyFrame` builds a tree of operations
that stays lazy until `collect()`. TrikeShed's staircase is the same idea
at the data structure level — `Combine.kt` is the equivalent of a query
planner deciding when to run a pipeline step vs deferring it.

---

## Series Variants: Complexity and JIT Tradeoffs

### CowSeriesHandle<T>

Copy-on-write with a flat `Array` body.

**Read path complexity**: O(1), `aaload` + checkcast.
- `letter.a` (size) is a final field — one load
- `letter.b` (index function) is a `Function1<Int,T>` — one load
- `b(i)` is the invoke: the JIT inlines the lambda body if monomorphic,
  which it will be for any stable series (single concrete type at the call site)
- Graal can devirtualize `Function1.invoke` when the receiver type is
  sealed (the body is a final class), turning it into a direct call

**Write path complexity**: O(n) where n = series size, due to `arraycopy`.
- `swap(letter, letter.set(index, item))` creates a new `COWSeriesBody`
  with a new array: `Arrays.copyOf(arr, newSize)` — intrinsic in HotSpot,
  compiles to `System.arraycopy` which emits `rep movsq` (SIMD-enhanced
  on Zen 4+) or `vmovdqa` (AVX) when counts are large
- The old body becomes GC-eligible immediately after the swap
- No lock contention if no observers — the swap is a single field write

**Observer callbacks**: `observer: ((Twin<Series<T>>) -> Unit)?` fires
on every swap. If this fires frequently (e.g., reactive cursor updates),
the callback must be cheap or the observer chain will dominate. Graal
inlines simple observers (no capture, no allocation) as a fast-path.

**Contemporary analog**: Apache Arrow's `RecordBatch` is immutable once
built — no COW needed because the builder pattern enforces single-owner
mutation before freeze. TrikeShed's COW is for multi-reader scenarios
with occasional writer: when a reader holds a reference and the writer
mutates, the reader keeps the old snapshot. This is equivalent to
Python's `copy-on-write` dict or Rust's `Rc<RefCell<Vec<T>>>` with
read-side clone isolation.

### ReifiedSplitSeries2<A, B>

Stores `leftSeries: Series<A>` and `rightSeries: Series<B>` separately.
`b: (Int) -> Join<A,B>` still constructs Joins on demand — but `.left`,
`.right`, `.valueAt(col)`, `.rightAt(col)` bypass the Join entirely.

**Complexity**: `valueAt(col)` is one array load — O(1) with zero allocation.
`rightAt(col)` is one array load — O(1) with zero allocation.
Building a full RowVec (all columns) without ReifiedSplit would allocate
N Join objects (one per column). With ReifiedSplit, zero allocations for
value-only columnar scans.

**JIT**: The `select(*indices: Int)` method uses the `IntArray` vararg
directly as an index vector. Graal can emit a tight loop over the IntArray
without bounds check overhead if the loop trip count is derived from
`indices.size` and the access pattern is provably in-bounds.

**Contemporary analog**: Arrow's `StructArray` stores columns separately
in a `children: Vec<ArrayRef>` — the same columnar layout. The difference
is Arrow's columnar arrays are self-describing with type metadata embedded
in the array struct. TrikeShed's split is purely a storage/layout decision;
the `rightSeries` carries `()` -> ColumnMeta lazy factories, not raw metadata.

### ChunkedMutableSeries<T>

Fixed-size chunk tree. Amortized O(1) append.

**Complexity**:
- `add(item)`: O(1) amortized — push to last chunk, allocate new chunk
  when last is full. No O(n) realloc because chunks are never moved.
- `get(i)`: O(log_chunks) — binary search on stairs array (`stairs[i]`
  cumulative sizes) + direct chunk access. For 4096-chunk size and 1M
  elements: ~250 chunks, binary search = 8 comparisons.
- `set(i, item)`: O(chunk_copy) — locate chunk, copy chunk once, rebuild
  combine view for that chunk. The copy is O(chunkSize), not O(n).

**LLVM tradeoff**: The stairs array (`IntArray`) enables `llvm.memcpy`
vectorization for chunk copies. When `chunkSize = 4096`, the copy is
large enough for LLVM's vectorizer to emit `llvm.memcpy.inline` with
AVX-512 (`vmovdqa64`/`vmovntdqa`). GCC and Clang both auto-vectorize
`memcpy` patterns from `memcpy(destination, source, size)` when size
is a compile-time constant and size >= 64 bytes.

**Contemporary analog**: RocksDB's `MemTable` uses a similar skiplist of
chunks for write buffering. The chunk size (default 4MB) is tuned for
L1 cache fit. TrikeShed's 4096-element default is tuned for 32KB L1
(line-sized for most x86_64) — small enough for the entire chunk to
stay in L1 during a write burst.

### JournalSeries<T>

COW + undo journal. Every mutation logs a `Delta` before applying.

**Complexity**: All mutations are O(1) on the journal (append-only list)
plus O(1) on the backing COW. Rollback is O(journal size) — replays
deltas in reverse.

**JIT**: The journal is a `RecursiveMutableSeries<Delta<T>>` — a tree.
Append is O(1) amortized (ChunkedMutable under the hood). The `Delta`
sealed hierarchy means the JIT sees a single `instanceof` branch per
rollback step. If the journal is hot (many rollbacks), the `instanceof`
becomes a monomorphic target and Graal emits a direct dispatch.

**Contemporary analog**: Filesystem journal (ext4, XFS) — write-ahead
log, replay on crash. TrikeShed's JournalSeries does the same thing at
the data structure level. The difference: ext4's journal is on disk,
JournalSeries is in memory.

### RingSeries<T>

Power-of-2 capacity, mask indexing.

**Complexity**: All operations O(1). `add` overwrites when full (no
resize). Mask `capacity - 1` replaces modulo — on modern x86, the
AND is a single-cycle ALU op vs the division+multiply of `%`.

**JIT**: The mask is a constant in the constructor's closure. All
subsequent index computations (`(head + i) and mask`) use the constant
mask value. The JIT can eliminate the mask if it can prove `i < capacity`
— BCE eliminates both the bounds check and the mask (because the result
is provably in range).

**Contemporary analog**: Linux kernel's `kfifo` (kernel FIFO) uses the
same power-of-2 + mask pattern. Java's `ArrayDeque` does not use mask
indexing — it uses head/tail indices with modulo. For small queue sizes,
the difference is negligible. For high-frequency trading (HFT) queues
where every nanosecond matters, the mask is measurably faster.

### DequeSeries<T>

Two-sided deque with front/back split.

**Complexity**: `addFirst` / `addLast` are O(1) — they create a new
view via `front + s_[item]` (the `+` is `combine`, a view, not a copy).
`removeFirst` / `removeLast` are O(1) if the affected side has >1 element;
O(n) only when the side becomes empty (must rebuild the other side's view).

The read path stitches: `deque[i] = front[front.size-1-i]` for front half,
`back[i-front.size]` for back half. Two branches, one load per access.
The branch prediction rate determines actual throughput — modern CPUs
predict the branch with 99%+ accuracy for sequential access patterns.

**LLVM tradeoff**: The stitch can be implemented as a conditional move
(`cmov`) on architectures with a cheap `cmov` implementation (x86_64 has
a slow `cmov` — 2-3 cycle latency — but ARM64's `csel` is a single-cycle
move). For hot-path cursor reads, the branch version is usually faster
on x86 due to micro-op fusion; for ARM64, `csel` eliminates the branch
altogether.

### SortedSeries<T>

Binary search insertion. Maintains sort order on every `add`.

**Complexity**: `insertionIndex` is O(log n) binary search.
`add(item)` = O(log n) + O(n) insert (shift elements right by 1).
`set(index, item)` = O(n) remove + O(log n) + O(n) insert.
`removeAt(index)` = O(n) shift.

**This is intentionally slow for unsorted bulk load**: if you insert
1000 elements one-by-one, it's O(n log n) total. The correct usage is
incremental insertion (stream processing) where the alternative is
O(log n) per element anyway. For bulk load, construct a plain Series
then sort with `SortChooser` (timings: introsort O(n log n), heapsort
O(n log n) worst-case, radix sort O(n) for fixed-width keys).

**Contemporary analog**: Python's `bisect.insort` is the same algorithm.
DuckDB's `ORDER BY` on a `List` column uses a similar incremental
insertion during streaming aggregation. The difference: DuckDB
compiles to SIMD-sort when the column is primitive and sorted in full
batch (radix sort, cache-aware).

### ColumnarSeries<Double> (and variants)

Dense primitive `DoubleArray` (or `LongArray`, `IntArray`, `FloatArray`).

**Complexity**: O(1) get/set, O(n) add/remove (realloc + copy).
No boxing — the array stores raw primitives, not `Double` objects.

**JIT**: The `DoubleArray.get(index)` is a primitive load — no
`checkcast`, no unboxing. On HotSpot, `dload` is a single bytecode
that compiles to a single `movsd` (scalar double) or `vmovdqu`
(AVX vector) depending on context. Graal can vectorize a loop
over `DoubleSeries` if the loop has enough ILP and the trip count
is provable.

**Contemporary analog**: Arrow's `PrimitiveArray<Double>` stores
doubles in a `Buffer` (mmap-able, zero-copy). NumPy's `ndarray`
stores doubles in contiguous C-order memory. The difference:
TrikeShed's ColumnarSeries is a `Series<T>` — it composes with the
Join/Series algebra. NumPy arrays don't compose with a generic
Series interface. Arrow arrays do, but with a richer type system
(`DataType`, `TypeLayout`, field-level null bitmap).

### LongSeries<T>

Long-indexed for >2GB files.

**Complexity**: O(1) get via the index function. The index function
is `(Long) -> T` — a captured lambda over a memory-mapped or buffered
source. Lookup is as fast as the underlying storage allows.

**JIT**: The index is a `Long`, not an `Int`. On x86_64, `Long` indices
fit in a register with no extra overhead. The mask/shift operations
in twin unpacking work identically on 64-bit registers.

---

## Concatenation and Combination

### combine: When to Flatten

The `combine` function in `Combine.kt` is TrikeShed's query execution
engine. It decides when to materialize stair-shaped compositions.

**Complexity**: Materialization is O(n) (copy all elements to a new Array).
Without materialization, access is O(1) for outer index + O(1) for inner
index (two lambda invocations). The tradeoff: how many accesses will
be made before the cost of the double-indirection exceeds the copy cost?

**ReificationContext** answers this via `CacheTopology`:

```kotlin
fun from(topology: CacheTopology): ReificationContext {
    val l1 = topology.l1DataBytes ?: return ReificationContext(Int.MAX_VALUE)
    if (l1 < 4096) return ReificationContext(0)  // always materialize
    return ReificationContext(log2(l1.toDouble() / 4096.0).roundToInt().coerceIn(0, 16))
}
```

If L1 < 4KB, always materialize — the cache working set is too small
to benefit from lazy. Otherwise, depth = log2(L1 / 4096) — the budget
is how many levels of lazy composition fit in L1 before the cost of
pointer-chasing exceeds the copy.

**Contemporary analog**: DuckDB's query planner decides between
materialized and streaming execution based on estimated cardinalities.
Polars' `LazyFrame` defers everything until `collect()`, then the
optimizer decides join order and scan strategy. TrikeShed's
ReificationContext is a simpler version — the decision is purely
cache-local, not cardinality-based.

---

## Buffer Types: CharSeries / ByteSeries

### Cache Windows

Both `CharSeries` and `ByteSeries` maintain a 4096-element sliding
cache window over the underlying `Series<Char>` / `Series<Byte>`.

```kotlin
var _charCache: CharArray? = null
var _cacheBase: Int = 0
var _cacheLen: Int = 0
val CHAR_CACHE_WINDOW: Int = 4096

fun raw(i: Int): Char {
    val c = _charCache
    if (c != null) {
        val b = _cacheBase; val l = _cacheLen
        if (i >= b && i < b + l) return c[i - b]
    }
    return b(i)  // fallback to index function
}
```

**Complexity**: Cache hit = O(1) with two integer comparisons.
Cache miss = O(1) for the index function plus O(4096) for the cache fill.
The cache is sized to fit in L1 dcache (32KB on Zen 3, 32KB on Ice Lake).

**Contemporary analog**: SIMDJson's `ParsedJson` maintains a structural
index (array of uint64_t, one per character position) that enables O(1)
jump to the next structural character. The 4096-char window is a similar
locality optimization — frequently accessed characters are in a hot
cache line rather than requiring a random memory access.

**Tradeoff**: The cache is updated on every miss. For random access
patterns (sparse reads), the cache adds overhead (cache fill on every
read). For sequential scan (the common case in parsing), the cache
eliminates ~99% of the index function calls after the initial fill.
The user explicitly chooses when to use CharSeries vs raw Series
based on access pattern.

---

## The Cursor Algebra

### RowVec = Series2<Any?, `ColumnMeta↻`>

The cursor's row is a split series: values separated from metadata.
This is the columnar storage format.

**Complexity**: `rowVec.left` (values) is a `Series<Any?>` — one load
from the ReifiedSplitSeries2. No Join allocation, no iteration over
columns. `rowVec.right` (metas) is a `Series<() -> ColumnMeta>` —
lazy, not computed until invoked.

**JIT**: In a tight loop over rows (`for (r in 0 until cursor.size)`),
`cursor[row]` returns a `RowVec`. Accessing `rowVec.values` in the
loop compiles to a load of the captured `leftSeries` field plus index
access into that series. If the leftSeries is a `DoubleSeries`, the
access is a raw `dload` — no boxing, no interface dispatch.

### Cursor α-Chain

```kotlin
Cursor α { row: RowVec -> ... }
```

The α over cursor applies the transform to each `RowVec`. The lambda
captures `row` as a `RowVec` — a `ReifiedSplitSeries2`. Inside the
lambda, column access is `.left[index]` (no Join allocation).

**LLVM**: If the lambda is simple enough (no captured state, purely
transformative), LLVM's SROA (Scalar Replacement of Aggregates) can
allocate each RowVec in registers rather than on the stack. Graal
does the same with its partial escape analysis.

### CursorTensorSnapshot

Reifies a cursor into a dense `DoubleArray`. Row-major layout for
SIMD friendliness.

**Why row-major?** Most ML workloads (linear regression, PCA, k-means)
access rows contiguously. Columnar access (values from one column across
all rows) would be `stride = colCount`, which has worse cache behavior
for sequential row scans. Arrow uses row-major internally for the same
reason in its `RecordBatch` (though columnar in the IPC format).

**Layout constants**: `TILE_BYTES = 512` is cache-line aligned.
Processing in 512-byte tiles ensures each tile fits exactly in one
L1 dcache line. This prevents cache thrashing in tiled ML algorithms.

**Contemporary analog**: ONNX Runtime's tensor representation uses
row-major with explicit tiling for cache-aware GEMM (matrix multiply).
CursorTensorSnapshot's 512-byte tile is the same idea.

---

## ISAM and Wire Format

### IOMemento: Fixed vs Variable Width

`networkSize: Int?` is the key complexity decision. Fixed-width types
(`IoInt(4)`, `IoLong(8)`, etc.) enable O(1) random access by offset
computation: `rowOffset = rowIndex * fixedRowSize + columnMeta.begin`.
Variable-width types (`IoString(null)`) require a separate offset index.

**Complexity**: Fixed-width cursor scan = O(1) per row (pure arithmetic).
Variable-width cursor scan = O(1) amortized if the variable-length prefix
is stored inline (pointer dereference + variable read). If not, requires
a bounce buffer or streaming read.

**Contemporary analog**: DuckDB's `StorageManager` uses fixed-width
columns with an optional overflow file for very large values. Arrow's
LENGTH / OFFSET buffer does the same for variable-size list types.
SQLite's record format uses a header with type codes followed by
serialized payloads — TrikeShed's `WireProto` is the same.

### WireProto: Per-Column Copy

```kotlin
for (x in 0 until meta.size) {
    val colMeta = meta[x]
    val colData = rowData[x]
    val pos = colMeta.begin
    val colBytes = colMeta.encoder(colData)
    colBytes.copyInto(rowBuf, pos, 0, colBytes.size)
}
```

**Complexity**: O(number of columns), one encode + one copy per column.
Each copy is `System.arraycopy` (intrinsic → SIMD vectorized). The
serialization is fully deterministic — no GC, no reflection.

**LLVM**: On Native targets (LLVM), the copy loop is compiled by
`llc` (LLVM static compiler). With LTO (Link-Time Optimization), the
encoder function is inlined into the copy loop, enabling loop-vectorize:
the compiler can emit a single `llvm.memcpy` that copies all column
bytes in one SIMD operation if the total row size is known at compile time.

---

## PlatformCodec: Endianness Without Branches

```kotlin
val isLittleEndian = (TEST_INT.toByte().toInt() and 0xFF) == 0x04
val currentPlatformCodec: PlatformCodec by lazy { CommonPlatformCodec(isLittleEndian) }
```

The codec is instantiated once, captured in the `PlatformCodec` singleton.
All `readInt`/`writeInt` calls go through the same codec object — no branch
per call, no if-statement at the call site.

**Complexity**: O(1) per read/write. On little-endian (x86_64, ARM64
in LE mode), the default codec reads the bytes directly without swap.
On big-endian (some ARM, POWER), `readInt` byteswaps: `(b[0] shl 24) or
(b[1] shl 16) or (b[2] shl 8) or b[3]`. The swap is 3 shifts + 3 ORs —
5 cycles on modern x86 (one `bswap` instruction handles it).

**Contemporary analog**: The Linux kernel's `__builtin_bswap32` / `bswap64`
intrinsics emit a single `bswap` instruction. TrikeShed's codec avoids
the branch-at-call-site by using a factory constructor — the platform
determines which codec to build once, then the codec is used directly.

---

## Confix Parser: Zero-Copy Parsing

### JsContext = Join<JsElement, Series<Char>>

The source text stays in its original `Series<Char>`. JsElement
stores (open, close) as `Join<Twin<Int>, Series<Int>>` — offsets into
the source, not copies of the text.

**Complexity**: Tokenization is O(n) over the source. Each JsElement
is constructed with integer offsets (O(1) each). No string allocation
during tokenization.

**Contemporary analog**: SIMDJson's parser generates a structural index
(array of uint64_t with type + position bits) — offsets into the raw
UTF-8 input, no copy. JsonScan does the same: `JsonBitmap` produces
structural markers as integers, the source text is never copied.
This is why SIMDJson achieves 3–4 GB/s parsing on modern CPUs —
the bottleneck is memory bandwidth, not CPU.

### The Kursive→Confix Migration

`parse.kursive` (legacy) is a traditional parser combinator library.
It allocates intermediate strings on every parse step. For small inputs,
this is fine. For large inputs (>10MB), the allocation rate dominates.

Confix replaces it with a state machine that emits `JsContext` records
without intermediate string allocation. The trade-off: Confix is less
ergonomic (no combinator composition), but the parsing is O(n) with
O(1) allocation per element.

---

## LLVM Optimization: TrikeShed on Native

### What LLVM Does Well With TrikeShed Code

**1. Scalar Replacement of Aggregates (SROA)**

Join's two-field interface maps cleanly to an LLVM struct:
```llvm
%Join = type { i64, i64 }  ; two fields, both 64-bit
```

When a function creates a Join and then accesses only `.a`, LLVM's SROA
pass can allocate `.a` in a register and discard `.b` entirely. This
is equivalent to Rust's `mem::transmute` but done automatically.

**2. Loop Vectorization**

`ChunkedMutableSeries` with fixed chunk size enables LLVM to vectorize
the chunk copy loop. The stairs array provides the trip count; the
copy loop is a `memcpy`-style loop over primitive elements.

`ColumnarSeries` with `DoubleArray` backing is the best case for vectorization:
contiguous doubles, known size, no aliasing. LLVM's vectorizer can emit
AVX-512 `vmovdqu64` (512-bit load/store = 8 doubles per instruction).

**3. Link-Time Optimization (LTO)**

When building Native targets with `lto = true`, LLVM's LTO merges the
TrikeShed standard library passes with user code. The `PackInts` inline
function can be inlined into the call site even across module boundaries.

**4. Escape Analysis + Stack Allocation**

For Native targets, LLVM's escape analysis can stack-allocate a `TwInt`
or `Join` when it can prove the value doesn't escape the function's
stack frame. This eliminates heap allocation entirely.

### Where LLVM Struggles

**1. Kotlin/Native's Value Classes**

Kotlin/Native's `value class` (formerly `inline class`) maps to LLVM's
`%Y` type wrapper. LLVM doesn't always eliminate the wrapper — the
Kotlin/Native ABI requires the wrapper to be transparent, but LLVM's
inliner doesn't always see through it. This is a known limitation
of the Kotlin/Native LLVM backend vs HotSpot's JIT.

**2. Dynamic Dispatch on Series Interface**

`MetaSeries<I, T>` has a function type `(I) -> T`. When the Series is
constructed via the builder syntax (`N j { ... }`), the function is
a captured lambda. When passed through interface methods, the function
type becomes a heap-allocated closure. LLVM's devirtualization of
function calls is weaker than Graal's, which has runtime profiling
data. Without PGO (Profile-Guided Optimization), LLVM cannot know the
monomorphic call site and emits a generic function call.

**3. Monomorphization**

Kotlin/Native does not support runtime monomorphization (no JIT).
All generic types must be reified or erased at compile time.
`Series<T>` is type-erased — `T` is `Any?` at runtime. The JIT on JVM
can monomorphize at runtime (specialize for `Int`, `Double`, etc.).
Native cannot. This means the hot-path inlining that works on JVM
doesn't apply to Native unless explicit specialization is coded.

---

## SOTA Comparison: Where TrikeShed Deliberately Stays Cold

TrikeShed's design philosophy: optimize when provably hot, stay simple
when cold. Most code paths are cold — parsing is done once per message,
cursor scans are done once per query, etc.

| Domain | SOTA | Approach | TrikeShed | Tradeoff |
|---|---|---|---|---|
| **JSON parsing** | SIMDJson | 3-4 GB/s, no allocation, SIMD scan | Confix: O(n) with per-element allocation | TrikeShed favors correctness and KMP over raw throughput |
| **Columnar storage** | Arrow | Zero-copy IPC, type-safe, mmap-able | RowVec = ReifiedSplitSeries2 | TrikeShed trades Arrow's rich type system for algebraic composability |
| **In-memory columnar** | Polars | Lazy graph, query optimizer, parallel scan | Cursor α-chain, Combine | TrikeShed's combine is manually orchestrated, no optimizer |
| **Persistence** | RocksDB | WAL, SST, Bloom filter, compression | IsamDataFile: WAL + sorted data | TrikeShed has no compression, no Bloom filter — simpler, slower on large datasets |
| **Query execution** | DuckDB | Vectorized execution, SIMD aggregate, cost-based optimizer | Series α + combine | DuckDB's vectorized execution processes 1024 rows per batch; TrikeShed processes one row per α call |
| **Memory management** | Rust / Arrow | Zero-copy, Rc/Arc, arena allocation | CowSeriesHandle | TrikeShed uses GC (JVM) or reference counting (Native); no zero-copy unless explicitly mapped |
| **Sorting** | Rust's `sort_unstable_by` | Pattern-defeating quicksort, radix sort for integers | SortChooser (introsort/heap/radix) | TrikeShed has the same algorithms, but no auto-detection of sorted input |
| **Network codec** | Cap'n Proto | Zero-copy, flat value layout, schema evolution | PlatformCodec | Cap'n Proto's value layout is code-generated for specific types; TrikeShed uses runtime introspection of IOMemento |
| **SIMD in ML** | ONNX Runtime | Tiled GEMM, cache blocking, AVX-512 | CursorTensorSnapshot (tile=512B) | ONNX Runtime tiles for L2 cache (256KB); TrikeShed tiles for L1 (32KB) — smaller tiles, more tile boundary crossings |

### The "Mostly Cold" Thesis

Most code paths in a trading system (or any IO-bound system) are cold:
the hot path is the market data cursor scan (cursor α over row values).
The cold paths are: parsing, ISAM writes, metadata lookups, cursor
combination.

TrikeShed invests JIT/LLVM optimization energy into the hot path
(Series `get`, dense twins, Cursor α) and deliberately keeps the
cold paths simple (Join.invoke, Confix element construction, IsamDataFile).

The contemporary analog is the C++ HFT stack: companies like
Flowrick and Simplex use a custom networking stack (DPDK, raw sockets)
for the hot path (market data ingress) and a standard FIX engine
for the cold path (order entry). They don't optimize the FIX parser —
it's called once per order. They optimize the market data path to
the nanosecond.

---

## Pre-Existing Build Failures (as of this doc)

These are known broken modules — fixes required, not workarounds:

- `:libs:cbadvanced` — `dreamer-kmm` not in `build.gradle.kts` dependencies.
  `dreamer.exchange.*` imports are unresolved. Fix: add the dependency.
- `:libs:miniduck` — `Isam3ResolvedColumn` source file is truncated.
  Missing properties: `meta`, `begin`, `type`. Fix: complete the class definition.
- `:libs:tiny-btrfs` — `java.*` imports in `commonMain`. KMP forbids `java.*`
  in common. Fix: move to platform-specific source set or replace with
  `kotlinx.cinterop` equivalent.
- `:libs:nars3` — Type mismatch in code path unrelated to current changes.
  Root cause TBD.

No conversion layers. No workarounds. Fix the module dependency or
source completeness.