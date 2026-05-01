# libs/miniduck — TODO

## Intent
Block-first columnar storage engine. RowVec families, MiniCursor, block codecs, ISAM indexes, column schemas, tablespace management, SQL→MiniDuck compilation. KMP full with posix source sets.

## Status: ALPHA (large surface, core algebra solid, integration gaps)

## Pure boundary audit

### Keys (need creation)
- No AsyncContextElement or Key types exist in miniduck currently.
- `ColumnType` enum — column type discriminator. Should stay enum (schema metadata, not routing key).
- `ColumnSchema` data class — pure schema value ✓
- `IndexPluginRegistry` object — plugin registry. [ ] Consider if this should be a context Key so plugins are per-scope.

### Elements (stateful — need AsyncContextElement wrapping)
- `BlockStore` (tablespace/) — has put/get/remove with no lifecycle. [ ] Wrap as AsyncContextElement with sealing semantics.
- `InMemoryBlockWal` — WAL append log. [ ] AsyncContextElement lifecycle.
- `Tablespace` — manages blocks + WAL. [ ] The main stateful unit, should be a context Element.
- `InMemoryTableSource` / `LsmrTableSource` (exec/) — execution context sources. [ ] Lifecycle elements.

### Statics that should stay static
- `BlockRowVec` — value type extending Series2 ✓
- `MiniCursor` — factory functions, pure ✓
- `CursorOps` — pure cursor transforms ✓
- `MiniDuckBlockCodec` — encode/decode, pure functions ✓
- `RowVecFamily` / `RowVecFamilies` — type taxonomies ✓
- `Predicate` — pure predicates ✓
- `CursorJoins`, `Agg` — pure query algebra ✓
- `JsonProjection`, `JsonEscape` — pure utilities ✓
- `KernelTransformer`, `KernelIndicators` — pure transforms ✓
- `ManifoldConcept` — data class ✓

### Enums → evaluate
- `ColumnType` — stays enum (schema metadata) ✓
- `WireProto` (root) — wire format discriminator, stays enum ✓
- `TypeMemento` (root cursor/) — type tag, stays enum ✓

### Schema management
- `SchemaManager` interface + `InMemorySchemaManager` + `LsmrSchemaManager` — correct hierarchy
- [ ] `LsmrSchemaManager` should be an AsyncContextElement with lifecycle (schema mutations need coordination)

## Integration partners
- **couch**: couch `api` main; uses miniduck BlockRowVec, MiniCursor, ColumnSchema extensively. couch tests depend on miniduck.
- **concurrency**: MvccBlockStore wraps miniduck BlockRowVec.
- **dreamer-kmm**: uses MiniCursor, cursor extensions, at() for backtesting.
- **root project**: root src has a parallel copy of ALL miniduck sources (under `src/commonMain/.../miniduck/`). This is the DRY problem.
  - [ ] **DRY cleanup**: root project miniduck/ sources should be `expect/actual` or just import from this module. Per AGENTS.md: TrikeShed DRY precedence OVER libs/**

## Path to stable
1. **DRY**: Resolve root-project miniduck duplication. Either root re-exports this module or root's copies become the canonical ones and this module is a thin dependency declaration.
2. Create `TablespaceKey : AsyncContextKey<Tablespace>` — make Tablespace a lifecycle element
3. Create `BlockStoreKey : AsyncContextKey<BlockStore>` — same
4. Wire Tablespace lifecycle into couch ReactorSupervisor context palette
5. Add SchemaManager lifecycle tests
6. SQL→MiniDuck compilation integration test
