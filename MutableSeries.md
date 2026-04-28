# MutableSeries — Strategy Catalog

Eight composable `MutableSeries<T>` implementations. None use `kotlin.collections.List`, `Map`, `Set`, or any stdlib collection as backing store. Every strategy composes from the TrikeShed core algebra: `Series<T>` = `Join<Int, (Int) -> T>`, `s_[]`, `combine`, `CowSeriesHandle`, `RecursiveMutableSeries`, and the `view`/`α`/`fold` surface.

## Existing primitives (pre-requisites)

| Primitive | Source | Signature |
|---|---|---|
| `Series<T>` | `Join.kt` | `Join<Int, (Int) -> T>` — lazy indexed sequence |
| `MutableSeries<T>` | `MutableSeries.kt` | Contract: set, add, removeAt, remove, clear, `+`, `-`, `+=`, `-=` |
| `CowSeriesHandle<T>` | `CowSeriesHandle.kt` | Copy-on-write envelope wrapping `COWSeriesBody`. Versioned, observable. |
| `RecursiveMutableSeries<T>` | `RecursiveMutableSeries.kt` | Lighter mutation: replaces `data: Series<T>` on every op. |
| `s_[]` | `s_.kt` | `s_[x]` → singleton Series, `s_[a,b,c]` → multi-element |
| `combine` | `Combine.kt` | Concatenate Series via stairs-indexed view |

## Strategy 1: RingSeries — Fixed-capacity circular overwrite

**O(1) append, O(1) get, O(1) set.** Capacity must be power of 2 (mask-based indexing, no modulo). When full, `add(item)` overwrites the oldest element and advances the head pointer. `removeAt` slides the window. `clear()` resets pointers without touching the buffer.

Use case: streaming kline windows, sliding indicators, realtime OHLCV ring buffers.

```
File: RingSeries.kt
```

## Strategy 2: ColumnarMutableSeries — Dense primitive, zero boxing

**O(1) get, O(1) set.** A `MutableSeries<Double>` that IS a `DoubleArray` underneath — no per-element allocation. `add`/`removeAt`/`clear` resize via realloc (same as ArrayList), but the read path is pointer-free. Also: `LongSeries`, `IntSeries`, `FloatSeries`.

Use case: EngineState baselines, price history buffers, portfolio tensor columns.

```
File: ColumnarSeries.kt
```

## Strategy 3: SortedSeries — Binary-search insert, maintained sort order

**O(log n) contains, O(n) add/remove.** `add(item)` binary-searches for insertion point, then rebuilds the backing Series with the item inserted at the correct position. `contains(item)` is O(log n) via binary search on `Series<T>` — much faster than the O(n) linear scan of `RecursiveMutableSeries`.

Use case: sorted kline time indexes, order books, tick aggregators.

```
File: SortedSeries.kt
```

## Strategy 4: ChunkedMutableSeries — Block-tree amortized O(1) append

**Amortized O(1) append, O(log k) get (k = chunk count).** Splits the backing into fixed-size chunks (default 4096). Append: push to last chunk until full, then allocate new chunk. Read path: stairs-indexed via `combine()` — same pattern as `Combine.kt`. Avoids O(n) copy on every append that `RecursiveMutableSeries` and `CowSeriesHandle` incur.

Use case: CSV line accumulators, trade history collectors, any append-heavy builder.

```
File: ChunkedMutableSeries.kt
```

## Strategy 5: DequeSeries — Two-ended O(1) addFirst/addLast

**O(1) addFirst, O(1) addLast, O(1) get.** Backed by two `Series<T>`: `front` (reversed) and `back` (forward). `addFirst(item)` prepends to front via `s_[item] + front` (view, O(1)). `addLast(item)` appends to back via `back + s_[item]` (view, O(1)). `get(i)` stitches: `if i < front.size → front[size-1-i] else → back[i-front.size]`.

Use case: sliding deques for order flow, event windows that grow at both ends.

```
File: DequeSeries.kt
```

## Strategy 6: GuardSeries — Predicate-gated mutation

**O(1) guard on mutation, O(1) read.** Wraps any `MutableSeries<T>` via delegation. `add(item)` and `set(index, item)` only succeed if `guard(item)` returns true. `remove`/`removeAt`/`clear` always pass through. The read path delegates directly.

Use case: domain-invariant enforcement (no negative quantities, no duplicate symbols, bounded values).

```
File: GuardSeries.kt
```

## Strategy 7: JournalSeries — Delta journal, commit/rollback

**O(1) mutation + O(1) journal append.** Every mutation records a `Delta<T>` (Set/Add/Remove) in a journal. `commit()` clears the journal (irreversible). `rollback()` replays journal in reverse to undo all mutations. Wraps any `CowSeriesHandle<T>` — the backing is always mutable; the journal provides the undo capability.

Use case: speculative trading engine cycles, optimistic simulation tick, trial-strategy evaluation.

```
File: JournalSeries.kt
```

## Strategy 8: MergeMutableSeries — Batch-insert with lazy k-way merge

**Amortized O(log n) insert, O(1) after compact.** Maintains a `sorted: Series<T>` and a `pending: RecursiveMutableSeries<T>`. `add(item)` appends to pending. When `pending.size >= mergeThreshold`, triggers `compact()`: external-sort pending, then merge into sorted. Reads come from sorted. Between compactions, inserts are cheap.

Use case: bulk trade record ingestion where reads happen periodically, not after every insert.

```
File: MergeMutableSeries.kt
```

---

## Complexity Table

| Strategy | append | get | set | contains | remove | Memory |
|---|---|---|---|---|---|---|
| RingSeries | O(1) | O(1) | O(1) | O(n) | O(1) | Fixed array |
| ColumnarMutableSeries | O(1)* | O(1) | O(1) | O(n) | O(n) | Dense primitive |
| SortedSeries | O(n) | O(1) | O(n) | O(log n) | O(n) | Lazy Series |
| ChunkedMutableSeries | amortized O(1) | O(log k) | O(chunk) | O(n) | O(chunk) | Block tree |
| DequeSeries | O(1) both | O(1) | O(1) | O(n) | O(1) | Two Series |
| GuardSeries | O(1)+guard | O(1) | O(1)+guard | O(n) | O(1) | Delegates |
| JournalSeries | O(1)+journal | O(1) | O(1)+journal | O(n) | O(1)+journal | Delegates + journal |
| MergeMutableSeries | amortized O(log n) | O(1) | — | O(log n) | — | Sorted + pending |

\* Columnar: O(1) amortized for add (realloc on capacity), O(n) for insert.
