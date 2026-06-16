# TrikeShed Analytical Architecture
# Confix as Cursor Gateway: DSL Factory Builders
# Grounded in PRELOAD.md contract

> Revision: June 2026

---

## 0. Enum Projection Macros — the DSL vocabulary layer

### 0.1 Enum → Series<E>

PRELOAD: `Series<T> = Join<Int, (Int) -> T>`. Every Kotlin enum exposes
`.entries: EnumEntries<E>` (Kotlin 1.9+), which is `List`-backed and index-
addressable. Lift it into algebra once:

```kotlin
// src/commonMain/.../analytical/EnumSeries.kt

/** Lift any enum's .entries into Series<E>. */
@Suppress("BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER")
inline fun <reified E : Enum<E>> enumSeries(): Series<E> {
    val entries = enumEntries<E>()
    return entries.size j { i: Int -> entries[i] }
}

/** Name-keyed lookup — returns null for unknown names. */
inline fun <reified E : Enum<E>> Series<E>.byName(): (String) -> E? {
    val map = view.associateBy { it.name }
    return { name: String -> map[name] }
}

/** α-projection: enum entries as column metadata. */
inline fun <reified E> Series<E>.asColumnMeta(
    crossinline nameOf:  (E) -> String,
    crossinline typeOf:  (E) -> IOMemento,
): Series<ColumnMeta> = this α { e -> ColumnMeta(nameOf(e), typeOf(e)) }
```

### 0.2 Dispatch tables replace when-blocks

All `when (enumValue) { A -> ...; B -> ... }` patterns become a
`Series<Join<E, Factory>>` built from `E.entries`:

```kotlin
// Pattern:  enumSeries<E>() α { e -> e j factory(e) }
// Lookup:   table.view.firstOrNull { it.a == key }?.b ?: error(...)
```

PRELOAD: "explicit algebra over opaque helpers." A dispatch table is a
Series — inspectable, projectable, composable. A when-block is not.

### 0.3 IOMemento as column-type vocabulary

```kotlin
val IO_SERIES: Series<IOMemento>      = enumSeries<IOMemento>()
val IO_BY_NAME: (String) -> IOMemento? = IO_SERIES.byName()

// DSL column() can accept either the enum directly or its name:
fun CursorSourceBuilder.column(name: String, typeName: String, child: ColumnMeta? = null) =
    column(name, IO_BY_NAME(typeName) ?: error("Unknown IOMemento: $typeName"), child)
```

### 0.4 AggFn dispatch table

```kotlin
enum class AggFn { SUM, AVG, MIN, MAX, COUNT }

typealias AggReducer = (Series<Any?>) -> Any?

/** Each AggFn entry paired with its reducer — built once, shared. */
val AGG_TABLE: Series<Join<AggFn, AggReducer>> = enumSeries<AggFn>() α { fn ->
    fn j when (fn) {
        AggFn.SUM   -> AggReducer { vals ->
            var s = 0.0; for (i in 0 until vals.size) s += (vals[i] as? Number)?.toDouble() ?: 0.0; s
        }
        AggFn.AVG   -> AggReducer { vals ->
            var s = 0.0; for (i in 0 until vals.size) s += (vals[i] as? Number)?.toDouble() ?: 0.0
            s / vals.size
        }
        AggFn.COUNT -> AggReducer { vals -> vals.size.toLong() }
        AggFn.MIN   -> AggReducer { vals ->
            @Suppress("UNCHECKED_CAST")
            (0 until vals.size).mapNotNull { vals[it] as? Comparable<Any> }.minOrNull()
        }
        AggFn.MAX   -> AggReducer { vals ->
            @Suppress("UNCHECKED_CAST")
            (0 until vals.size).mapNotNull { vals[it] as? Comparable<Any> }.maxOrNull()
        }
    }
}

fun AggFn.reducer(): AggReducer =
    AGG_TABLE.view.first { it.a == this }.b
```

### 0.5 ConfixIndexK dispatch table

```kotlin
/** Registry of all supported ConfixIndexK → index factory pairs. */
typealias FacetFactory = (wal: TypedWal?, collection: String) -> ConfixFacetIndex

val FACET_TABLE: Series<Join<ConfixIndexK<*>, FacetFactory>> = s_[
    ConfixIndexK.KeyToChild j { wal, col -> ConfixKeyIndex(wal, col)   },
    ConfixIndexK.Tags       j { wal, col -> ConfixTagIndex(wal, col)   },
    ConfixIndexK.Depths     j { wal, col -> ConfixDepthIndex(wal, col) },
    ConfixIndexK.Spans      j { wal, col -> ConfixSpanIndex(wal, col)  },
]

fun ConfixIndexK<*>.makeIndex(wal: TypedWal? = null, collection: String = "default"): ConfixFacetIndex =
    FACET_TABLE.view.firstOrNull { it.a == this }?.b?.invoke(wal, collection)
        ?: error("No ConfixFacetIndex factory for $this")
```

### 0.6 SlabFacetFlag projection macro (libs/classfile)

```kotlin
// SlabFacetFlag.entries as Series — already has BitMasked<Long>
val SLAB_FLAG_SERIES: Series<SlabFacetFlag> = enumSeries<SlabFacetFlag>()

/** Build a facet mask from a vararg of flag names. */
fun slabMask(vararg names: String): SlabFacet {
    val byName = SLAB_FLAG_SERIES.byName()
    return names.fold(SlabFacet(0L)) { acc, n ->
        acc or (byName(n) ?: error("Unknown SlabFacetFlag: $n")).facet
    }
}

// Usage in DSL:
//   facets = slabMask("HOT", "COMPUTED", "INDEXED")
```

---

## 0B. Contract: Everything Collapses to Join

PRELOAD.md is the law.

  Join<A,B>           base binary composition
  Twin<T>             Join<T,T>
  Series<T>           Join<Int, (Int)->T>
  j                   infix constructor
  α                   lazy projection over Series
  ↺                   left-identity / constant anchor
  RowVec              Series2<Any?, () -> ColumnMeta>
  Cursor              Series<RowVec>

Design bias from PRELOAD:
  - composition over inheritance
  - ranges and projections over mutable loops
  - explicit algebra over opaque helpers
  - lazy views first; materialization later
  - typealiases compress semantics, not substance

Every DSL builder in this document:
  1. Takes a receiver lambda
  2. Accumulates Join-shaped configuration
  3. Emits a canonical algebra type (Cursor, ConfixDoc, Series<T>) from build()
  4. Never emits a wrapper — the output IS the algebra

---

## 1. @DslMarker: one shared marker, applied to all builders

```kotlin
// src/commonMain/.../analytical/AnalyticalDsl.kt

@DslMarker
annotation class AnalyticalDsl
```

All builder classes below carry @AnalyticalDsl. This prevents lambda nesting
bugs (a where {} inside a map {} is a compile error, not a runtime surprise).

---

## 2. Wall 5 (first — it unlocks everything): Cursor.where()

PRELOAD rule: "lazy views first; materialization later."

The missing combinator in CursorOps.kt:

```kotlin
// CursorOps.kt — add these three functions

/** Lazy row filter. Matching indices computed once on first access. */
fun Cursor.where(pred: (RowVec) -> Boolean): Cursor {
    val hits by lazy {
        val a = mutableListOf<Int>()
        for (i in 0 until size) if (pred(this[i])) a.add(i)
        a
    }
    return object : Cursor {
        override val a get() = hits.size
        override val b = { i: Int -> this@where[hits[i]] }
    }
}

/** Column-named predicate sugar. */
fun Cursor.whereColumn(name: CharSequence, pred: (Any?) -> Boolean): Cursor =
    where { row ->
        val idx = (0 until row.size).firstOrNull { row[it].b().name == name } ?: return@where false
        pred(row[idx].a)
    }

/** Bounded prefix: first n rows. Pure — returns a sub-Series. */
fun Cursor.limit(n: Int): Cursor = minOf(size, n) j { i: Int -> this[i] }

/** Skip first n rows. Pure. */
fun Cursor.offset(n: Int): Cursor = maxOf(0, size - n) j { i: Int -> this[n + i] }
```

These are the only pure functional additions needed. All query DSLs below
compose on top of them.

---

## 3. Wall 1: CursorSource — the SPI between storage and analysis

### 3.1 Interface

```kotlin
// src/commonMain/.../cursor/CursorSource.kt

/** Bounded scan hint — pushed down to backends that support it. */
typealias KeyRange<K> = Join<K?, K?>          // lo j hi; null = open bound

val <K> KeyRange<K>.lo: K? get() = a
val <K> KeyRange<K>.hi: K? get() = b

fun <K> from(lo: K): KeyRange<K> = lo j null
fun <K> upTo(hi: K): KeyRange<K> = null j hi
fun <K> between(lo: K, hi: K): KeyRange<K> = lo j hi

/** CursorSource = name j schema j open-function */
typealias CursorSource = Join<
    CharSequence,                         // name
    Join<
        Series<ColumnMeta>,               // schema
        suspend (KeyRange<*>?) -> Cursor  // open
    >
>

val CursorSource.sourceName: CharSequence         get() = a
val CursorSource.schema: Series<ColumnMeta>       get() = b.a
val CursorSource.openFn: suspend (KeyRange<*>?) -> Cursor get() = b.b
suspend fun CursorSource.open(range: KeyRange<*>? = null): Cursor = openFn(range)
```

### 3.2 CursorSourceBuilder — enum-projected column specs

```kotlin
@AnalyticalDsl
class CursorSourceBuilder(private val name: CharSequence) {

    private val cols = mutableListOf<ColumnMeta>()
    private var openFn: (suspend (KeyRange<*>?) -> Cursor)? = null

    // Accept IOMemento directly
    fun column(name: String, type: IOMemento, child: ColumnMeta? = null) {
        cols.add(ColumnMeta(name, type, child))
    }

    // Accept enum name string — resolved via IO_BY_NAME dispatch table
    fun column(name: String, typeName: String, child: ColumnMeta? = null) =
        column(name, IO_BY_NAME(typeName) ?: error("Unknown IOMemento: $typeName"), child)

    // Bulk-define columns from any enum whose entries name the columns:
    //   columnEnum<MyColumns> { e -> e.name to e.ioType }
    inline fun <reified E : Enum<E>> columnEnum(
        crossinline spec: (E) -> Pair<String, IOMemento>,
    ) {
        enumSeries<E>().view.forEach { e ->
            val (n, t) = spec(e)
            column(n, t)
        }
    }

    fun from(block: suspend (KeyRange<*>?) -> Cursor) { openFn = block }

    fun build(): CursorSource {
        val schema = cols.size j { i: Int -> cols[i] }
        val fn     = openFn ?: error("CursorSourceBuilder '$name': no from() supplied")
        return name j (schema j fn)
    }
}
```
fun cursorSource(name: String, block: CursorSourceBuilder.() -> Unit): CursorSource =
    CursorSourceBuilder(name).apply(block).build()

### 3.3 Usage

```kotlin
// Standard — enum literals
val usersSource: CursorSource = cursorSource("users") {
    column("id",   IOMemento.IoLong)
    column("name", IOMemento.IoString)
    column("age",  IOMemento.IoInt)
    from { _ -> inMemoryUsersCursor() }
}

// String names — resolved via IO_BY_NAME dispatch table
val logsSource: CursorSource = cursorSource("logs") {
    column("ts",  "IoLong")
    column("msg", "IoString")
    column("lvl", "IoInt")
    from { _ -> logCursor() }
}

// Enum-projected schema — define column shape in your own enum
enum class OrderCol(val ioType: IOMemento) {
    id(IOMemento.IoLong), user_id(IOMemento.IoLong),
    amount(IOMemento.IoDouble), region(IOMemento.IoString)
}

val ordersSource: CursorSource = cursorSource("orders") {
    columnEnum<OrderCol> { e -> e.name to e.ioType }   // α over OrderCol.entries
    from { range -> db.scanAsCursor("orders", schema, range) }
}
```

---

## 4. Wall 5 continued: CursorQuery DSL

All predicates compose lazily via Cursor.where() from §2.

```kotlin
@AnalyticalDsl
class CursorQueryBuilder(private val source: CursorSource) {

    private val preds    = mutableListOf<(RowVec) -> Boolean>()
    private var cols     = emptyArray<String>()
    private var limitN: Int? = null
    private var offsetN  = 0
    private var range: KeyRange<*>? = null

    // ── predicates ───────────────────────────────────────────────────
    fun where(pred: (RowVec) -> Boolean) { preds.add(pred) }

    fun eq(col: String, v: Any?)    = where { row -> row.cell(col) == v }
    fun ne(col: String, v: Any?)    = where { row -> row.cell(col) != v }
    fun gt(col: String, v: Number)  = where { row -> (row.cell(col) as? Number)?.toDouble()?.let { it > v.toDouble() } == true }
    fun lt(col: String, v: Number)  = where { row -> (row.cell(col) as? Number)?.toDouble()?.let { it < v.toDouble() } == true }
    fun like(col: String, pfx: String) = where { row -> row.cell(col)?.toString()?.startsWith(pfx) == true }

    // ── projection ────────────────────────────────────────────────────
    fun select(vararg names: String) { cols = names.toTypedArray() }

    // ── pagination ────────────────────────────────────────────────────
    fun limit(n: Int)  { limitN = n }
    fun offset(n: Int) { offsetN = n }
    fun range(r: KeyRange<*>) { range = r }

    // ── terminal ─────────────────────────────────────────────────────
    suspend fun execute(): Cursor {
        var c: Cursor = source.open(range)
        preds.forEach { p -> c = c.where(p) }
        if (cols.isNotEmpty()) c = c.select(*cols)
        if (offsetN > 0) c = c.offset(offsetN)
        limitN?.let  { c = c.limit(it) }
        return c
    }
}

// Helper: cell value by column name
private fun RowVec.cell(name: String): Any? {
    for (i in 0 until size) if (this[i].b().name == name) return this[i].a
    return null
}

// Extension entry point
suspend fun CursorSource.query(block: CursorQueryBuilder.() -> Unit): Cursor =
    CursorQueryBuilder(this).apply(block).execute()
```

### Usage

```kotlin
val result: Cursor = usersSource.query {
    gt("age", 30)
    select("name", "age")
    limit(50)
}

val premiumOrders: Cursor = ordersSource.query {
    gt("amount", 1000.0)
    eq("status", "complete")
    range(from("ord:2024"))
    select("id", "user_id", "amount")
}
```

---

## 5. Wall 2: TypedWal DSL

WAL entries carry RowVec payloads — no String serialization loss.

```kotlin
// src/commonMain/.../analytical/TypedWal.kt

/** A WAL op is a Join of (collection j key) and (seq j value-or-tombstone). */
typealias WalKey = Join<String, ByteArray>           // collection j raw key
typealias WalValue = Join<Long, RowVec?>             // seq j row (null = tombstone)
typealias TypedWalOp = Join<WalKey, WalValue>

val TypedWalOp.collection: String  get() = a.a
val TypedWalOp.rawKey: ByteArray   get() = a.b
val TypedWalOp.seq: Long           get() = b.a
val TypedWalOp.row: RowVec?        get() = b.b          // null = delete tombstone
val TypedWalOp.isTombstone: Boolean get() = b.b == null

fun walPut(collection: String, key: ByteArray, seq: Long, row: RowVec): TypedWalOp =
    (collection j key) j (seq j row)

fun walDelete(collection: String, key: ByteArray, seq: Long): TypedWalOp =
    (collection j key) j (seq j null)

/** TypedWal = headSeq j (append / readFrom / replayTo / compact) operations */
interface TypedWal {
    val headSequence: Long
    fun append(op: TypedWalOp): Long
    fun readFrom(fromSeq: Long): Series<TypedWalOp>
    fun replayTo(sink: (TypedWalOp) -> Unit, toSeq: Long)
    fun compact(keepFrom: Long)
}
```

### DSL Builder

```kotlin
@AnalyticalDsl
class TypedWalBuilder {
    private var capacity: Int  = 4096
    private var keepLast: Int? = null
    private var durablePath: String? = null

    fun capacity(n: Int)           { capacity = n }
    fun compactKeepLast(n: Int)    { keepLast = n }
    fun durable(path: String)      { durablePath = path }

    fun build(): TypedWal = InMemoryTypedWal(capacity, keepLast)
    // swap for FileTypedWal(durablePath!!) when durablePath != null
}

fun typedWal(block: TypedWalBuilder.() -> Unit = {}): TypedWal =
    TypedWalBuilder().apply(block).build()
```

### Usage

```kotlin
val wal = typedWal()

val wal = typedWal {
    capacity(16384)
    compactKeepLast(100_000)
    durable("/var/lib/trikeshed/wal")
}

val seq = wal.append(walPut("users", "u:001".encodeToByteArray(), wal.headSequence + 1, userRow))
```

---

## 6. Wall 3: MapReduceView DSL

```kotlin
// src/commonMain/.../analytical/MapReduceView.kt

/**
 * MapReduceView = name j (mapFn j reduceFn)
 * mapFn:    ConfixDoc  -> Series<Join<K,V>>
 * reduceFn: Join<K?, Join<Series<V>, Boolean>> -> R
 */
typealias MapFn<K,V>    = (ConfixDoc) -> Series<Join<K,V>>
typealias ReduceFn<K,V,R> = (K?, Series<V>, Boolean) -> R

typealias MapReduceView<K,V,R> = Join<
    String,
    Join<MapFn<K,V>, ReduceFn<K,V,R>>
>

val <K,V,R> MapReduceView<K,V,R>.viewName: String       get() = a
val <K,V,R> MapReduceView<K,V,R>.mapFn: MapFn<K,V>     get() = b.a
val <K,V,R> MapReduceView<K,V,R>.reduceFn: ReduceFn<K,V,R> get() = b.b

fun <K,V,R> MapReduceView<K,V,R>.map(doc: ConfixDoc): Series<Join<K,V>> = mapFn(doc)
fun <K,V,R> MapReduceView<K,V,R>.reduce(key: K?, values: Series<V>, rereduce: Boolean): R =
    reduceFn(key, values, rereduce)
```

### DSL Builder

```kotlin
@AnalyticalDsl
class MapReduceViewBuilder<K : Any, V : Any, R : Any>(private val name: String) {

    private var mapFn: MapFn<K,V>?     = null
    private var reduceFn: ReduceFn<K,V,R>? = null

    fun map(block: (ConfixDoc) -> Series<Join<K,V>>)                { mapFn    = block }
    fun reduce(block: (K?, Series<V>, Boolean) -> R)                { reduceFn = block }

    /** Convenience: emit a single pair from map. */
    fun mapOne(block: (ConfixDoc) -> Join<K,V>?) {
        mapFn = { doc -> block(doc)?.let { 1 j { _: Int -> it } } ?: (0 j { error("empty") }) }
    }

    fun build(): MapReduceView<K,V,R> {
        val m = mapFn    ?: error("MapReduceViewBuilder '$name': map not defined")
        val r = reduceFn ?: error("MapReduceViewBuilder '$name': reduce not defined")
        return name j (m j r)
    }
}

fun <K:Any, V:Any, R:Any> mapReduceView(
    name: String,
    block: MapReduceViewBuilder<K,V,R>.() -> Unit,
): MapReduceView<K,V,R> = MapReduceViewBuilder<K,V,R>(name).apply(block).build()
```

### CursorMapReduceEngine DSL

```kotlin
@AnalyticalDsl
class MapReduceEngineBuilder {

    private var source: CursorSource? = null
    private val views = mutableListOf<MapReduceView<*,*,*>>()
    private var rereduceDepth = 1

    fun source(s: CursorSource)       { source = s }
    fun view(v: MapReduceView<*,*,*>) { views.add(v) }
    fun <K:Any,V:Any,R:Any> view(name: String, block: MapReduceViewBuilder<K,V,R>.() -> Unit) {
        views.add(mapReduceView(name, block))
    }
    fun rereduceDepth(n: Int)         { rereduceDepth = n }

    suspend fun build(): CursorMapReduceEngine {
        val src = source ?: error("MapReduceEngineBuilder: source not set")
        return CursorMapReduceEngine(src, views, rereduceDepth)
    }
}

suspend fun mapReduceEngine(block: MapReduceEngineBuilder.() -> Unit): CursorMapReduceEngine =
    MapReduceEngineBuilder().apply(block).build()
```

### Usage

```kotlin
val byCat = mapReduceView<String, Double, Double>("by_category") {
    map { doc ->
        val cat   = doc.value("category") as? String ?: return@map 0 j { error("") }
        val price = doc.value("price")    as? Double ?: return@map 0 j { error("") }
        1 j { _: Int -> cat j price }
    }
    reduce { _, values, _ ->
        var sum = 0.0
        for (i in 0 until values.size) sum += values[i]
        sum
    }
}

val engine = mapReduceEngine {
    source(ordersSource)
    view(byCat)
    rereduceDepth(2)
}
val summary: Cursor = engine.execute("by_category")
```

---

## 7. Wall 4: ConfixFacetIndex DSL

Promotes ephemeral ConfixIndexK facets to durable per-collection indexes.

```kotlin
// src/commonMain/.../analytical/ConfixFacetIndex.kt

/**
 * A FacetPredicate is a Join of (facetKey j testValue).
 * Deliberately keeps the shape algebraic, not a sealed hierarchy.
 */
typealias FacetPredicate = Join<ConfixIndexK<*>, Any?>
val FacetPredicate.facetKey: ConfixIndexK<*> get() = a
val FacetPredicate.testValue: Any?           get() = b

fun exactKey(key: String): FacetPredicate           = ConfixIndexK.KeyToChild j key
fun tagFilter(tag: IOMemento): FacetPredicate       = ConfixIndexK.Tags j tag
fun depthRange(lo: Int, hi: Int): FacetPredicate    = ConfixIndexK.Depths j (lo j hi)

interface ConfixFacetIndex {
    val facetKey: ConfixIndexK<*>
    val collection: String
    fun insert(docId: String, facetValue: Any, seq: Long)
    fun query(pred: FacetPredicate): Cursor
}
```

### DSL Builder

```kotlin
@AnalyticalDsl
class ConfixFacetIndexBuilder {

    private var facetKey: ConfixIndexK<*>? = null
    private var collection: String         = "default"
    private var wal: TypedWal?             = null

    fun facet(key: ConfixIndexK<*>) { facetKey = key }
    fun collection(name: String)    { collection = name }
    fun store(w: TypedWal)          { wal = w }

    fun build(): ConfixFacetIndex {
        val fk = facetKey ?: error("ConfixFacetIndexBuilder: facet key not set")
        // dispatch table lookup — no when block
        return fk.makeIndex(wal, collection)
    }
}

@AnalyticalDsl
class ConfixIndexRegistryBuilder {
    private val indexes = mutableListOf<ConfixFacetIndex>()
    private var autoIndex = false

    fun index(block: ConfixFacetIndexBuilder.() -> Unit) {
        indexes.add(ConfixFacetIndexBuilder().apply(block).build())
    }
    fun autoIndexAll() { autoIndex = true }

    fun build(): ConfixIndexRegistry = ConfixIndexRegistry(
        indexes.size j { i: Int -> indexes[i] },
        autoIndex,
    )
}

fun confixFacetIndex(block: ConfixFacetIndexBuilder.() -> Unit): ConfixFacetIndex =
    ConfixFacetIndexBuilder().apply(block).build()

fun confixIndexRegistry(block: ConfixIndexRegistryBuilder.() -> Unit): ConfixIndexRegistry =
    ConfixIndexRegistryBuilder().apply(block).build()
```

### Usage

```kotlin
val sharedWal = typedWal { capacity(8192) }

val registry = confixIndexRegistry {
    index {
        facet(ConfixIndexK.KeyToChild)
        collection("products")
        store(sharedWal)
    }
    index {
        facet(ConfixIndexK.Tags)
        collection("products")
        store(sharedWal)
    }
    autoIndexAll()
}

// Query
val priceRows: Cursor = registry[ConfixIndexK.KeyToChild].query(exactKey("price"))
```

---

## 8. Algorithm A: LsmrDatabase Builder

Replaces the hollow `linkedMapOf` with a wired LsmrMergeTree + TypedWal.

```kotlin
@AnalyticalDsl
class LsmrDatabaseBuilder {

    private var memtableThreshold = 1024
    private var maxSegments       = 4
    private var path              = ""
    private var wal: TypedWal?    = null

    fun memtable(n: Int)                           { memtableThreshold = n }
    fun maxSegments(n: Int)                        { maxSegments = n }
    fun path(p: String)                            { path = p }
    fun wal(w: TypedWal)                           { wal = w }
    fun wal(block: TypedWalBuilder.() -> Unit)     { wal = typedWal(block) }

    fun build(): LsmrDatabase {
        val config = LsmrConfig(path, memtableThreshold, maxSegments)
        return WiredLsmrDatabase(config, wal ?: typedWal())
        // WiredLsmrDatabase owns LsmrMergeTree; every put() goes through:
        //   wal.append(walPut(...)) → mergeTree.put(key, value, seq)
        //   auto-flush L0 → L1 when l0Buffer.size >= memtableThreshold
        //   auto-merge L1 → L2 when l1Runs.size >= maxSegments
    }
}

fun lsmrDatabase(block: LsmrDatabaseBuilder.() -> Unit = {}): LsmrDatabase =
    LsmrDatabaseBuilder().apply(block).build()
```

### Usage

```kotlin
val db = lsmrDatabase {
    memtable(4096)
    maxSegments(8)
    wal {
        capacity(32768)
        compactKeepLast(200_000)
        durable("/var/lib/trikeshed/wal")
    }
}

// Pair with a CursorSource
val src = cursorSource("users") {
    column("id",   IOMemento.IoLong)
    column("name", IOMemento.IoString)
    column("age",  IOMemento.IoInt)
    from { range -> db.scanAsCursor("users", schema, range) }
}
```

`scanAsCursor` lives on `WiredLsmrDatabase`:

```kotlin
fun WiredLsmrDatabase.scanAsCursor(
    table: String,
    schema: Series<ColumnMeta>,
    range: KeyRange<*>?,
): Cursor {
    val prefix = "$table/"
    val entries = mergeTree.scan()
        .filter { it.key.startsWith(prefix) }
        .let { seq ->
            when {
                range?.lo != null -> seq.dropWhile { it.key < "$prefix${range.lo}" }
                else -> seq
            }
        }
        .let { seq ->
            when {
                range?.hi != null -> seq.takeWhile { it.key <= "$prefix${range.hi}" }
                else -> seq
            }
        }
        .map { entry -> decodeRowVec(entry.value!!, schema) }
        .toList()
    return entries.size j { i: Int -> entries[i] }
}
```

---

## 9. Algorithm D: SQL DSL Builder

All plan nodes are simple data classes. The DSL builds them; execution
walks them via `QueryPlan.execute(ctx)`.

```kotlin
// PRELOAD bias: composition over inheritance — each node is a Join or data class
sealed class QueryPlan
data class ScanNode   (val table: String)                                    : QueryPlan()
data class FilterNode (val source: QueryPlan, val pred: (RowVec) -> Boolean) : QueryPlan()
data class ProjectNode(val source: QueryPlan, val cols: List<String>)        : QueryPlan()
data class LimitNode  (val source: QueryPlan, val n: Int)                    : QueryPlan()
data class OffsetNode (val source: QueryPlan, val n: Int)                    : QueryPlan()
data class JoinNode   (val left: QueryPlan, val right: QueryPlan,
                       val lCol: String, val rCol: String)                   : QueryPlan()
data class AggNode    (val source: QueryPlan,
                       val groupBy: List<String>,
                       val aggs: List<AggSpec>)                              : QueryPlan()

data class AggSpec(val col: String, val fn: AggFn, val alias: String = col)
enum class AggFn { SUM, AVG, MIN, MAX, COUNT }

// Execution context carries table registry
typealias TableRegistry = Join<Int, (Int) -> CursorSource>   // Series<CursorSource>
typealias ExecutionContext = Join<TableRegistry, Unit>         // extensible via more j-composition

fun executionContext(sources: List<CursorSource>): ExecutionContext =
    (sources.size j { i: Int -> sources[i] }) j Unit

fun ExecutionContext.find(name: String): CursorSource? =
    a.view.firstOrNull { it.sourceName == name }

suspend fun QueryPlan.execute(ctx: ExecutionContext): Cursor = when (this) {
    is ScanNode    -> ctx.find(table)?.open() ?: error("Table '$table' not found")
    is FilterNode  -> source.execute(ctx).where(pred)
    is ProjectNode -> source.execute(ctx).select(*cols.toTypedArray())
    is LimitNode   -> source.execute(ctx).limit(n)
    is OffsetNode  -> source.execute(ctx).offset(n)
    is JoinNode    -> join(left.execute(ctx), right.execute(ctx))   // CursorOps.join
    is AggNode     -> AggEngine.execute(this, ctx)
}

// AggEngine uses AGG_TABLE dispatch — no when-block on AggFn
object AggEngine {
    suspend fun execute(node: AggNode, ctx: ExecutionContext): Cursor {
        val src = node.source.execute(ctx)
        // Group rows by groupBy columns
        val groups = mutableMapOf<List<Any?>, MutableList<RowVec>>()
        for (i in 0 until src.size) {
            val row = src[i]
            val key = node.groupBy.map { col -> row.cell(col) }
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        // For each group, apply each AggSpec via AGG_TABLE lookup
        val resultRows = groups.entries.map { (groupKey, rows) ->
            val aggResults: List<Join<Any?, () -> ColumnMeta>> = node.aggs.map { spec ->
                val reducer = spec.fn.reducer()          // AGG_TABLE dispatch
                val colVals = rows.size j { i -> rows[i].cell(spec.col) }
                val result  = reducer(colVals)
                (result as Any?) j { ColumnMeta(spec.alias, IOMemento.IoDouble) }
            }
            val keyParts: List<Join<Any?, () -> ColumnMeta>> = node.groupBy.mapIndexed { i, col ->
                groupKey[i] j { ColumnMeta(col, IOMemento.IoString) }
            }
            val allCols = keyParts + aggResults
            allCols.size j { c: Int -> allCols[c] }
        }
        return resultRows.size j { i: Int -> resultRows[i] }
    }
}
```

### SQL DSL Builder

```kotlin
@AnalyticalDsl
class SqlQueryBuilder(private val table: String) {

    private val preds    = mutableListOf<(RowVec) -> Boolean>()
    private var cols     = emptyList<String>()
    private var aggs     = emptyList<AggSpec>()
    private var groupBy  = emptyList<String>()
    private var joinWith: Pair<SqlQueryBuilder, Pair<String,String>>? = null
    private var limitN: Int? = null
    private var offsetN  = 0

    // ── where ─────────────────────────────────────────────────────
    fun where(pred: (RowVec) -> Boolean)   { preds.add(pred) }
    fun eq(col: String, v: Any?)           { preds.add { it.cell(col) == v } }
    fun gt(col: String, v: Number)         { preds.add { (it.cell(col) as? Number)?.toDouble()?.let { n -> n > v.toDouble() } == true } }
    fun lt(col: String, v: Number)         { preds.add { (it.cell(col) as? Number)?.toDouble()?.let { n -> n < v.toDouble() } == true } }

    // ── select / project ──────────────────────────────────────────
    fun select(vararg names: String)       { cols = names.toList() }

    // ── aggregation ───────────────────────────────────────────────
    fun groupBy(vararg names: String)      { groupBy = names.toList() }
    fun sum(col: String, alias: String = col) { aggs = aggs + AggSpec(col, AggFn.SUM, alias) }
    fun avg(col: String, alias: String = col) { aggs = aggs + AggSpec(col, AggFn.AVG, alias) }
    fun count(alias: String = "count")    { aggs = aggs + AggSpec("*", AggFn.COUNT, alias) }

    // ── join ──────────────────────────────────────────────────────
    fun join(rhs: SqlQueryBuilder, on: Pair<String,String>) { joinWith = rhs to on }

    // ── pagination ────────────────────────────────────────────────
    fun limit(n: Int)  { limitN = n }
    fun offset(n: Int) { offsetN = n }

    // ── build plan ────────────────────────────────────────────────
    fun plan(): QueryPlan {
        var node: QueryPlan = ScanNode(table)
        joinWith?.let { (rhs, on) -> node = JoinNode(node, rhs.plan(), on.first, on.second) }
        preds.forEach { p -> node = FilterNode(node, p) }
        if (aggs.isNotEmpty() || groupBy.isNotEmpty())
            node = AggNode(node, groupBy, aggs)
        if (cols.isNotEmpty())
            node = ProjectNode(node, cols)
        limitN?.let { node = LimitNode(node, it) }
        if (offsetN > 0) node = OffsetNode(node, offsetN)
        return node
    }

    suspend fun execute(ctx: ExecutionContext): Cursor = plan().execute(ctx)
}

fun from(table: String): SqlQueryBuilder = SqlQueryBuilder(table)
```

### Usage

```kotlin
val ctx = executionContext(listOf(usersSource, ordersSource))

// Scan + filter + project
val names: Cursor = from("users")
    .also { it.gt("age", 30) }
    .also { it.select("name", "age") }
    .also { it.limit(50) }
    .execute(ctx)

// Aggregate
val regional: Cursor = from("orders")
    .also { it.eq("status", "complete") }
    .also { it.groupBy("region") }
    .also { it.sum("amount", alias = "total") }
    .also { it.count() }
    .execute(ctx)

// Join
val enriched: Cursor = from("orders")
    .also { it.join(from("users"), on = "user_id" to "id") }
    .also { it.select("name", "amount", "region") }
    .execute(ctx)

// Plan inspection (pure, no execution)
val plan: QueryPlan = from("products")
    .also { it.lt("price", 100.0) }
    .also { it.select("sku", "price") }
    .plan()
// → ProjectNode(FilterNode(ScanNode("products")))
```

---

## 10. Algorithm E: AnalyticalStore — composing all walls

```kotlin
@AnalyticalDsl
class AnalyticalStoreBuilder {

    private var db: LsmrDatabase?                 = null
    private var wal: TypedWal?                    = null
    private val sources = mutableListOf<CursorSource>()
    private val mrViews = mutableListOf<MapReduceView<*,*,*>>()
    private val idxBuilders = mutableListOf<ConfixFacetIndexBuilder.() -> Unit>()

    fun database(block: LsmrDatabaseBuilder.() -> Unit)   { db = lsmrDatabase(block) }
    fun wal(block: TypedWalBuilder.() -> Unit)             { wal = typedWal(block) }

    fun table(name: String, block: CursorSourceBuilder.() -> Unit) {
        sources.add(cursorSource(name, block))
    }

    fun <K:Any,V:Any,R:Any> view(name: String, block: MapReduceViewBuilder<K,V,R>.() -> Unit) {
        mrViews.add(mapReduceView(name, block))
    }

    fun index(block: ConfixFacetIndexBuilder.() -> Unit) { idxBuilders.add(block) }

    suspend fun build(): AnalyticalStore {
        val d = db  ?: lsmrDatabase()
        val w = wal ?: typedWal()
        val registry = confixIndexRegistry { idxBuilders.forEach { index(it) } }
        val engine   = if (mrViews.isNotEmpty()) mapReduceEngine {
            mrViews.forEach { view(it) }
        } else null
        val srcSeries: Series<CursorSource> = sources.size j { i: Int -> sources[i] }
        val ctx = executionContext(sources)
        return AnalyticalStore(d, w, srcSeries, registry, engine, ctx)
    }
}

suspend fun analyticalStore(block: AnalyticalStoreBuilder.() -> Unit): AnalyticalStore =
    AnalyticalStoreBuilder().apply(block).build()
```

### Full-stack usage — one block, all three analytical modes

```kotlin
val store = analyticalStore {

    database {
        memtable(4096)
        maxSegments(8)
        wal { durable("/data/wal"); compactKeepLast(200_000) }
    }

    table("users") {
        column("id",   IOMemento.IoLong)
        column("name", IOMemento.IoString)
        column("age",  IOMemento.IoInt)
        from { range -> db.scanAsCursor("users", schema, range) }
    }

    table("orders") {
        column("id",      IOMemento.IoLong)
        column("user_id", IOMemento.IoLong)
        column("amount",  IOMemento.IoDouble)
        column("region",  IOMemento.IoString)
        from { range -> db.scanAsCursor("orders", schema, range) }
    }

    index {
        facet(ConfixIndexK.KeyToChild)
        collection("users")
    }

    view<String, Double, Double>("revenue_by_region") {
        map { doc ->
            val region = doc.value("region") as? String ?: return@map 0 j { error("") }
            val amount = doc.value("amount") as? Double ?: return@map 0 j { error("") }
            1 j { _: Int -> region j amount }
        }
        reduce { _, values, _ ->
            var sum = 0.0; for (i in 0 until values.size) sum += values[i]; sum
        }
    }
}

// ── Three analytical modes, one store ──

// 1. Cursor query
val seniors: Cursor = store.source("users").query {
    gt("age", 60)
    select("name", "age")
}

// 2. SQL DSL
val topOrders: Cursor = from("orders")
    .also { it.gt("amount", 500.0) }
    .also { it.join(from("users"), on = "user_id" to "id") }
    .also { it.select("name", "amount", "region") }
    .execute(store.executionContext)

// 3. Map-reduce
val revenue: Cursor = store.mapReduce("revenue_by_region")
```

---

## 11. File Map

```
src/commonMain/kotlin/borg/trikeshed/
  cursor/
    CursorOps.kt              ADD: where(), whereColumn(), limit(), offset()
    CursorSource.kt           NEW: CursorSource typealias, KeyRange, cursorSource{}
  analytical/
    AnalyticalDsl.kt          NEW: @AnalyticalDsl marker
    TypedWal.kt               NEW: TypedWalOp, TypedWal, typedWal{}
    MapReduceView.kt          NEW: MapReduceView typealias, mapReduceView{}, engine{}
    ConfixFacetIndex.kt       NEW: ConfixFacetIndex, FacetPredicate, registry{}
    SqlDsl.kt                 NEW: QueryPlan nodes, SqlQueryBuilder, from()
    AnalyticalStore.kt        NEW: AnalyticalStoreBuilder, analyticalStore{}

libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/
  WiredLsmrDatabase.kt        NEW: wraps LsmrMergeTree + TypedWal; lsmrDatabase{}
  (LsmrDatabase.kt unchanged as public API; WiredLsmrDatabase is the impl)
```

All types that cross the Confix/Cursor/storage boundary are typealiases over
Join. No wrapper types. No class hierarchies except where Kotlin demands it
(interface for TypedWal; sealed class for QueryPlan because pattern matching).

---

## 12. TDD Entry Points (RED first)

```kotlin
// Wall 5 — unlocks everything
@Test fun `where is lazy and filters correctly`() {
    val src: Cursor = 5 j { i: Int ->
        1 j { _: Int -> (i as Any?) j { ColumnMeta("n", IOMemento.IoInt) } }
    }
    val filtered = src.where { (it[0].a as Int) > 2 }
    assertEquals(2, filtered.size)
    assertEquals(3, filtered[0][0].a)
    assertEquals(4, filtered[1][0].a)
}

// Wall 1 — CursorSource round-trip
@Test fun `cursorSource DSL opens correct cursor`() = runBlocking {
    val src = cursorSource("t") {
        column("x", IOMemento.IoInt)
        from { _ -> 3 j { i: Int -> 1 j { _: Int -> (i * 10 as Any?) j { ColumnMeta("x", IOMemento.IoInt) } } } }
    }
    val c = src.open()
    assertEquals(3, c.size)
    assertEquals(20, c[2][0].a)
}

// Algorithm A — wired LSMR
@Test fun `lsmrDatabase wires MergeTree — put survives flush`() {
    val db = lsmrDatabase { memtable(2) }
    db.put("t/a", byteArrayOf(1))
    db.put("t/b", byteArrayOf(2))
    db.put("t/c", byteArrayOf(3))   // triggers L0→L1 flush
    assertNotNull(db.get("t/b"))
}

// Algorithm D — SQL plan executes
@Test fun `from-gt-select plan executes end-to-end`() = runBlocking {
    val src = cursorSource("nums") {
        column("v", IOMemento.IoInt)
        from { _ -> 5 j { i: Int -> 1 j { _: Int -> (i as Any?) j { ColumnMeta("v", IOMemento.IoInt) } } } }
    }
    val ctx = executionContext(listOf(src))
    val result = from("nums").also { it.gt("v", 2) }.also { it.select("v") }.execute(ctx)
    assertEquals(2, result.size)   // rows 3, 4
}

// Full stack
@Test fun `analyticalStore runs all three modes`() = runBlocking {
    val store = analyticalStore {
        table("items") {
            column("cat",   IOMemento.IoString)
            column("price", IOMemento.IoDouble)
            from { _ -> testItemsCursor() }
        }
        view<String, Double, Double>("by_cat") {
            map { doc -> 1 j { _: Int -> (doc.value("cat") as String) j (doc.value("price") as Double) } }
            reduce { _, vals, _ -> var s = 0.0; for (i in 0 until vals.size) s += vals[i]; s }
        }
    }
    // cursor query
    val cheap = store.source("items").query { lt("price", 10.0) }
    assertTrue(cheap.size > 0)
    // map-reduce
    val totals = store.mapReduce("by_cat")
    assertTrue(totals.size > 0)
}
```
