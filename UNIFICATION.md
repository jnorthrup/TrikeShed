# Algorithm Unification Candidates

*TrikeShed — surveyed Saturday, April 25, 2026*

---

## Priority 1 — High Impact, Low Risk

### 1. ObjectStoreRowVec Sealed Class: GcsRowVec / S3RowVec / AlibabaRowVec are 98% Identical

**Location:** `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/RowVecFamilies.kt` lines 143–207

**Problem:** Three nearly identical data classes that only differ by `provider: ObjectStoreProvider` enum value:

```kotlin
class GcsRowVec(override val bucket, override val key, override val byteSize, ...override val blob) :
    ObjectStoreRowVec(...) {
    override val provider = ObjectStoreProvider.GCS
}
class S3RowVec(...) : ObjectStoreRowVec(...) {
    override val provider = ObjectStoreProvider.S3
}
class AlibabaRowVec(...) : ObjectStoreRowVec(...) {
    override val provider = ObjectStoreProvider.ALIBABA
}
```

**Unification:** The sealed class `ObjectStoreRowVec` already holds all fields. The concrete classes only override
`provider`. Replace the 3 classes with a single factory:

```kotlin
class ObjectStoreRowVecconstructor(
    override val provider: ObjectStoreProvider,
    override val bucket: String,
    override val key: String,
    override val byteSize: Long,
    override val contentType: String?,
    override val etag: String?,
    override val lastModified: String?,
    override val versionId: String?,
    override val metadata: Map<String, String>?,
    override val blob: Series<MiniRowVec>?,
) : ObjectStoreRowVec(...) {
    companion object {
    fun gcs(...params...) = ObjectStoreRowVec(GCS, ...)
    fun s3(...params...) = ObjectStoreRowVec(S3, ...)
    fun alibaba(...params...) = ObjectStoreRowVec(ALIBABA, ...)
}
}
```

**Impact:** 45 lines removed, zero behavior change. Eliminates the only reason for 3 subclasses.

---

### 2. `child: Series<MiniRowVec>?` Is Duplicated 7 Times — Extract `LazyChildRowVec` Base

**Location:** `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/RowVecFamilies.kt`

Every RowVec class implements the same lazy child pattern:

| Class         | child pattern                                                         |
|---------------|-----------------------------------------------------------------------|
| DocRowVec     | `override val child: Series<MiniRowVec>? = null` (constructor param)  |
| ViewRowVec    | `private var loadedChild` + getter with null-check + `docLoader?.let` |
| BlobRowVec    | `childFactory?.invoke(bytes)`                                         |
| JsonRowVec    | `childFactory?.invoke()`                                              |
| YamlRowVec    | `childFactory?.invoke()`                                              |
| GcsRowVec     | `override val child: Series<MiniRowVec>? get() = blob`                |
| S3RowVec      | same                                                                  |
| AlibabaRowVec | same                                                                  |

**Unification — two-part:**

**Part A:** Abstract base that defines the `child` contract:

```kotlin
abstract class LazyChildRowVec : MiniRowVec() {
    abstract override val child: Series<MiniRowVec>?
}
```

**Part B:** Shared lazy-loading helper:

```kotlin
protected fun <T : MiniRowVec> loadChild(
    cached: Series<T>?,
    factory: () -> Series<T>?
): Series<T>? = cached ?: factory()?.also { cached = it }
```

ViewRowVec's double-checked pattern becomes:

```kotlinvar loadedChild: Series<MiniRowVec>? = null
override val child: Series<MiniRowVec>? get() = loadChild(loadedChild) { docLoader?.invoke() }
```

**Impact:** Reduces boilerplate across 4 classes. Makes the lazy semantics explicit in one place.

---

### 3. `α` (Alpha Transform) on RowVec / Cursor — Project Columns Lazily

**Location:** `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt` line 32;
`src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt` line 22

Series has: `V.α(crossinline xform: (X) -> C): Series<C>`

But projecting a Cursor's RowVecs column-by-column requires manual iteration. Example from `Cursor.div(KClass<A>)` (line
45–48 of Cursor.kt):

```kotlin
this α { it: SrInnr -> it α Join<A, *>::a } α { it: Series<A> -> it α { it: A -> it } } α { it: Series<A> -> it α { it: A -> it } }
```

**Unification:** Extend `α` to work on Cursor (which is `Series<RowVec>`):

```kotlin
infix fun <C> Cursor.α(xform: (RowVec) -> C): Series<C> =
    size j { i -> xform(row(i)) }
```

Then Cursor projection becomes: `cursor α { row -> row.α(Join<*, ()`ColumnMeta↻`>::a) }` — or with further unification
of the RowVec alpha:

```kotlin
// On RowVec directly — project the value side only (skip metadata)
val RowVec.values: Series<Any?> get() = size j { get(it) }
// Then:
cursor α { it.values }
```

**Impact:** Eliminates triple-`α` chain in `div(KClass)` operator. Makes Cursor projections as ergonomic as Series
projections.

---

## Priority 2 — Medium Impact

### 4. Multiple `zip` Definitions Are Scattered and Inconsistent

**Location:**

- `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt` lines 530–536 (2 zip overloads)
- `src/commonMain/kotlin/borg/trikeshed/lib/Series2.kt` line 6 (`join(a, b)`)
- `src/commonMain/kotlin/borg/trikeshed/cursor/SimpleCursor.kt` line 17 (inline zip)
- `src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt` line 22 (`Series<Any?>.joins(meta)`)

**Problem:** `join` and `zip` are synonyms in this codebase. `Series2<A, B>` is defined as `Series<Join<A, B>>` but
there's also `Series<Any?>.joins(meta)` which is the same shape.

**Unification:** Rename `joins` to use the existing `j` grammar:

```kotlin
// Instead of: values.joins(meta) — rename to:
infix fun <T> Series<T>.j(meta: Series<()`ColumnMeta↻`>): RowVec = this.zip(meta)
```

This makes `j` the universal constructor:

- `a j b` → `Join<A, B>`
- `values j meta` → `RowVec` (values + column metadata)

**Impact:** Consistent naming. `j` becomes the single binary composition operator for all major types.

---

### 5. `Series.div(Int)` and `Cursor.groupBy` Share Partitioning Algebra

**Location:**

- `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt` line 204 (`Series<T>.div(d: Int)`)
- `src/commonMain/kotlin/borg/trikeshed/cursor/GroupBy.kt` line 31 (`Cursor.groupBy`)

**Problem:** Both split a Series/Cursor into parts:

- `Series.div(d)` — splits into `d` equal-sized sub-Series
- `Cursor.groupBy(*axis)` — splits into groups by key column values

Both use: `N j { i -> subRange(i) }` constructor pattern internally.

**Unification:** Consider a shared `Partition` algebra:

```kotlin
// Generic partitioning that works on any Series
fun <T> Series<T>.partitionBySize(parts: Int): Series<Series<T>> = div(parts)
fun <T> Series<T>.partitionBy(predicate: (T) -> Boolean): Twin<Series<T>>  // already exists as filter/drop?
```

The key insight: `div(Int)` already IS the partitioning algebra — `groupBy` could use it internally for the slab
construction phase.

**Impact:** Reduced code duplication in GroupBy. One fewer specialized partition function.

---

### 6. `fold` / `runningfold` vs `RowReducer` Are the Same Pattern

**Location:**

- `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt` line 165 (`Series.fold`, `runningfold`)
- `src/commonMain/kotlin/borg/trikeshed/cursor/GroupBy.kt` line 5 (`RowReducer = (Any?, Any?) -> Any?`)

**Problem:** `Series.fold` and `Series.runningfold` are generic and type-safe. `RowReducer` is `Any?`-based because
GroupBy operates on untyped rows. But the semantics are identical: reduce a sequence of values into one.

**Unification:** Make GroupBy use a typed fold internally, or extract a common reducer interface:

```kotlin
interface Reducer<T, R> {
    val zero: R
    fun combine(acc: R, element: T): R
}

// Series.fold becomes:
fun <T, R> Series<T>.fold(zero: R, r: Reducer<T, R>): R = ...
```

This is a larger refactor — only pursue if typed cursors become a priority.

**Impact:** Type-safe reducers throughout. Eliminates `Any?` casts in GroupBy.

---

## Priority 3 — Nice to Have

### 7. `toSeries()` / `toArray()` Overloads — Use Generic With Reified Type

**Location:** `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt` lines 325–343

11 near-identical `toSeries()` overloads for `List`, `BooleanArray`, `ByteArray`, `ShortArray`, `IntArray`, `LongArray`,
`FloatArray`, `DoubleArray`, `CharArray`, `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`.

**Unification:** Use `toArray()` pattern (line 78–90) which already has a single reified generic:

```kotlin
inline fun <reified T> Series<T>.toArray(): Array<T> = Array(size, ::get)
```

For `toSeries()`, Kotlin's stdlib already provides `asList()` / `toList()` on arrays. These overloads could be replaced
with a single `Iterable<T>.toSeries()` (which already exists at line 325).

**Impact:** 10+ lines removed. Stdlib already handles this.

---

### 8. `ObjectStoreRowVec` vs `BlobRowVec` — Both Are Zero-Length Shells

**Location:** `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/RowVecFamilies.kt`

Both `BlobRowVec` (line 65) and `ObjectStoreRowVec` (line 144) have:

- `override val size: Int get() = 0`
- `override fun get(index: Int) = throw IndexOutOfBoundsException`

**Observation:** This is not necessarily duplication — they serve different purposes (opaque blob vs cloud metadata
shell). But they could share a `ShellRowVec` base if more shell types emerge.

---

## Implemented (April 25, 2026)

- **U4** ✅ `j` as universal constructor: `Series<T>.j(meta)` replaces `joins(meta)` — Cursor.kt + 3 call sites updated
- **U3** ✅ `Cursor.α(xform)` + `RowVec.values` — Cursor.kt, single-step projection now available
- **U1** ✅ `ObjectStoreRowVec.companion` factory methods (`.gcs()`, `.s3()`, `.alibaba()`) — adapters updated to use
  factory; subclasses preserved as thin codec-compatible wrappers
- **U2** ✅ `LazyChildRowVec` abstract base class — `loadChild` helper shared across ViewRowVec, BlobRowVec, JsonRowVec, YamlRowVec; added to Predicate.kt dispatch
- **U5** ✅ `Series.div(Int)` remainder fix — remainder elements now go to last partition; `5 div 2` produces `[2, 3]` not `[2, 2]`
- **U6** ✅ `Reducer<T, R>` interface — placed in `lib/Series.kt` (shared across modules); `Series.fold(Reducer)` overload; `RowReducer` moved to `lib` (was in GroupBy.kt); `Reducer<in T, R>` with `in` variance for contravariance
- **U7** ✅ `toSeries()` deduplication — removed 9 dead overloads (BooleanArray, ShortArray, LongArray, FloatArray, CharArray, UByteArray, UShortArray, ULongArray, closedRange variant); kept List, ByteArray, IntArray, UIntArray, DoubleArray, String, CharSequence, Sequence, ClosedRange<Int>

## Implementation Order

| # | Unification                                     | Risk     | Est. Lines         | Status  |
|---|-------------------------------------------------|----------|--------------------|---------|
| 1 | ObjectStoreRowVec factory (remove 3 subclasses) | Low      | -45                | ✅ Done  |
| 2 | Lazy child base class                           | Medium   | -20, +shared infra | ✅ Done  |
| 3 | `α` on Cursor                                   | Low      | +10                | ✅ Done  |
| 4 | `j` as universal constructor (rename `joins`)   | Medium   | -5, +clarity       | ✅ Done  |
| 5 | `div` / `groupBy` shared algebra               | Medium   | -15                | ✅ Done  |
| 6 | Typed reducers                                  | High     | larger refactor    | ✅ Done  |
| 7 | `toSeries()` dedup                              | Low      | -10                | ✅ Done  |
| 8 | ShellRowVec base                                | Very Low | future-proofing    | Pending |

**Recommended start:** Unifications 1 → 3 → 4 → 2 → 5

Unification 1 (ObjectStoreRowVec factory) is a pure refactor with zero behavior change — safest entry point. Unification
3 (`α` on Cursor) is additive and low-risk. Unification 4 (rename `joins`) requires updating all call sites in Cursor.kt
and SimpleCursor.kt.

These types carry zero side effects; they are referentially transparent throughout.

Join<A, B> — Join.kt:
11-44                                                                                                                                                                                                         
- Pure interface: two vals a: A, b:
B                                                                                                                                                                                              
- Three factory methods (invoke) all
pure                                                                                                                                                                                          
- j infix constructor — Join.kt:
60                                                                                                                                                                                                 
- Twin<T> = Join<T, T> —
pure                                                                                                                                                                                                      
- Property projections first, second — pure

Series<T> = Join<Int, (Int) -> T> — Series.kt:
16                                                                                                                                                                                   
- Pure lazy-indexed sequence; size j ::get
pattern                                                                                                                                                                                 
- α (alpha transform) — Series.kt:32 — pure projection, no
mutation                                                                                                                                                                
- div(Int) partitioning — Series.kt:204 — pure construction of sub-series
views                                                                                                                                                    
- fold / runningfold — Series.kt:165,173 — pure folds over pure
sequences                                                                                                                                                          
- take / drop / get(IntRange) / get(IntArray) / get(Series<Int>) — all pure
slicing                                                                                                                                                
- reversed() — Series.kt:366 — pure
reindexing                                                                                                                                                                                     
- zip / zipWithNext — Series.kt:530-541 — pure pairwise
construction                                                                                                                                                               
- Iterator over Series — Series.kt:239 — no side
effects                                                                                                                                                                           
- toList() / toArray() / toSet() — Series.kt:69,90,233 — pure conversions

Series2<A, B> = Series<Join<A, B>> — Series2.kt:
3                                                                                                                                                                                  
- Pure type alias, no additional
implementation                                                                                                                                                                                    
THE INTERFACE CONTRACT (structurally pure, semantically deferred)

     These abstract types/aliases define the shape; their purity depends entirely on their concrete children.                                                                                                                           
        
        MiniRowVec — RowVecFamilies.kt (base class, not shown fully but
        implied)                                                                                                                                                           
        - Abstract: size: Int, get(Int): Any?, child:
        Series<MiniRowVec>?                                                                                                                                                                  
          - Contract is pure shape; effect lives in implementations
        
        Cursor = Series<RowVec> — Cursor.kt:
        19                                                                                                                                                                                             
        - Structurally pure Series; effect lives in the concrete RowVec
        elements                                                                                                                                                           
          - row(Int) / at(Int) — Cursor.kt:169-170 — pure
          indexing                                                                                                                                                                           
          - meta — Cursor.kt:77 — pure extraction of column
          metadata                                                                                                                                                                         
          - Cursor.α(xform) — Cursor.kt:43 — pure cursor-level
          transform                                                                                                                                                                     
          - Cursor.get(IntRange/Int/String) — pure column projection
        
        RowVec = Series2<Any?, ()`ColumnMeta↻`> — Cursor.kt:
        15                                                                                                                                                                            
        - values — Cursor.kt:40 — pure: this α {
        it.a }                                                                                                                                                                                    
          - All Series2 projections (left/right) —
          pure                                                                                                                                                                                      
          THE IMPURE / EFFECT-BEARING
          IMPLEMENTATIONS                                                                                                                                                                                        
          Shell types with deferred effect (lazy I/O)
        
        ViewRowVec — RowVecFamilies.kt:31-57

    var loadedChild: Series<MiniRowVec>? = null   ← MUTABLE CACHE                                                                                                                                                              
     docLoader: (() -> MiniRowVec)?                       ← EFFECT (I/O function)                                                                                                                                                       
                                                                                                                                                                                                                                        
     - First access to child calls docLoader() — effectful I/O                                                                                                                                                                          
     - Subsequent accesses return cached loadedChild — mutates the var                                                                                                                                                                  
                                                                                                                                                                                                                                        
     BlobRowVec — RowVecFamilies.kt:65-75                                                                                                                                                                                               
                                                                                                                                                                                                                                        
     childFactory: ((ByteArray) -> Series<MiniRowVec>)?    ← EFFECT (byte decode)                                                                                                                                                       
     child.get() → childFactory?.invoke(bytes)            ← impure                                                                                                                                                                      
                                                                                                                                                                                                                                        
     JsonRowVec — RowVecFamilies.kt:85-97                                                                                                                                                                                               
                                                                                                                                                                                                                                        
     childFactory: (() -> Series<MiniRowVec>)?            ← EFFECT (JSON parse)                                                                                                                                                         
                                                                                                                                                                                                                                        
     YamlRowVec — RowVecFamilies.kt:105-117                                                                                                                                                                                             
                                                                                                                                                                                                                                        
     childFactory: (() -> Series<MiniRowVec>)?            ← EFFECT (YAML parse)                                                                                                                                                         
                                                                                                                                                                                                                                        
     ObjectStoreRowVec (and GcsRowVec/S3RowVec/AlibabaRowVec) — RowVecFamilies.kt:144-245                                                                                                                                               
                                                                                                                                                                                                                                        
     blob: Series<MiniRowVec>?                            ← DEFERRED BLOB (effect)                                                                                                                                                      
     provider: ObjectStoreProvider                        ← identity tag                                                                                                                                                                
     size = 0 (shell)                                    ← all meaning in blob                                                                                                                                                          
                                                                                                                                                                                                                                        
     - child getter returns blob — the actual cloud-fetched bytes live here                                                                                                                                                             
     - The factory methods (ObjectStoreRowVec.gcs/s3/alibaba) are constructors only, but the blob they receive is already an effect                                                                                                     
     Aggregation with internal mutable state                                                                                                                                                                                            
                                                                                                                                                                                                                                        
     IntHeap — Series.kt:269-323                                                                                                                                                                                                        
                                                                                                                                                                                                                                        
    var heap: IntArray = IntArray(series.size)   ← MUTABLE                                                                                                                                                                     
    var size: Int                               ← MUTABLE COUNTER                                                                                                                                                              
     add() / remove()                                     ← MUTATES internal state                                                                                                                                                      
                                                                                                                                                                                                                                        
     - NOT referentially transparent                                                                                                                                                                                                    
                                                                                                                                                                                                                                        
     GroupBy — GroupBy.kt:5-59                                                                                                                                                                                                          
                                                                                                                                                                                                                                        
     clusters: LinkedMap<List<Any?>, IntAccumulator>()    ← MUTABLE ACCUMULATOR                                                                                                                                                         
     RowReducer = (Any?, Any?) -> Any?                   ← EFFECT (reducer is opaque fn)                                                                                                                                                
     buildGroups()                                        ← mutates clusters                                                                                                                                                            

# THE VENN DIAGRAM (textual)

```txt
         ┌─────────────────────────────────────────────────────────────────┐                                                                                                                                                            
         │                      IMPURE / EFFECTS                           │                                                                                                                                                            
         │  ┌───────────────────┐  ┌─────────────────┐  ┌───────────────┐ │                                                                                                                                                             
         │  │  ViewRowVec      │  │  BlobRowVec     │  │ IntHeap       │ │                                                                                                                                                              
         │  │  • mutable cache  │  │  • byte decode  │  │ • mut heap[]  │ │                                                                                                                                                             
         │  │  • docLoader I/O  │  │    (effect fn)  │  │ • mut size    │ │                                                                                                                                                             
         │  └───────────────────┘  ├─────────────────┤  └───────────────┘ │                                                                                                                                                             
         │  ┌───────────────────┐  │  JsonRowVec    │  ┌───────────────┐ │                                                                                                                                                              
         │  │  ObjectStoreRowVec│  │  YamlRowVec    │  │  GroupBy      │ │                                                                                                                                                              
         │  │  (Gcs/S3/Alibaba) │  │  • parse effect│  │  • LinkedMap  │ │                                                                                                                                                              
         │  │  • blob = effect  │  └─────────────────┘  │  • RowReducer │ │                                                                                                                                                             
         │  └───────────────────┘                       └───────────────┘ │                                                                                                                                                             
         │                    │              │                             │                                                                                                                                                            
         │  ┌──────────────────┼──────────────┼──────────────────────────┐│                                                                                                                                                             
         │  │   MINIROWVEC     │  DocRowVec   │   IMPURE: external child  ││                                                                                                                                                            
         │  │   (abstract)     │  ──────────  │   loading possible        ││                                                                                                                                                            
         │  │                  │  pure if     │                            ││                                                                                                                                                           
         │  │                  │  child==null  │                            ││                                                                                                                                                          
         │  └──────────────────┴──────────────┴────────────────────────────┘│                                                                                                                                                           
         │  ┌──────────────────────────────────────────────────────────────┐ │                                                                                                                                                          
         │  │                     SERIES<T>                               │ │                                                                                                                                                           
         │  │   Pure: α, div, fold, zip, take, drop, reversed, slice... │ │                                                                                                                                                             
         │  │   Pure: get(Int), get(IntRange), get(IntArray), get(Series)│ │                                                                                                                                                            
         │  │   Pure: toList, toArray, toSet                             │ │                                                                                                                                                            
         │  └──────────────────────────────────────────────────────────────┘ │                                                                                                                                                          
         │  ┌──────────────────────────────────────────────────────────────┐ │                                                                                                                                                          
         │  │   SERIES2<A,B>  =  Series<Join<A,B>>                         │ │                                                                                                                                                          
         │  │   Pure type alias — left/right projections are pure         │ │                                                                                                                                                           
         │  └──────────────────────────────────────────────────────────────┘ │                                                                                                                                                          
         │  ┌──────────────────────────────────────────────────────────────┐ │                                                                                                                                                          
         │  │   JOIN<A,B>  —  Pure interface + 3 factory methods            │ │                                                                                                                                                         
         │  │   j infix constructor — pure                                  │ │                                                                                                                                                         
         │  │   Twin<T> = Join<T,T> — pure                                 │ │                                                                                                                                                          
         │  └──────────────────────────────────────────────────────────────┘ │                                                                                                                                                          
         │                         PURE / IMMUTABLE                         │                                                                                                                                                           
         └─────────────────────────────────────────────────────────────────┘                                                                                                                                                            
 ```
                                                                                                                                                                                                                    
# SUMMARY TABLE                                                                                                                                                                                                                      
                                                                                                                                                                                                                
| Type | Location | Purity | Reason |                                                                                                                                                                                              
|------|----------|--------|--------|                                                                                                                                                                                              
| Join<A,B> | Join.kt:11 | PURE | Two vals, no state, no I/O |                                                                                                                                                                     
| j infix | Join.kt:60 | PURE | Factory call only |                                                                                                                                                                                
| Series<T> | Series.kt:16 | PURE | Lazy index function, no mutation |                                                                                                                                                             
| Series2<A,B> | Series2.kt:3 | PURE | Type alias of pure Series |                                                                                                                                                                 
| Series.α | Series.kt:32 | PURE | Lazy projection, no mutation |                                                                                                                                                                  
| Series.div(Int) | Series.kt:204 | PURE | View construction, no mutation |                                                                                                                                                        
| Series.fold | Series.kt:165 | PURE | Delegates to Iterable fold |                                                                                                                                                                
| Cursor = Series<RowVec> | Cursor.kt:19 | CONDITIONAL | Purity depends on RowVec elements |                                                                                                                                       
| Cursor.α | Cursor.kt:43 | CONDITIONAL | Same |                                                                                                                                                                                   
| RowVec.values | Cursor.kt:40 | PURE | Alpha extraction only |                                                                                                                                                                    
| DocRowVec | RowVecFamilies.kt:12 | CONDITIONAL | Pure if child==null, impure if child is effect |                                                                                                                                
| ViewRowVec | RowVecFamilies.kt:31 | IMPURE | Mutable cache + docLoader I/O |                                                                                                                                                     
| BlobRowVec | RowVecFamilies.kt:65 | IMPURE | childFactory is effect |                                                                                                                                                            
| JsonRowVec | RowVecFamilies.kt:85 | IMPURE | childFactory is effect |                                                                                                                                                            
| YamlRowVec | RowVecFamilies.kt:105 | IMPURE | childFactory is effect |                                                                                                                                                           
| ObjectStoreRowVec | RowVecFamilies.kt:144 | IMPURE | blob carries deferred cloud I/O |                                                                                                                                           
| Gcs/S3/AlibabaRowVec | RowVecFamilies.kt:203-245 | IMPURE | Subclasses of impure parent |                                                                                                                                        
| IntHeap | Series.kt:269 | IMPURE | Internal mutable array |                                                                                                                                                                      
| GroupBy.buildGroups | GroupBy.kt:15 | IMPURE | Mutable LinkedMap accumulation |                                                                                                                                                  
| RowReducer | GroupBy.kt:5 | IMPURE | Opaque function type, not referentially transparent |                


# KEY OBSERVATIONS FOR UNIFICATION                                                                                                                                                                                                   
                                                                                                                                                                                                                                        
   1. Shell types are uniformly impure — BlobRowVec, JsonRowVec, YamlRowVec, and all ObjectStoreRowVec variants all carry deferred effects via factory functions. The child: Series<MiniRowVec>? getter is the effect boundary in     
    every case.                                                                                                                                                                                                                        
                                                                                                                                                                                                                                    
   2. Lazy child loading is a common pattern but not unified — ViewRowVec uses a double-checked mutable cache; Blob/Json/Yaml use a no-caching factory invoke. These could share a LazyChildLoader helper as the UNIFICATION.md       
   proposes.                                                                                                                                                                                                                          
                                                                                                                                                                                                                                    
   3. Series and Join are 100% pure — all the algebraic operations (α, j, div, zip, fold) live in the pure zone. The impurity is confined to the concrete RowVec families at the leaves.                                              
                                                                                                                                                                                                                                    
   4. Cursor inherits the impurity of its RowVecs — Cursor.α is structurally pure but semantically impure when applied to rows that carry effects. This is unavoidable given the semantic center (block algebra with deferred         
   child loading).                                                                                                                                                                                                                    
                                                                                                                                                                                                                                    
   5. ObjectStoreRowVec factory methods (U1 ✅) are correctly placed — the factory constructors themselves are pure (they just call constructors), but the blob: Series<MiniRowVec>? they receive from the adapters is the actual     
   effect.                                                                                                                                                                                                                            

```
