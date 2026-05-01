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
