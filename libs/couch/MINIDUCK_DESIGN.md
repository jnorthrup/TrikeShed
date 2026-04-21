# MiniDuck Design

Goal: define the small DuckDB-like query/storage layer for `libs/couch` without slipping back into builder-style SQL or server-centric assumptions.

Design stance
- algebra first: MiniDuck is composed from `Join`, `Series`, Tensor snapshots, and Manifold projections
- the true starting point is specialization of `RowVec` families, not secondary indexing
- analytical/storage state should be modeled as child blackboard constructs hanging off parent `RowVec` families
- chunky block execution should mirror DuckDB more closely than ad hoc row-at-a-time traversal
- `Series<RowVec>` is the canonical query surface
- infix/compositional DSL is acceptable; procedural builders are not
- CouchDB-compatible endpoints remain the compatibility shell
- HTX/Reactor are transport/runtime concerns, not query-language concerns

## 1. Core mental model

MiniDuck is not a full SQL engine.
It is an algebra of query/storage transforms built from:
- `Join<A, B>` as the primitive semantic pairing
- `Series<T>` as the primitive lazy/indexed sequence
- Tensor snapshots for dense lowered numeric execution
- Manifold charts/atlases for moving between semantic and lowered coordinate systems

Three layers:
1. RowVec-family layer: specialize parent/child `RowVec` families for docs, views, blobs, JSON, YAML, and expansion shells
2. Block layer: sealed chunky blocks of `Series<RowVec>` and child blackboard state
3. Algebra layer: infix plans composed from Join/Series/Tensor/Manifold over those families and blocks
4. Compatibility layer: CouchDB and RelaxFactory-shaped endpoints over the same algebra

## 2. Canonical data types

Use existing TrikeShed primitives wherever possible, but tighten Tensor around the algebra the user asked for:
- `ColumnMeta = Join<String, TypeMemento>`
- `RowVec = Series2<Any?, () -> ColumnMeta>` as the parent row abstraction
- `Cursor = Series<RowVec>`
- `Series<T> = Join<Int, (Int) -> T>`
- `typealias Shape = Series<Int>`
- `typealias Tensor<T> = Join<Shape, (Shape) -> T>`
- higher-order tensor transforms are then naturally `Join<Shape, (Shape) -> Tensor<T>>`
- `Coordinates`, `Chart`, `Atlas`, `Manifold` remain the semantic/lowered projection machinery

MiniDuck-specific logical types should stay thin:

```kotlin
typealias Shape = Series<Int>
typealias Tensor<T> = Join<Shape, (Shape) -> T>

interface MiniDuck {
    suspend fun scan(source: RelationRef): Cursor
    suspend fun execute(query: QueryPlan): Cursor
    suspend fun tensor(query: QueryPlan): Tensor<Double>
}

data class RelationRef(
    val database: String,
    val name: String,
    val kind: RelationKind,
)

enum class RelationKind {
    DOCS,
    ALL_DOCS,
    VIEW,
    INDEX,
    SEGMENT,
}
```

The engine should not invent a parallel row model.
Everything normalizes into `Cursor`, then may lower into Tensor/Manifold views for optimized execution.

### Hierarchical RowVec stance

`RowVec` should be treated as the parent abstraction for hierarchical records, not just as a flat row.
That means a row may expose a lazy `.child` hierarchy for nested structures such as:
- arrays/lists
- structs/objects
- exploded or expanded document subtrees
- JSON/YAML tree projections
- blob-backed deferred payload effects
- view rows that defer materialization of nested payloads

Design consequences:
- a parent `RowVec` may legitimately have zero visible scalar columns while still owning lazy children
- zero-length parent records are valid if they act as carriers for deferred nested expansions
- child rows should only materialize when traversal/projection actually demands them
- Duck-style nested expanses are matched by walking the row hierarchy rather than eagerly flattening everything into one rectangular row
- the parent row plus its descendants should be understood as a blackboard family, where children carry derived, blob, parse-tree, or expansion effects without forcing flattening at the parent

This lets MiniDuck model nested document structure, lazy expansion, sparse/empty parents, and JSON/YAML/blob effects without abandoning the `Cursor = Series<RowVec>` surface.

### RowVec family specialization first

Before designing indexes, MiniDuck should define specialized `RowVec` families.
Examples:
- `DocRowVec`: visible scalar doc fields + child families for nested objects/arrays
- `JsonRowVec`: parse-tree/projection family for JSON blobs
- `YamlRowVec`: parse-tree/projection family for YAML blobs
- `BlobRowVec`: zero-length or sparse parent with lazy content/metadata/effect children
- `ViewRowVec`: Couch view-style key/value/doc shell with lazy child payload expansion
- `BlockRowVec`: parent record representing a sealed analytical/storage block

These families are the feature that makes later Duck-like expanses possible.
Only after they exist should we talk about derived indexes or acceleration structures.

## 8. Chunk/block model

DuckDB thinks in chunks/blocks, so MiniDuck should too.
Instead of treating NDJSON as a primary lazy-index problem, we should treat storage and analytics as sealed blocks composed of `RowVec` families.

Proposed block abstraction:
- a block is a parent blackboard construct
- its visible rows are `Series<RowVec>`
- its children hold:
  - doc families
  - JSON/YAML/blob specializations
  - derived view/group summaries
  - optional lowered tensor/manifold projections

Properties:
- append builds a mutable current block
- seal produces an immutable read-many analytical/storage block
- scans operate blockwise first, rowwise second
- expansion walks child families lazily inside a block
- indexes are derived children of sealed blocks, not the starting abstraction

This is closer to DuckDB’s chunky execution model while keeping nested document effects intact.

## 9. Query algebra, not builder SQL

Acceptable surface:

```kotlin
val q =
    (db("acme") docs "vehicles")
        whereKey eq "vw"
        orderBy "created_at"
        limit 10
```

or:

```kotlin
val q =
    (db("acme") relation "vehicles")
        filter (col("brand") eqv "vw")
        then project (cols("_id", "brand", "model"))
        then groupBy (cols("brand"))
```

The deeper algebra should be understood as:
- `Join` pairs semantics with structure (`column j value`, `chart j coordinates`, `key j aggregate`)
- `Series` supplies lazy indexed collections of rows, columns, predicates, and plans
- Tensor is now explicitly `Join<Shape, (Shape) -> T>` with `Shape = Series<Int>`
- higher-order tensor lifting/composition becomes `Join<Shape, (Shape) -> Tensor<T>>`
- Manifold supplies chart transitions between:
  - document space
  - row/column space
  - key/range space
  - tensor space

Not acceptable:

```kotlin
sql {
    select("brand")
    from("vehicles")
    where(eq("brand", "vw"))
}
```

The design target is an infix-friendly, immutable query plan.

## 4. Suggested query plan model

```kotlin
sealed interface QueryPlan {
    val source: RelationRef
}

data class ScanPlan(
    override val source: RelationRef,
) : QueryPlan

data class FilterPlan(
    override val source: RelationRef,
    val upstream: QueryPlan,
    val predicate: Predicate,
) : QueryPlan

data class ProjectPlan(
    override val source: RelationRef,
    val upstream: QueryPlan,
    val projection: Projection,
) : QueryPlan

data class OrderPlan(
    override val source: RelationRef,
    val upstream: QueryPlan,
    val ordering: Ordering,
) : QueryPlan

data class GroupPlan(
    override val source: RelationRef,
    val upstream: QueryPlan,
    val grouping: Grouping,
    val aggregates: List<AggregateSpec>,
) : QueryPlan

data class LimitPlan(
    override val source: RelationRef,
    val upstream: QueryPlan,
    val limit: Int,
) : QueryPlan
```

This keeps the query layer:
- serializable in spirit
- inspectable for optimization
- easy to lower into Couch view params or in-memory cursor transforms

## 5. Predicate and projection model

```kotlin
sealed interface Predicate

data class Eq(val column: String, val value: Any?) : Predicate

data class Gt(val column: String, val value: Comparable<*>) : Predicate

data class Lt(val column: String, val value: Comparable<*>) : Predicate

data class Between(val column: String, val lower: Any?, val upper: Any?) : Predicate

data class InList(val column: String, val values: List<Any?>) : Predicate

data class And(val left: Predicate, val right: Predicate) : Predicate

data class Or(val left: Predicate, val right: Predicate) : Predicate

data class Not(val inner: Predicate) : Predicate
```

Projection should be structural rather than stringly-typed SQL snippets:

```kotlin
data class Projection(
    val columns: List<String>,
)
```

Ordering and grouping should likewise be explicit data.

## 6. Where MiniDuck meets Couch compatibility

MiniDuck does not replace Couch APIs.
It backs them.

Mapping:
- `_all_docs` -> direct index/range scan plan
- `_design/{ddoc}/_view/{view}` -> named relation or cached grouped plan
- ad hoc in-process query -> direct `QueryPlan`
- RelaxFactory-style `@View` service invocation -> compile annotation metadata to a `RelationRef + QueryPlan + HTTP params`

This means the same lowerings can feed:
- Couch HTTP response shaping
- RelaxFactory parity services
- local query execution

## 7. Storage assumptions

MiniDuck reads from snapshot + segment state, not directly from the WAL unless necessary.

Expected storage tiers:
1. active memtable / mutable doc index
2. append-only WAL for durability and replay
3. immutable NDJSON segments / snapshots
4. derived view/index artifacts

MiniDuck query execution should prefer:
- materialized views when exact and fresh enough
- snapshot segments for wide scans
- in-memory memtable merge for newest writes

## 8. Execution pipeline

A practical execution path:
1. normalize DSL/infix surface into `QueryPlan`
2. analyze whether it can lower to a Couch-style range/view query directly
3. if yes, route through view/index path
4. if not, perform composed cursor transforms over merged storage snapshots
5. shape back into `Cursor`

Minimal optimizer rules worth having:
- push filter before projection where safe
- fuse consecutive projections
- collapse repeated limits to min(limit)
- lower key/startkey/endkey/group/group_level into native Couch-style parameters when source is a design view

## 9. Invariants

MiniDuck must preserve:
- deterministic column metadata for each resulting cursor
- stable `_id`, `_rev`, and key/value/doc row semantics when emulating Couch view rows
- no hidden mutable builder state in the query surface
- ability to serialize/inspect plans for debugging and transport

## 10. First GREEN slices

1. Define `RelationRef`, `QueryPlan`, `Predicate`, `Projection`, `Ordering`, `Grouping`, `AggregateSpec`
2. Define infix DSL wrappers that only build plans
3. Lower the simplest plan (`scan -> filter -> limit`) to in-memory cursor transforms
4. Add lowering from `group/group_level` to Couch view parameter model
5. Add compatibility shims so RelaxFactory-style compiled services can emit MiniDuck plans instead of opaque strings

## 11. Non-goals

Not in first cut:
- full SQL parser
- joins across arbitrary relations
- cost-based optimizer
- expression codegen
- builder-style mutable query DSL

## 12. Recommended file targets

When implementation starts, prefer:
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/QueryPlan.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/Predicate.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/RelationRef.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/minidsl/InfixMiniDsl.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/MiniDuck.kt`

## 13. One-sentence summary

MiniDuck is a Series/Join-centric, infix-composed query algebra over WAL-backed Couch-like storage, not a builder-pattern SQL toy and not a standalone SQL server.
