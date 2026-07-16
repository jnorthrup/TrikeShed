below is the project's kernel fp concepts we use in our kotlin-common projects.

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

## alpha xform 

```kotlin
         val supers = o.lattice.supertypes(cursor)
         // Cursor → Series → MetaSeries → Join (at minimum)
-        val superNames = (0 until supers.size).map { o.tdNames(supers[it]) }
+        val superNames = supers .α { o.tdNames(it) }
         assertTrue("Series" in superNames || "MetaSeries" in superNames || "Join" in superNames,
            "Expected at least one transitive supertype, got: $superNames")
```


## looping with views

```kotlin
         // params preserved
-        for (i in 0 until o.entries.size){
-            val e = o.entries[i]
+        for (e in o.entries.view)
             if (e.name == "Tuple") { tupleEntry = e; break }
-            }
 ```