below is the project's kernel fp concepts we use in our kotlin-common projects.

> **Lineage.** This algebra is a clean-room Kotlin expression of four
> well-established traditions. Read each primitive through the matching lens:
>
> | TrikeShed | Pedigree | Notes |
> |-----------|----------|-------|
> | `Series<T> = Join<Int, (Int) -> T>` | **K / kdb+ enumerable** (1993) | size + index oracle. `α`=`each`, `j`=`,`, `s_[]`=`enlist`, `_l`/`_a`/`_s`/`_m` = typed literals, `/`=`reshape`, `%`=`where` |
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

/** a range IS a Series: lazy view, no List; (lo..hi).toSeries() α { f(it) } */
fun ClosedRange<Int>.toSeries(): Series<Int> = (endInclusive - start + 1) j { i: Int -> i + start }
/** range view of a Series: composition, not a copy */
operator fun <T> Series<T>.get(range: IntRange): Series<T> = range.count() j { i -> this[range.first + i] }

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

// collection literals: first-class operators of the algebra, not helpers
object _l { operator fun <T> get(vararg t: T): List<T> = listOf(*t) }
_l[...]   // List<T>
_a[...]   // Array<T> and primitive arrays (IntArray, ByteArray, ...)
_s[...]   // Set<T>
_m[...]   // Map<K, V> from Joins: _m["a" j 1, "b" j 2, ...]
s_[...]   // Series<T>
```

### Literal operators

`_l`, `_a`, `_s`, `_m` and `s_` are first-class operators, on a level with `j`, `⋈`, `α` and `↺`. Write a
collection value with its literal operator, never with `listOf`, `arrayOf`/`intArrayOf`/`byteArrayOf`, `setOf`,
`mapOf`/`to` pairs or a `List(n) { }` builder:

| Operator | Result | Replaces |
|----------|--------|----------|
| `_l[a, b, ...]` | `List<T>` | `listOf(a, b, ...)` |
| `_a[a, b, ...]` | `Array<T>`, or the primitive array for primitive elements | `arrayOf(a, b, ...)`, `intArrayOf(...)`, `byteArrayOf(...)`, ... |
| `_s[a, b, ...]` | `Set<T>` | `setOf(a, b, ...)` |
| `_m[k j v, ...]` | `Map<K, V>` | `mapOf(k to v, ...)` |
| `s_[a, b, ...]` | `Series<T>` | `listOf(a, b, ...).toSeries()` |

`s_` is the default: a literal that is only read is a Series (maxim of categorical idempotency below). Reach for
`_l`, `_a`, `_s` or `_m` only where the consumer needs that type. Tests follow the same rule: expected values are
literals or `α` projections, not Lists built to satisfy `assertEquals`.

Read this algebra as:
- Join = the base binary composition
- Twin = same-typed Join
- Series = size paired with index function
- `j` = infix constructor grammar
- `⋈` = factory/literal grammar over the same Join representations (backticks required in Kotlin)
- `α` = lazy map / projection
- `(lo..hi).toSeries()` = a range as a lazy Series of its Ints; `xs[lo..hi]` = a range view of a Series. Neither copies;
  write `(0 until n).toSeries() α { f(it) }`, never `(0 until n).map { f(it) }` or `List(n) { f(it) }`
- `↺` = visible constant / left identity anchor
- `_l`, `_a`, `_s`, `_m`, `s_` = first-class literal operators; they keep composition dense without hiding type shape

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

## Network address grammar (InetGrammar)

Addresses, ports, ranges and host:port tuples parse as `CharSeries` productions
(`userspace/nio/channels/InetGrammar.kt`), never with `split`/`toInt` on Strings. Each production consumes its text
and returns the value, or restores the position and returns null; `parseWhole(text) { production() }` requires the
whole text.

```kotlin
typealias Port = Int
typealias PortRange = IntRange
typealias Inet = ByteArray            // 4 octets (IPv4) or 16 (IPv6); call sites mono- or bimorphize, no class tree
typealias Ipv4 = Inet
typealias Ipv6 = Inet
typealias Endpoint = Join<Inet, Port>
typealias SctpEndpoint = Join<Series<Inet>, Port>   // RFC 9260: a multihomed association's addresses share one port

//  port         = digit{1,5}                (0..65535)
//  portRange    = port [ '-' port ]
//  octet        = digit{1,3}                (0..255)
//  ipv4         = octet '.' octet '.' octet '.' octet
//  ipv4Range    = ipv4 [ '-' octet ]        192.168.50.201-208 -> Series<Ipv4>
//  ipv6         = RFC 4291 text: 8 hex16 groups, one '::', optional ipv4 tail (::ffff:192.168.50.1)
//  inet         = ipv4 | ipv6
//  hostInet     = ipv4 | '[' ipv6 ']'
//  endpoint     = hostInet ':' port         -> Endpoint; Endpoint.sockaddr picks sockaddr_in / sockaddr_in6
//  sctpEndpoint = hostInet { ',' hostInet } ':' port
//  list(p)      = p { ',' p }               -> Series<T>
parseWhole("10000-48999") { portRange() }
parseWhole("127.0.0.1:3000,[::1]:3001") { list { endpoint() } }
parseWhole("10.0.0.1,[2001:db8::1]:9899") { sctpEndpoint() }
```

Platform port facts are configuration, not constants: `SourcePorts` reads `TRIKESHED_SOURCE_PORTS=lo-hi` through this
grammar and otherwise sits under the host's ephemeral range (macOS/Windows 10000-48999, Linux 10000-32767).

## Userspace async context algebra (aka CCEK meaning Coroutine, Context, Element, Key )

The userspace side is modeled as explicit async context elements and fanout, not hidden ambient magic.

Implementation obligations, not a claim that every element currently meets them:
- async context keys are singleton identity objects
- current owners include `FunctionalUringFacade.Key` and `NioSupervisor.Key`
- lifecycle means admission, ownership of outstanding effects, and awaited cleanup
- use Kotlin Job, Channel, and Deferred completion where they express that lifecycle
- retain an enum only where its values govern actual domain behavior
- typed context lookup does not require a shared lifecycle superclass
- userspace fanout is structured concurrency via `coroutineScope { launch { ... } }` over listeners

Read this as:
- key = routing identity
- lifecycle = enforced admission and completion obligations
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

## public as default 

'private' is not forbidden, but it doesn't add much, it should not be used or preserved


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
channel pipeline is a first-class composition; branching is introduced where the
work requires it. Kotlin coroutine scopes contain the work, with fan-in
where branches must be collected.

# RULES — current mechanics (appended 2026-09-11)

Binding rules, not preferences. The reward function is authenticity and
parsimony: do no extra. Finish unfinished code; never induce a false
completion. This file is the implementation contract.

## Naming / taxonomy
- lead with zero-cost taxonomical abstractions favoring value classes and type-aliases  
- do not invent paradigm -- first-time nouns/verbs are typeo's when no intro is specific
- conversational verbs /nouns/adjectives have no place in code naming

## Lifecycle — the Job and the Channel are the lifecycle
- An element is `CoroutineContext.Element` with `companion object Key : CoroutineContext.Key<T>`; the Key is the factory and returns only an opened element. `AsyncContextKey` and `AsyncContextElement` are retired: never extend, import, recreate or rename them.
- No lifecycle enum or flag (`ElementState`, CREATED/OPEN/ACTIVE/DRAINING/CLOSED, `started`, `running`) and no Mutex guarding one. Admission stopped is `input.isClosedForSend`; completion is the supervisor `Job`. A renamed, sealed or `final` wrapper over the same states is the same violation.
- Admission is the channel send. Never check a state and then act on it.
- Drain: close the input, the worker empties it, join the worker, complete and join the supervisor. Hard cancellation is the parent-failure path only.
- A submitted uring op is a child Job of the supervisor, completed by its CQE, so join waits on the kernel.
- Enums remain only where their values decide domain outcomes.
- Existing users of the retired types are debt being paid down, not precedent to copy.

## Build hygiene — no rug pull
- The debt-ratchet exclusion files (`gradle/*-target-debt.excludes`) are the
  rug pull: hiding code from a compile is forbidden. No excluded compiles, no
  excluded tests. Pay the debt in the compile itself.
- No scripts. No garbage in the root dir. No broken code saved for later.
- `TODO()` is the sanctioned escape hatch: write real code where a target can
  back it; mark the genuine gap with `TODO()` where it cannot. Never delete
  the `expect` to hide it.

## expect / actual placement
- `expect`/`actual` lives ONLY in the userspace-nio commonMain SPI impls.
  Anywhere a cross-target difference is genuinely required outside that SPI,
  the declaration becomes a plain common declaration (no `expect`).

## uring gating — all targets
- IO follows uring kernel-module detection on every target, not just Linux.
  Each target probes for the Linux io_uring kernel module / native binding;
  the JVM in particular gates on module presence and captures IO through the
  emulated uring backend when the module is absent.
- The commonMain NIO uring facade shall not differ from liburing.
  `capabilities` is identical across real ring and emulation;
  `nativeCapabilities` alone marks which is executing, so the facade is one
  contract regardless of backend.

## ISAM and mapped memory
- ISAM data, metadata, append, flush and file lifetime use the same commonMain
  userspace NIO/uring facade on every target.
- mmap is accommodated as an explicitly owned userspace memory lifecycle:
  mapping, sync, advice, registration retention and unmap stay at the NIO SPI
  boundary. The trace sleeve distinguishes these VM operations from SQEs/CQEs;
  mapped access must not be reported as completed disk I/O. No invented uring
  opcode or platform ISAM I/O bypass substitutes for this boundary.

## platform codec / endian framing
- `platformCodec` expresses the major runtime invariants and the endian
  uptake, and frames IO.
- Network endian, NIO compatible, when an order is assumed; selectable when
  not, as a java.nio.ByteBuffer is BIG_ENDIAN until `order()` selects. Host
  order is reported, never assumed.
- NIO IO must not assert endian translation when Kotlin does not. The
  ByteBuffer/uring framing is endian-honest: it does not inject a byte order
  the JVM/JDK NIO layer would not, and it matches what the uring kernel
  accepts. The JVM `java.nio.ByteOrder.nativeOrder()` assumption is the
  hazard; the facade's framing owns the bytes, not the JDK's.

## Test standing 
- Tests older than 30 days have no standing — they expire as development guidance. 
- Concurrent branch development demands TDD Red/Green, and reading intent before reverting code based on test results.  working on main is reference in all other cases.

<<<<<<< HEAD
## Distributed 3way merge — D3M
||||||| 6d2d1127a
=======
## Distributed 3way merge — D3M

D3M is the endorsed integration technique for distributed branch work. Its goal
is to preserve every branch's intent and conflict evidence, integrate sequentially
through real common ancestry, then repair the combined result for maximum useful
behaviour. A branch name, author, model, age, or position as "ours" or "theirs"
does not grant its implementation priority.

0. **Commit, publish, then fetch locally.** Before integration on Jim's machine,
   commit all task-authorized work in the current participating checkout and
   push the current integrated work to remote master with a normal,
   non-forced push. Include the D3M contract change in that checkpoint. Inspect
   untracked files before staging; "all" does not authorize committing secrets,
   private corpora, generated caches or unrelated work. If remote master has
   advanced, preserve and integrate that ancestry before the push. Other
   distributed branches must likewise have their intended work committed and
   their exact branch tips published; do not flatten incompatible branch tips
   onto master merely to transport them. Confirm the published OIDs, then fetch
   the configured remotes in Jim's local TrikeShed checkout without pruning.
   Only after this local fetch freeze the integration set below. A cloud push
   is not proof that the local fetch happened. If local execution is unavailable,
   report that boundary and retain the published checkpoint for continuation.
1. **Freeze the integration set.** On the requested machine, inventory local
   branches, remote-tracking branches, worktrees, dirty paths and existing merge
   state. Record exact tip OIDs and the starting master/remote-master OIDs after
   the distribution/fetch preflight. Freeze
   a manifest of the requested branch set; collapse duplicate tips and explicitly
   account for already-contained branches. Preserve uncommitted work without
   resetting, cleaning or overwriting it. Do not substitute a cloud clone for a
   requested local checkout.
2. **Preserve recovery.** Retain the original master and every input tip with
   backup refs or a verified Git bundle. Integrate in an isolated local worktree
   and temporary branch. Keep an external-to-source conflict archive under the
   repository's Git storage, with a session identifier and manifest. Source
   branches and their worktrees remain intact.
3. **Merge sequentially.** Record a reproducible order, initially lexical ref
   order unless dependencies require another documented order. Merge one frozen
   tip at a time with Git's ordinary three-way ancestry handling. Do not use
   octopus integration, blanket ours/theirs strategies, `-Xours`, `-Xtheirs`,
   union resolution, or an unreviewed rerere result to hide conflicts. Review
   merge attributes and custom drivers before allowing them to resolve content.
   An unrelated history is an explicit integration exception, not an excuse to
   invent a common base silently.
4. **Retain every conflict.** Before changing an unmerged path, save its stage-1
   base, stage-2 accumulated side and stage-3 incoming side, including absent
   stages, OIDs, modes, renames, deletes, symlinks and binary/type conflicts.
   Retain the conflicted worktree representation and the complete Git diagnostic.
   A conflict archive remains evidence after the working file is repaired.
5. **Make intermediate state honest.** Git cannot start the next ordinary merge
   with unresolved index entries. When semantic resolution is deferred until
   all branches have been examined, use explicitly marked provisional commits
   only on the temporary integration branch. Preserve text conflict carriers;
   for binary, path or type conflicts use a manifest-backed neutral carrier and
   preserve all original variants. These carriers are accounting devices, not
   chosen implementations. Subsequent conflicts must retain their original tip
   ancestry as well as the accumulated carrier. Never publish, deploy, or claim
   build success for an unresolved checkpoint. If a carrier prevents meaningful
   continuation, perform the minimum reviewed structural resolution needed and
   retain the still-open semantic decision in the manifest.
6. **Resolve intent across the complete set.** After the sequential pass, inspect
   each branch's changes from its merge base, relevant design notes and current
   callers. Review cleanly merged overlaps too. For every conflict record the
   competing requirements, retained benefits, incompatible assumptions and final
   resolution. Compose compatible behaviour; repair shared contracts where
   necessary. Do not equate retaining both text blocks with retaining both
   behaviours. Do not resurrect obsolete behaviour merely because its branch
   remains present. Any omitted intent needs an explicit technical reason.
7. **Verify the integrated result.** Remove all provisional carriers and resolve
   every conflict-manifest entry. Check for unresolved index stages and marker
   artifacts, compile all affected targets without exclusions, and exercise the
   composed production paths and meaningful regressions. Follow the current
   test-standing and intent rules above. Record failures honestly and fix their
   causes; do not weaken checks to manufacture a merge success.
8. **Advance and publish master.** Commit the final repairs and integration
   report. Verify every included tip is an ancestor of the result or has an
   explicitly recorded equivalent/inapplicable disposition. Re-read local and
   remote master before advancing; incorporate intervening commits with the same
   method. Advance master only from the expected starting OID, respecting any
   checked-out worktree and uncommitted work. Push the verified master normally,
   never force-push over new remote work. Confirm the remote OID and retain the
   conflict archive, backup refs and branch-disposition report.

D3M is sequential and therefore order-sensitive. "No bias" means no automatic
side preference and an auditable review of all intents, not a false claim that
merge order cannot affect conflicts. Conflicts remain preserved as evidence;
the published master must contain the resolved, verified implementation.

>>>>>>> 28c9689e34952168ae0cbf00d9e266425976e351

D3M is the endorsed integration technique for distributed branch work. Merge
ascending history through real common ancestry, retain every conflict and its
intent, commit each integration checkpoint, then resolve and fix the complete
composition. Work is preserved in local commits, never in shelves or Git stash.

### Unbiased means no automatic conflict choices

No branch, author, model, age, timestamp, accumulated side, incoming side, "ours"
or "theirs" has priority. Neither old patches nor new patches win by default.
Do not sort or select diff hunks as "old first" or "new first", accept a side
because it is newer, or restore it because it is older. Ascending history is an
ancestry-preserving schedule for whole branch tips, not a resolution policy.

Inspect the base and both sides, each branch's intended behaviour, current
callers and the composed requirements before resolving a conflict. Preserve
compatible intent and repair incompatible assumptions. Patch equivalence is
supporting evidence to inspect, never an automatic instruction to discard a
side. Record the reason for every retained, changed or omitted behaviour.
Cleanly merged overlapping changes also require intent review; Git's ability to
merge text is not approval of the resulting behaviour.

### Required sequence

0. **Fetch first.** In the requested local checkout, fetch every configured
   remote without pruning. Do not substitute a cloud clone. Record the fetched
   tip OIDs; fetch does not authorize changing or discarding working files.
1. **Add everything and commit on the spot.** From the checkout root run
   `git add .`, then commit all tracked changes and all non-ignored untracked
   files locally before integration. `.gitignore` governs inclusion; do not
   selectively leave working files behind or force-add ignored files. Commit
   nested repository changes first, then their gitlinks in the parent. Include
   the D3M contract update in the checkpoint. Do not wait for tests, cleanup,
   task boundaries or permission to preserve work. A checkpoint makes no build
   or completion claim. If unmerged index entries already exist, retain their
   stages and conflict carriers as below, then commit the checkpoint.
2. **Freeze and retain the inputs.** Inventory local branches, remote-tracking
   branches, worktrees, dirty paths and merge state after the fetch/checkpoint.
   Freeze exact OIDs and the starting local/remote master OIDs. Collapse duplicate
   tips and explicitly account for already-contained branches. Retain every
   original tip and starting master through recovery refs or a verified bundle.
   Any newly discovered dirty work must likewise be committed locally on the
   spot; never reset, clean, overwrite or defer it.
3. **Order ascending history.** Merge full frozen branch tips sequentially in
   ascending ancestry order: an ancestor precedes its descendants. For divergent
   tips with no ancestry order, use ascending recorded commit time, breaking
   equal timestamps by OID, and retain the exact schedule. Ancestry takes
   precedence over clock skew. This deterministic scheduling gives neither
   side semantic priority. Do not privilege remote master, a recent branch or
   an old branch by merging it outside that schedule. Already-contained tips
   receive an explicit disposition instead of an invented conflict or empty
   merge. Do not rebase, squash, cherry-pick or flatten patches to evade a
   three-way merge.
4. **Perform each real three-way merge.** Use the actual common base and the
   accumulated and incoming histories, one tip at a time. Retain diagnostics,
   base OIDs, input OIDs and the resulting checkpoint OID. Do not use octopus,
   blanket ours/theirs strategies, `-Xours`, `-Xtheirs`, union resolution,
   automatic rerere acceptance, autostash or custom drivers that hide conflicts.
   Review merge attributes and drivers first. Unrelated history is an explicit
   exception requiring an intent decision, not an invented common base.
5. **Retain maximum conflict and intent evidence.** Before editing any unmerged
   path, retain its stage-1 base, stage-2 accumulated side and stage-3 incoming
   side, including absent stages, modes, OIDs, renames, deletes, symlinks and
   binary/type conflicts. Preserve the conflicted working representation with
   base-inclusive markers and complete Git diagnostics. Retain each branch's
   changes from its common base and its intent account, including clean overlaps.
   Preserve every real conflict; never reduce the conflict set by accepting a
   side, dropping a patch or hiding content. Maximum conflicts means maximum
   exposure and retention of real disagreements for intent review, not fabricated
   conflicts produced by damaging otherwise compatible code. Conflict evidence
   remains after final repair.
6. **Commit each merge checkpoint, then edit and fix.** Git cannot commit an
   unmerged index. After archiving all stages, stage preserved text conflict
   carriers and commit an explicitly provisional checkpoint with the merge
   ancestry intact. Binary/path/type conflicts require a manifest-backed carrier
   retaining all variants and an explicit open semantic decision; the carrier
   must not silently choose an implementation. Every non-contained tip gets
   its own committed checkpoint. Do not proceed with uncommitted working files.
   Inspect and edit the conflicts in view of the entire set's intent, then
   commit the resolutions and subsequent fixes on the spot. If structural
   repair is necessary to continue, record the minimum repair and keep the
   semantic decision open until the complete set has been reviewed. An archived
   variant, temporary worktree or patch file never replaces a local commit.
7. **Resolve the complete composition.** After the ascending pass, review every
   conflict and cleanly merged overlap against all inputs and callers. Record
   competing requirements, retained benefits, incompatible assumptions and final
   repairs. Retaining both text blocks does not establish both behaviours.
   Neither recency nor historical presence establishes correctness. Remove all
   provisional carriers and close every intent/conflict-manifest entry before
   publishing or claiming build success.
8. **Apply test non-standing and fix the result.** Tests older than 30 days have
   no standing as development guidance or merge-resolution authority. Do not
   revert intended code merely to satisfy them. Read current intent, use current
   evidence and exercise meaningful recent regressions. Compile all affected
   targets without source or test exclusions, inspect the composed production
   path, and fix causes rather than weakening checks. Preserve failures and the
   reason for their disposition. A passing test count is not completion.
9. **Commit and publish verified master.** Commit the integration report and all
   repairs. Verify no unresolved index stages, conflict markers or provisional
   carriers remain and every included tip is an ancestor or has an explicit
   equivalent/inapplicable disposition. Re-fetch before publication and account
   for intervening history with the same three-way method. Advance local master
   while preserving any new working files through immediate commits. Push master
   normally, never force-push over remote work, confirm the remote OID and retain
   recovery refs, conflict evidence, the schedule and the intent/disposition report.

### No shelving or Git stash

Never create an IDE shelf, run `git stash`, use `--autostash`, or park uncommitted
files in another worktree as a substitute for a commit. Recover any pre-existing
shelf belonging to the work being integrated into local commit history before
merging it; retain its original identity and all variants. Do not discard it or
create another shelf. Conflict archives and recovery refs supplement commits;
they never replace committing all non-ignored working files on the spot.

D3M is order-sensitive. "Unbiased" is an auditable absence of automatic side
preference, not a claim that merge order cannot affect conflicts. The result is
the repaired composition of intent, with the full conflict record preserved.
