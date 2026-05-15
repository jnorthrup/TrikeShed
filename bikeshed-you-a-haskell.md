# bikeshed you a haskell

Intent: explain the TrikeShed `Series` / `Join` / mutable-series architecture in Haskell and categorical terms, then say exactly where that analogy is true, where it leaks on Kotlin/JVM/KMP, what the asymptotic and machine costs are, and why Graal JIT rewards some shapes and punishes others.

This is not a sales pitch for "functional style". It is an architectural map from the algebra actually present in the code to the runtime consequences actually paid by HotSpot/Graal/native backends.

## 1. The core object: `Series<T>` is not a collection, it is an index morphism

In code:

```kotlin
typealias MetaSeries<I, T> = Join<I, (I) -> T>
typealias Series<T> = MetaSeries<Int, T>
```

So a `Series<T>` is literally:

- a bound `a : Int`
- an accessor `b : Int -> T`

That is not "an array with methods".
That is a finite representable object: a shape plus an indexer.

Haskell translation:

```haskell
data Series t = Series
  { size  :: Int
  , index :: Int -> t
  }
```

This is nearest to:

- a finite representable functor with representation `Int` constrained by `size`
- a reader-like encoding over the index domain
- an extensional sequence view, not an intensional storage commitment

The key point:
`Series` says what observations are legal, not how storage is implemented.

That means the algebra is upstream of representation.
An `Array`, `ByteArray`, mmap window, parser view, decoded row, B-tree leaf, ring buffer slice, or synthetic λ-chain can all inhabit the same API if they can answer `(size, index)`.

This is the purity bubble you keep defending: semantics first, storage second.

## 2. Categorical reading

`Series` can be read through several categorical lenses.

### 2.1 Product encoding

`Join<A,B>` is a binary product.

```kotlin
interface Join<A, B> {
    val a: A
    val b: B
}
```

So `Series<T>` is the product:

```text
Int × (Int -> T)
```

That is a dependent-looking approximation to a representable container: a size plus a total indexing function, where callers are expected to stay in the legal domain `0 until size`.

In strict category language this is not a full dependent pair because Kotlin has no type-level proof that `b` is only consumed on the valid subset. The bound is dynamic, not in the type. So the true object is:

```text
(size, accessor) with an external law: accessor is only valid on [0, size)
```

That law matters, because JVM optimization only sees the law when it can inline through the call sites and recover bounds checks.

### 2.2 Functor

`α` is plain `fmap`.

```kotlin
inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }
```

Haskell:

```haskell
fmap :: (a -> b) -> Series a -> Series b
fmap f (Series n ix) = Series n (f . ix)
```

Laws, extensionally:

- identity: `xs α id == xs`
- composition: `xs α f α g == xs α (g . f)`

Operationally on JVM:
- every `α` adds one more lambda layer unless reified/materialized
- laws hold denotationally
- costs accumulate operationally if you build deep chains and never collapse them

That gap between denotation and cost is one of the main themes of this architecture.

### 2.3 Applicative / zippy structure

A representable container often admits zippy applicative structure because it has pointwise indexing over a shared shape. `Series` has the ingredients for that even where it is not explicitly packaged as an `Applicative` instance:

```text
pure x      ~ n j { x }   -- only if a shape/bound is supplied externally
(<*>) pointwise over indices
```

But Kotlin lacks the typeclass machinery to make this canonical, and `Series` uses dynamic size agreement rather than a statically carried shape witness. So the abstraction exists conceptually, not as first-class law-bearing syntax.

### 2.4 Comonadic intuition

There is also a weak comonadic flavor: a `Series` can be thought of as a context from which you can observe positions. But because the finite bound is dynamic and there is no lawful `duplicate :: Series a -> Series (Series a)` preserving a clean positional coalgebra without additional structure, this should be treated as analogy, not canon.

### 2.5 Representable functor, but weakened by runtime indices

The clean Haskell story would be:

```haskell
class Representable f where
  type Rep f
  tabulate :: (Rep f -> a) -> f a
  index    :: f a -> Rep f -> a
```

`Series` approximates this as:

- `tabulate = n j f`
- `index = get`

but the representation object is not just `Int`; it is "Int below the current bound". Since Kotlin cannot express finite index proofs like `Fin n`, the representable law is carried semantically, not syntactically.

That is why so much of the codebase oscillates between very beautiful algebra and very ugly bounds/runtime recovery work. The idea is representable. The host language is not.

## 3. Haskell comparison: what it is and what it is not

### 3.1 It is not `[]`

A Haskell list is:

```haskell
data [] a = [] | a : [a]
```

That is initial algebra / cons-cell recursion / spine-first structure.

`Series` is not that.

A `Series` is closer to:

```haskell
newtype VecView a = VecView (Int, Int -> a)
```

So:

- list = recursive structural data
- series = extensional observational data

A Haskell list gives cheap head/tail and expensive random access.
A `Series` gives constant-shape random indexing if the backing/indexer allows it, but can hide arbitrarily expensive access behind `Int -> T`.

This is why equational elegance alone is insufficient. `Series` is semantically uniform and operationally wildly non-uniform.

### 3.2 It is closer to `Vector` views, streams, and representable encodings

The nearest Haskell family resemblance is somewhere among:

- `vector` slices/views
- `Data.Array`/`Ix` indexing
- representable containers
- stream fusion producer nodes

But it still differs because TrikeShed chooses a *single algebraic surface* for both:

- finite materialized storage
- lazy synthetic views
- parsed/transcoded/index remapped projections
- mutable envelopes over immutable letters

Haskell usually gets away with richer law stories because GHC owns much stronger rewrite machinery and can fuse or specialize through typeclass dictionaries more aggressively than Kotlin/JVM can through arbitrary object lambdas.

### 3.3 `Join` is product, not tuple sugar

In Haskell, `(a,b)` is already a product.
Here `Join` is used intentionally as a named algebraic product whose `a`/`b` fields are semantically significant. That matters because `Series<T>` itself is a specific product, not just a class with random methods.

There is a design thesis here:

- the algebra is built from products and morphisms
- storage structures are downstream implementations of those products and morphisms
- conversion layers are a code smell when they destroy that algebraic continuity

That thesis is much closer to categorical programming than to ordinary Kotlin collection design.

## 4. Complexity model: denotational sameness, operational plurality

Because `Series` abstracts observation rather than representation, big-O depends entirely on the witness used for `b`.

### 4.1 Flat array-backed `Series`

Example:

```kotlin
count j { i -> buf[i] as T }
```

Complexity:

- index: O(1)
- map/α creation: O(1)
- map/α read: O(depth of unfused lambda chain)
- materialization to array/list: O(n)

Machine cost on JVM/Graal:

- best case: inlines to `aaload`/primitive load
- good locality, BCE possible
- branch behavior predictable
- escape analysis can sometimes kill wrapper allocation if the series itself does not escape

### 4.2 Recursive closure-backed mutable series

`RecursiveMutableSeries` mutates by replacing the whole indexing function with a new lambda referencing the old one.

That is persistent-update semantics expressed as a function tower.

Complexity after k updates:

- mutation creation: O(1) allocation per update
- read worst-case: O(k) because each lookup may walk all prior closures
- remove/insert/set create one more layer
- full materialization: O(nk) in the degenerate case if each lookup traverses k layers

Category story: pure and elegant.

JIT story: hostile.

### 4.3 Old closure-chain COW

The previous `CowSeriesHandle`/`COWSeriesBody` shape used raw series composition/closure capture as the letter body.

Operational effect:

- each mutation built a fresh immutable body
- the body pointed to previous body/accessor structure through lambdas
- mixed operations (`set`, `insert`, `removeAt`) produced a family of receiver classes at the same `Function1.invoke` site

Complexity:

- mutation: O(1) structurally, but alloc-heavy
- read after k updates: O(k) or worse depending on shape
- memory: chain depth proportional to mutation history

This was algebraically cute and machine-toxic.

### 4.4 New flat-array COW

The rewritten body now does:

- exact-sized `Array<Any?>`
- `b` is one stored lambda reading the array
- every mutation allocates a new exact-sized array and copies

Complexity:

- get: O(1)
- set: O(n) copy
- insert/removeAt: O(n) copy
- append: O(n) copy
- snapshot semantics: structural sharing at the body/reference level only, not backing-array sharing

This is the honest COW trade.
You pay the copy up front, not on every future read.

For immutable snapshot semantics under JVM JIT, that is vastly better than paying an unbounded interpretive tax on each access.

### 4.5 SeriesBuffer

`SeriesBuffer` is the general mutable floor for generic `T` on JVM.

Complexity:

- append: amortized O(1)
- set: O(1)
- insert/removeAt middle: O(n)
- clear: O(n) nulling for GC hygiene
- snapshot: O(1) to make a view, O(n) if materialized/copying externally

This is essentially the same complexity profile as `ArrayList`, but with less framework fat and much closer alignment to the `Series` algebra.

### 4.6 Primitive columnar series

`DoubleSeries`, `LongBackingSeries`, `IntSeries`, `FloatSeries` are strictly better than generic object arrays when the domain is primitive.

Complexity:

- get/set: O(1)
- append/insert/remove: O(n) copy in current implementation

But machine-level gain is critical:

- no boxing on storage
- no cast on read path
- tighter memory density
- better cache residency
- vectorization/autovector-friendly copy loops

Haskell comparison: this is where TrikeShed is effectively doing by-hand specialization that Haskell libraries often address through unboxed vectors and specialized instances.

## 5. Graal JIT canon: what shapes it likes

Graal does not care that a design is category-theoretically elegant.
It cares whether the IR becomes small, monomorphic, bounded, and memory-local.

The winning properties are:

1. monomorphic call sites
2. bounded inlining depth
3. scalar-replaceable temporary objects
4. contiguous memory
5. stable branch structure
6. analyzable bounds

Flat array-backed series satisfies most of that.
Closure towers satisfy almost none of it.

### 5.1 Ideal shape for Graal

The ideal generic `Series<T>` read path on JVM is:

```text
load handle.field
load body.field
load array.field
bounds-check
aaload
checkcast (often removable)
```

If the lambda is inlined, the abstraction cost disappears into ordinary array access.

That is what the new `COWSeriesBody` gives Graal a chance to do.

### 5.2 Why closure chains die

In the old design, each lambda captured prior state and delegated to prior `backing.b(i)`.
At bytecode level that means repeated calls to `Function1.invoke(Object)` on a growing family of synthetic classes.

Once multiple mutation forms coexist, the receiver profile at that site becomes polymorphic, then megamorphic.

Consequences:

- inlining stops
- escape analysis stops seeing through the chain
- bounds-check elimination stops because real indexing is hidden behind virtual calls
- every access becomes a ladder of indirect calls and branches
- profile pollution leaks into surrounding optimized code

This is the real "showstopper for Graal JIT" in the old COW architecture.
Not merely allocation count. Shape opacity.

### 5.3 Escape analysis limits here

EA/PEA can remove wrappers that are:

- created locally
- fully visible
- not stored into escaping objects

But if you allocate:

- a new body and store it into the handle
- a lambda and store it into the body
- a `PairJoin` and store it into the body

those objects escape by definition.

So for closure-based persistent updates, EA saves crumbs and loses the war.

### 5.4 Array copy is a feature, not a failure

People sometimes over-penalize O(n) copy because they reason only at asymptotic level.
For JIT reality, an `arraycopy` intrinsic is often a better trade than preserving a theoretically lazier O(1)-update functional object graph.

Why:

- `System.arraycopy` is intrinsified
- memory layout is contiguous
- prefetch/cache works
- later reads are cheap forever
- JIT sees stable code shape independent of mutation history

So in mutation+read workloads, "copy now, read flat later" often beats "update lazily, interpret forever".

This is especially true when the same series is read repeatedly after updates.

## 6. Haskell canon versus JVM canon

This is the real comparison.

### 6.1 In Haskell, elegant producers often get fused away

A Haskell programmer can write pipelines in a highly compositional style and rely on:

- rewrite rules
- stream fusion
- worker-wrapper transformation
- specialization
- unboxed vector internals

to recover low-level loops.

Kotlin/JVM does not provide that as a language-wide law-preserving optimizer.
It gives you:

- inlining for `inline` functions only
- JIT speculative inlining through dynamic profiles
- escape analysis only where object graphs stay visible
- no whole-program rewrite system for arbitrary collection algebra

So a Haskell-looking encoding on JVM is not innocent. It is a bet against the optimizer.
Many of those bets lose.

### 6.2 Denotational purity is cheap in Haskell, not necessarily on JVM

Haskell can often represent a pure transformation pipeline as a composition that later compiles into a tight loop.
On JVM, that same composition may survive as:

- multiple heap objects
- multiple lambdas
- multiple interface dispatches
- hidden bounds checks
- megamorphic call sites

Therefore TrikeShed needs a dual discipline:

1. preserve the algebraic API shape
2. choose reified/storage shapes that the backend can actually optimize

That is not a betrayal of the algebra. It is the only way to keep the algebra alive in a hostile host runtime.

## 7. The architecture as a stratified canon

The actual TrikeShed architecture, read charitably, already wants a layered canon.

### Layer A: pure extensional algebra

Objects:

- `Join<A,B>`
- `Series<T> = Int × (Int -> T)`
- `α`, slicing, indexing, combination

This layer is close to Haskell/category language.
It defines semantics independent of storage.

### Layer B: reification policy

Objects:

- `combine`
- `ReificationContext`
- `StaircaseSeries`
- bounded entrant flattening

This layer acknowledges that unrestricted composition destroys locality and JIT visibility.
So it imposes a machine-sensitive normalization policy.

That is basically a partial evaluator / fusion governor.
Not mathematically pure, but architecturally necessary.

### Layer C: concrete mutable/storage witnesses

Objects:

- `SeriesBuffer`
- primitive `*Series`
- `ChunkedMutableSeries`
- `CowSeriesHandle` with flat body
- `RingSeries`, `DequeSeries`, `SortedSeries`, etc.

This layer chooses concrete asymptotics and cache behavior.
The pure layer alone cannot do that.

### Layer D: domain embeddings

Examples elsewhere in the repo:

- parsing
- rows/cursors
- storage/index structures
- eBPF algebra per `PRELOAD.md`

The eBPF preload note shows the same overall pattern in another domain:

```text
pure algebra -> verifier -> JIT -> execute
```

For series/mutation, the analogous pattern is:

```text
pure extensional algebra -> reification policy -> storage witness -> execution profile
```

That is the same TrikeShed move.
First define the algebraic contract. Then explicitly choose where machine reality enters.

## 8. Complexity table by representation

| representation | get | set | append | insert/remove middle | memory locality | Graal profile |
|---|---:|---:|---:|---:|---|---|
| raw array-backed view | O(1) | N/A | N/A | N/A | excellent | ideal |
| `SeriesBuffer<T>` | O(1) | O(1) | amortized O(1) | O(n) | excellent | ideal-to-good |
| primitive columnar series | O(1) | O(1) | O(n) current impl | O(n) | excellent | best |
| new flat-array COW | O(1) | O(n) | O(n) | O(n) | excellent after mutation | very good |
| `ChunkedMutableSeries<T>` | O(1) with chunk lookup | O(chunk + outer rebuild cost) | amortized O(1) append-ish | O(chunk/local rebuild) but current outer rebuilding adds cost | mixed | decent if flattened, weaker than flat arrays |
| `RecursiveMutableSeries<T>` | O(depth) | O(1) new layer | O(1) new layer | O(1) new layer | poor | bad |
| old closure-chain COW | O(depth) / worse | O(1) structural alloc | O(1) structural alloc | O(1) structural alloc | poor | worst |
| `combine` staircase view | O(log k) or small-linear on child count + child access | N/A | N/A | N/A | fragmented | okay only with bounded depth |

Important nuance: `ChunkedMutableSeries` is asymptotically trying to move toward a finger-tree / chunk-vector intuition, but its current implementation still rebuilds chunk-indexing series functionally, so it has not fully cashed in the theoretical win.

## 9. Where the category story is strongest

The category/Haskell story is strongest when you talk about:

- extensional equality
- compositional view construction
- product decomposition via `Join`
- functorial mapping via `α`
- representation independence
- law-first API design

It is weaker when you try to pretend:

- dynamic bound checks are type-level proofs
- arbitrary `Int -> T` chains are free
- JVM lambdas are equivalent to GHC core simplifications
- persistent update by function layering is an acceptable substitute for data-structure engineering

Those are the points where math poetry must yield to machine facts.

## 10. Where TrikeShed is more radical than standard Kotlin

Standard Kotlin collections begin from concrete storage and layer APIs on top.

TrikeShed begins from algebraic observation and lets storage enter later.

That has real advantages:

- one shared semantic surface across parsers, views, arrays, buffers, mmap-ish structures
- easy expression of α-conversion and index remapping
- reduced pressure to collapse everything to `List`
- direct composition with `Join`, `Cursor`, and related algebraic forms

It also has real danger:

- if you do not reify aggressively at the right boundaries, runtime cost becomes invisible and explosive
- if you worship the extensional interface too much, you build lambda cathedrals that the JIT cannot enter

So the mature reading is not "Series is better than arrays".
It is:

`Series` is the semantic canopy under which multiple storage witnesses can live, and correctness requires keeping the canopy pure while performance requires choosing the right witness early enough.

## 11. Direct comparison to Haskell abstractions

### `Series` vs `Functor`

Yes, directly analogous through `α`.
But Haskell's `Functor` lawfulness is static and ecosystem-canonical; TrikeShed's is conventional and encoded through functions rather than a typeclass instance.

### `Series` vs representable functor

Very close in spirit.
The main gap is absence of a typed finite index like `Fin n`.
If TrikeShed had a practical `Fin<size>` witness, the correspondence would become much tighter.

### `Join` vs product type

Directly analogous.
`Join` is a named product with semantic intent.

### Mutable series wrappers vs state monad / ST

No, not really.
`JournalSeries`, `GuardSeries`, `CowSeriesHandle`, `SeriesBuffer` are operational wrappers around mutable behavior, not a principled encoding of linear/state-threaded effect semantics.
The resemblance is metaphorical at best.

### `combine` vs free monoid concatenation

At the semantic layer, yes: concatenation of finite indexed sequences.
At the operational layer, absolutely not free: every concatenation shape changes lookup topology and JIT visibility.

### eBPF builder pipeline vs category-theoretic staging

Closer analogy:

- algebra term construction
- verification as law/barrier phase
- JIT as interpretation/compilation into a lower category of machine programs

That staged architecture is clearer and more honest than the old closure-chain mutable series design because the barrier is explicit.
The important lesson carries back: mutation/view layers also need explicit reification barriers.

## 12. Graal consequences by abstraction choice

### Best for Graal

1. primitive arrays
2. object arrays with monomorphic accessor lambda
3. exact-sized immutable array bodies for snapshot/COW
4. mutable buffers with direct fields and `copyInto`
5. bounded staircase/reified views

### Worst for Graal

1. unbounded `Function1` delegation chains
2. mixed synthetic lambda classes at a hot invoke site
3. interfaces whose concrete shape count grows with mutation history
4. representations where each read replays prior edits
5. designs that hide array indices behind multiple virtual boundaries

### Practical law

For JVM/Kotlin:

- preserve algebraic purity at API boundaries
- reify before call-site polymorphism explodes
- prefer contiguous memory to elegant closure persistence in hot paths
- use primitive specialization whenever the element type allows it
- treat `arraycopy` as a friend
- treat megamorphic `invoke` as a structural bug, not a micro-optimization target

## 13. What this says about the "pure code among concurrency heavy concepts" thesis

The thesis survives.
But it survives only if purity is assigned to the right layer.

Good purity:

- `Series` as observational algebra
- `Join`/`Twin` as product forms
- domain APIs expressed without leaking JVM collections
- snapshots as immutable values

Bad pseudo-purity:

- encoding mutation history as ever-deeper function towers
- assuming asymptotic elegance beats contiguous memory
- hiding machine costs behind extensional sameness

The right architecture is:

- algebra remains pure
- lifecycle/effects are explicit envelopes
- reification points are first-class and intentional
- storage witnesses are chosen for backend reality, not aesthetic symmetry

That is consistent with the broader TrikeShed thesis, not opposed to it.

## 14. Bottom line

If you "bikeshed it into Haskell", the canonical reading is:

- `Join` is product
- `Series` is a finite representable-ish functor encoded as `Int × (Int -> T)`
- `α` is `fmap`
- `combine` is concatenative composition over indexed observables
- mutable series wrappers are operational interpreters over that algebra
- reification layers are compulsory because Kotlin/JVM is not GHC

If you "bikeshed it into Graal", the canonical reading is harsher:

- arrays and primitive columns are truth
- exact-sized immutable array bodies are acceptable truth for snapshot semantics
- chunking is conditional truth
- closure chains are lies the optimizer eventually exposes

So the mature canon is neither "just write Haskell in Kotlin" nor "just use ArrayList".
It is:

1. keep the algebraic surface as the source of truth
2. preserve representation independence where it matters semantically
3. collapse to monomorphic contiguous witnesses where it matters operationally
4. make reification barriers explicit, because JITs optimize shapes, not intentions

That is the architecture.
