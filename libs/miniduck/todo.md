# libs/miniduck -- todo.md

## Boundary Audit

### Completed
- [x] MiniRowVec sealed hierarchy with 10+ subtypes (Doc/View/Blob/Json/Yaml/Csv/Block/Manifold/ObjectStore/Wrapped)
- [x] BlockRowVec MUTABLE->SEALED state machine
- [x] MiniDuckBlockCodec NDJSON encode/decode with full RowVec family roundtrip
- [x] QueryPlan sealed tree with execute() interpreter
- [x] Predicate DSL (sealed tree, ColumnRef infix operators)
- [x] SQL frontend: SqlToMiniDuck + ExpressionCompiler over kursive SQL AST
- [x] PlanNode pull-model execution (TableScan/Filter/Project/Limit)
- [x] ExecutionContext + TableSource + InMemoryTableSource
- [x] CursorJoins: hashJoin, groupBy with Agg sealed class
- [x] UnifyCodec: JSON/YAML/CBOR unified with CoroutineContext KEYS/ELEMENTS
- [x] LSMR merge tree (L0/L1/L2 sorted runs, flush, merge, scan)
- [x] Tablespace/Region/BlockStore/InMemoryBlockWal
- [x] Object store adapter SPI + S3/GCS/Alibaba data classes
- [x] ManifoldConcept, BudgetCoord, NarsBag, Timeline
- [x] KernelTransformer + stochastic training harness
- [x] GapDetector + SpanMatcher for kline time-series

### Boundary Issues

1. **Two ColumnSchema classes** -- `columnar.ColumnSchema(name,type,plugin)` and
   `schema.ColumnSchema(id,name)` are unrelated. Tablespace.discoverSchema() uses
   `schema.ColumnSchema` but columnar operations use `columnar.ColumnSchema`.
   These should either be unified or explicitly documented as separate concerns.

2. **Two SchemaManager hierarchies** -- `schema.SchemaManager` is an interface with
   suspend+sync wrappers. `schema.InMemorySchemaManager` and `schema.LsmrSchemaManager`
   implement it. But there is no integration between SchemaManager and the
   columnar ColumnSchema. The SQL path uses SchemaManager; the ISAM path uses
   columnar.ColumnSchema. These two worlds are not connected.

3. **IsamVolume/IsamCursor/ZranIndex/Lz4Index are all TODO stubs** -- every method
   throws TODOError. The ISAM layer is sketched but unimplemented. Tests exist
   for generation, roundtrip, and cursor API but they will fail until implemented.

4. **SqlExecutor.kt is an empty stub** -- only imports, no class or function.
   SQL execution goes through PlanNode.open(ExecutionContext) instead.

5. **CursorOps.kt is an empty file** -- just import aliases. Operations live in
   CursorJoins.kt and QueryPlan.kt.

6. **UnifyCodec lifecycle is not integrated with AsyncContextElement** -- UnifyCodec
   manages its own `state: ElementState` but does NOT extend AsyncContextElement.
   Compare with UserspaceBtrfsBuffer which does. Inconsistent.

7. **InMemoryBlockWal.applyOp() casts to InMemoryBlockStore** -- replay only works
   with InMemoryBlockStore, not the BlockStore interface. WAL replay against
   other store implementations (S3-backed, etc.) will silently skip Put ops.

8. **MvccBlockStore lives in `concurrency` module** -- but imports and depends on
   miniduck types (BlockRowVec, MiniCursor, MiniRowVec, DocRowVec). This is a
   cross-module dependency that should be documented.

9. **LsmrSchemaManager depends on `userspace.database.LsmrDatabase`** -- a type
   from another module. Creates a tight coupling between miniduck and whatever
   module provides LsmrDatabase.

10. **No delete operation on BPlusTree** -- the B+Tree has put/get/findLeaf/insert
    but no remove. The contract tests mention delete and rebalancing but the
    implementation is missing.

### Integration Steps

1. **Unify ColumnSchema** -- Merge `columnar.ColumnSchema` and `schema.ColumnSchema`
   into a single type, or create an explicit adapter between them.

2. **Implement ISAM layer** -- Fill in IsamVolume.generateIsam(), IsamCursor,
   ZranIndex, Lz4Index with real zstd/lz4 compression and block indexing.

3. **Wire SQL -> Tablespace** -- Complete SqlExecutor so SQL queries can run
   against a Tablespace without manually constructing ExecutionContext.

4. **Make UnifyCodec an AsyncContextElement** -- Extend the Key/Element pattern
   for lifecycle consistency with the rest of TrikeShed.

5. **Generalize WAL replay** -- Make InMemoryBlockWal.applyOp work against the
   BlockStore interface (add putWithId to BlockStore SPI).

6. **Add BPlusTree delete** -- Implement remove with rebalancing/merging to pass
   the contract tests.

## Path to Stable

- **v0.1** (current): RowVec taxonomy, block codec, query plan, SQL frontend,
  tablespace, WAL, object store adapters -- all in place but ISAM layer is stubs.
- **v0.2**: Implement ISAM (ZranIndex + IsamVolume + IsamCursor), unify ColumnSchema,
  complete SqlExecutor, generalize WAL replay.
- **v0.3**: BPlusTree delete + rebalance, MVCC integration with concurrency module,
  UnifyCodec as AsyncContextElement.
- **v1.0**: All TODO stubs replaced, cross-module dependencies documented and
  minimized, full test coverage on columnar path.
