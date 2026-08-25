below is the project's kernel fp concepts we use in our kotlin-common projects.

> **Lineage.** This algebra is a clean-room Kotlin expression of four
> well-established traditions. Read each primitive through the matching lens:
>
> | TrikeShed | Pedigree | Notes |
> |-----------|----------|-------|
> | `Series<T> = Join<Int, (Int) -> T>` | **K / kdb+ enumerable** (1993) | size + index oracle. `α`=`each`, `j`=`,`, `s_[]`=`enlist`, `/`=`reshape`, `%`=`where` |
> | `Cursor = Series<RowVec>` + `ColumnMeta` + `IOMemento` | **Apache Arrow RecordBatch + FieldVector + ArrowType** | lazified; `get(range)`/`get(IntArray)` = fancy indexing |
> | `ConfixIndexK<R>` / `facet(key): R` GADT-key pattern | **Haskell `Lens' s a` / Monocle optics** | sealed-key singletons fix the result type by the key |
> | CCEK (`CREATED→OPEN→ACTIVE→DRAINING→CLOSED` + fanout) | **Rx `Subject` / Reactor `Flux`** | same state machine, different alphabet |
>
> The design bias below (composition over inheritance; ranges and projections
> over mutable loops; lazy views first; typealiases compress semantics) is the
> K/Arrow house style. Where a primitive here diverges from its original — e.g.
> `Series.filter` is missing and `%`/`[Predicate]` return `Iterator` not `Series`
> (K's `&` and Arrow's `filter(mask)` both return same-typed lazy structures) —
> that is a port gap, not a design choice. Fix by matching the original.

## Kernel algebra

```kotlin
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b)
    // ... many extensions and specializations hang off this shape
}

/** exactly like `to` for Join, but shorter and idiomatic to the algebra */
inline infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a
operator fun <T> Series<T>.get(i: Int): T = b(i)
val <T> Series<T>.view: IterableSeries<T> get() = this as? IterableSeries ?: IterableSeries(this)

/** left identity / constant anchor */
inline val <T> T.`↺`: () -> T get() = leftIdentity

/** lazy projection over a Series */
inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }

/** iterable projection exists too */
infix fun <X, C, Subject : Iterable<X>> Subject.α(/* ... */)

interface CSeries<T : Comparable<T>> : Series<T>, Comparable<Series<T>>
val <T : Comparable<T>> Series<T>.cpb: CSeries<T>

    series.view = IterableSeries for .map , .filter, .all, and so on stdlib collection iterators

// collection literals / macros
object _l { operator fun <T> get(vararg t: T): List<T> = listOf(*t) }
_l[...]   // List<T>
_a[...]   // Array<T> and primitive arrays
_s[...]   // Set<T>
s_[...]   // Series<T>
```

Read this algebra as:
- Join = the base binary composition
- Twin = same-typed Join
- Series = size paired with index function
- `j` = infix constructor grammar
- `α` = lazy map / projection
- `↺` = visible constant / left identity anchor
- literals like `_l`, `_a`, `_s`, `s_` keep composition dense without hiding type shape

Design bias:
1. composition over inheritance
2. ranges and projections over mutable loops
3. explicit algebra over opaque helpers
4. lazy views first; materialization later
5. typealiases compress semantics, not substance

**Maxim of categorical idempotency.** If a structure is not mutated, it should
stay in the category it came from. A `Series` that gets copied to a `List` only
to be read back is a type demotion: the List is a step-function pause, not a
resting type. The rule cuts both ways:
- A `mutableListOf` that is built and never mutated thereafter should be a
  `Series` (`s_[...]` literal, or `xs α { ... }` projection, or `xs.toSeries()`).
  The List shape was a transient; if the end state is read-only, return to Series.
- A `List` that genuinely gets mutated (append-in-loop, index-assign, remove)
  stays a List — but then say so with `mutableListOf`, not `listOf`, so the
  mutation is honest in the type.

The round-trip `list.toSeries()` → `series.toList()` is a category change and
should be a no-op when the List was never mutated. If you see `.toList()` on a
Series followed by no further mutation, the `.toList()` is the demotion — revert
it. The canonical form is `(xs α { f(it) }).toList()` only when the consumer
demands `List` (kotlinx `JsonArray`, stdlib `joinToString`, etc.); otherwise the
α result stays a Series and composes downstream.

Idempotency check: `xs.toSeries().toList().toSeries() == xs.toSeries()`. If a
refactor introduces a step that breaks this equality by materializing, that
step is the debt.

## Cursor algebra

```kotlin
typealias RowVec = Series2<Any, () -> RecordMeta>
/** Cursors are a columnar abstraction composed of Series of joined value+meta pairs */
typealias Cursor = Series<RowVec>
```

Read Cursor as:
- RowVec = row-shaped value view plus metadata supplier
- Cursor = indexed composition of RowVec
- metadata is part of the algebra, not an afterthought

Common cursor ideas from the project:
- `cursor[i]` selects a row/view by index
- `cursor[i0 until i1]` is a range view
- `cursor[1,3,2]` reorders / projects columns by index
- `cursor["name","age"]` projects by column name
- `cursor[-"debug"]` excludes columns
- `join(cursor1, cursor2)` widens along columns
- `combine(cursor1, cursor2)` concatenates along rows

Cursor rules:
1. prefer projection over mutation
2. treat range selection as composition, not control flow
3. preserve metadata through transforms
4. widen and combine explicitly
5. keep cursor transforms pure where possible

## JSON scan / path algebra

now handles yaml and cbor, and cursors under the name Confix 

```kotlin
typealias JsElement = Join<Twin<Int>, Series<Int>>
typealias JsIndex = Join<Twin<Int>, Series<Char>>
typealias JsContext = Join<JsElement, Series<Char>>
typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>
```

These aliases support the project's lightweight JSON indexer/reifier/path selector:
- index first, reify later
- segments stay cheap and sliceable
- path selection is algebra over indexed structure, not reflection-driven object walking

## Userspace async context algebra (aka CCEK meaning Coroutine, Context, Element, Key )

The userspace side is modeled as explicit async context elements and fanout, not hidden ambient magic.

Ground truths from the code/tests:
- async context keys are singleton identity objects
- current keys include `NioUserspaceKey`, `LiburingKey`, `FanoutDispatcherKey`
- element lifecycle is forward-only:
  - `CREATED`
  - `OPEN`
  - `ACTIVE`
  - `DRAINING`
  - `CLOSED`
- elements expose:
  - `key`
  - `lifecycleState`
  - `fanoutSubscribers`
  - `open()` / `drain()` / `close()`
- userspace fanout is structured concurrency via `coroutineScope { launch { ... } }` over listeners

Read this as:
- key = routing identity
- lifecycle = explicit state machine
- fanout = structured delivery, not callback soup
- userspace = composition and coordination layer around effects

## What this preload is trying to preserve

1. most project shapes collapse back to Join
2. Series is the default indexed abstraction
3. Cursor is the dataframe-shaped specialization of the same algebra
4. purity means transforms read like projections, selections, joins, and ranges
5. side effects belong at the userspace boundary with explicit lifecycle and fanout
6. the goal is dense readable composition, not ceremonial abstraction

# REFACTOR RECIPES

These are the two canonical loop-elimination strategies. Both close on the
same principle: **keep the result a `Series` so downstream `α`/`get(range)`/
`/`/`%` compose; only call `.view`/`.toList()` at the stdlib boundary.**

## α xform — replace `(0 until X.size).map { X[it] }` with a lazy projection

> Strategy: K `each` / Arrow `map` — the projection stays lazy and same-typed.
> Don't follow this with `.toList()` to satisfy an `assertEquals(listOf(...))`;
> that's materialization ceremony that throws away the laziness α just bought.
> Use α when the result is consumed by another Series combinator or by a
> Series-typed `contains`/`in` check. Use `.view.map { }` (stdlib) when you
> genuinely need a List at the boundary.

```kotlin
         val supers = o.lattice.supertypes(cursor)
         // Cursor → Series → MetaSeries → Join (at minimum)
-        val superNames = (0 until supers.size).map { o.tdNames(supers[it]) }
+        val superNames = supers .α { o.tdNames(it) }
         assertTrue("Series" in superNames || "MetaSeries" in superNames || "Join" in superNames,
            "Expected at least one transitive supertype, got: $superNames")
```


## looping with views — replace index-only `for` with element iteration

> Strategy: stdlib `for (e in iterable)` via the `IterableSeries` bridge.
> Use when the loop body only uses the index to dereference the Series
> (`X[i]`/`X.b(i)`) and the result is a side effect (append to builder,
> mutate state), not a new Series. If the body builds a new Series, prefer
> the α recipe above — the loop vanishes entirely.
>
> When the index is also needed (separator guards like `if (i > 0) append(",")`,
> or building `Document("reconstructed-$i")`), use `forEachIndexed` on the view
> rather than forcing the whole Series into a List just to get `.withIndex()`.

```kotlin
         // params preserved
-        for (i in 0 until o.entries.size){
-            val e = o.entries[i]
+        for (e in o.entries.view)
             if (e.name == "Tuple") { tupleEntry = e; break }
-            }
 ```

## Taxonomical self-doc

as code is maintained or created move abstractions into their own scaffolding hierarchy of enums, typealiases, and inline value classes, potentially closed hierarchies to support taxonomy DSL's which have stricter typing rules with negligable  runtime overheads

typealias Tick = Int
typealias Lightyear = Double
typealias Serializer = Confix

alternate:
value class LightYear (it:Double){...}

# CCEK element completeness

The lifecycle is five states. Implementations with fewer are incomplete:

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

- CREATED: element exists, not wired. `open()` → OPEN.
- OPEN: registered, idle. First subscriber/consumer → ACTIVE.
- ACTIVE: processing. `drain()` → DRAINING.
- DRAINING: no new work; in-flight completes, then → CLOSED.
- CLOSED: resources released, channels closed.

An element IS a `CoroutineContext.Element`:
- `key: CoroutineContext.Key<*>` — singleton identity object, reference equality. NEVER `.toString()` comparison.
- `lifecycleState`, `fanoutSubscribers`
- `open()` / `drain()` / `close()`

DRAINING is graceful drain, not hard cancel. In-flight operations finish; the
channel drains to empty before close. Hard-cancel of children is a last resort,
not the default shutdown path.

## HTX serial element chain

```
HtxKey → HtxElement → HtxReactorElement → JvmTlsCodecBackend → SSLEngine
```

One SSLEngine per endpoint ordinal. Cipher state (sequence numbers, handshake
context) is mutable and serial — concurrent writes corrupt it. Therefore:

- All HTX exchanges through one endpoint ordinal serialize (per-element mutex).
- HtxReactorElement should be a POOL: N elements, each owning its own SSLEngine,
  borrow/return lifecycle. Pool capacity = concurrency limit. The global htxMutex
  is a degenerate pool of size 1.
- HtxKey must be baked into the CoroutineScope: `CoroutineScope(htxElement +
  Dispatchers.IO)`. Every `scope.launch {}` inherits it; there is no way to
  drop the transport by forgetting `+ htxElement`.

## HTX terminology is mandatory

Never summarize HTX as "TLS" or "the transport layer." The element chain is:
HtxKey (context key) → HtxElement (context element) → HtxReactorElement (reactor)
→ JvmTlsCodecBackend (codec) → SSLEngine (JVM). `connectionOrdinal` on
`TlsFlowState` identifies the engine instance. These are the names; use them.

# Zero-cost domain taxonomy mandate

The value-class algebra in lib/ extends to the domain layer. Every domain
identity is a packed primitive, not a heap object or String.

```kotlin
@JvmInline value class Nuid(val raw: Long)        // cap(4) + subnet(4) + nonceHash(56)
@JvmInline value class ContentId(val raw: Long)    // or Twin<Long> for full fingerprint
@JvmInline value class CausalKey(val raw: Long)    // mix64 of causal fields
@JvmInline value class BlockId(val raw: Int)       // interning pool index
@JvmInline value class ColumnId(val raw: Byte)     // enum ordinal (3 columns = 1 byte)
@JvmInline value class SessionId(val raw: Long)
@JvmInline value class CausalGraphNode(val rowIndex: Int)  // row into the graph Cursor
```

Strings exist only at the JSON/HTTP serialization boundary — `Id.toString()`
for the wire, never as the in-process identity. The existing bit-packing
primitives (TwInt, TwinPacked, IsAEdge, packInts, packFloats, NeighborStamp,
Mini64) are the templates.

**The causal graph is one Cursor, not N heap objects.** Columns: nodeId
(Series<String>, interned), opId, parentNodeIds, causalClock (LongArray),
topoOrdinal (IntArray). A CausalGraphNode IS a row index; "fields" are column
projections via ConfixIndexK facet lookup.

**The board is a Cursor.** Columns: title (Series<String>), order (IntArray),
priority (IntArray of enum ordinals), columnId (ByteArray). A KanbanCard IS a
row index. boardJson is `(board α { it.toCardMap() })` at the boundary, not N
Map allocations.

# Reactor decomposition rules

A daemon is a composition of CCEK elements, not an object with methods:

1. **Acceptor** (LitebikeListenerElement): owns the bind, emits ChannelMessages.
2. **Router** (NuidFanoutElement): dispatches by typed key (NUID capability), not
   by string path. Routing IS ConfixIndexK facet lookup:
   `routes.faclet(nuid.capability)` returns the reducer element.
3. **Reducers**: one element per concern (board, submit, health, invoke). Each
   has its own lifecycle, subscribes to the fanout, produces typed responses.
4. **Responder**: ChannelMessage carries `CompletableDeferred<HttpResponse>`.
   Reducer completes it; acceptor awaits it. No side-channel ConnectionRegistry.
5. **Persistence** (WAL element): CCEK element with open()/drain()/close().
   Reducers emit to its input channel; it batches and fsyncs.

A god object (one class with accept + route + reduce + persist + respond) is
prohibited. Each role is an element; composition is through fanout channels,
not method calls.

# Durability contract

A WAL is not `append + fsync per record`. It is:

1. **Group-committed**: batch N appends, one fsync per flush interval.
2. **Commit-marked**: each record carries CRC32 or trailing sentinel. Replay
   validates; torn tail records truncate to last-good. A crash mid-write must
   not corrupt the WAL.
3. **CAS-addressed payloads**: payload stored in the CAS once (content-addressed);
   the WAL stores only ContentId. Deduplicates; keeps the WAL a thin ordering log.
4. **Series projection**: `replay()` returns `Series<EventNode>`, not
   `Sequence<Pair<String, ByteArray>>`. The graph is a lazy WAL projection.
5. **Segmented + snapshoted**: roll segments at N bytes; periodic checkpoints;
   boot is O(snapshot + delta).
6. **Directory fsync** after initial WAL creation (ext4/xfs can lose the file
   entry on crash even if data was fsync'd).

# MutableSeries fill/spill cascade

MutableSeries.append must not be O(N). The backing is a tiered chunk tree:

```
Level 0: inline Array (capacity C)         — hot, heap
Level 1: direct ByteBuffer chunks           — warm, off-heap
Level 2: mmap'd SeekFileBuffer segments     — cold, disk-backed
```

- **Fill**: write to current chunk's cursor. O(1) amortized.
- **Spill**: chunk fills → cascade to next level. New chunk, linked in tier index.
- **Read**: `Series[i]` traverses tier index O(log_C(N)), O(1) within chunk.
- **freeze**: O(1) flag flip. Persistent-vector trie (32-ary): subsequent
  mutation copies only O(log32(N)) nodes.
- **cowSnapshot**: O(1) shared backing, ref-counted. Copy on first write.
  NOT a full array copy.

IOMemento already tags which tier a range lives in. The spill cascade is the
materialization of that metadata as a tiered storage strategy.

HashSeriesSet needs a treeify threshold (chain depth > N → balanced subtree),
matching Java 8+ HashMap. resize() must redistribute buckets directly without
re-entering add() (which re-checks the threshold and cascades).

# Prohibited patterns (debt this session surfaced)

- `when(path)` string switch for routing — use typed key facet lookup
- `mutableListOf` built and never mutated — use Series / s_[] / α projection
- Per-record fsync — use group commit
- `@Volatile var` on a data class field inside ConcurrentHashMap — race condition
- `Channel.UNLIMITED` for back-pressured pipelines — use bounded channels
- SharedFlow with `replay=64` for real-time projections — use CONFLATED
- Swallowed `catch (e: Throwable) {}` — errors are first-class projections
- `.toString()` comparison for CoroutineContext.Key identity — use reference eq
- String domain IDs in-process — use value-class packed primitives
- God object daemon — decompose into CCEK elements

# Open gaps (RGA Aug 08 2026 — factual observations, not spec changes)

These are things PRELOAD describes that the code does not yet implement.
They are TODOs for the code, not corrections to PRELOAD.

- ~~Cursor fancy indexing~~ CLOSED Aug 24 2026: operator grammar lives in
  cursor/CursorIndexing.kt (`cursor[1,3,2]` ordinal projection, `cursor["name","age"]`,
  `cursor[-"debug"]` via ColumnExclusion value class + CharSequence.unaryMinus) as thin
  delegates over the named CursorOps combinators; `cursor[range]`/`cursor[IntArray]` were
  already covered by the generic Series gets (Join.kt range view is lazy); `join()`/
  `combine()` already existed in CursorOps. Proof: CursorIndexingTest (7 tests).
- ~~`↺` (leftIdentity)~~ STALE: standalone `T.`↺`` exists at Join.kt:91 over
  `leftIdentity` at Join.kt:94. The Aug 08 observation no longer holds.
- ~~Series.filter laziness~~ CLOSED Aug 24 2026: Series.kt filter now memoizes the
  match scan behind `lazy {}` — no work at call time, one scan on first size/element
  access (the K `where` vector, deferred). Predicate.kt `%` (rem) still scans eagerly.