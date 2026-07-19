# Taste — High-Performance Data Engines for Hierarchical UIs

Distilled from the TreeSheets/columnar-engine essay (2026-07-19 review).
Ten principles for an engine where the hierarchical UI never fights the
machine, mapped against TrikeShed's live tree. Each entry: the principle,
what TrikeShed already has, and the gap.

## The ten principles

| # | Principle | TrikeShed has | Gap |
|---|-----------|---------------|-----|
| 1 | **Leaf first, hierarchy as indexing** — flat columnar arena + structural metadata as facets, not pointer trees | `ConfixIndex` — flat token array with `Spans`/`Tags`/`Depths`/`DirectChildren` facets over one flat `Series<Byte>` (ConfixKit.kt:204-221). Hierarchy IS an index here. | `ForgeDoc` block tree is a real pointer tree — blocks hold child references, zoom walks pointers. Forge doesn't consume the cursor shape Confix already offers. |
| 2 | **Immutability default, mutation as transaction** — persistent structures (shared subtrees) at metadata layer, COW/append-only at data layer | `CowBPlusTree` (COW pages in CAS, checkpoint + tail replay), job nexus command/snapshot split, CAS dedup at blob level. | No structural sharing WITHIN documents. Editing one cell re-encodes the whole Confix doc. No delta columns, no lazy compaction. Caps interactive editing on large docs. |
| 3 | **Cursor as primary abstraction** — `zoom(path)`/`transpose()`/`filter(pred)`/`join` all composable | `Cursor = Series<RowVec>`, `get(range)`, `get(IntArray)` reorder, `joins`, `combine`, `α` projection. | 3 of 4 ops missing: `filter` returns Iterator not Series (`%`/`[Predicate]`, Predicate.kt:10-15); `zoom` returns `ConfixCell` not `Cursor` (breaks composition); `transpose()` doesn't exist. |
| 4 | **mmap first** — map the columnar arena, io_uring feeds the cursor, zero-copy disk→UI | `ByteSeries` zero-copy over `ByteRegion`, `LiburingImpl` + `ChannelRunner`, Panama MemorySegment, WAL frames w/ CRC32C. | CAS is heap-based (`LinearHashMap<ContentId, ByteArray>`). Uring exists for transport IO, never for the document arena. One-cut gap: `MmapCasStore` returning mapped slices. |
| 5 | **Declarative but blazing** — vectorized columnar execution, SIMD, materialized hot paths, incremental deltas | ViewServer Confix-DSL reducers, `evaluateReducerAst`/`evaluateExpr`. | Boxing wall: `RowVec = Series2<Any?, ColumnMeta↻>` — every value boxed `Any?`, defeats autovec. `DoubleSeries` (primitive DoubleArray) exists but not wired into query engine. No incremental propagation — full rebuild per commit. |
| 6 | **Hierarchy as first-class geometry** — grid coordinates, spatial index for viewport culling, transposition as coordinate transform | `ForgeBlackboardCamera` (momentum/tilt/zoom), `ForgeBlackboard3D` (elevation), `layout3D` with explicit centerX/centerY/width/height/elevation. | No spatial index — rendering is O(nodes) per frame, walks every node through the camera. No quadtree/interval tree over `layout3D`. No transpose gesture. |
| 7 | **Concurrency without tears** — UI thread owns root cursor, workers produce candidate roots, atomic swap | CCEK lifecycle, bounded channels, SupervisorJob, durable commit sequence (server side). | Browser side violates it: JS hydrates from seed then mutates local state directly (dual-truth seam). Fix: browser mutations lower to `JobCommand` through bounded ingress, same as server. |
| 8 | **Extensibility as language** — sandboxed guest language operating on cursors | GraalVM Polyglot (`GraalVmViewServerHost`), Confix DSL reducers, parse/eval separation (JS-injection fix). | Polyglot bound to ViewServer's addTool/custom-reduce path, not to cursors as universal operand. JVM-only — no guest surface on js/wasm targets. |
| 9 | **Metrics of taste** — cold start <300ms, keystroke <16ms, zoom <8ms, <20 bytes/cell, tracked religiously | JMH benches (`jmhJoin`, `jmhConfix`, `jmhWal`), gh-pages element-count verification. | Zero UX-level metrics. Nothing measures keystroke echo, zoom latency, or bytes-per-cell. Seed-strip episode (322KB→162KB) was ad hoc, not tracked. Need bench harness with regression gates. |
| 10 | **Philosophical alignment** — strict hierarchy + orthogonal 2D grid + reference escape hatches; optimize the 80% | The blackboard IS strict hierarchy (sections) + orthogonal grid (page/board/gallery elevations) + escape hatches (causal edges). Kernel is small: Join/Series/Cursor. | Escape hatch asymmetric: references (causal graph nodes) are heavier than containment (cells). A card-in-column is cheap; a reference-to-document costs a full graph node + causal key. |

## Meta-finding

The essay and TrikeShed agree on *shape* almost everywhere — columnar
arena, structural facets, COW, cursor-primary, hierarchy+grid. The gaps
are all in *depth*: the shapes exist but stop one composition short:

- heap, not mmap (§4)
- boxed, not primitive (§5)
- Iterator, not Series (§3)
- rebuild, not delta (§5)
- mutate, not command (§7)
- Cell, not Cursor (§3)

No re-architecture needed. Ten focused cuts, most small.

## Cut list (ranked by how much of the essay's promise they unblock)

1. **Structural sharing within Confix docs** (§2) — git-tree-style shared
   subtrees so single-cell edits don't re-encode the document.
2. **Primitive typed columns in query path** (§5) — `IOMemento.IoDouble`
   columns dispatch to `DoubleArray` execution, not boxed `Any?` iteration.
3. **Lazy `Series.filter(pred): Series<T>`** (§3) — precompute matching
   indices into IntArray, return `indices.size j { this[indices[it]] }`.
   Collapses `%`, `[Predicate]`, and PointcutCoordinate.div onto one shape.
4. **`MmapCasStore`** (§4) — `get(cid)` returns a mapped slice, not a heap
   copy. io_uring + Series<Byte> + Confix-over-bytes composed.
5. **Browser mutations lower to JobCommand** (§7) — same bounded ingress
   as server; kills the dual-truth seam.
6. **`zoom(path): Cursor`** (§3) — navigation returns a sub-cursor with
   inherited columns, not a cell.
7. **Spatial index over layout3D** (§6) — quadtree or interval tree for
   viewport culling.
8. **UX metrics harness** (§9) — cold-start-to-interactive and
   keystroke-to-paint as JMH/browser-trace targets with regression gates.
9. **Incremental delta propagation** (§5) — projections subscribe to Rete
   affected-branch events instead of full rebuilds.
10. **Guest language on cursors, multi-target** (§8) — GraalVM surface
    takes and returns cursors; investigate wasm guest for js/wasm targets.
