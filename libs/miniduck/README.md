# libs/miniduck

Block-first columnar storage engine for TrikeShed.

## What It Is

MiniDuck is a DuckDB-style columnar storage and query engine built on TrikeShed's
kernel algebra (Series, Join, Twin, Cursor, `j` infix).  It provides:

- A sealed-row type taxonomy (`MiniRowVec`) with lazy child expansion
- Block storage (`BlockRowVec`: mutable append -> seal -> read-many)
- NDJSON block codec (`MiniDuckBlockCodec`) for serialization
- Query plan algebra (`QueryPlan` sealed tree: Scan/Filter/Project/Order/Limit)
- SQL frontend (`SqlToMiniDuck`, `ExpressionCompiler`) over kursive SQL ASTs
- Execution layer (`ExecutionContext`, `TableSource`, `PlanNode` hierarchy)
- Tablespace/region architecture with WAL-backed BlockStore
- MVCC-ready LSMR merge tree for sorted-run compaction
- Object store adapters (S3, GCS, Alibaba OSS) with RowVec shells
- Unified codec (`UnifyCodec`) for JSON/YAML/CBOR with CoroutineContext KEYS/ELEMENTS
- NARS manifold concepts (attention bag, BudgetCoord, Hamming hypersphere)
- ML training harness (KernelTransformer, stochastic indicators)

## Source Layout

### commonMain
```
miniduck/
  RowVecFamily.kt          -- MiniRowVec sealed base, LazyChildRowVec, WrappedRowVec, toRowVec()
  RowVecFamilies.kt        -- DocRowVec, ViewRowVec, BlobRowVec, JsonRowVec, YamlRowVec,
                              CsvRowVec, ObjectStoreRowVec (GcsRowVec/S3RowVec/AlibabaRowVec)
  BlockRowVec.kt           -- BlockRowVec: mutable->sealed chunky block (DuckDB-style)
  MiniCursor.kt            -- typealias MiniCursor = Cursor, emptyMiniCursor()
  MiniDuckBlockCodec.kt    -- NDJSON block encode/decode for BlockRowVec
  Predicate.kt             -- sealed Predicate tree (Eq/Gt/Lt/And/Or/Not/Between/InList),
                              ColumnRef DSL, compareKeys()
  QueryPlan.kt             -- sealed QueryPlan tree (Scan/Filter/Project/Order/Limit),
                              infix plan builders, execute()
  JsonProjection.kt        -- MiniCursor.toJson(), rowToJson(), valueToJson()
  JsonEscape.kt            -- internal escapeJson()
  CursorOps.kt             -- Cursor import aliases (placeholder)
  SeriesOf.kt              -- identity passthrough helpers
  ManifoldConcept.kt       -- ManifoldConcept: Hamming hypersphere F_2^63,
                              BudgetCoord, angularWalk, decay/reinforce
  BudgetCoord.kt           -- BudgetCoord: 30-bit packed P/D/Q radial coordinate
  NarsBag.kt               -- NarsBag: manifold-shaped attention bag, Timeline, totalRecall
  Trainer.kt               -- KernelStochasticTrainer, NoOpTrainer, executeKernelOptimizingHarness
  KernelTransformer.kt     -- KernelFeatureTransformer, ExampleKernelTransformer
  KernelIndicators.kt      -- logReturn, sma, rollingStd projections
  HarnessStochasticCache.kt-- draw-through stochastic cache for harness
  SqlExecutor.kt           -- SQL executor imports (stub)
  RunBlocking.kt           -- expect fun runBlockingCommon()
  unify/
    UnifyCodec.kt          -- UnifyCodec: unified JSON/YAML/CBOR codec with
                              CoroutineContext KEYS/ELEMENTS, SupervisorJob fanout,
                              Format/Direction/UnifyResult types
  columnar/
    ColumnSchema.kt        -- ColumnSchema data class
    ColumnType.kt          -- ColumnType enum (Long/Double/Boolean)
    GapDetector.kt         -- GapDetector: finds gaps in sorted kline streams
    SpanMatcher.kt         -- SpanMatcher: overlapping time span detection
    IndexPlugin.kt         -- IndexPlugin interface
    IndexPluginRegistry.kt -- resolves plugin name -> IndexPlugin
    IndexCursor.kt         -- IndexCursor interface (seek/next/current)
    Lz4Index.kt            -- Lz4Index plugin (TODO stub)
    ZranIndex.kt           -- ZranIndex: zran-style block index (TODO stub)
    IsamVolume.kt          -- IsamVolume: ISAM directory of compressed blocks (TODO stub)
    IsamCursor.kt          -- IsamCursor: random-access cursor over IsamVolume (TODO stub)
  plan/
    PlanNodes.kt           -- PlanNode, TableScanNode, FilterNode, ProjectNode, LimitNode
  exec/
    ExecutionContext.kt    -- TableSource, ExecutionContext, Cursor, RowAccessor interfaces
    InMemoryTableSource.kt -- in-memory TableSource for tests/examples
    LsmrTableSource.kt     -- commonMain stub (JVM-only)
  query/
    CursorJoins.kt         -- groupBy(), hashJoin(), join() infix for MiniCursor
    Agg.kt                 -- sealed Agg (Count/Sum/Avg/Min/Max) + AggAccumulator
  sql/
    SqlToMiniDuck.kt       -- SQL SELECT -> PlanNode transform (PlannerConfig, PlannerContext)
    ExpressionCompiler.kt  -- compileExpression/compilePredicate for SQL AST
  schema/
    SchemaManager.kt       -- SchemaManager interface (suspend + sync bridges)
    InMemorySchemaManager.kt -- in-memory SchemaManager
    LsmrSchemaManager.kt   -- LSMR-backed SchemaManager
  lsmr/
    LsmrMergeTree.kt       -- LsmrMergeTree: L0/L1/L2 sorted runs, flush, merge, scan
  tablespace/
    Tablespace.kt          -- Tablespace + Region: named collection of regions, scan, discoverSchema
    BlockStore.kt          -- BlockStore SPI + InMemoryBlockStore
    InMemoryBlockWal.kt    -- WAL operations (Put/Remove), WalEntry, replay, compact
  objectstore/
    ObjectStoreAdapter.kt  -- ObjectStoreAdapter interface, provider-specific data classes
```

### Platform source sets
- `jvmMain/` -- LsmrTableSource (JVM), MiniDuckBlockFiles, RunBlocking.jvm, object store adapters (S3/GCS/Alibaba)
- `jsMain/`  -- LsmrTableSource stub, RunBlocking.js
- `wasmJsMain/` -- RunBlocking.wasm
- `posixMain/` -- RunBlocking.native

### Tests
- `commonTest/` -- block algebra, codec roundtrip, cursor ops, query (join/aggregate), harness, columnar (gap/span/zran/lz4/isam), tablespace, LSMR
- `jvmTest/` -- block I/O, codec JVM, blob, unicode, Couch compatibility, object store adapters, SQL integration, LSMR integration

## Key/Element/Reactor Status

| Component | Key (Identity) | Element (Lifecycle) | Reactor | Status |
|---|---|---|---|---|
| UnifyCodec | JsonCodecKey, YamlCodecKey, CborCodecKey, ActiveFormat, WamPc | JsonCodecService, YamlCodecService, CborCodecService, WamAcc, WamEnv | SupervisorJob per fanout | Implemented |
| KernelStochasticTrainer | -- | ElementState lifecycle in NoOpTrainer | Structured concurrency (coroutineScope) | Implemented (NoOp) |
| UserspaceBtrfsBuffer | Companion Key | AsyncContextElement (CREATED->OPEN->DRAINING->CLOSED) | -- | In tiny-btrfs lib |

## Architectural Position

MiniDuck is not a database. It is a query engine that returns analytical cursors.
Where DuckDB compiles SQL to a parallelized execution DAG over typed column vectors
(C++ core, SIMD, language bindings), MiniDuck composes projections over `Cursor = Series<RowVec>`
using the Join algebra and delivers the cursor to the caller for further composition.

The benefits over a hybrid/immature JDBC approach (as current DuckDB Java bindings):
- Cursor is a dataframe-shaped grid that looks the same whether row-aligned or column-aligned
- No JDBC adapter layer, no ResultSet materialization, no driver version coupling
- RowVec is `size j { i -> ... }` — a pair of size and function. Reordering, projecting,
  and transposing cost the size of the IntArray permutation, never the data itself.
- Every target (JVM, JS, WASM, native) gets the same cursor-shaped result, even if
  autovectorization depth varies by platform. Autovec holds water where it can;
  tailorkor/native is added only when a measured speedup justifies the path.

## Capacity Planning: Debt vs Payback

Each capability below carries implementation debt and a payback trigger. Review each
opportunistically — when a workload demonstrates a clear win in context, move it from
consideration to implementation. Do not build all at once; disproportionate wins come
from reserved consideration, not from throwing resources at every feature simultaneously.

| Capability | Debt | Payback Trigger | Review Criterion |
|---|---|---|---|
| **Zone maps (min/max per block)** | Low — ~20 lines per column type at write | Full-scan with predicates | Any scan that reads blocks with no matching rows |
| **Bloom filters** | Low — bit array + hash | Equality predicates on large datasets | Hash join build cost on negative lookups |
| **Dictionary encoding (strings)** | Medium — map string→int at block write | High-cardinality string columns repeated | Storage shrinks, decode cost < scan savings |
| **RLE / bitpacking** | Medium — encode/decode per type | Numeric columns with locality or small ranges | Working set exceeds L1/L2 cache |
| **FSST (string compression)** | High — symbol table build + decode | String-heavy workloads (logs, JSON) | Only after RLE/dict prove the pattern |
| **ART indexes** | Medium — radix tree over column values | Point lookups on string/numeric keys | Port from `../columnar/` as planned |
| **Additional joins (Perfect, IEJoin, ASOF, Range)** | Medium-High — algorithm + cardinality estimator | Queries that blow up on hash join | Perfect join for PK/FK; ASOF for time-series |
| **Cost model + optimizer** | High — needs cardinality stats | ≥2 indexes + ≥2 join strategies | Zone maps ARE the first stats. Cannot exist without them. |
| **MVCC** | High — version chain + conflict resolution | Concurrent readers + writers on same blocks | `MvccBlockStore` stubs exist. Needs WAL surface. |
| **Full SQL surface** | Medium — parser is mechanical, optimizer is not | ORM / ad-hoc query surface | Parser is easy. Plan mapping is the hard part. |

### Staging Priority

1. **Zone maps → Bloom → Dictionary** — storage primitives, no transaction dependency,
   pay back on every read. Zone maps feed the cost model directly.
2. **Join strategies + cardinality** — forces the cost model to exist at a crude level.
   Use zone map span ratios as primitive cardinality estimates.
3. **ART indexes** — completes the pruning surface alongside zone maps and Bloom filters.
4. **RLE/bitpacking → FSST** — storage compression, deferred until scan patterns justify it.
5. **MVCC → full CBO** — high-ticket, only when concurrency and query complexity demand it.

### Guardrail

Every encoding, index, and join strategy must be expressible as a cursor transform.
Zone maps are a filter on Series. Bloom filters are a probe. Dictionary is a map projection.
If it cannot be written as a cursor operation, it does not belong in MiniDuck.
RowVec algebra (`size j { i -> ... }`) must not change — all compression and indexing
live behind the same indirection surface.

## Dependencies

- `borg.trikeshed.cursor` -- Cursor, RowVec, ColumnMeta
- `borg.trikeshed.lib` -- Series, Join, Twin, j infix, ReifiedSplitSeries2, Tensor
- `borg.trikeshed.isam.meta` -- IOMemento
- `borg.trikeshed.parse.json` -- JsonParser, JsElement, JsPath
- `borg.trikeshed.parse.yaml` -- YamlParser, YamlDocument
- `borg.trikeshed.parse.kursive.sql` -- SQL AST (SelectStmt, Expr, etc.)
- `borg.trikeshed.collections.associative` -- Cbor, Item
- `borg.trikeshed.common` -- TypeEvidence, toLongSeries, toRowVec
- `borg.trikeshed.context` -- ElementState, AsyncContextElement
- `borg.trikeshed.indicator` -- Stochastic
- `borg.trikeshed.userspace.database` -- LsmrDatabase (for LsmrSchemaManager)
- kotlinx.coroutines -- CoroutineScope, SupervisorJob, async, Mutex
