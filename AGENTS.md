# AGENTS.md

This repository is built around a small algebraic core. Prefer these shapes over ad hoc builders, parser-first designs, or SQL-style DSLs.

## 1) Core algebra

```kotlin
interface Join<A, B> {
    val a: A
    val b: B
}

inline infix fun <A, B> A.j(b: B): Join<A, B>

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>
```

Read this as:
- Join = base binary composition
- Twin = same-typed Join
- Series = size plus index function
- `j` = constructor grammar
- `α`-style transforms = lazy projection, not eager materialization

Design bias:
1. composition over inheritance
2. projections and ranges over mutable loops
3. explicit algebra over opaque helpers
4. lazy views first; materialization later
5. typealiases compress semantics, they do not replace meaning

## 2) Cursor algebra

```kotlin
typealias RowVec = Series2<Any, () -> RecordMeta>
typealias Cursor = Series<RowVec>
```

Treat Cursor as a composed row-view algebra, not a table engine.

Rules:
- prefer projection over mutation
- preserve metadata through transforms
- keep range selection as composition
- widen and combine explicitly
- keep cursor transforms pure when possible

## 3) JSON scan / path algebra

Path/index abstractions should stay cheap, sliceable, and compositional.

- index first, reify later
- avoid reflection-driven object walking as the primary model
- keep path selection algebraic

## 4) Userspace async context

Effects live at the userspace boundary and should be modeled explicitly.

Ground truths:
- async context keys are singleton identity objects
- lifecycle is forward-only
- elements expose key, lifecycleState, fanoutSubscribers, open/drain/close
- fanout should be structured concurrency, not callback soup

Prefer explicit state and routing identity over hidden ambient behavior.

## 5) MiniDuck / couch north star

For MiniDuck and related couch work, start rowvec/block-first, not SQL/index-first.

Preferred model:
- RowVec family specialization
- chunky block storage/execution
- many-reader / one-writer sealing
- derived views/indexes later
- tensor lowering only as an optimization target

Important rules:
- one writer owns the mutable working block
- readers only see sealed immutable blocks
- sealing is the synchronization boundary
- avoid row-level locking for analytic paths
- scans are block-first, row-second
- derived summaries/indexes hang off sealed blocks
- zero-length parent rows are valid if they carry deferred children
- do not eagerly flatten parse trees or blobs into rectangular tables
- preserve provenance/evidence/overlays through child expansion
- treat NDJSON as serialized block body, not the core abstraction

Useful families include:
- DocRowVec
- ViewRowVec
- JsonRowVec
- YamlRowVec
- BlobRowVec
- BlockRowVec

Semantic center:
- Join
- Series
- RowVec
- Cursor = Series<RowVec>

Not the semantic center:
- builder-style SQL DSL
- eager DataFrame columns
- tensor as the primary meaning

## 6) DSL shape

Infix composition is good; procedural builders are not.

Prefer algebraic composition like:
- cursor/filter/project/order/join composition over Series<RowVec>

Avoid directions like:
- `sql { select(...).where(...).join(...) }`

## 7) KMP / module discipline

For common code:
- keep `commonMain` and `commonTest` usable across targets where practical
- avoid JVM-only APIs in common code
- move platform-specific I/O into platform source sets
- keep adapters and tests in common when possible
- for this path, use internal `Json.kt` and `yaml.kt` helpers from `commonMain`; do not introduce Kotlin serialization here

For `libs/couch` specifically:
- `libs/couch` should be subsumed by `/TrikeShed` DRY precedence
- remove overlap instead of duplicating shared algebra into `dreamer-kmm` or keeping parallel `libs/couch` shims
- prefer canonical root TrikeShed algebra/types over local duplicates
- standalone `libs/couch` must still build and publish cleanly via composite/published dependencies, without shadowing or forking canonical core types

## 8) How to change code here

When implementing changes:
- add the smallest test that pins the desired algebra or behavior
- make the minimum code change to pass it
- do not refactor unrelated code
- keep edits surgical
- preserve existing style unless the change requires otherwise

SupervisorTask / scoped-exposure rule:
- when delegating or reviewing with SupervisorTask, expose only the files, symbols, and line ranges required for the task
- prefer narrow snippets and exact paths over whole-file dumps or repo-wide context
- do not include unrelated code paths, tests, or generated sources unless they are directly needed
- if a smaller slice can answer the question, use the smaller slice
- keep the supervised context minimal so the task stays focused and does not drift

Good verification targets:
- child-family preservation
- sealed-block write refusal
- block round-trip I/O
- scan/projection behavior
- lifecycle boundary behavior

## 9) What to avoid

- parser-first redesigns
- lazy secondary indexes as the starting point
- tensor-first abstractions
- flat-table assumptions
- eager child flattening
- hidden mutable global state
- broad refactors that are not directly required

## 10) One-line summary

Build the block algebra first.
Let rows expose lazy children.
Seal for readers.
Derive views and indexes afterward.
Lower to tensors only when needed.


	* /Trikeshed DRY PRECEDENCE OVER -> libs/**  <-- DRY  removes overlap from dreamer-kmm 
