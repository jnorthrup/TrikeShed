below is the project's kernel fp concepts we use in our kotlin-common projects.

> **Lineage.** This algebra is a clean-room Kotlin expression of four
> well-established traditions. Read each primitive through the matching lens:
>
> | TrikeShed | Pedigree | Notes |
> |-----------|----------|-------|
> | `Series<T> = Join<Int, (Int) -> T>` | **K / kdb+ enumerable** (1993) | size + index oracle. `α`=`each`, `j`=`,`, `s_[]`=`enlist`, `/`=`reshape`, `%`=`where` |
> | `Cursor = Series<RowVec>` + `ColumnMeta` + `IOMemento` | **Apache Arrow RecordBatch + FieldVector + ArrowType** | lazified; `get(range)`/`get(IntArray)` = fancy indexing |
> | `ConfixIndexK<R>` / `facet(key): R` GADT-key pattern | **Haskell `Lens' s a` / Monocle optics** | sealed-key singletons fix the result type by the key |
> |

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

// Named factory, literal syntax, and a stdlib boundary conversion; all retain j's packing.
val joined = `⋈`("name", 42)
val literal = `⋈`[1, 2.0f]
val converted = `⋈`("name" to 42)
val indexed = `⋈`(4) { i -> i * i } // inferred Series<Int>, lazy oracle
val zipped = `⋈`.zip(indexed, indexed) // lazy Series2; unequal sizes are rejected

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
- `⋈` = factory/literal grammar over the same Join representations (backticks required in Kotlin)
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

now also  handles yaml and cbor, and cursors under the name Confix 

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

## CCEK composition mandate

CCEK means Coroutine, Context, Element, Key. It is an acronym for composition
using the documented algebra, not a package, library, separate runtime, or
central module.

- CCEK composition is required in **at least half the codebase** and in
  **all asynchronous code**.
- Implement actual **channel compositions under owning SupervisorJobs**.
  Straight pipelines and branched fan-out/fan-in are both valid. Bounded
  channels carry work through stages and return results or failures. Branched
  work requires fan-in; every composition accounts for and joins admitted
  work before completion. A discarded claim is not processed work.
- Implement these asynchronous compositions **exclusively through userspace
  NIO/uring in `commonMain`**.
- Resolve dependencies through singleton typed `CoroutineContext.Key`
  identities and compose the existing domain elements. Follow the documented
  Join, Series, Cursor, and ConfixIndexK algebra and zero-cost taxonomy.
- Drain stops admission, finishes in-flight work, joins children, and closes
  channels. Detached work and hard cancellation do not substitute for drain.
- **No do-nothing placeholder modules, god classes, or replacement CCEK
  runtime. Do not resurrect the removed `CCEK.kt` facade.**
- Adding an element to a scope, importing CCEK names, or attaching labels does
  not establish composition. Completion requires actual channel dispatch,
  stage processing, fan-in where the topology requires it, and lifecycle
  behavior through the callers.
  Do not weaken these requirements to match unfinished implementations.

Fan out the implementation work into **25 tasks** with concrete ownership,
then integrate their changes and verify the composed behavior. This directs
the implementation work; it does not call for 25 new modules. These are
requirements, not a claim that the current code already satisfies them.

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

# RFC: CCEK element completeness

Status: Draft for proofreading. This RFC describes the intended design.

| Term | Kotlin default | TrikeShed CCEK role |
| --- | --- | --- |
| [SupervisorJob](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-supervisor-job.html) | Parent job whose children can fail independently. | Supervises the launched CCEK coroutines. |
| [Element](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.coroutines/-coroutine-context/-element/) | A keyed member of a coroutine context. | A constructed instance carrying mutable state. |
| [Key](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.coroutines/-coroutine-context/-key/) | Typed identity used to look up an element. | A factory defining the element's module boundary. |

For a coroutine launch, use one or more key factories to construct elements,
or supply already constructed elements with their existing state. The resulting
elements are composed into the context supplied to or inherited by
[`launch`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/launch.html).
The context carries the elements; the scope's supervisor job supervises the
launched child jobs.

An element may contain one or more supervisor jobs within the module boundary
defined by its key. The key can be a fully enabled factory for that entire job
composition.

TrikeShed element design uses Linux kernel `io_uring` through the userspace I/O
boundary, with channelization as the primary means of conveying state between
elements.

## Userspace I/O and structured completion

Userspace NIO is uring-centric. Kotlin `commonMain` owns one submission and
completion contract, buffer ownership, bounded delivery and drain. Kernel
`io_uring` executes supported operations when setup, operation probes and a
reachable runtime binding permit it; uring-compatible emulation is required
otherwise. Runtime language does not determine kernel availability. Existing
NIO-shaped channel APIs adapt to this contract. Unix, TCP/IP and file streams
compose through ordinary Kotlin channels and flows under owning SupervisorJobs.

Each composition defines its expected final element state and the intermediate
tasks required to reach it. Nested and parallel coroutines are owned by one
supervisor scope or explicitly parented nested supervisor scopes. A straight
channel pipeline is a first-class composition; branching is introduced where
the work requires it. Kotlin coroutine scopes contain the work, with fan-in
where branches must be collected.
 
