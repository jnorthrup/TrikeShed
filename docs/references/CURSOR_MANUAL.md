# Cursor Manual — Complete Operator & Extension Surface

> **Core principle**: `Cursor = Series<RowVec>` where `RowVec = Series2<Any?, ColumnMeta↻>`
>
> Cursor-specific operators live in `borg.trikeshed.cursor.CursorOps`.
> Series index operators (multi-index, range, predicate) live in `borg.trikeshed.lib.Series`.
> **Do not import both `CursorOps` and `Series` index operators simultaneously** — they overlap on `get(IntRange)` and will cause ambiguous overload resolution.

---

## Import Discipline

```kotlin
// ✅ SAFE — Cursor-only operators (row/range/column)
import borg.trikeshed.cursor.CursorOps.*

// ✅ SAFE — Series index operators (IntArray, Iterable<Int>, Series<Int>, Predicate)
//    Use when you need multi-index projection on ANY Series<T>, including Cursor
import borg.trikeshed.lib.SeriesKt.get  // or import borg.trikeshed.lib.*

// ❌ UNSAFE — Both together cause ambiguous get(IntRange) on Cursor
// import borg.trikeshed.cursor.CursorOps.*
// import borg.trikeshed.lib.SeriesKt.get
```

---

## Type Hierarchy

```
Series<T>         = MetaSeries<Int, T> = Join<Int, (Int) -> T>
MetaSeries<I, T>  = Join<I, (I) -> T>
Series2<A, B>     = Series<Join<A, B>>

ColumnMeta        = Join<CharSequence, Join<TypeMemento, ColumnMeta?>>
ColumnMeta↻       = () -> ColumnMeta
RowVec            = Series2<Any?, ColumnMeta↻>
Cursor            = Series<RowVec>
```

---

## Cursor Operators (from `CursorOps.kt`)

| Operator | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Row access** | `operator fun Cursor.get(i: Int): RowVec` | `RowVec` | Direct row selection — `b(i)` |
| **Range view** | `operator fun Cursor.get(range: IntRange): Cursor` | `Cursor` | Lazy range composition: `range.count() j { i -> this[range.first + i] }` |
| **Column exclusion** | `operator fun Cursor.minus(name: CharSequence): Cursor` | `Cursor` | Removes column by name from all rows |

> **Note**: `get(IntRange)` is the **only** `operator fun get` defined on `Cursor` directly.
> Multi-index (`IntArray`, `Iterable<Int>`, `Series<Int>`) and `Predicate` are inherited from `Series<RowVec>`.

---

## Cursor Extension Functions (from `CursorOps.kt`)

| Function | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Column projection (ordinal)** | `fun Cursor.select(vararg cols: Int): Cursor` | `Cursor` | Reorders/projects columns by index: `size j { row -> cols.size j { c -> this[row][cols[c]] } }` |
| **Column projection (name)** | `fun Cursor.select(vararg names: CharSequence): Cursor` | `Cursor` | Resolves name→index from row 0 metadata, then delegates to `select(vararg Int)` |
| **Lazy row map (α)** | `infix fun <C> Cursor.α(xform: (RowVec) -> C): Series<C>` | `Series<C>` | `size j { i -> xform(this[i]) }` — projection over rows |
| **First row** | `val Cursor.head: RowVec` | `RowVec` | `this[0]` |
| **All but first** | `val Cursor.tail: Cursor` | `Cursor` | `this[1..< size]` |
| **Column metadata** | `val Cursor.meta: Series<ColumnMeta>` | `Series<ColumnMeta>` | Row 0's metadata suppliers invoked: `this[0].size j { c -> this[0][c].b() }` |
| **Column names** | `val Cursor.columnNames: Series<CharSequence>` | `Series<CharSequence>` | `meta α { it.name }` |
| **Column count** | `val Cursor.width: Int` | `Int` | `this[0].size` |

---

## Series Index Operators Inherited by Cursor (from `lib/Series.kt` + `lib/Join.kt`)

These are **not** Cursor-specific — they apply to any `Series<T>` including `Cursor = Series<RowVec>`.

| Operator | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Single index** | `operator fun Series<T>.get(i: Int): T` | `T` | `b(i)` — inherited, same as `Cursor.get(Int)` |
| **Range** | `operator fun Series<T>.get(range: IntRange): Series<T>` | `Series<T>` | `range.count() j { i -> this[range.first + i] }` — **CONFLICTS** with `Cursor.get(IntRange)` |
| **Iterable indices** | `operator fun Series<T>.get(index: Iterable<Int>): Series<T>` | `Series<T>` | Projects via `IntArray(index.count(), index::elementAt)` |
| **Series<Int> indices** | `operator fun Series<T>.get(index: Series<Int>): Series<T>` | `Series<T>` | Projects via `IntArray(index.size) { index[it] }` |
| **IntArray indices** | `operator fun Series<T>.get(index: IntArray): Series<T>` | `Series<T>` | `index.size j { i -> this[index[i]] }` — scatter/gather |
| **Predicate filter** | `operator fun Series<T>.get(test: Predicate<T>): Iterator<T>` | `Iterator<T>` | Returns iterator of matching elements |
| **Index exclusion** | `operator fun Series<T>.minus(killbag: Series<Int>): Series<T>` | `Series<T>` | Removes indices in `killbag` — allocation-free linear scan |
| **Enum index** | `operator fun Join<Int, (Int)->S>.get(e: Enum<E>): S` | `S` | `get(e.ordinal)` |
| **Split (div)** | `operator fun Series<T>.div(d: Int): Series<Series<T>>` | `Series<Series<T>>` | Splits into `d` parts |
| **Infix access** | `infix fun Series<T>.at(index: Int): T` | `T` | Alias for `get(index)` |

---

## Cursor Combinators (standalone functions, not extensions)

| Function | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Widen (join)** | `fun join(left: Cursor, right: Cursor): Cursor` | `Cursor` | Side-by-side column concatenation: `minOf(left.size, right.size) j { row -> (lr.size + rr.size) j { c -> if (c < lr.size) lr[c] else rr[c - lr.size] } }` |
| **Concat rows (combine)** | `fun combine(top: Cursor, bottom: Cursor): Cursor` | `Cursor` | Top-to-bottom row concatenation: `(top.size + bottom.size) j { row -> if (row < top.size) top[row] else bottom[row - top.size] }` |
| **Combine w/ policy** | `fun combine(top: Series<T>, bottom: Series<T>, ctx: ReificationContext, currentDepth: Int): Series<T>` | `Series<T>` | Cache-topology-aware materialization (from `Combine.kt`) |

---

## RowVec Accessors (from `ReifiedSplitSeries2.kt` + `RowVecSupport.kt`)

| Accessor | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Values series** | `val RowVec.values: Series<Any?>` | `Series<Any?>` | Zero-allocation if `ReifiedSplitSeries2`, else `α { it.a }` |
| **Metadata series** | `val RowVec.metas: Series<ColumnMeta↻>` | `Series<ColumnMeta↻>` | Zero-allocation if `ReifiedSplitSeries2`, else `α { it.b }` |
| **Single metadata** | `fun RowVec.meta(col: Int): ColumnMeta` | `ColumnMeta` | `this[col].b()` |
| **Column names** | `val RowVec.keys: Series<String>` | `Series<String>` | `right α ColumnMeta↻::invoke α { it.name.toString() }` |
| **Cell values** | `val RowVec.cells: Series<Any?>` | `Series<Any?>` | Alias for `values` |
| **Child row** | `val RowVec.child: RowVec?` | `RowVec?` | Always `null` (deferred per spec) |
| **Value by name** | `fun RowVec.getValue(key: String): Any?` | `Any?` | Scans columns for matching metadata name |
| **Typed getters** | `RowVec.intValue(name)`, `longValue`, `doubleValue`, `stringValue` | Primitive | Coerces via `getValue` |

---

## GroupBy Operations (from `GroupBy.kt`)

| Function | Signature | Returns | Semantics |
|----------|-----------|---------|-----------|
| **Group by columns** | `fun Cursor.groupBy(vararg axis: Int): Cursor` | `Cursor` | Non-key columns become `Series<Any?>` of grouped values |
| **Group with reducer** | `inline fun Cursor.groupBy(axis: IntArray, reducer: RowReducer): Cursor` | `Cursor` | Reduces non-key columns with `(Any?, Any?) -> Any?` |

---

## Column Key Algebra (from `ColK.kt`)

```kotlin
sealed class ColK<out R> : OpK<R>() {
    data class ByIndex(val col: Int)          : ColK<Any?>()
    data class ByName(val name: CharSequence) : ColK<Any?>()
    data object Meta                          : ColK<Series<ColumnMeta>>()
    data object Width                         : ColK<Int>()
}

fun RowVec.asFaceted(): FacetedRow<ColK<*>>
fun FacetedRow<ColK<*>>.asRowVec(): RowVec
```

---

## Usage Patterns

### Row Selection & Range Views
```kotlin
val row: RowVec = cursor[5]           // Cursor.get(Int) → RowVec
val range: Cursor = cursor[2..7]      // Cursor.get(IntRange) → Cursor (LAZY)
val tail: Cursor = cursor.tail        // cursor[1..< size]
```

### Multi-Row Projection (inherited from Series)
```kotlin
// Requires: import borg.trikeshed.lib.SeriesKt.get
val reordered: Cursor = cursor[intArrayOf(3, 1, 4, 1, 5)]
val sampled: Cursor = cursor[seriesOf(10, 20, 30)]
val filtered: Cursor = cursor[listOf(0, 2, 4, 6, 8)]
val excluded: Cursor = cursor.minus(killbag)  // Series.minus(Series<Int>)
```

### Column Operations
```kotlin
// Ordinal projection
val narrow: Cursor = cursor.select(0, 2, 4)

// Name projection
val named: Cursor = cursor.select("id", "timestamp", "payload")

// Exclusion by name (operator)
val noDebug: Cursor = cursor - "debug"

// All column metadata
val meta: Series<ColumnMeta> = cursor.meta
val names: Series<CharSequence> = cursor.columnNames
val width: Int = cursor.width
```

### Widening & Concatenation
```kotlin
val wider: Cursor = join(cursorA, cursorB)    // Side-by-side
val taller: Cursor = combine(cursorA, cursorB) // Top-to-bottom
```

### Row Projection (α)
```kotlin
val rowSizes: Series<Int> = cursor α { it.size }
val firstCol: Series<Any?> = cursor α { it[0].a }
```

### GroupBy
```kotlin
val grouped: Cursor = cursor.groupBy(0, 1)           // Group by cols 0 & 1
val reduced: Cursor = cursor.groupBy(intArrayOf(0)) { acc, v -> 
    (acc as? Int ?: 0) + (v as? Int ?: 0) 
}
```

---

## Chaining Example

```kotlin
// c[1,3,4,5,6][1,1,1,1,1,1][1,2,3,4,5]
// Each [...] returns Cursor (Series<RowVec>) — same view algebra stacks
val c: Cursor = ...
val step1: Cursor = c[intArrayOf(1, 3, 4, 5, 6)]      // 5 rows
val step2: Cursor = step1[intArrayOf(1, 1, 1, 1, 1, 1)] // 6 rows (all row 1 of step1)
val step3: Cursor = step2[intArrayOf(1, 2, 3, 4, 5)]   // 5 rows (re-indexed)
```

Each link is a **lazy index remapping** — no row data copied until materialization.

---

## Materialization Control

```kotlin
import borg.trikeshed.cursor.Combine.combine
import borg.trikeshed.cursor.Combine.ReificationContext

// Eager — flatten immediately
val flat: Cursor = combine(top, bottom, ReificationContext.EAGER)

// Lazy — keep staircase composition
val lazy: Cursor = combine(top, bottom, ReificationContext.LAZY)

// Auto — derive from cache topology
val auto: Cursor = combine(top, bottom, ReificationContext.from(CacheTopology.DEFAULT))

// Force materialize any Series
val materialized: Series<T> = materialize(lazySeries)
```

---

## Summary: What Lives Where

| File | Contains |
|------|----------|
| `Cursor.kt` | Type aliases: `Cursor`, `RowVec`, `ColumnMeta`, `TypeMemento` |
| `CursorOps.kt` | **Cursor-specific**: `get(Int)`, `get(IntRange)`, `minus(CharSequence)`, `select(...)`, `α`, `head`, `tail`, `meta`, `columnNames`, `width` |
| `Series.kt` / `Join.kt` | **Series index operators**: `get(IntArray)`, `get(Iterable<Int>)`, `get(Series<Int>)`, `get(IntRange)`, `get(Predicate)`, `minus(Series<Int>)`, `div(Int)`, `at(Int)` |
| `Combine.kt` | `join`, `combine`, `materialize`, `ReificationContext` |
| `GroupBy.kt` | `groupBy(vararg Int)`, `groupBy(IntArray, RowReducer)` |
| `ReifiedSplitSeries2.kt` | Hot-path `RowVec.values`, `RowVec.metas`, `RowVec.meta(Int)` |
| `RowVecSupport.kt` | `getValue(String)`, typed getters, `cellsToRowVec`, `keys`, `cells`, `child` |
| `ColK.kt` | `ColK` sealed class, `RowVec ↔ FacetedRow` isomorphism |

---

## Import Cheat Sheet

```kotlin
// Minimal: Cursor row/range/column ops only
import borg.trikeshed.cursor.CursorOps.*

// Add Series multi-index ops (IntArray, Iterable, Series<Int>, Predicate)
// Use ONE of these, not both:
import borg.trikeshed.lib.SeriesKt.get          // extension-style
// OR
import borg.trikeshed.lib.*                     // brings all Series operators

// Combinators
import borg.trikeshed.cursor.join
import borg.trikeshed.cursor.combine
import borg.trikeshed.cursor.Combine.ReificationContext

// GroupBy
import borg.trikeshed.cursor.groupBy

// RowVec hot-path
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.RowVecSupport.*

// Column keys
import borg.trikeshed.cursor.ColK.*
```