================================
=== concepts-gap-analysis.md ===
================================
# `doc/concepts.md` Gap Analysis — refresh 2026-07-19

Re-audit of the prior pass (which compared `cebea1da` → `638fb71b`) against
current master (`90541f94`, post 2026-07-19 upstream merge). Each prior finding
is re-scored against live disk; new findings from the litebike/NUID/LCNC
session and the 2026-07-19 upstream merge are appended.

## 2026-07-19 merge summary

Two remote branches merged into master after pre-flight verification
(no libs/, no merge artifacts, root-shaped):

- `origin/jules-18017460688326899188-3d405ebb` (1 commit, 121 lines)
  — adds `ProcessReactorEndpoint` (commonMain) + JVM test. NUID-authorized
  exec dispatcher over `ProcessOperations` SPI. Fulfills T12.
- `origin/fix-forge-assets-11945900129262057005` (1 commit, ~7.5k lines)
  — moves the Forge HTML/CSS/JS shell out of inline Kotlin strings into
  `src/commonMain/resources/web/` and adds the `generateForgeAssets` Gradle
  task that bakes them into `borg.trikeshed.forge.generated.ForgeAssets`
  (ByteArray chunk objects). `ForgeApp.kt`/`ForgePersistenceScript.kt`
  now reference the generated object. `commit_changes.py` (Jules extraction
  scaffolding) was dropped before commit. 3 conflicts in
  `ForgeApp.kt`/`ForgePersistenceScript.kt`/`index.html` resolved by taking
  THEIRS on all three (consistent consolidated-asset form).

Skipped branches:
- `origin/gh-pages` — no merge base with master (Pages deployment root);
  every unique commit subject is superseded into master via other merges.
  Deployment target, not source-of-truth work.

`./gradlew compileKotlinJvm` → **BUILD SUCCESSFUL** after both merges
(pre-existing warnings only, zero new errors).

## 2026-07-19 doc curation

The N1–N7 findings from the prior refresh have been **applied to
`concepts.md`** (not just recorded here):

- N1 (Forge DTO removal) → §2 spine row updated; §8.1 prose describes
  BlackboardSurface as the seed source.
- N2 (`elastic/` shadow removed) — implicit in the canonical-types rule;
  not called out separately (decision: covered by the kernel-algebra note).
- N3/N4 (compiled-out slab + CircularQueue loud-hollow) → §0 orientation
  has a "Compiled-out layers" line.
- N5 (litebike/NUID spot-check) → no action; remains a verification record.
- N6 (LCNC package absent from spine) — deferred (decision, not prose):
  the package is still self-enclosed with zero external consumers.
- N7 (task-ledger pointer) → §0 orientation has a "Task ledger" line.

New doc edits for the 2026-07-19 merges:
- §2 spine: added a row noting the `resources/web/` consolidation and
  `generateForgeAssets` symbol-based reference.
- §0 orientation: added a "Static assets" line pointing at `resources/web/`
  as the single source of truth.
- §8.1c: new section documenting `ProcessReactorEndpoint`.
- §9 build tasks: added `generateForgeAssets` with a one-line contract.
- §10 reading paths: added a "Process reactor" row and added
  `resources/web/` + `generateForgeAssets` to the Gallery / Pages row.

## Re-scored prior findings

| ID | Prior claim | Live-tree status | Verdict |
|----|---|---|---|
| G1 | Oroboros is a substantial undocumented subsystem; components tested but uncomposed | `rg 'borg.trikeshed.util.oroboros' src -g '!src/commonMain/.../oroboros/**' -g '!*Test.kt'` → **1 hit**, and that hit is `src/commonTest/.../FakeFileOperations.kt`. Zero non-test external consumers. `OroborosNetwork.kt:53` still carries `// ... mocked for testing tests` with a `frame.toString() == "MOCK_PAYLOAD"` extractor. | **OPEN, unchanged.** Components exist, no production composition root, mock remains. Code gap, not a doc gap. |
| G2 | Couch CQRS docs claim Job/CID semantics the impl does not provide | `CouchStore.inMemory()` and `withPersistence()` now both build a **`ProductionCouchIngress`** (`CouchStore.kt:257,264`); `SyncTestIngress` (`CouchStore.kt:215`) still exists as a nested class but is no longer the default factory path. `CouchHeadProjection` still stores the raw revision string (`CouchHeadProjection.kt:24-56`) — no CID-derived `_id`/`_rev`. | **PARTIALLY CLOSED, unchanged.** `concepts.md` Couch prose updated to say "revision string stored raw; CID-derived revisions not yet implemented". |
| G3 | Checkpoint recovery incomplete — clears preceding snapshots, never hydrates from tree | `JobRepository.recover()` now has `verifyAndHydrateTree(cid)` (`JobRepository.kt:79-98`) which walks `BTreeNode.Internal`/`Leaf`, fetches each snapshot CID from CAS, decodes via `CanonicalCbor.decodeJobSnapshot`, and inserts into `recoveredSnapshots`. | **CLOSED, unchanged.** `concepts.md` recovery prose is accurate. |
| G4 | Stringpool documented but file-backed backing is simulated; WAL logger durability overstated | `FileBackedStringpool` (`Stringpool.kt:18-49`) now has a real `init` block: `fileOps.exists(location)` → `readAllBytes` → frame-walk with `isCorrupted` flag. `ReactorLogger.kt:60` now calls `durableAppendLog?.flush()`. | **PARTIALLY CLOSED, unchanged.** Recovery-on-open is real; append path and mmap/WAL block are still aspirational. |
| G5 | View-server runtime forks into two incompatible APIs (`addFunction` typed vs `addTool` raw-JSON) | `src/viewServerCommonMain`, `src/viewServerJsMain`, `src/viewServerJvmMain` **do not exist on disk**. `rg addTool src` → 0 hits. Only the common `CommonViewServer` + `CouchDbCascadeTool` path remains. `build.gradle.kts:27` still sets `viewServerNodeSlice = false` as a dead flag. | **CLOSED, unchanged.** The duplicate raw-JSON fork was deleted from the tree. |
| G6 | Build section commands not executable; serializer contract violated (commonMain has `kotlinx-serialization-json` directly) | `./gradlew compileKotlinJvm compileKotlinMacos compileKotlinJs compileKotlinWasmJs` → **BUILD SUCCESSFUL**. `kotlinx-serialization-json` still a direct `commonMain` dependency (`build.gradle.kts:146`). | **PARTIALLY CLOSED, unchanged.** All four targets compile; serializer contract violation is the actual enforced state. |
| N1 | HIGH: `ForgeAppState` DTO family deleted, `BlackboardSurface` projection is the seed source | `concepts.md` §2 spine row + §8.1 prose now describe BlackboardSurface as the seed source and call out the DTO removal. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |
| N2 | MED: `elastic/` shadow removed; canonical-types rule enforced by deletion | Decision: covered implicitly by the kernel-algebra note. Not called out separately — the rule is now structural (no shadow exists to mislead a reader). | **DECISION 2026-07-19.** No separate doc line; the absence is the enforcement. |
| N3 | MED: `classfile/slab/**` excluded from commonMain compile; spine had no mention | `concepts.md` §0 orientation now has a "Compiled-out layers" line listing the slab tree + `CircularQueue` loud-hollow. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |
| N4 | LOW: `CircularQueue` poll/peek/iterator converted to `error(...)` | Folded into the N3 note. | **APPLIED 2026-07-18, verified 2026-07-19.** Same line as N3. |
| N5 | HIGH: litebike/NUID doc spot-check | `concepts.md` §8.1a/§8.1b verified against `Taxonomy.kt` and `JvmKanbanServer.kt`; IDs 1–7 match litebike `taxonomy.rs`. | **VERIFIED 2026-07-18, re-verified 2026-07-19.** No action; remains a verification record. |
| N6 | MED: `LcncIngestPipeline` has zero production callers; LCNC package absent from spine | Decision: deferred. The package is still self-enclosed with zero external consumers. Adding a spine row would promote an aspirational surface. | **DEFERRED 2026-07-19.** Re-evaluate when the package gains an external consumer. |
| N7 | LOW: `concepts.md` does not reference `doc/todo.md` as the task ledger | `concepts.md` §0 orientation now has a "Task ledger" line. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |

## Historical findings (applied or closed)

The detailed N1–N7 findings from the 2026-07-18 refresh are preserved in git
history at commit `603b0859`. They have all been applied to `concepts.md`,
deferred by decision (N2, N6), or verified (N5) — see the re-scored table above
for the current verdict on each.

### Coverage disposition (current)

| Concept-map area | Disposition |
|---|---|
| Oroboros | G1 OPEN (code gap) — doc correctly says "uncomposed"; do not rewrite until a composition root lands |
| Couch | G2 PARTIALLY CLOSED — prose says "revision string stored raw; CID-derived revisions not yet implemented" |
| B+tree/recovery | G3 CLOSED — recovery hydration is real; prose is accurate |
| Collections | G4 PARTIALLY CLOSED — stringpool recovery-on-open real; append/mmap aspirational |
| Observability | G4 PARTIALLY CLOSED — ReactorLogger flushes via `durableAppendLog?.flush()` |
| View server | G5 CLOSED — fork deleted from disk; `viewServerNodeSlice = false` is a dead flag |
| Build | G6 PARTIALLY CLOSED — all four targets compile; serializer boundary aspirational |
| Forge | N1 APPLIED — BlackboardSurface projection is the seed source; DTO family removed from prose |
| Litebike/NUID | N5 VERIFIED — §8.1a/§8.1b spot-checked against code |
| LCNC | N6 DEFERRED — self-enclosed package; re-evaluate when it gains an external consumer |
| Process reactor | **NEW 2026-07-19** — §8.1c documents `ProcessReactorEndpoint` (merged from `origin/jules-1801...`) |
| Static assets | **NEW 2026-07-19** — §0 + §2 + §9 document `resources/web/` consolidation + `generateForgeAssets` (merged from `origin/fix-forge-assets-...`) |
| treedoc | **NEW 2026-07-19** — memvid renamed to treedoc; doc cursor expanded to 5 fields (firstFrameOrdinal/frameCount); restore is direct slice, not scan |
| LinearHashMap | **NEW 2026-07-19** — CasStore backing moved from MutableMap to LinearHashMap (KMP-native); put→set, operator get/set for MutableMap drop-in |

## Taste-essay gap review (2026-07-19)

The "Taste in High-Performance Data Engines for Hierarchical UIs" essay
(mapped in `doc/taste.md`) reviewed against the live tree. The essay and
TrikeShed agree on shape; the gaps are all in depth — shapes that exist
but stop one composition short. Ten findings, ranked by impact:

| # | Finding | Severity | Evidence |
|---|---------|----------|----------|
|| T1 | No structural sharing within Confix docs — single-cell edits re-encode the whole document | HIGH | `ConfixDoc` re-encode on edit; CAS dedups blobs not subtrees. `ConfixIndexK.StructuralNodes: Series<String?>` (`ConfixIndexK.kt:21,32`) emits CID per token; `StructuralSharingTest.kt:22,60` reads `facet(StructuralNodes)` but no `ConfixDoc.edit(path, value)` exists — any mutation path drops to full byte re-encode (`src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixKit.kt` has no write facet). |
|| T2 | Boxing wall in query path — `RowVec = Series2<Any?, ColumnMeta↻>` defeats autovec | HIGH | `ViewServer.evaluateExpr` walks boxed `Any?`; `DoubleSeries` primitive path exists but unwired |
|| T3 | No lazy `Series.filter(pred): Series<T>` — `%`/`[Predicate]` return Iterator not Series | HIGH | `Predicate.kt:10-15`; PointcutCoordinate.div materializes via `.toList()` |
|| T4 | CAS is heap-based, not mmap — uring exists for transport, never for document arena | MED | `CasStore.blobs` is `LinearHashMap<ContentId, ByteArray>`; `MmapCasStore` is the one-cut composition |
|| T5 | Browser dual-truth — JS mutates local state instead of lowering to JobCommand | MED | `script.js` local state mutation vs server-side bounded ingress |
|| T6 | `zoom(path)` returns `ConfixCell` not `Cursor` — breaks cursor composability at the most common hierarchical op | MED | `ConfixKit.kt:106` `docAt` → `rootCell?.cellGetAt()` returns `ConfixCell?`; `ConfixKit.kt:88-103` `cellGetAt` walks `ConfixCell` chain, no `Cursor` projection. Contrast `ConfixKit.kt:43` `roots: Cursor = index.facet(TreeCursor)` — the `TreeCursor` facet exists but `docAt`/`cellGetAt` don't return it. |
| T7 | No spatial index over `layout3D` — rendering is O(nodes) per frame | MED | camera projects every node; no quadtree/interval tree |
| T8 | No UX metrics harness — JMH for algebra, nothing for keystroke/zoom/cold-start | LOW | gh-pages element counts verify correctness, not latency |
| T9 | No incremental delta propagation — projections full-rebuild per commit | LOW | Rete has affected-branch machinery; projections don't subscribe |
| T10 | Guest language bound to ViewServer not cursors; JVM-only | LOW | `GraalVmViewServerHost` — wrong operand, single target |

Cut list in `doc/taste.md` §"Cut list". T1–T3 are the essay's core promise
(interactive editing on large documents); T4–T6 are the storage/cursor
compositions that make it feel inevitable; T7–T10 are refinement.

## Stale-evidence note

The 2026-07-18 version of this file compared against `638fb71b` and claimed the
build could not configure. That is no longer true; the 2026-07-19 refresh
supersedes those sections. Structure preserved (re-scored table first, applied
findings summary, current disposition) so the next refresh can diff row-by-row.

===================
=== concepts.md ===
===================
# TrikeShed Concept Map — for the Kotlin Maintainer

> One place a new maintainer who only knows Kotlin can read end-to-end.
> Covers the architecture spine, data algebra, runtime contracts, and the integration seams you will touch.

---

## 0. Quick Orientation

```
TrikeShed/
├── src/                    ← single source root (KMP: JVM / JS / WASM / native)
├── utils/htxc/             ← standalone CLI (composite build, see 8.3)
├── utils/ingest/           ← catalog tool (composite build)
├── build.gradle.kts        ← locked: Kotlin 2.4.10, Gradle 9.6.1, JDK 25, GraalVM CE 25.0.2
├── settings.gradle.kts     ← compose plugin, prefer-project repos
├── gradle.properties       ← jvmargs, native ignore
├── docs/                   ← GitHub Pages output (wasmJsBrowserProductionWebpack)
└── PRELOAD.md              ← kernel algebra cheatsheet (read first)
```

**Toolchain** — JDK 25 (GraalVM CE 25.0.2), Kotlin 2.4.10, Gradle 9.6.1.  
**No libs/ subprojects** — everything lives in `src/`.  
**Confix** — the only portable serializer; `kotlinx-serialization-json` is not a `commonMain` dependency (jvmMain pulls it for the one target that needs the kotlinx JSON frontend). `commonMain` source allows only `kotlinx-serialization-core` annotations (`@Serializable`/`@Contextual`) via the `kotlin("plugin.serialization")` plugin; the json runtime never crosses into portable code.  
**License** — AGPLv3 (effective 2017). Do not change.  
**Task ledger** — `doc/todo.md` (LCNC T22–T29, Kanban-live T-KANBAN-*, Storage-unification T-CAS-PROJ-* queues).  
**Architecture docs** — `doc/rewire.md` (user-centric Forge workspace architecture, storage unification, K8s emulation via GraalVM pointcut server), `doc/taste.md` (high-performance hierarchical-UI engine principles, 10-point gap review).  
**Compiled-out layers** — `classfile/slab/**` is excluded from `commonMain` compile in `build.gradle.kts` (~20 `TODO()` stubs: GraalJS eval, DuckDB c-interop, `FacetedCursorContract`, `MiniDuckContract`; files preserved on disk). `CircularQueue.poll/peek/iterator.remove` converted from `TODO()` to `error(...)` — loud hollow, not silent stub.  
**Static assets** — `src/commonMain/resources/web/` (index.html, styles.css, script.js, manifest.webmanifest, icons/) is the single source of truth for the Forge HTML shell; the `generateForgeAssets` Gradle task bakes these into the Kotlin-internal `ForgeAssets` object so no runtime resource lookup is needed.  
**Categorical idempotency** — the kernel maxim (see PRELOAD.md): if a structure is not mutated, it stays in the category it came from. `Series` that gets copied to `List` only to be read back is a type demotion. `LinearHashMap` (KMP-native) replaces `MutableMap` where the map is not mutated post-construction; CasStore uses it as the blob backing.  
**Storage unification** — one CAS, five lenses (auxiliary CAS / materialized / reified Confix / btrfs content / graph trees). `doc/rewire.md` §0. Projection registry (`project(cid): Lens`) is the one new piece (T-CAS-PROJ-1).

---

## 1. Kernel Algebra (the mental model)

All shapes collapse to `Join<A,B>`:

```kotlin
interface Join<A, B> { val a: A; val b: B }
infix fun <A,B> A.j(b: B): Join<A,B> = object : Join<A,B> { override val a = this@j; override val b = b }

typealias Twin<T>    = Join<T, T>
typealias Series<T>  = Join<Int, (Int) -> T>          // size + index function
typealias Series2<A,B> = Series<Join<A,B>>             // split-storage specialization
typealias Cursor     = Series<RowVec>                  // columnar dataframe
typealias RowVec     = Series2<Any, () -> RecordMeta>  // value + metadata supplier
```

Key operators (in `lib/Join.kt`, `lib/Series.kt`):

| Symbol | Meaning |
|--------|---------|
| `a j b` | infix `Join` constructor |
| `s.α { it → it*2 }` | lazy projection (map) over a `Series` |
| `x.`↺`` | left-identity anchor — constant supplier `() -> x` |
| `s[i]` / `s[i0 until i1]` / `s[1,3,2]` | index, range, reorder |
| `s_ [1,2,3]` | Series literal |
| `join(c1,c2)` | widen columns (Series2) |
| `combine(c1,c2)` | concat rows |

**Cursor rules** — prefer projection over mutation; range selection is composition, not control flow; preserve metadata through transforms; widen/combine explicitly; keep transforms pure.

**Read** `PRELOAD.md` and `src/README.md` before touching code — they are the algebra contract.

---

## 2. Architecture Spine (runtime layers)

```
┌──────────────────────────────────────────────────────────────────────┐
│  FORGE / KANBAN / BLACKBOARD   (user-facing surfaces)               │
│  - Forge Workspace: light-theme block editor (sidebar + doc + board)│
│  - ForgeDoc block tree (H1/H2/H3, P, TODO, BULLET, QUOTE, CODE)     │
│  - ForgeBoardFSM, KanbanFSM, slash-command menu, localStorage PWA   │
│  - CCEK choreography (channels, projections, agents)                │
│  - Gallery / blackboard 2.5D/3D spatial layout                      │
│  - BlackboardSurface projection: `confixDoc(persistedJson)` → `BlackboardSurface.project(...)` → seed rows; the `ForgeAppState` DTO family was removed (commit `1e8fd692`) │
│  - Static HTML/CSS/JS shell consolidated under src/commonMain/resources/web/; `generateForgeAssets` task bakes them into the `ForgeAssets` Kotlin object so `ForgeApp.kt` references the asset by symbol, not by resource lookup │
│  - ManimWM camera: momentum, tilt, 2.5D parallax + 3D orbit         │
├──────────────────────────────────────────────────────────────────────┤
│  NUID / CCEK FANOUT   (authorization + dispatch)                    │
│  - Nuid = Join<Capability, Join<Nonce, Subnet>>                     │
│  - NuidFanoutElement: concentric narrowing, escalation, CAS claim   │
│  - Workgroup: scope + TraitSpace → canHandle(request)               │
├──────────────────────────────────────────────────────────────────────┤
│  LITEBIKE LISTENER   (multiprotocol CCEK listener)                  │
│  - LitebikeListenerElement: protocol-keyed channel slots            │
│  - JvmLitebikeBindAdapter: sole socket bind, bytes → CCEK accept    │
│  - JvmMulticastAdapter: mDNS/SSDP join + SO_REUSEPORT fallback      │
│  - JvmKanbanServer: daemon, no framework, hand-rolled HTTP          │
├──────────────────────────────────────────────────────────────────────┤
│  JOB NEXUS   (durable work orchestration)                           │
│  - JobSupervisorElement — bounded command channel + reactor         │
│  - JobReducer (pure) — idempotency, optimistic revision, lifecycle  │
│  - CasStore (CAS), JobLog (WAL), JobIndex, Checkpoint              │
│  - ReteNetwork — production rule engine (alpha/beta/agenda/refraction)│
│  - JobKanbanProjection / ForgeKanbanJobSink — Kanban as projection  │
├──────────────────────────────────────────────────────────────────────┤
│  COUCH / ISAM / TREEDOC   (content-addressed persistence)           │
│  - CasStore — LinearHashMap<ContentId, ByteArray> (KMP-native)      │
│  - CouchStore (in-memory, pluggable persistence)                    │
│  - TreeDocPipeline — document archive over CAS (git-tree-shaped)    │
│  - DurableAppendLog / WalFrame — frame format with CRC32C           │
│  - JobRepository — recovery from checkpoint + tail replay           │
│  - ConfixDocStore, ViewServer cascade rollups                       │
│  - CowBPlusTree — COW pages in CAS, btrfs-style snapshot/send/recv  │
├──────────────────────────────────────────────────────────────────────┤
│  DAG / RETE   (causal + rule engine)                                │
│  - ReteWorkingMemory, Alpha/Beta memories, Agenda, Refraction       │
│  - BlackboardDagCausalGraph, BlackboardDagFabric                    │
│  - ReteAgent — CCEK bridge                                          │
├──────────────────────────────────────────────────────────────────────┤
│  COLLECTIONS   (index algebra)                                      │
│  - LinearHashMap, FunnelHashMap, ElasticHashIndex, RadixTree        │
│  - MultiIndexK (exact/order/range/prefix) with stable IndexSpecId   │
│  - COW B+Tree (btree/) — deterministic pages in CasStore            │
├──────────────────────────────────────────────────────────────────────┤
│  CONFIX   (schema-driven config oracle)                             │
│  - ConfixDoc / ConfixCell — index-first, reify-later                │
│  - ConfixFacetPlan — compiled from job-nexus.schema.json            │
│  - JSON / YAML / CBOR single parser (Syntax enum)                   │
├──────────────────────────────────────────────────────────────────────┤
│  CHOREOGRAPHY / REACTOR   (structured async)                        │
│  - AsyncContextElement (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)        │
│  - NioSupervisor / LiburingElement / FanoutDispatcherElement        │
│  - ChannelRunner — RelaxFactory inner loop → coroutines             │
│  - MuxReactorElement — keymux/modelmux/taxonomy/kanban events       │
│  - ProcessReactorEndpoint — NUID-authorized exec (Capability.Process)│
├──────────────────────────────────────────────────────────────────────┤
│  TRANSPORT / HTX   (version-agnostic HTTP)                          │
│  - HtxMessage blocks (ReqSl·Hdr·EOH·Data·EOT·EOM)                   │
│  - HtxClientReactorElement — channelized client                     │
│  - DHTX_REQ/DHTX_RES for non-HTTP protocols                         │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL SURFACES   (expect/actual)                                  │
│  - FileImpl, LiburingImpl, FilesImpl, ChannelsImpl                  │
│  - FunctionalUringFacade wraps UserspaceChannelBackend              │
│  - ByteBuffer / ByteRegion / ByteSeries — zero-copy IO path         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Job Nexus — the durable work spine

### 3.1 Command → Event Pipeline (in `JobSupervisorElement`)

```
JobCommand (Submit/Start/Complete/Fail/Retry/Progress/Block/Cancel/Move/Ack/Retract)
   │
   ├─ 1. Schema validation  (ConfixFacetPlan)
   ├─ 2. Canonical CBOR      (CanonicalCbor.encode → deterministic bytes)
   ├─ 3. CasStore.put        (SHA-256 CID, digest verification on get)
   ├─ 4. JobLog.append       (sequence + payload; monotonic)
   ├─ 5. Durability barrier  (flush/fsync contract)
   ├─ 6. JobReducer.reduce   (idempotencyKey + expectedRevision → JobSnapshot)
   └─ 7. Committed → JobEvent.Accepted/Rejected → channels
```

**Invariants**  
- `idempotencyKey` deduplication (first wins, later rejected)  
- `expectedRevision` optimistic concurrency (stale → Rejected)  
- Commands enter **only** through the bounded `Channel<JobCommand>` (SUSPEND on overflow)  
- Failed step leaves **no visible snapshot** and does **not** advance committed sequence

### 3.2 Core Types (`job/IdentityTypes.kt`, `JobSnapshot.kt`)

```kotlin
@Serializable data class JobId(val value: String)
@Serializable data class Revision(val value: Long)
@Serializable data class KanbanColumnId(val value: String)
@Serializable data class Sequence(val value: Long)

data class JobSnapshot(
  val jobId: JobId,
  val revision: Long,
  val causalKey: String,
  val lifecycle: String,              // submitted | ready | active | blocked | failed | closed | moved | acknowledged | retracted
  val dependencies: List<JobId>,
  val attemptCount: Int = 0,
  val attemptId: String = ""
)
```

**Lifecycle derivation** — in `JobReducer.deriveLifecycle`; blocked if any dependency failed; ready if all deps closed.

### 3.3 ReteNetwork (production rule engine)

```
ReteWorkingMemory  ← assert/modify/retract by FactId + version CID
ReteAlphaMemory    ← shared single-condition nodes (predicate sharing)
ReteBetaMemory     ← equality join (leftFacetId = rightFacetId) with token memory
ReteAgenda         ← salience↓, sequence↑, activationId↑ deterministic pop
ReteRefraction     ← one firing per (ruleVersion, sorted supportCIDs)
ReteNetwork        ← owns all above; runs on bounded SendChannel<JobCommand>
```

**Rules currently encoded** (see `ReteNetwork.evaluateRules`):
- all deps `closed` → `Start` command
- any dep `failed`  → `Block` command with support evidence

Actions **never** mutate Kanban/Couch/snapshots directly — they enqueue `JobCommand` via the reactor ingress channel.

### 3.4 Projections (read models)

| Projection | Purpose |
|------------|---------|
| `JobKanbanProjection` | Kanban cards from committed snapshots (`applyCommit` + `rebuild`) |
| `ForgeKanbanJobSink`  | Monotonic sequence gate → projection |
| `CouchHeadProjection` | revision string stored raw; CID-derived `_id`/`_rev` not yet implemented, MVCC |
| `CouchChangesProjection` | Strict monotonic `_changes` stream |
| `CowBPlusTree` | Persistent ordered/range index (pages in CasStore) |
| `JobCheckpoint` | Committed sequence + root CID + schema CID |

---

## 4. Confix — the config oracle

**Single parser** (`Syntax` enum: JSON, CBOR, YAML) → `ConfixIndex` (flat token array + `FlatIndex`) → lazy `reify()`.

```kotlin
typealias ConfixDoc  = Join<ConfixIndex, Series<Byte>>
typealias ConfixCell = Join<RowVec, Series<Byte>>
```

**Navigation** (`ConfixKit.kt`):
```kotlin
doc.value("operation")                 // scalar
doc.docAt("dependencies", 0)?.get("jobId")  // nested
cell.reify()                           // Any? with tag dispatch
doc.navigate(jsPath)                   // typed JsPath (String | Int steps)
```

**ConfixFacetPlan** — compiled from `src/commonMain/resources/confix/job-nexus.schema.json`:
- operation enums, frame families, required fields, primitive/array constraints
- stable facet/index IDs, exact/order/range/prefix index policies
- validation errors include schema/document path

**No second hand-maintained field table** — the schema resource is the contract.

---

## 5. Couch — content store + projections

```
CouchStore (in-memory, pluggable CouchPersistence)
  ├─ put/get/delete  → MutationEvent (Inserted/Updated/Deleted)
  ├─ query()         → Cursor (row = doc, cols = _id + fields)
  ├─ subscribeMutations → MutableSeries observer
  └─ CouchHeadProjection / CouchChangesProjection  (built from committed Job frames)
```

**Head/Changes semantics** — revision string stored raw by the projection; stale revision rejected; delete = tombstone; `_changes` resumes after sequence without gaps. CID-derived `_id`/`_rev` is an integration gap, not the current state.

---

## 6. Collections — index algebra

| Component | Purpose |
|-----------|---------|
| `LinearHashMap` | open-addressing base (mixed hash bits, bounded probes) |
| `FunnelHashMap` / `FunnelHashIndex` | Krapivin 2025 funnel hashing (tiered geometry) |
| `ElasticHashIndex` | append-only, deterministic split |
| `RadixTree` / `Trie` | prefix queries, deterministic order |
| `MultiIndexK / MultiIndexContainer` | stable `IndexSpecId`, unique/non-unique, txn add/modify/retract, immutable snapshots |
| `CowBPlusTree` | COW pages in CasStore, deterministic page CID, checkpoint validation + tree hydration + tail replay |

**MultiIndex transition** — lambda-identity keys replaced by explicit `IndexSpecId`; incremental order/range (binary insert), no full-store resort.

---

## 7. Choreography / Reactor — structured async

### 7.1 Element lifecycle

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Every IO component extends `AsyncContextElement` and installs its `CoroutineContext.Key`:

```kotlin
object NioUserspaceKey    : Key<NioUserspaceElement>
object LiburingKey        : Key<LiburingElement>
object FanoutDispatcherKey: Key<FanoutDispatcherElement>
object BtrfsCodecKey      : Key<BtrfsCodecElement>
```

### 7.2 NioSupervisor (root registry)

```kotlin
open class NioSupervisor : AsyncContextElement() {
  internal val services = mutableListOf<CoroutineContext.Element>()
  fun <T: CoroutineContext.Element> service(): T?
  // opens platform providers in CREATED→OPEN→ACTIVE
}
```

### 7.3 ChannelRunner (RelaxFactory → coroutines)

```kotlin
suspend fun readAsync(fd: Int): Int { ... }      // CompletableDeferred per fd
suspend fun writeAsync(fd: Int) { ... }          // FIFO queue per fd
fun run(scope, pollTimeout, onSignal) { ... }    // CQE loop → dispatch
```

### 7.4 MuxReactorElement (keymux/modelmux/taxonomy/kanban events)

- Owns `ModelApiCache`, `SharedFlow<KanbanEvent>`, `StateFlow<MuxReactorState>`
- Kanban FSM **consumes** `kanbanEvents`; it never owns the stream
- External callers `ingestTaxonomyEvents` / `lookupModel` / `cacheModel` — reactor is the single writer

---

## 8. Surfaces a maintainer will touch

### 8.1 Forge / Kanban / Blackboard (user-facing)

```
Forge Workspace   ← block-based document editor (light theme)
  src/commonMain/resources/web/
    index.html  ← shell (sidebar + document + board + slash menu)
    styles.css  ← light theme, 16px Inter, sidebar #f7f6f3, doc #fff
    script.js   ← block editor: h1/h2/h3/p/todo/bullet/quote/code/divider
                  slash command menu, hover affordances (+/drag handle)
                  localStorage persistence, seed hydration, board view
ForgeApp.kt       ← placeholder substitution: {{STYLES}} {{SEED}} {{SCRIPT}}
                    → ForgeAssets.indexHtml/stylesCss/scriptJs
                    (generateForgeAssets bakes web/ into Kotlin object)
generateForgeAssets ← Gradle task, 5000-byte ByteArray chunks
                      → borg.trikeshed.forge.generated.ForgeAssets

ForgeDoc          ← block tree (H1/H2/H3, P, TODO, BULLET, NUMBERED, QUOTE, CODE, DIVIDER)
ForgeBoardFSM     ← board/card FSM (BoardLoaded, CardMoved, CardCreated, Drag*)
ForgeKanbanIngest ← /tmp/hi markdown → Rete facts + causal nodes + Kanban cards
ForgeGalleryCatalog/Renderer ← widget catalog (sections LAYOUT..CAS, preview tokens)
ForgePersistenceScript.kt ← browser IndexedDB/localStorage/Cache persistence
```

**Shell architecture** — the workspace shell is a pure client-side block
editor (no server at runtime). It hydrates from a baked seed JSON
(`<script id="forge-seed">`) and persists all edits to `localStorage`.
The seed is injected server-side by `ForgeApp.kt` via `{{SEED}}`
placeholder; `jsNodeProductionRun` captures the fully-baked HTML into
`docs/index.html` for gh-pages deployment.

**Block types and slash commands** — typing `/` at the start of a block
opens a slash menu with: Text, Heading 1/2/3, To-do, Bulleted list,
Numbered list, Quote, Code, Divider. Each block has hover affordances
(`+` to add below, `⋮⋮` to drag). Enter on a heading exits to paragraph;
Backspace on empty block deletes and focuses the previous block.

**Board view** — toggle between Document and Board views via the topbar.
The board shows kanban columns (To do / Doing / Done) populated from
seed cards (lcncEntities) or user-created cards. Cards cycle columns
on click. Same items as the document — different projection.

**Sidebar page tree** — left sidebar shows workspace pages with icons,
titles, and active highlighting. "+ Add a page" creates a new page.
Pages persist to `localStorage`.

**Seed hydration** — the baked seed carries `lcncEntities` (→ bulleted
list in the document + cards on the board), `causalNodes` (→ causal
graph), and `gallery` (→ widget catalog). The shell note in the sidebar
bottom shows the seed summary ("13 entities · 13 causal nodes · gallery").

**Gallery on GitHub Pages** — `jsNodeProductionRun` prints exact HTML
to stdout; awk-extract `<!doctype`..`</html>` into `docs/index.html`.
Seed is ~200KB baked. `kotlinUpgradeYarnLock` may be needed if yarn
lock drifts.

**Blackboard-as-Confix-cursor** — the target architecture. A single JSON
file is the blackboard; `confixDoc(json)` → `Cursor` →
`BlackboardSurface.project(cursor)` → UI renders cursor slices by
path/offset/facet. No parallel DTO truth. `BlackboardSurface` joins
`LcncEntitySurface` + `CausalGraphNodeIndex` into a deterministic
`Cursor` of `BlackboardSurfaceRow`s. Facet drilldown = child cursor
projections from the same doc.

**ManimWM 2.5D/3D surface** — `ForgeBlackboardCamera` carries momentum
(`vx`, `vy`, `vz`), tilt (2.5D parallax), and bounded zoom. The
blackboard is the VFS; cursors are the files; facets are drilldown
views. `ForgeBlackboard3D` adds true 3D orbit with per-section
elevation (gallery above board above page).

### 8.1a NUID / CCEK Fanout (authorization + dispatch)

```
Nuid = Join<Capability, Join<Nonce, Subnet>>
  - Capability: sealed hierarchy (Process/Cas/Wireproto/Sctp/Model/BlackBoard/Custom + wildcard family roots)
  - Subnet: concentric containment (core < process < local < lan.localhost < mesh.worker.* < global.relay)
  - Nonce: RandomBytes + Derived (causal chaining)

NuidFanoutElement
  - CCEK lifecycle (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)
  - Concentric narrowing: filter by scope⊇subnet AND TraitSpace.can(capability), sort by scope.level ascending
  - Escalation: timeout at request level → walk outward up to escalationBudget+1 levels
  - Claim: first WorkgroupSlot.tryTake() matching claimId wins; losers stand down

Workgroup
  - name + scope: Subnet + traits: TraitSpace
  - canHandle(request: Nuid) = traits.can(capability) && (scope contains subnet)
```

### 8.1b Litebike Listener (clean-room Kotlin port — no FFI)

```
LitebikeListenerElement
  - CCEK element; registry keyed by Protocol.id (UByte)
  - register(protocol) → ChannelWorkgroupSlot; slot.consume() suspends for ChannelMessage
  - accept(protocol, bytes) → offers to slot, fires LitebikeFanoutEvent to CCEK subscribers
  - Protocol enum: Http(1) Socks5(2) Tls(3) Dns(4) Json(5) Http2(6) WebSocket(7) Bonjour(8) Upnp(9)
  - IDs 1-7 match litebike taxonomy.rs conceptually; 8-9 are TrikeShed-local extensions

JvmLitebikeBindAdapter
  - The ONLY place that opens AsynchronousServerSocketChannel
  - Reads bytes → ProtocolDetector.detect(head) → listener.accept(protocol, bytes)
  - No HtxReactorElement, no com.sun.net.httpserver, no RfxHttpServerJvm

JvmMulticastAdapter
  - Joins mDNS 224.0.0.251:5353 and SSDP 239.255.255.250:1900 via DatagramChannel
  - SO_REUSEPORT-first fallback for macOS mDNSResponder port conflict
  - Tracks Jobs + MembershipKeys; close() cancels all read loops and drops groups

JvmKanbanServer
  - Daemon entrypoint (--port, --donor)
  - Owns one LitebikeListenerElement; registers Http/Json/Socks5/Tls/Bonjour/Upnp slots
  - HTTP worker consumes httpSlot, hand-parses request line, routes to /api/health|cap|board|submit|donor
  - Bonjour/Upnp consumers parse minimal mDNS/SSDP headers, emit JSON to Json slot
  - No server framework; CCEK fanout all the way down
```

### 8.2 CCEK (choreography for Forge)

```
ArticulatedNode
  signalIn: Channel<ForgeSignal> (AppendBlock/UpdateText/DeleteBlock/MoveCard)
  projections: SharedFlow<ForgeDocument>, <KanbanBoard>, <String>
  agents: Map<String, (ForgeSignal) -> Unit>
  fanOutJob: structured concurrency dispatch
```

Signals flow **into** `ArticulatedNode`; projections flow **out** via `SharedFlow`. No dual-mutable truth.

### 8.1c ProcessReactorEndpoint (NUID-authorized exec)

```
ProcessReactorEndpoint  ← ReactorEndpoint (commonMain)
  - Requires Capability.Process on the NUID; rejects other capabilities
  - Verb "exec" → ProcessOperations.exec(command, args)
  - Response verb: "ok" (stdout) when exit==0, "error" (stderr) otherwise
  - Fulfills T12 (Process worker) — wires ProcessOperations SPI into the reactor algebra
  - Lives in userspace/reactor/process/ (commonMain) — platform exec lives in ProcessOperations actuals
```

The endpoint is a thin Capability.Process dispatcher. It does not own a process pool; it is the reactor surface for one-shot exec. Long-lived processes belong to a future worker element on the same NUID/Capability contract.

### 8.3 HTX / htxc (CLI utility)

```
utils/htxc/          ← composite build (includeBuild("../.."))
  - bin/htxc         ← shell launcher, exact arg forwarding, preserves exit code
  - HtxAria2CliArgs  ← aria2-compatible switches (dir/out/split/max-conn/continue/checksum)
  - HtxAria2Engine   ← range/HEAD + chunked download via HtxClientReactorElement
```

---

## 9. Build & Deploy (what you will run)

```bash
# Environment
export JAVA_HOME=/Users/jim/.sdkman/candidates/java/25.0.2-graalce
export PATH="$JAVA_HOME/bin:$PATH"

# Full build + test
./gradlew build --console=plain

# Focused test suites
./gradlew jvmTest --tests "borg.trikeshed.dag.*"
./gradlew jvmTest --tests "borg.trikeshed.job.*"
./gradlew jvmTest --tests "borg.trikeshed.collections.multiindex.*"

# GitHub Pages deploy (manual capture)
./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 \
  | awk '/^<!doctype html>/,/^<\/html>/' > docs/index.html
git add docs/index.html && git commit -m "feat: deploy Forge workspace" && git push

# Verify deploy
gh api repos/jnorthrup/TrikeShed/pages/builds -X POST
gh api repos/jnorthrup/TrikeShed/pages/builds/latest
```

**Common tasks registered** (`build.gradle.kts`):
- `jmh`, `jmhJoin`, `jmhConfix`, `jmhWal` — JMH benches
- `benchmarkJoin`, `benchmarkSequence`, `benchmarkVector`, `benchmarkMath`, `benchmarkConfix`
- `printForgeGallery` — JVM text grid of catalog + blackboard
- `runForgeJvm` — Compose Desktop shell
- `generateForgePages` — Sync task (WASM target → docs/)
- `generateForgeAssets` — bakes `src/commonMain/resources/web/{index.html,styles.css,script.js}` into `borg.trikeshed.forge.generated.ForgeAssets` (ByteArray chunk objects, 5000 bytes each) so the Forge HTML/CSS/JS shell ships as a Kotlin-internal asset, not a resource lookup. `commonMain` consumes the generated object; `ForgeApp.kt` / `ForgePersistenceScript.kt` / `index.html` template all reference it via `{{SEED}}`/`{{STYLES}}`/`{{GALLERY}}`/`{{SCRIPT}}` placeholders.

---

## 10. Reading / Recovery Paths (where to look next)

| Need | Files |
|------|-------|
| Algebra cheat sheet | `PRELOAD.md`, `src/README.md`, `lib/Join.kt`, `lib/Series.kt` |
| Job Nexus end-to-end | `JobSupervisorElement.kt`, `JobReducer.kt`, `JobNexusFactory.kt`, `JobNexusBindings.kt` |
| Rete rule engine | `dag/ReteNetwork.kt`, `dag/Rete*.kt`, `dag/BlackboardDag*.kt` |
| Schema → Confix plan | `resources/confix/job-nexus.schema.json`, `ConfixFacetPlan.kt`, `ConfixKit.kt` |
| Couch projections | `couch/CouchStore.kt`, `couch/*Projection.kt`, `couch/ConfixRepositoryView.kt` |
| COW B+Tree | `collections/btree/*`, `JobRepository.kt`, `JobCheckpoint.kt` |
| MultiIndex | `collections/multiindex/*.kt`, `collections/associative/trie/RadixTree.kt` |
| Forge surfaces | `forge/ForgeDoc.kt`, `forge/ForgeBoardFSM.kt`, `forge/ForgeKanbanIngest.kt`, `forge/ForgePersistenceScript.kt` |
| Reactor / choreography | `userspace/reactor/MuxReactorElement.kt`, `context/AsyncContextElement.kt`, `userspace/nio/channels/ChannelRunner.kt` |
| NUID / CCEK fanout | `context/nuid/Nuid.kt`, `context/nuid/NuidFanoutElement.kt` |
| Litebike listener | `litebike/LitebikeListenerElement.kt`, `litebike/ProtocolDetector.kt`, `litebike/taxonomy/Taxonomy.kt`, `jvmMain/litebike/JvmLitebikeBindAdapter.kt`, `jvmMain/litebike/JvmMulticastAdapter.kt`, `jvmMain/litebike/JvmKanbanServer.kt` |
| Blackboard-as-cursor | `blackboard/BlackboardSurface.kt`, `parse/confix/Confix.kt`, `parse/confix/ConfixKit.kt` |
| ManimWM RTS camera | `forge/blackboard/ForgeBlackboardCamera.kt`, `forge/blackboard/ForgeBlackboardInteraction.kt`, `manimwm/` |
| Transport / HTX | `htx/Htx*.kt`, `cli/htx/HtxAria2*.kt` |
| Process reactor | `userspace/reactor/process/ProcessReactorEndpoint.kt`, `userspace/nio/channels/spi/ProcessOperations.kt` |
| Gallery / Pages | `forge/gallery/*.kt`, `ForgeApp.kt`, `resources/web/`, `build.gradle.kts` (`generateForgeAssets`, `generateForgePages`) |

---

## 11. Common Pitfalls (don't relearn these)

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| `Series[T]` vs `MatchGroupCollection.get` collision | `Unresolved reference … receiver type mismatch` | `import borg.trikeshed.lib.get` + `import borg.trikeshed.lib.size` (or `.b(i)` for raw) |
| CursorDriven empty-page placeholder child | `first()` returns empty TEXT block | filter by `kind` (`HEADING_1`, `BULLET`) or add `pageHeading(state)` helper |
| Compose Desktop import fragility | 70-80 import lines; patch breaks | prefer `git checkout <file>` + tiny patches, or `write_file` full rewrite |
| Coroutine polling in `runBlocking` + `Dispatchers.Default` | Only 1 of N events fires | Replace with `Channel<T>` — `trySend` / `for (item in channel)` |
| Dual-truth (Kotlin state + JS mutation) | Silent fork | One runtime authoritative (JVM); other mirrors via reactor/event channel |
| Orphaned submodule (gitmode 160000, no .gitmodules) | CI checkout fails silently | `git rm --cached <path>` |
| `build.gradle.kts` checkout from ref | Local commits lost | Never `git checkout <ref> -- build.gradle.kts` |
| `rm -rf` untracked `??` dirs | Sibling Jules jobs destroyed | Never — they are active work, not stubs |
| macOS mDNS bind with only `SO_REUSEADDR` | `EADDRINUSE` on port 5353 | Try `SO_REUSEPORT` first (runCatching), fall back to `SO_REUSEADDR` |
| `Random.Default` / `nextBits` in commonMain | Native compile failure | Use `Random(0L)` + `nextInt(0, 256)` — KMP-safe |
| `System.currentTimeMillis()` in commonMain | Deprecated / KMP-unsafe | Use `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` |
| `@Volatile` / `synchronized` in commonMain | KMP compile failure | Use `Mutex` + `withLock` — kotlinx-coroutines is KMP-safe |
| `Charsets.US_ASCII` in commonMain | JVM-only constant | Use `CharArray(n) { bytes[i].toInt().toChar() }.concatToString()` |
| `for (msg in channel)` on `Channel<T>` | Ambiguous iterator / compile error | Use `while (true) { val msg = slot.consume() }` or `channel.consumeEach { }` |
| `runCatching { subscriber.javaClass.methods... }` in commonMain | `javaClass` unresolved on JS/Wasm | Use explicit interface (`LitebikeFanoutEventSink`), no reflection |
| `toSortedMap()` on `groupBy` result | JVM-only stdlib | Use `.keys.sorted()` explicitly |
| `String(bytes, 0, len, charset)` in commonMain | JVM-only constructor | Decode via `CharArray` + `concatToString()` |
| `HtxReactorElement` used as server listener | Exchange-only, does not bind | Use `LitebikeListenerElement` + `JvmLitebikeBindAdapter` — Htx is client-side only |
| `com.sun.net.httpserver` for Kanban server | Framework dependency, not CCEK | Hand-rolled HTTP worker on `LitebikeListenerElement` slot — zero framework |
| Parallel DTO truth (`ForgeAppState` vs Confix doc) | Dual-truth seam, silent fork | Single JSON file → `confixDoc()` → `Cursor` → `BlackboardSurface.project()` — one canonical source |

---

## 12. Contribution Contract (how work lands)

1. **Vertical slice** — failing contract test → minimal production wiring → adjacent/full verification.
2. **Exclusive file ownership** — Jules tasks declare owned paths + forbidden paths; no overlap.
3. **No libs/ references** — root-only, composite builds consume via `includeBuild("../..")`.
4. **No FFI / no Rust linkage** — litebike is conceptual inspiration only; ports are clean-room Kotlin with TrikeShed-local conventions.
5. **Pre-commit** — `git diff --check`, verify no `kotlinx-serialization-json/cbor` in commonMain, run focused tests.
6. **Evidence** — real test output, generated artifact proof (HTML/WASM), branch + PR with exact commands.

---

## 13. Quick Start Checklist for Day 1

```bash
# 1. Toolchain
sdk install java 25.0.2-graalce
sdk use java 25.0.2-graalce
./gradlew --version   # Gradle 9.6.1

# 2. Read the algebra
cat PRELOAD.md
cat src/README.md

# 3. Run a focused test
./gradlew jvmTest --tests "borg.trikeshed.lib.JoinTest" --console=plain

# 4. Inspect the Job Nexus spine
cat src/commonMain/kotlin/borg/trikeshed/job/JobSupervisorElement.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobReducer.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobNexusFactory.kt

# 5. Browse the gallery (local)
./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 \
  | awk '/^<!doctype html>/,/^<\/html>/' > /tmp/index.html
open /tmp/index.html
```

---

*End of concept map. When you land a change, update the relevant section above — this doc is the maintenance lineage.*
=================
=== ignest.md ===
=================
# Ingest: commonMain Confix / Cursor / CAS design

## Current gap

`utils/ingest` has a useful commonMain scheduling and media-detection SPI, but the payload is still DTO-shaped (`String`, `Map`, `List`) and does not persist extracted bytes, metadata, or manifests through TrikeShed CAS. The JVM Tika adapter detects files but does not expose extracted content as Cursor rows or canonical Confix, and its metadata helper references an undefined `detector`. The root tree has no treedoc production implementation; only `src/commonTest/kotlin/borg/trikeshed/treedoc/TreeDocPipelineTest.kt` defines the intended archive behavior.

## Boundary

Portable code owns identity and structure:

- `ConfixDoc` is the canonical manifest/metadata representation.
- `Cursor` / `RowVec` is the lazy tabular view for documents, frames, and extracted records.
- `CasStore` owns all payload bytes and canonical Confix bytes by `ContentId`.
- commonMain never imports Apache Tika, Apache Camel, `java.io`, or kotlinx JSON/CBOR serializers.

Platform adapters own extraction:

- JVM Tika reads a source and emits portable extracted records and byte payloads.
- suffix/magic-byte detection remains the dependency-free JVM fallback.
- other targets can supply the same SPI without changing archive identity or schemas.

## TreeDoc archive contract

Add `borg.trikeshed.treedoc` in root commonMain.

- `TreeDocument(path, mediaType, bytes)` is input only.
- Split each document into deterministic `maxFrameBytes` chunks.
- Put every chunk in `CasStore`; frame rows store document ordinal, frame ordinal, byte range, chunk CID, and a lazy payload cell that resolves through CAS.
- Put a canonical Confix document manifest in CAS. Its CID is both `ArchiveId` and `ManifestCid`.
- Return a typed meta-series keyed by `TreeDocK`: archive identity, document count, frame count, document cursor, and frame cursor.
- Restore joins frames in ordinal order, verifies each chunk through `CasStore.get`, then verifies the restored document CID before returning bytes.
- Empty archives are valid; `maxFrameBytes <= 0` is rejected.
- Identical ordered inputs and frame size produce identical manifest bytes and archive CID.

The document cursor schema is stable and includes ordinal, path, media type, byte size, document CID, first frame ordinal, and frame count. The frame cursor schema is stable and includes document ordinal, frame ordinal, offset, length, chunk CID, and payload. Cursor metadata uses `ColumnMeta` and `IOMemento`; outside `borg.trikeshed.lib`, access `Series` with `.b(index)`.

## Tika4all ingest contract

Evolve `utils/ingest` without moving JVM libraries into commonMain.

- Replace DTO-only extraction results with a portable envelope containing source identity, media facet, requested projections, canonical Confix metadata CID, payload CID(s), and Cursor projections.
- Persist raw input, extracted text/bytes, and canonical Confix metadata through the injected `CasStore`.
- Expose detected files and extraction outputs as stable Cursor schemas; keep `Series` lazy.
- JVM Tika maps Tika metadata into Confix deterministically (sorted keys, repeated values preserved), stores extracted payloads in CAS, and returns only portable envelope values.
- Keep `JvmMediaFormatChannel` as fallback when Tika fails or is absent.
- Build `utils/ingest` as a composite consumer using `includeBuild("../..")`; do not add portable serializer formats beyond Confix.

## Camel decision

Do not add Apache Camel now. Camel is JVM-only and would duplicate the existing `IngestSchedule` coroutine/channel fan-in while widening the dependency and native-image surface. Permit a future JVM adapter only if a concrete connector requirement appears (for example S3, Kafka, or JMS) and a focused benchmark shows value. Such an adapter must terminate at the same commonMain ingest SPI and must not own manifests, CAS identity, retries, or canonical state.

## Jules split

### J1 — treedoc commonMain archive

Own only:

- `src/commonMain/kotlin/borg/trikeshed/treedoc/**`
- `src/commonTest/kotlin/borg/trikeshed/treedoc/**`

Do not edit `CasStore`, Confix, Cursor, Gradle, or `utils/ingest`. Implement the archive contract above and make the focused common tests pass on JVM plus one non-JVM compile target.

### J2 — tika4all portable ingest

Own only:

- `utils/ingest/src/**`
- `utils/ingest/build.gradle.kts` only if required for existing Tika dependencies

Do not edit root `src/**`. Introduce the commonMain Confix/Cursor/CAS envelope and JVM Tika adapter, preserve suffix fallback, and prove CAS corruption detection and deterministic metadata identity with tests. Do not add Camel.

## Gates

1. Root: `./gradlew compileKotlinJvm` and focused treedoc tests pass.
2. Root: at least `compileKotlinJs` or `compileKotlinWasmJs` compiles the treedoc commonMain implementation.
3. Ingest: `utils/ingest/gradlew jvmTest` or `../../gradlew -p utils/ingest jvmTest` passes using the composite build.
4. No `java.*`, Tika, Camel, kotlinx JSON, or kotlinx CBOR imports in either commonMain tree.
5. CAS corruption is detected on read; identical input yields identical CIDs.
6. All schemas are asserted by column name and `IOMemento`, not only row counts.

=========================
=== initialize-env.sh ===
=========================
set -e
export SDKMAN_DIR="$HOME/.sdkman"

if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
  curl -s "https://get.sdkman.io?ci=true" | bash
fi

set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"

sdk install java 25.0.2-graalce
sdk install kotlin 2.4.10
sdk install gradle 9.6.1
hash -r
set -u
============================================
=== keymux-modelmux-mesh-architecture.md ===
============================================
# KeyMux + ModelMux Mesh Architecture

> Assignment-bound credential leasing and capability-based model routing across
> local and pooled VPS resources, coordinated by the TrikeShed reactor.

## 1. Architectural position

The reactor is the control plane. KeyMux and ModelMux are reactor services, not
independent schedulers and not competing sources of truth.

- **Reactor** owns assignments, worker lifecycle, leases, provider state, and
  observable operational events.
- **KeyMux** resolves authority and issues the least credential needed by one
  assignment for a bounded time.
- **ModelMux** selects a model execution route from local and mesh-advertised
  resources.
- **Forge agents** execute assignments and return results and measurements.
- **Forge/Kanban** projects committed and live reactor state. It does not own
  credentials, leases, provider selection, or worker state.
- **CAS/Confix** carries durable assignment and result identity. Live flows are
  observation and coordination surfaces, not durability.

The system remains local-first. VPS capacity is an additive execution pool, not
the owner of the workspace.

## 2. Deployment topology

```text
                         Pooled execution mesh

      Local workstation                 VPS resource pool
  ┌────────────────────────┐     ┌───────────────────────────┐
  │ Forge / Kanban         │     │ VPS A: local model       │
  │ Reactor + Job Nexus    │◄───►│ VPS B: cloud providers   │
  │ KeyMux + ModelMux      │     │ VPS C: tools / storage   │
  │ CAS / Confix state     │     │ VPS N: mixed capability  │
  └────────────┬───────────┘     └─────────────┬─────────────┘
               │                                │
               └──────── encrypted mesh ────────┘
                                ▲
                                │
                    ┌───────────┴───────────┐
                    │ WRT Linux sentinel    │
                    │ upstream Forge agent  │
                    │ capability-limited   │
                    └───────────────────────┘
```

The WRT sentinel is a mesh participant and Forge agent. It is upstream and
well-positioned to maintain connectivity, discovery, and bounded background
work, but it is not a mandatory relay for every request and it is not a vault.
It accepts only assignments matching its advertised resources and policy.

## 3. Mesh substrate

The existing mesh contract remains authoritative:

- Passive peer discovery through mDNS and UPnP/SSDP.
- One litebike bind point per node.
- Encrypted peer transport through the existing TLS/SSH tunnel line.
- NUID authorization by capability, nonce, and concentric subnet.
- Confix documents as the portable control and replication payload.
- CAS content IDs as durable object identity.

A peer advertisement contains no secret material. It announces:

```text
PeerAdvertisement =
    peer NUID
    + reachable endpoint
    + capability set
    + model cards
    + capacity/load envelope
    + lease-broker reachability
    + advertisement expiry
```

Advertisements are soft state. They expire unless refreshed. Assignment and
result state remains durable through the Job Nexus and CAS.

## 4. Assignment execution flow

```text
1. Assignment is committed to the reactor.
2. Reactor derives required worker, tool, and model capabilities.
3. ModelMux ranks eligible local and mesh execution routes.
4. Selected route declares the provider authority it requires.
5. KeyMux requests an assignment-bound credential lease.
6. NUID policy authorizes assignment, holder, capability, and subnet.
7. Lease broker delivers an opaque handle or sealed credential payload only
   to the selected holder over its encrypted session.
8. Forge agent executes under structured assignment scope.
9. Result, usage, latency, errors, and provider health return to the reactor.
10. Lease is released in `finally`; TTL reclaims it after holder failure.
11. Committed result rebuilds Forge/Kanban projections.
```

Routing and leasing form one transaction boundary: a route is not dispatchable
until its required authority has been leased. A lease is not issued without a
specific committed assignment and selected holder.

## 5. KeyMux: assignment-bound authority

### 5.1 Responsibility

KeyMux answers:

> What authority may this assignment borrow, on which node, for how long, and
> under which provider/model/tool scope?

KeyMux does not choose the best model and does not replicate the vault. It
combines ordered credential sources with reactor-owned lease state and policy.
The current source precedence remains useful: environment, persisted local
source, API source, and reactor source are all `KeySource` bindings.

### 5.2 Credential inventory

A secure arena may hold credentials with different operational roles:

- **Primary** — normal provider access.
- **Guest** — deliberately restricted authority for an external or temporary
  worker.
- **Expiring** — credential whose own validity ends at a fixed time.
- **Backup** — normally dormant authority enabled after policy-defined failure.
- **Coordination** — authority for mesh control operations, never a generic
  model-provider credential.

These are metadata and policy on credential records, not separate KeyMux
instances and not builder modes.

```kotlin
enum class CredentialRole {
    PRIMARY,
    GUEST,
    EXPIRING,
    BACKUP,
    COORDINATION,
}

data class CredentialDescriptor(
    val keyId: String,
    val provider: String,
    val role: CredentialRole,
    val capabilities: Series<String>,
    val permittedSubnets: Series<Subnet>,
    val expiresAtMs: Long?,
    val status: MuxKeyStatus,
)
```

Descriptors are safe to advertise or project. Secret values are not.

### 5.3 Lease contract

```kotlin
data class CredentialLeaseRequest(
    val assignment: Nuid,
    val holder: Nuid,
    val provider: String,
    val modelId: String?,
    val requiredCapabilities: Series<String>,
    val requestedTtlMs: Long,
)

data class CredentialLease(
    val leaseId: String,
    val keyId: String,
    val assignment: Nuid,
    val holder: Nuid,
    val provider: String,
    val modelId: String?,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val authority: CredentialAuthority,
)

sealed class CredentialAuthority {
    data class OpaqueHandle(val handle: String) : CredentialAuthority()
    data class SealedPayload(val ciphertext: ByteSeries) : CredentialAuthority()
}
```

Prefer `OpaqueHandle`: the provider call is made by a trusted broker or sidecar
and the worker never receives reusable secret text. Use `SealedPayload` only
when the remote worker must call the provider directly; seal it to the holder's
session identity and assignment scope.

### 5.4 Lease invariants

- A lease names exactly one assignment and one holder.
- Lease TTL cannot exceed credential expiry or assignment deadline.
- Lease capability cannot exceed the credential descriptor.
- Lease subnet must be contained by the credential policy.
- Release is idempotent.
- Expiration and revocation prevent further broker use immediately.
- Worker completion always attempts release in `finally`.
- Crashed or disconnected workers are recovered by TTL.
- Secret values never appear in Forge state, Kanban events, logs, model cards,
  peer advertisements, cache keys, or operational history.
- Rotation changes the credential behind a stable descriptor; assignment code
  does not need to learn the new secret.

### 5.5 Existing implementation seam

`MuxReactorElement` already owns:

- credential records,
- `leasedTo`, lease start, and lease expiry,
- provider concurrency limits,
- explicit release,
- expired-lease reclamation,
- immutable state snapshots and Kanban events.

The missing operation is requirement-driven acquisition. The current `tick()`
leases the next available key while spawning a synthetic reactor agent. Replace
that implicit coupling with an explicit reactor command:

```kotlin
suspend fun leaseCredential(request: CredentialLeaseRequest): CredentialLeaseResult
```

The reactor validates policy, selects an eligible descriptor, records the
lease, then allows dispatch. `tick()` may still drive scheduling, but it must
not invent the holder or select an unrelated key independently of the
assignment.

## 6. ModelMux: route selection over pooled resources

### 6.1 Responsibility

ModelMux answers:

> Which eligible execution route best satisfies this assignment now?

A route is the composition of a peer, endpoint, model card, required authority,
and current operational envelope.

```kotlin
data class ModelRoute(
    val routeId: String,
    val peer: Nuid,
    val endpoint: ReactorEndpoint,
    val model: AcpModelCard,
    val provider: String,
    val requiredKeyCapabilities: Series<String>,
    val locality: RouteLocality,
    val observations: RouteObservations,
)

enum class RouteLocality { LOCAL, LAN, VPS, GLOBAL_RELAY }

data class RouteObservations(
    val inFlight: Int,
    val capacity: Int,
    val latencyEwmaMs: Double,
    val failureEwma: Double,
    val estimatedCostPerMillionTokens: Double?,
    val backoffUntilMs: Long?,
    val observedAtMs: Long,
)
```

### 6.2 Eligibility before ranking

A route is eligible only when:

- its peer advertisement is fresh,
- its NUID subnet contains the assignment route,
- its model card satisfies the required action and capabilities,
- its node has available capacity,
- its provider is not benched or in backoff,
- KeyMux can issue the required assignment-bound authority,
- assignment policy permits its locality and cost class.

No score may make an ineligible route eligible.

### 6.3 Ranking

After eligibility, use a deterministic lexicographic rank rather than a vague
single "best" score:

1. explicit assignment/provider pin,
2. local or already-resident model,
3. reusable valid cache hit,
4. healthy route with capacity,
5. lower failure/backoff pressure,
6. lower latency class,
7. lower estimated cost,
8. stable route ID tie-break.

Weights may later refine ranking, but deterministic ordering makes routing
explainable and reproducible. Every decision emits a redacted `RouteDecision`
with candidates, rejection reasons, selected route, and observation timestamp.

### 6.4 Cache role

Cache state informs routing but does not become provider authority.

- A valid response-cache hit may satisfy the assignment without a new lease.
- A model-metadata cache hit avoids rediscovery.
- A cache miss is an observation, not automatically a provider switch.
- Cache keys contain request identity and model/provider identity, never secret
  values or lease handles.
- Cached responses remain subject to assignment privacy and TTL policy.

### 6.5 Failure and retry

Retries are assignment attempts, not hidden loops inside the HTTP client.

- Record the failed route and error class.
- Release or revoke the failed attempt's lease.
- Recompute eligibility from current observations.
- Select a different route when policy allows.
- Preserve one assignment identity and append attempt facts to its causal log.
- Never submit the same non-idempotent tool action twice without an explicit
  idempotency key.

## 7. WRT Linux sentinel

The sentinel is a small, continuously available Forge agent on the upstream
router. Its useful capabilities are expected to be connectivity-oriented:

- peer discovery and advertisement refresh,
- encrypted tunnel maintenance,
- reachability and latency observation,
- low-cost queue watching,
- bounded assignment dispatch or forwarding,
- lease renewal/release on behalf of work it owns,
- optional lightweight provider calls when hardware and policy allow.

The sentinel must not:

- persist the entire secure arena,
- broadcast or log credential values,
- become the sole mesh coordinator,
- inspect unrelated network payloads,
- accept work outside its NUID capabilities,
- run memory-heavy local models unless explicitly advertised,
- become a required hop between healthy peers.

If the sentinel disappears, existing peer sessions and assignments continue.
Only sentinel-owned leases and connectivity duties enter TTL recovery.

## 8. Security model

### Control plane

- NUID identifies bearer authority and concentric subnet.
- Assignment identity binds dispatch, lease, attempt, and result.
- Peer advertisements are signed or authenticated by the mesh session.
- Replay protection uses assignment identity, lease identity, expiry, and nonce.

### Data plane

- Mesh transport is encrypted.
- Secret delivery is point-to-point and holder-bound.
- Provider responses follow assignment data policy.
- CAS stores encrypted sensitive payloads or redacted durable facts, never raw
  reusable credentials.

### Observability

Safe telemetry includes:

- key ID or descriptor ID,
- provider and model ID,
- holder and assignment NUID,
- lease state and expiry,
- route decision and rejection reason,
- latency, token usage, cache state, and error class.

Unsafe telemetry includes:

- credential values,
- authorization headers,
- opaque lease handles,
- sealed payload bytes,
- full prompts or responses unless assignment policy explicitly permits them.

## 9. State ownership

| State | Canonical owner | Persistence |
|---|---|---|
| Assignment lifecycle | Job Nexus / reactor | WAL + CAS/Confix |
| Credential value | Secure arena | arena-specific encrypted storage |
| Credential descriptor | KeyMux inventory | encrypted config / reactor bootstrap |
| Active lease | Reactor lease ledger | durable event or recoverable TTL state |
| Model and node advertisement | Mesh peer registry | soft state with expiry |
| Route observation | Reactor / ModelMux | bounded operational history |
| Model response cache | Reactor model cache | configured local persistence |
| Forge/Kanban view | Projection only | rebuilt from canonical state |

## 10. Delivery cuts

### T-MESH-1 — Assignment-bound credential leasing

- Add `CredentialDescriptor`, `CredentialLeaseRequest`, and
  `CredentialLeaseResult` in commonMain.
- Add explicit requirement-driven lease acquisition to `MuxReactorElement`.
- Preserve release and TTL reclamation already present.
- Change ModelMux call paths to acquire a lease before dispatch and release it
  in `finally`.
- Verify provider/model mismatch is rejected and no secret appears in state or
  events.

### T-MESH-2 — Mesh resource advertisements and ModelMux routes

- Define expiring `PeerAdvertisement` and `ModelRoute` commonMain algebra.
- Project local and VPS model cards into one route series.
- Implement eligibility and deterministic ranking with rejection reasons.
- Verify capability, subnet, capacity, backoff, cache, latency, and cost order.
- Keep discovery and transport behind existing reactor/litebike endpoints.

### T-MESH-3 — WRT sentinel deployment adapter

- Compose the existing litebike bind point, peer registry, reactor endpoint,
  KeyMux lease client, and ModelMux route executor for WRT Linux.
- Advertise only actual sentinel capabilities.
- Prove that direct peer execution survives sentinel shutdown.
- Prove sentinel-owned assignment failure releases by `finally` or TTL.

## 11. Acceptance evidence

1. One committed assignment selects an eligible VPS route and obtains exactly
   one matching credential lease.
2. A route lacking authority is rejected before dispatch.
3. Lease state is visible by descriptor ID, assignment, holder, and expiry;
   secret values are absent from every projection and log.
4. Successful completion releases the lease.
5. Agent termination causes TTL reclamation and makes the credential available.
6. Provider backoff or VPS loss causes a new route decision with a recorded
   rejection reason.
7. A valid response-cache hit completes without issuing a provider lease.
8. Two peers continue direct execution after the WRT sentinel is stopped.
9. All mesh control payloads round-trip through Confix and preserve NUID and
   assignment identity.
10. Existing KeyMux, ModelMux, and reactor tests remain green.

## 12. Non-goals

- No peer-to-peer replication of credential values.
- No new consensus protocol.
- No universal central gateway.
- No new HTTP or mesh framework.
- No cloud-owned workspace state.
- No secret material in Forge/Kanban.
- No replacement of the existing reactor, litebike, NUID, CAS, or Confix
  foundations.

===========================
=== pijul-kmp-design.md ===
===========================
# Libpijul KMP Port + Git Gateway Design

## Goal
Port libpijul (Rust) to Kotlin Multiplatform for CRDT-based patch theory, with a bidirectional Git gateway for interop.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                      KMP Libpijul Core                              │
├─────────────────────────────────────────────────────────────────────┤
│  commonMain                                                         │
│  ├── pijul/                                                         │
│  │   ├── Patch.kt           — Patch (hunk + dependencies)           │
│  │   ├── Change.kt          — AddFile, AddDir, Remove, Move, Edit   │
│  │   ├── Hash.kt            — BLAKE3 hash (CID-compatible)          │
│  │   ├── RepoState.kt       — Branch state, pristine, patches       │
│  │   ├── Pristine.kt        — In-memory filesystem (BTree + files)  │
│  │   ├── Graph.kt           — Patch dependency graph (CRDT)         │
│  │   ├── ApplyResult.kt     — Success / Conflict / Error            │
│  │   └── RecordOptions.kt   — Author, message, timestamp           │
│  │                                                              │
│  │   ├── backend/                                                      │
│  │   │   ├── Backend.kt          — Storage abstraction              │
│  │   │   ├── CasBackend.kt       — CAS-backed storage               │
│  │   │   └── FileBackend.kt      — Local file storage               │
│  │   │                                                              │
│  │   └── gateway/                                                      │
│  │       ├── GitGateway.kt       — Git ⇄ Pijul conversion           │
│  │       ├── GitImporter.kt      — Git commits → Pijul patches      │
│  │       ├── GitExporter.kt      — Pijul patches → Git commits      │
│  │       └── ConflictResolver.kt — Merge conflicts via CRDT         │
│  │                                                              │
│  ├── jvmMain/                                                         │
│  │   └── jni/                — JNI bindings to libpijul (optional)   │
│  │                                                              │
│  └── nativeMain/                                                      │
      └── cinterop/            — Native libpijul FFI (optional)         │
```

## CRDT Patch Theory

### Patch Identity
- Each patch has a **globally unique hash** (BLAKE3 of: dependencies + change + metadata)
- Patches form a **DAG** via explicit dependencies
- No central authority — patches can be created independently

### Change Types
```
Change =
  | AddFile(path, content_hash, executable)
  | AddDir(path)
  | Remove(path)
  | Move(old_path, new_path)
  | Edit(path, diff_hunk)           // line-level diff
  | Metadata(path, key, value)
```

### Dependency Graph (CRDT)
- **Concurrent patches** = no dependency edge between them
- **Sequential patches** = explicit dependency edge
- **Merge** = union of patch sets + transitive closure of deps
- **Conflict** = two patches editing same lines with different content

## Git Gateway Design

### Git → Pijul (Import)
```
Git commit → Tree diff → Changes → Pijul patches
  │
  ├─ Parse commit: tree, parent(s), author, message, timestamp
  ├─ Diff against parent: tree walk → change list
  ├─ Convert each change to Pijul Change
  ├─ Assign dependencies: parent commits = patch deps
  ├─ Hash = BLAKE3(parents + changes + metadata)
  └─ Apply to pristine
```

### Pijul → Git (Export)
```
Pijul patches (topological order) → Git commits
  │
  ├─ Sort patches by dependency DAG
  ├─ For each patch:
  │   ├─ Apply to Git index
  │   ├─ Create commit with author/message/timestamp from patch
  │   └─ Parent = previous commit in topological order
  └─ Result: linear Git history matching Pijul's causal order
```

### Bidirectional Sync
```
Pijul repo          Git repo
    │                  │
    │  record          │  commit
    │  patch(hash)     │  commit(hash)
    │  with deps       │  with parent
    ▼                  ▼
┌─────────────────────────────┐
│   Mapping Table             │
│  patch_hash ↔ commit_hash   │
│  branch_name ↔ branch_name  │
└─────────────────────────────┘
```

## KMP Implementation Strategy

### 1. Pure Kotlin First (commonMain)
- Implement core CRDT logic in pure Kotlin
- BLAKE3 hashing via `kotlinx-serialization` + `com.soywiz.klock.b3`
- BTree for pristine using `kotlinx.collections.immutable`

### 2. JNI/Native Acceleration (Optional)
- JVM: JNI wrapper around libpijul (Rust `cdylib`)
- Native: Direct cinterop with libpijul
- JS/Wasm: Pure Kotlin fallback

### 3. CasStore Integration
- Patches stored as CAS objects (BLAKE3 = CID)
- Pristine files stored as CAS blobs
- Dependency graph as Confix docs

## API Surface

```kotlin
// Core
interface PijulRepo {
    suspend fun init(branch: String = "main"): Result<Unit>
    suspend fun record(changes: List<Change>, opts: RecordOptions): Result<Patch>
    suspend fun apply(patch: Patch): ApplyResult
    suspend fun unrecord(patchHash: Hash): Result<Unit>
    suspend fun log(branch: String): Series<Patch>
    suspend fun diff(from: Hash, to: Hash): Series<Change>
    suspend fun branches(): Series<String>
    suspend fun checkout(branch: String): Result<Unit>
}

// Git Gateway
interface GitGateway {
    suspend fun importGitRepo(gitDir: File, pijulDir: File): Result<ImportReport>
    suspend fun exportToGit(pijulDir: File, gitDir: File): Result<ExportReport>
    suspend fun sync(pijulDir: File, gitDir: File): Result<SyncReport>
}

// CRDT Merge
interface PatchSet {
    fun union(other: PatchSet): PatchSet
    fun intersect(other: PatchSet): PatchSet
    fun conflicts(): Series<Conflict>
}
```

## Verification

```kotlin
// Round-trip test
suspend fun testGitPijulRoundtrip() {
    val gitDir = createTempGitRepo()
    val pijulDir = createTempPijulRepo()
    
    // 1. Git → Pijul
    GitGateway.importGitRepo(gitDir, pijulDir)
    
    // 2. Pijul → Git (new repo)
    val gitDir2 = createTempGitRepo()
    GitGateway.exportToGit(pijulDir, gitDir2)
    
    // 3. Verify commit graph isomorphism
    assertCommitGraphsEqual(gitDir, gitDir2)
    
    // 4. Verify content equality
    assertTreesEqual(gitDir, gitDir2)
}
```

## Dependencies

| Crate/Lib | Purpose | KMP Target |
|-----------|---------|------------|
| `blake3` | Hashing | JVM/Native/JS (pure Kotlin) |
| `b-tree` | Pristine index | `kotlinx.collections.immutable` |
| `libpijul` | Reference impl | JNI (JVM), cinterop (Native) |
| `git2` | Git ops | `kt-git` / JGit |

## Migration Path

1. **Phase 1**: Pure Kotlin CRDT core + CAS backend
2. **Phase 2**: Git gateway (import/export)
3. **Phase 3**: JNI binding for performance
4. **Phase 4**: Native cinterop for native targets
5. **Phase 5**: Wasm/JS for browser PWA

## Files to Create

```
src/commonMain/kotlin/borg/trikeshed/pijul/
├── Hash.kt
├── Change.kt
├── Patch.kt
├── RepoState.kt
├── Pristine.kt
├── Graph.kt
├── ApplyResult.kt
├── RecordOptions.kt
├── backend/
│   ├── Backend.kt
│   ├── CasBackend.kt
│   └── FileBackend.kt
└── gateway/
    ├── GitGateway.kt
    ├── GitImporter.kt
    ├── GitExporter.kt
    └── ConflictResolver.kt
```
=================
=== rewire.md ===
=================
# Forge Rewire — User-Centric Workspace Architecture

> **What Forge is:** a local-first, mesh-native workspace where documents,
> boards, and knowledge graphs are the same thing. The blackboard IS the
> database. The Rete engine IS the inference layer. The force-directed
> graph IS the UI. The VFS is btrfs-on-JBOD with COW snapshots. The VCS
> gateway is pijul<->git over CAS/IPFS/IPNS.
>
> **What this document is:** the architecture rewire. Not vaporware —
> every claim maps to code that exists or is one focused cut away.

---

## 0. Storage Unification — One CID, Five Lenses

The foundational rule: **the bytes are the thing; the views are lenses,
not copies.** A CAS blob never gets materialized five ways. It gets stored
once as Confix bytes, and the tag inside the bytes decides which projection
applies. Everything else is a lazy read of the same content address.

```
cas.get(cid) → ByteArray                        (auxiliary CAS — the raw lens)
     │
     ├─ materialized   → the bytes exist in the store (LinearHashMap / mmap)
     │
     ├─ confixDoc(bytes) → ConfixIndex → cells   (reified — decode on demand)
     │
     ├─ tag == "btree-page"      → {keys[], values[], children[]}  (btrfs content)
     ├─ tag == "causal-node"     → {causalKey, deps[], payload}    (graph tree)
     └─ tag == "treedoc-manifest" → {docs[], frames[]}            (archive)
```

Three mechanisms make this work:

**1. Tag dispatch, not storage dispatch.** You don't decide "this blob is
a btree page" when you store it. You read the bytes, look at the Confix
tag/kind field, and project. This is the existing `ConfixIndexK<R>`
GADT-key pattern — `facet(TreeCursor)` gives a Cursor, `facet(CausalNode)`
gives a graph node, `facet(BtreePage)` gives a page. The key fixes the
result type; the bytes stay bytes. No parallel storage systems.

**2. Edges are CIDs, so the graph is free.** A graph node is a Confix doc
whose `deps` field is an array of CIDs. Traversal is
`cas.get(dep) → confixDoc → recurse`. The blackboard's causal graph becomes
CAS-backed for free — nodes are content addresses, edges are references
into the same store. Force-directed layout consumes this directly: CID =
node identity, deps = edge list. CAS dedup means two nodes sharing a
dependency literally share the blob — diamond structures are physical.

**3. btrfs semantics fall out of CIDs + COW discipline.** A btrfs tree is
a COW page tree whose root is a content address. `CowBPlusTree` already
does this: pages as Confix docs in CAS, root is a CID, checkpoint +
hydrate. Snapshot = record the root CID. Send/recv = walk two root CIDs
and emit pages reachable from one but not the other (shared pages have
identical CIDs). Compression is TreeDoc frame chunking. The "btrfs
content" isn't a separate format — it's Confix pages obeying the COW rule.

| Lens | Existing code | State |
|------|--------------|-------|
| auxiliary CAS | `CasStore.get(cid)` → bytes, digest-verified | done |
| materialized | `LinearHashMap<ContentId, ByteArray>` | done; `MmapCasStore` pending (T4) |
| reified | `confixDoc(bytes)` → index → `cell.reify()` | done |
| btrfs content | `CowBPlusTree` pages in CAS, root CID | done for job snapshots |
| graph trees | `BlackboardDagCausalGraph` | **in-memory, NOT CAS-backed** — the gap |
| Confix at rest | manifest via `cas.put(confixDoc)` | done (treedoc, job frames) |

**The one new piece:** a projection registry — `project(cid): Lens` where
`Lens = Raw | Cursor | BtreePage | CausalNode | Manifest`, dispatched on
the doc's tag. Sealed class + existing facet machinery. One file, no new
storage, no new formats. Turns "five systems that happen to share a CAS"
into "one store with five lenses." Task: T-CAS-PROJ-1 in `doc/todo.md`.

---

## 1. The Unified Surface (Blackboard + Rete + Types + UI)

The workspace is not a set of views over a database. It is one continuous
surface where the blackboard, the rule engine, the type system, and the
force-directed graph are the same thing seen from different angles.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE BLACKBOARD SURFACE                                              │
│                                                                      │
│  One Confix document. One Cursor. Every projection is a slice.       │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │
│  │   Page      │  │   Board     │  │   Graph     │  │  Gallery   │ │
│  │  (blocks)   │  │  (cards)    │  │  (force)    │  │  (widgets) │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────┬──────┘ │
│         └─────────────────┴────────────────┴────────────────┘        │
│                         BlackboardSurface                            │
│                    confixDoc(persistedJson) → Cursor                 │
│                         project(cursor) → rows                        │
├──────────────────────────────────────────────────────────────────────┤
│  RETE INFERENCE   (the rule engine IS the type system)               │
│                                                                      │
│  ReteWorkingMemory  ← facts are typed (TypeEvidence → IOMemento)    │
│  ReteAlphaMemory    ← predicates dispatch on type tags               │
│  ReteBetaMemory     ← joins on facet identity (leftFacetId=right)    │
│  ReteAgenda         ← salience↓, sequence↑, deterministic pop        │
│  ReteRefraction     ← one firing per (ruleVersion, supportCIDs)      │
│                                                                      │
│  The Rete engine does not just fire rules. It infers semantic types  │
│  from structure and dispatches work to the right handler:            │
│  - TypeEvidence.sample(bytes) → IOMemento type code                  │
│  - ConfixIndexK facet plan → typed ColumnMeta per column             │
│  - TypeDefOracle lattice → IsA edges → semantic subtyping            │
│  - Rete rules match on type + structure, not just value              │
│                                                                      │
│  Type dispatch is not a switch statement. It is the alpha memory     │
│  of the Rete network — predicates are type predicates, and the       │
│  network routes facts to the right beta node by type identity.       │
├──────────────────────────────────────────────────────────────────────┤
│  FORCE-DIRECTED UI   (the graph IS the workspace)                    │
│                                                                      │
│  BlackboardDagCausalGraph — nodes are cards, edges are causal links  │
│  ForgeBlackboardCamera — momentum, tilt, zoom, RTS surface           │
│  ForgeBlackboard3D — true 3D orbit, elevation per section            │
│                                                                      │
│  The force layout is not a visualization. It is the workspace.       │
│  Cards attract their dependencies. Documents repel their neighbors.  │
│  The camera momentum carries you through the graph. Sections sit at  │
│  different elevations (gallery above board above page).              │
│                                                                      │
│  Click a card → it expands into a document. Drag a card → it        │
│  reorders its column. Zoom out → the board becomes a constellation. │
│  The physics is the UX.                                               │
├──────────────────────────────────────────────────────────────────────┤
│  SEMANTIC TYPE INFERENCE   (types emerge from structure)             │
│                                                                      │
│  TypeEvidence.sample(Series<Byte>) → deduced IOMemento              │
│  - Statistical analysis of byte patterns                             │
│  - Deduces: IoByte, IoInt, IoLong, IoFloat, IoDouble, IoString,     │
│    IoChar, IoBoolean, IoByteArray, IoMap, IoArray                   │
│                                                                      │
│  TypeDefOracle — typedef lattice from Confix schemas                 │
│  - IsA edges: "Person isA Entity", "Employee isA Person"            │
│  - Lattice: transitive closure, supertypes, subtypes                 │
│  - Used by Rete for rule matching: "fire when fact isA Person"      │
│                                                                      │
│  ConfixFacetPlan — compiled from job-nexus.schema.json               │
│  - Typed columns: each column has IOMemento type + ColumnMeta        │
│  - Facet dispatch: ConfixIndexK<R> keys fix the result type          │
│  - No runtime casts at the call site — the key IS the type           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. The Storage Layer (btrfs-on-JBOD, VFS emulation)

The VFS is not a filesystem. It is a content-addressed block store with
btrfs semantics running on JBOD (just a bunch of disks). The kernel
algebra treats it as `Series<Byte>` all the way down.

```
┌──────────────────────────────────────────────────────────────────────┐
│  BTRFS(TINY) ON JBOD                                                 │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  VFS SURFACE   (what the workspace sees)                        │ │
│  │                                                                 │ │
│  │  /workspace/                                                    │ │
│  │  ├── documents/        ← TreeDoc archives (CAS + manifest)      │ │
│  │  ├── boards/           ← KanbanFSM state (Confix doc)           │ │
│  │  ├── graphs/           ← BlackboardDagCausalGraph (cursor)      │ │
│  │  ├── snapshots/        ← COW snapshots (btrfs-style)            │ │
│  │  └── volumes/          ← JBOD volume mounts                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  BTRFS SEMANTICS   (copy-on-write, snapshot, send/recv)         │ │
│  │                                                                 │ │
│  │  CowBPlusTree — COW pages in CAS                                │ │
│  │  - Every write is a new page, never in-place                    │ │
│  │  - Pages addressed by ContentId (SHA-256)                       │ │
│  │  - Checkpoint = root CID + sequence number                      │ │
│  │  - Recovery = hydrate from checkpoint + replay tail              │ │
│  │                                                                 │ │
│  │  Snapshot = freeze the root CID. The tree is immutable.          │ │
│  │  Send/recv = serialize the delta between two CIDs.               │ │
│  │  Compression = frame-level (TreeDoc maxFrameBytes chunks).       │ │
│  │  Deduplication = same bytes → same CID → stored once.           │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  JBOD ARRAYS   (arrays of arrays, no RAID)                      │ │
│  │                                                                 │ │
│  │  Volume { blockSize, capacity, read(lba, count), write(lba,    │ │
│  │  data), sync() }                                                │ │
│  │                                                                 │ │
│  │  JBOD = N volumes, each with its own block size and capacity.   │ │
│  │  The array of arrays is the storage topology:                   │ │
│  │  - Volume 0: NVMe SSD (fast, small) → hot CAS blocks            │ │
│  │  - Volume 1: HDD (slow, large) → cold CAS blocks                │ │
│  │  - Volume 2: tmpfs (ephemeral) → WAL tail, not yet committed   │ │
│  │                                                                 │ │
│  │  LiburingVolume — io_uring-backed block device                  │ │
│  │  PosixVolume — POSIX fd-backed block device                     │ │
│  │  IndexedDbVolume — browser IndexedDB block device               │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  CAS/IPFS   (content addressing as the naming layer)            │ │
│  │                                                                 │ │
│  │  CasStore — LinearHashMap<ContentId, ByteArray>                 │ │
│  │  - SHA-256 CID → blob                                           │ │
│  │  - Digest verification on read                                  │ │
│  │  - put(doc) → canonical CBOR → CID                              │ │
│  │                                                                 │ │
│  │  IPFS bridge (todo, jules):                                     │ │
│  │  - CAS blocks are IPFS blocks (same CID → same content)         │ │
│  │  - IPNS names point to CAS manifest CIDs                        │ │
│  │  - Workspace publish = IPNS update to latest ArchiveId          │ │
│  │  - Workspace sync = IPFS pin + IPNS resolve                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. The VCS Gateway (pijul ↔ git ↔ IPFS)

The workspace is versioned. Every document, every board state, every
graph is a snapshot in a COW tree. The VCS gateway bridges three
naming systems:

```
┌──────────────────────────────────────────────────────────────────────┐
│  VERSION CONTROL GATEWAY                                             │
│                                                                      │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │   pijul      │◄──►│     git      │◄──►│  IPFS/IPNS   │          │
│  │  (patches)   │    │  (objects)   │    │  (blocks)    │          │
│  └──────────────┘    └──────────────┘    └──────────────┘          │
│                                                                      │
│  pijul side:                                                         │
│  - Patches are Confix documents (typed, canonical, deterministic)   │
│  - Dependencies are causal edges in the blackboard graph            │
│  - Cherry-pick = cursor projection over the patch DAG               │
│  - The blackboard IS the patch repository                           │
│                                                                      │
│  git side:                                                           │
│  - Objects are CAS blobs (blob = content, tree = manifest,          │ │
│    commit = snapshot root CID)                                      │
│  - Refs are IPNS names (mutable pointer to immutable CID)           │
│  - git push = IPFS pin + IPNS update                                │
│  - git pull = IPNS resolve + IPFS fetch                             │
│                                                                      │
│  IPFS/IPNS side:                                                     │
│  - IPFS blocks are CAS blobs (same SHA-256 → same CID)              │
│  - IPNS names are workspace pointers (/ipns/workspace-alice)        │
│  - Pinning = keep the CAS block alive (don't GC)                    │
│  - Publishing = IPNS update to the latest snapshot CID              │
│                                                                      │
│  The gateway is not a sync tool. It is a naming bridge.             │
│  pijul names patches, git names objects, IPNS names workspaces.    │
│  The CAS is the common ground — same content, same CID.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. The Agent Layer (Modelmux + Kanban + Jules)

Modelmux agents are job executors that use LLMs for reasoning. The kanban
is the visible trace of their conversations. Jules is the remote executor.

```
┌──────────────────────────────────────────────────────────────────────┐
│  AGENT WORKFLOW                                                      │
│                                                                      │
│  User types "fix the login bug" into the board                       │
│    │                                                                 │
│    ├─ JobCommand.Submit(jobId, idempotencyKey)                      │
│    ├─ Kanban card appears in "triage" column                        │
│    │                                                                 │
│    ├─ ReteNetwork fires: "new card in triage → route to agent"      │
│    ├─ ModelMuxBuilder.route("chat", requiredCaps=["tools"])         │
│    ├─ Agent picks up the card                                       │
│    ├─ JobCommand.Start(jobId)                                       │
│    ├─ Card moves to "active"                                        │
│    │                                                                 │
│    ├─ Agent reads code, forms hypothesis                            │
│    ├─ JobCommand.Progress(jobId, 0.3)                               │
│    ├─ Card updates progress bar                                     │
│    │                                                                 │
│    ├─ Agent asks clarifying question                                │
│    ├─ JobCommand.Block(jobId, "need clarification")                 │
│    ├─ Card moves to "blocked", question appears in comments         │
│    │                                                                 │
│    ├─ User answers in the board                                     │
│    ├─ JobCommand.Progress(jobId, 0.7)                               │
│    ├─ Card unblocks, agent continues                                │
│    │                                                                 │
│    ├─ Agent writes fix, opens PR                                    │
│    ├─ JobCommand.Complete(jobId, prUrl)                             │
│    ├─ Card moves to "review"                                        │
│    │                                                                 │
│    ├─ CI passes, PR merged                                          │
│    ├─ JobCommand.Ack(jobId)                                         │
│    ├─ Card moves to "done"                                          │
│    │                                                                 │
│    └─ The blackboard graph grows a new causal node                  │
│       linking the card to the commit to the files changed           │
│                                                                      │
│  Jules sessions are the remote execution surface. Each Jules job    │
│  is a JobCommand with a typed payload (the ArchiveId of the work    │
│  package). The job reads from CAS, produces Confix rows, commits    │
│  through the supervisor. The projection rebuilds. The board moves.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. The Ingest Pipeline (Corpus → Workspace)

Drop a document. Get a workspace. Progressive rendering by Jules jobs.

```
Document dropped (PDF, markdown, CSV, image)
  │
  ├─ 1. DETECTION (suffix + magic bytes)
  │     Tika (JVM) or suffix-based fallback
  │     TypeEvidence.sample(bytes) → IOMemento type code
  │
  ├─ 2. STORAGE (TreeDocPipeline)
  │     Split into frames (deterministic maxFrameBytes)
  │     CAS.put(frame) → ContentId (SHA-256)
  │     Build manifest → ArchiveId
  │     Snapshot the workspace root CID (btrfs-style COW)
  │
  ├─ 3. TYPE INFERENCE (semantic, not just MIME)
  │     TypeDefOracle: extract IsA edges from structure
  │     ConfixFacetPlan: compile column types from schema
  │     ReteAlphaMemory: match on inferred type, not just value
  │
  ├─ 4. PROJECTION (ConfixDoc → Cursor → BlackboardSurface)
  │     Document cursor: path, mediaType, cid, firstFrameOrdinal, frameCount
  │     Frame cursor: docOrdinal, frameOrdinal, offset, length, chunkCid
  │     BlackboardSurface.project(cursor) → UI rows
  │
  ├─ 5. PROGRESSIVE RENDERING (Jules jobs, one per enrichment pass)
  │     Job 1: extract structure → ForgeDoc block tree
  │     Job 2: extract metadata → property database rows
  │     Job 3: extract references → causal graph edges
  │     Job 4: generate summary → card on the board
  │     Job 5: semantic typing → Rete facts (this isA that)
  │     Job N: cross-link → mesh sync, IPFS pin
  │
  ├─ 6. WORKSPACE UPDATE (the surface rebuilds)
  │     Page gets new blocks
  │     Board gets new cards
  │     Graph gets new nodes (force-directed layout adjusts)
  │     Gallery gets a preview card
  │     All projections rebuild from committed state
  │
  └─ 7. VERSIONING (pijul/git/IPFS gateway)
       Snapshot the workspace root CID
       Publish to IPNS (/ipns/workspace-alice)
       Push to git (objects = CAS blobs, refs = IPNS names)
       The corpus is versioned, the workspace is a snapshot
```

---

## 6. The Mesh Layer (Litebike + SSH + UPnP)

The mesh is how workspaces find each other and sync. Discovery is
passive (UPnP/SSDP announce). Transport is encrypted (SSH tunnels).
The litebike listener is the single bind point.

```
┌──────────────────────────────────────────────────────────────────────┐
│  MESH TOPOLOGY                                                       │
│                                                                      │
│  ┌─────────────┐         ┌─────────────┐         ┌─────────────┐  │
│  │  Laptop     │◄───────►│  Desktop    │◄───────►│  Server     │  │
│  │  (browser)  │  mDNS   │  (daemon)   │   SSH   │  (daemon)   │  │
│  └─────────────┘         └─────────────┘         └─────────────┘  │
│                                                                      │
│  Discovery: UPnP/SSDP on 239.255.255.250:1900                       │
│  - NOTIFY * HTTP/1.1                                                │
│  - NT: urn:trikeshed:workspace:1                                     │
│  - USN: uuid:workspace-<nuid>                                       │
│  - Each node announces presence + capability set                    │
│                                                                      │
│  Transport: SSH tunnels over litebike Tls protocol                  │
│  - Each node runs a lightweight SSH server                          │
│  - Mesh peers authenticate via NUID (capability + nonce + subnet)   │
│  - Sync is Confix document replication over the tunnel              │
│  - The tunnel is the wire, the Confix doc is the payload            │
│                                                                      │
│  The litebike listener is the only bind point:                       │
│  - JvmLitebikeBindAdapter opens one AsynchronousServerSocketChannel│
│  - ProtocolDetector detects Http/Tls/Socks5/Bonjour/Upnp           │
│  - Bytes route to the matching CCEK slot                            │
│  - No framework, no embedded server, no spring boot                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 7. The User Experience (What It Feels Like)

You open Forge. You see a **force-directed graph** of your workspace —
cards, documents, and references arranged by causal proximity. The graph
has momentum. You can orbit it, zoom into it, tilt it.

You click a card. It expands into a document with blocks (text, headings,
todos, code). You type a task. It becomes a kanban card. A modelmux agent
picks it up, asks a question, writes code, opens a PR. The card moves
through columns as the work progresses. You never dragged it.

You drop a PDF. It becomes a document with extracted blocks, a set of
kanban cards for follow-up work, and a graph node linked to the source.
Jules jobs progressively render and tabulate the corpus — each pass
enriches the projection without human intervention.

You open Forge on your laptop. It discovers your desktop via UPnP/mDNS,
syncs the workspace over the SSH mesh, and continues where you left off.
The graph is the same. The cards are the same. The documents are the same.
The CAS is the common ground.

---

## 8. What This Is Not

- **Not a Notion clone with a database backend.** The "database" is a
  Confix document. The "queries" are cursor projections. The "indexes"
  are MultiIndexK facets. There is no SQL, no ORM, no migration.

- **Not a chat UI with a kanban skin.** The kanban is a projection over
  committed job state. The agents are job executors. The board is the
  ledger, not the interface.

- **Not a cloud service with a local cache.** The workspace is local-first.
  The mesh is additive. The cloud is a deployment target for the static
  shell, not a runtime dependency.

- **Not a framework.** No spring, no ktor, no embedded server. The
  litebike listener is the only bind point. The CCEK lifecycle is the
  only choreography. The kernel algebra is the only vocabulary.

- **Not a filesystem.** The VFS is a content-addressed block store with
  btrfs semantics on JBOD. Files are TreeDoc archives. Directories are
  Confix documents. Snapshots are frozen root CIDs. The array of arrays
  is the storage topology, not a RAID level.

---

## 9. Immediate Cuts (What Lands Next)

1. **Confix ingest adapter** — `ForgeKanbanIngest` already parses markdown
   into Rete facts + causal nodes + Kanban cards. Extend to accept
   TreeDoc archives (the corpus path).

2. **Modelmux kanban agent** — a JobCommand handler that routes cards
   through modelmux, tracks conversation state in the card payload, and
   commits state transitions through the supervisor.

3. **UPnP workspace discovery** — `JvmMulticastAdapter` already joins
   mDNS/SSDP groups. Add a workspace announcement payload (NUID + port +
   capability set) and a peer registry.

4. **SSH mesh transport** — litebike Tls protocol already exists. Add an
   SSH tunnel layer that carries Confix document replication between
   workspace daemons.

5. **IPFS/IPNS bridge** — CAS blocks are IPFS blocks (same SHA-256).
   IPNS names point to CAS manifest CIDs. Workspace publish = IPNS
   update to latest ArchiveId. Workspace sync = IPFS pin + IPNS resolve.

6. **Progressive rendering** — Jules jobs that read TreeDoc archives
   from CAS and project them into ForgeDoc block trees, property
   database rows, and causal graph edges. Each job is a JobCommand
   with the ArchiveId as payload.

7. **btrfs snapshot/restore** — `CowBPlusTree` already does COW pages
   in CAS. Add snapshot (freeze root CID), send (serialize delta
   between two CIDs), recv (apply delta to a target tree).

8. **Force-directed blackboard** — `BlackboardDagCausalGraph` already
   has the causal edges. Add the force layout (spring/electrostatic
   simulation) and wire it into the `ForgeBlackboardCamera` momentum
   model.

---

*This document is the architecture rewire. The blackboard is the
database. The Rete engine is the inference layer. The force-directed
graph is the UI. The VFS is btrfs-on-JBOD with COW snapshots. The VCS
gateway is pijul<->git over CAS/IPFS/IPNS. Every claim maps to code
that exists or is one focused cut away.*

================
=== taste.md ===
================
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

===============
=== todo.md ===
===============
# TrikeShed Local-First Reactor / litebike Taxonomy Integration

This is the architectural worklog and task queue for dividing the TrikeShed
KMP targets into inheritance-based domains around a shared, addressable reactor
blackboard. It preserves the `Join`/`Series`/`Cursor` algebra in `commonMain`
and adapts the `../litebike/` taxonomy into the TrikeShed source tree.

## Core hierarchy (non-designated NUID -> subnets -> workgroups -> capabilities)

```
NUID = capability + nonce + subnet
    |
    +-- subnet  (e.g., local, lan.localhost, mesh.worker.<id>, global.relay)
    |       |
    |       +-- workgroup  (a set of workers registered on a reactor slot)
    |               |
    |               +-- capability  (process, CAS, wireproto, sctp, mesh, modelmux, ...)
```

Capabilities are traits. Workgroups advertise a `TraitSpace`. Subnets scope
where a NUID is valid. The undesignated random nonce makes each NUID a bearer
token, not an identity.

## DRY chokepoints (must remain thin and stable)

1. `Reactor` — `WamBlock`, `SessionState`, `TransformCode`, `Protocol` from litebike.
2. `Nuid` — `Join<Capability, Join<Nonce, Subnet>>` authorization context.
3. `Volume` — `BlockArray` + `BootBlock` block storage surface.
4. `ReactorEndpoint` — `ReactorAction`/`ReactorResult` request/response algebra.

All higher layers (CAS, wireproto, mesh, modelmux, litebike gates) must use
these interfaces. No platform IO leaks into `commonMain`.

## Platform targets

- `commonMain` — algebra and shared interfaces only.
- `jvmMain`/`nativeMain` — real Btrfs/JBOD userspace, io_uring, posix sockets.
- `jsMain` — Node localhost proxy and browser PWA runtime.
- `wasmJsMain` — browser PWA with localStorage/IndexedDB/OPFS backends.

## Task DAG (Jules-sized domains)

### Foundation layer (must land first)

- [ ] **T1. Reactor algebra in commonMain**
  - Port `Protocol`, `WamBlock`, `SessionState`, `TransformCode` from litebike taxonomy.
  - Define `ReactorError`, `ChannelMessage`, `ChannelResponse`, `ReactorConfig`.
  - Keep it pure Join/Series/Cursor-shaped.
  - Targets: `commonMain`.
  - Evidence: compiles in `commonMain`, unit tests for protocol ID round-trip and transform identity.

- [ ] **T2. NUID / authorization algebra in commonMain**
  - `Nuid`, `Capability`, `Nonce`, `Subnet` as data classes / typealiases.
  - `TraitSpace` matching `capability` against a worker's advertised traits.
  - Subnet routing prefix rules.
  - Targets: `commonMain`.
  - Evidence: compiles, tests for trait matching and subnet containment.

- [x] **T3. Volume / BlockArray / BootBlock interface in commonMain**
  - `Volume` interface: `blockSize`, `capacity`, `read(lba, count)`, `write(lba, data)`, `sync()`.
  - `BlockArray` and `BootBlock` helpers on top of `Volume`.
  - Targets: `commonMain`.
  - Evidence: compiles, tests for in-memory `Volume` backend.

- [ ] **T4. ReactorEndpoint / confix wire transport in commonMain**
  - `ReactorAction` and `ReactorResult` as `Join<Nuid, Join<Verb, Payload>>` and response envelope.
  - `ReactorEndpoint` interface: `invoke(action) -> result`.
  - Confix serialization for action/result payloads.
  - Targets: `commonMain`.
  - Evidence: compiles, round-trip serialize a NUID-authorized action.

### Storage backend layer (parallel after T3)

- [ ] **T5. Native Volume backend**
  - `PosixVolume` using existing `PosixFileOperations`.
  - `LiburingVolume` for async batching on Linux.
  - Targets: `nativeMain` (`posixMain` / `linuxMain`).
  - Evidence: native tests pass on Linux; macOS uses posix fallback.

- [ ] **T6. Btrfs userspace JBOD backend**
  - `BtrfsVolume` implementing `Volume` by parsing superblock, chunk tree, device tree.
  - Built on top of `Volume`, not replacing it.
  - Targets: `jvmMain`/`nativeMain` (mmap + io_uring).
  - Evidence: can read a raw Btrfs image or JBOD array metadata.

- [ ] **T7. Browser storage backend**
  - `OpfsVolume` and `IndexedDbVolume` implementing `Volume` over browser storage APIs.
  - Block semantics emulated; no real Btrfs in the browser.
  - Targets: `jsMain`/`wasmJsMain`.
  - Evidence: browser tests or headless JS test for read/write block round-trip.

### Transport / proxy layer (parallel after T1, T2, T4)

- [ ] **T8. Node localhost proxy**
  - `NodeReactorEndpoint` in `jsMain` that wraps `FetchReactorEndpoint`.
  - Server-side forwarder that accepts `/api` actions and routes to a local `Reactor`.
  - Targets: `jsMain` + JVM/Native server.
  - Evidence: PWA can connect to `localhost:PORT` and invoke a ping action.

- [ ] **T9. Mesh / SCTP transport**
  - `MeshReactorEndpoint` and `SctpReactorEndpoint` implementing `ReactorEndpoint`.
  - Peer discovery over the reactor blackboard.
  - Targets: `commonMain` interfaces; native implementations.
  - Evidence: two native peers exchange a NUID-authorized action over loopback.

- [ ] **T10. litebike gate / tunnel adaptation**
  - Port litebike `Protocol`, `rbcursive`, `gates` into `commonMain` taxonomy.
  - `Tunnel` interface; SSH / shadowsocks / SOCKS5 / proxy backends in native.
  - Browser uses `ReactorEndpoint` to ask a native/node peer to open a tunnel.
  - Targets: `commonMain` + `nativeMain`/`jvmMain` + `jsMain`.
  - Evidence: protocol detection test; native SSH exec round-trip (or mock).

### Workers / capabilities layer (parallel after T2, T4, T7, T8, T9, T10)

- [ ] **T11. CAS worker**
  - Content-addressed store (`CasStore`) on `Volume`.
  - Manifest CIDs, deterministic archives, replication hooks.
  - Targets: `commonMain` + platform backends.
  - Evidence: `ContentId` round-trip, manifest CID deterministic across runs.

- [ ] **T12. Process worker**
  - `Process` capability using existing `PosixProcessOperations` (moved to macOS/linux).
  - NUID-authorized process spawn/exec over the reactor.
  - Targets: `nativeMain`.
  - Evidence: spawn `echo` via reactor action, receive stdout as result.

- [ ] **T13. Wireproto / Confix worker**
  - Serialize/deserialize `ReactorAction` over wireproto.
  - Path/cursor transport over `ReactorEndpoint`.
  - Targets: `commonMain`.
  - Evidence: round-trip a cursor through a wireproto-encoded action.

- [ ] **T14. ModelMux worker**
  - Port litebike `keymux` model facade / DSEL / provider selection.
  - Model invocation as a `ReactorAction` proxied to a model worker.
  - Targets: `commonMain` + `ReactorEndpoint`.
  - Evidence: provider selection rule resolves; model request routes to a mock worker.

### UI / blackboard layer (last)

- [ ] **T15. PWA / gallery UI**
  - Forge gallery / blackboard renderer in `wasmJs`/`js`.
  - Talks to localhost Node proxy or degrades to offline OPFS.
  - Targets: `jsMain`/`wasmJsMain`.
  - Evidence: browser build passes, gallery renders from a test blackboard.

## Target-feature bijection for the HTML window manager

The Forge window manager should be a single HTML/DOM shell in
`src/commonMain/resources`, rendered on every platform by a per-target
`ForgeWindowManager` SPI. `manimwm-tk` is retained as a native desktop
render/composit layer, not as the window manager.

Per-target mapping:

| Target | `ForgeWindowManager` impl | Display surface | Storage prefix | Network |
|---|---|---|---|---|
| `jvm` | `JvmForgeWindowManager` | Compose + embedded browser (JCEF/WebView) or external browser | `.local/forge` | JVM sockets |
| `macos`/`linux` | `NativeForgeWindowManager` | system browser or embedded WebView (optional) | `.local/forge` | native sockets |
| `js` (node) | `NodeForgeWindowManager` | serve HTML to browser, or headless | `.local/forge` | node sockets |
| `wasmJs` (browser) | `BrowserForgeWindowManager` | browser DOM | OPFS/IndexedDB | fetch/WebSocket |
| `android` | `AndroidForgeWindowManager` | WebView | app storage | Android sockets |
| `wasi` | `WasiForgeWindowManager` | none / textual | WASM sandbox | WASI sockets |

- [ ] **T16. Define `ForgeWindowManager` SPI in commonMain**
  - Interface: `bind(html)`, `injectScript`, `dispatchEvent`, `captureSnapshot`.
  - Targets: `commonMain`.
  - Evidence: compiles; all existing targets have a no-op or real implementation.

- [ ] **T17. Move HTML shell assets into `src/commonMain/resources`**
  - Currently `src/jsMain/resources/index.html` + inline `forgeAppHtml()`.
  - Make `ForgeApp` generate from a shared HTML template, CSS, and JS snippets.
  - Targets: `commonMain` resources + `jsMain`/`wasmJsMain` consumers.
  - Evidence: `wasmJs`/`js` binaries still produce the same PWA; `index.html` still references `./TrikeShed.js`.

- [ ] **T18. Implement per-target window managers**
  - `BrowserForgeWindowManager`: uses `document` (existing behavior in `wasmJs`/`js`).
  - `NodeForgeWindowManager`: serves HTML over local HTTP; opens system browser or stays headless.
  - `JvmForgeWindowManager`: serves HTML or embeds JCEF/JavaFX WebView inside Compose window.
  - `NativeForgeWindowManager`: serve HTML + system browser; optional native WebView.
  - `AndroidForgeWindowManager`: WebView wrapper.
  - `WasiForgeWindowManager`: textual/no-op.
  - Targets: per-target source sets.
  - Evidence: each target can launch and render the HTML shell; at least one platform test per implementation.

- [x] **T19. Reposition `manimwm-tk` as a native render/composit layer**
  - `manimwm` keeps its SPI (`ManimWmSpi`) but is no longer the window manager.
  - Native desktop: the HTML window manager requests frames/textures from `manimwm` and positions them in the DOM via a canvas or WebGL surface.
  - Browser: `manimwm` can render to a `<canvas>`/WebGL if ported, or the browser uses its own animation layer.
  - Targets: `commonMain` interface; native/JVM implementations.
  - Evidence: a native desktop build shows the HTML shell with a manim-rendered canvas panel inside it.

- [x] **T20. Add missing targets to Gradle build**
  - `android()` target with `androidMain` source set.
  - `wasmWasi()` target with `wasiMain` source set.
  - Ensure `composeCompiler` stays restricted to `KotlinPlatformType.jvm`.
  - Targets: `build.gradle.kts`.
  - Evidence: `./gradlew build` succeeds for new targets on host; non-host targets are ignored via `kotlin.native.ignoreDisabledTargets=true`.

- [ ] **T21. Align docs/ gh-pages with the shared HTML shell**
  - The `docs/` build is the same `src/commonMain/resources` HTML, packaged for static hosting.
  - Sync task must keep `<script src="./TrikeShed.js"></script>` verbatim.
  - Targets: `build.gradle.kts` sync task + `docs/`.
  - Evidence: `curl -s <gh-pages url> | grep TrikeShed.js` matches.

## Build / gateway discipline

- Every Jules task must keep its own slice green under `./gradlew build`.
- The global `./gradlew build` is a gateway check, not a long-running Jules session.
- Stale or broken tests should be excluded from compilation, not deleted, until reconciled.
- No platform IO or Btrfs code in `commonMain` or browser targets.

## Open questions / risks

- [ ] Linux `PosixProcessOperations` currently missing (file is in `macosMain`). Need `linuxMain` copy.
- [ ] `macosX64Main.dependsOn(macosMain)` triggers Gradle warning; may need to drop or rewire.
- [ ] `../litebike/` is Rust; porting `rbcursive` SIMD detection may require JVM Panama or native fallback.
- [ ] NUID key material / revocation story needs a concrete design before T2 is finalized.
- [ ] Browser PWA cannot open raw sockets; all tunneling must be proxy-mediated.
- [ ] `jvm` target currently uses Compose Desktop; embedding HTML means choosing JCEF, JavaFX WebView, or an external browser. Decision needed before T18.
- [ ] `wasmWasi` has no display; T18 will be a no-op/textual implementation. Need to confirm whether this is useful for a headless reactor worker.
- [ ] `android` target is not yet in build.gradle.kts; adding it requires Android Gradle Plugin and SDK setup.

## LCNC no-code layer — gap follow-up (Jul 2026 audit)

The `lcnc/` package is half implementation, half aspirational. The no-code
model — `LcncAssociative` (Database + PropertySchema + PropertyType),
`LcncTaxonomy` / `ForgeTaxonomy` (block-tree page model), `IngestCodec`
(Paste / FileStream / Link + IngestFormat), `IngestStateElement`, and
`LcncGrid` (Cursor surface) — is real and unit-tested at the type level.

The visual, formula, relation, and page-as-database layers exist only as
empty enum cases in `LcncAssociative.PropertyType`. No editor, no parser,
no reducer, no consumer. Each is a stub that future tasks must either
implement or remove.

- [ ] **T22. LCNC visual editor — Block + Database views**
  - Currently: `LcncAssociative` defines column types and `LcncBlock` defines
    block kinds, but there is no `BlockEditor` / `PropertyEditor` /
    `DatabaseView` implementation anywhere in `commonMain` or per-target source sets.
  - Targets: `commonMain` algebra + `jsMain`/`wasmJsMain` rendering for the PWA;
    `jvmMain` Compose Desktop view optional.
  - Evidence: a property-grid surface renders a Database with at least three
    column types (`TEXT`, `SELECT`, `CHECKBOX`), cells persist, edits round-trip
    through `IngestStateElement`.
  - Note: zero production callers today (the entire package is self-enclosed).
    This task cannot land until at least one of `forge/`, `kanban/`, or a new
    `lcnc-view/` package actually imports the editor.

- [ ] **T23. LCNC `FORMULA` parser + reducer (PropertyType.FORMULA)**
  - Currently: `PropertyType.FORMULA` enum case exists; no `Formula` AST,
    no parser, no evaluator.
  - Targets: `commonMain` parser + evaluator; `jvmMain`/`nativeMain` may want a
    Panama-backed fast path later but commonMain-only first.
  - Evidence: parse `if(prop("Done"), 1, 0)` into a Formula AST, evaluate against
    a row, return the right typed value; round-trip through a `Database`.
  - Coupling: T22 needs this for property grid cells of type FORMULA.

- [ ] **T24. LCNC `ROLLUP` reducer (PropertyType.ROLLUP)**
  - Currently: enum case exists; no `Rollup` traversal; the closest code path
    is `LcncReductions` with `BuiltinReducer.{SUM, COUNT, MIN, MAX, AVG,
    STDDEV, PERCENTILE_*}` — that algebra is the right spine but it is not
    wired to PropertyType.ROLLUP.
  - Targets: `commonMain`; reuse the `reduction/Carrier` + `LcncReduction`
    pipeline already in package.
  - Evidence: a `RereduceStage` consumer reading a related database produces the
    right rollup cell for a SUM, AVG, and PERCENTILE_95 reducer.

- [ ] **T25. LCNC `RELATION` traversal (PropertyType.RELATION)**
  - Currently: enum case exists; no `RelationIndex`, no `RelationView`,
    no query path.
  - Targets: `commonMain` algebra + per-target cursor projection.
  - Evidence: a Database with `PropertySchema(type = RELATION, target =
    otherDb)` lets a view resolve the related rows and project them through
    a Cursor.

- [ ] **T26. LCNC `PEOPLE` / `FILES` typed properties (PropertyType.PEOPLE,
      FILES)**
  - Currently: enum cases exist; no producer, no consumer, no ingest support
    beyond the bare enum case.
  - Targets: `commonMain` types + `IngestCodec` adapters that consume them.
  - Evidence: a `PEOPLE` cell serializes as `Series<UserRef>` and a `FILES`
    cell as `Series<FileRef>`; an ingest path that reads `markdown` with image
    references produces `FILES` cells.

- [ ] **T27. Ingest pipeline that actually feeds a Database / page**
  - Currently: `IngestCodec` defines `IngestSource` and `IngestFormat` (CSV,
    TSV, MARKDOWN, HTML, JSON, LCNC_NATIVE) — format/transport enums only;
    there is no parser that produces an `LcncBlock` series or a `Database`,
    and no consumer that writes one. `IngestStateElement` collects entities
    into a `mutableListOf` in-process but never emits them.
  - Targets: `commonMain` parsers + reactor binding through `IngestStateElement`.
  - Evidence: paste a CSV, see a `Database` with inferred column types;
    paste a Markdown doc, see an `LcncBlock` tree; both written through the
    CCEK element's lifecycle (CREATED → OPEN → ACTIVE → DRAINING → CLOSED),
    not just a `mutableListOf` accumulator.

- [ ] **T28. Split `lcnc/reduction/*` out of the LCNC package**
  - The reduction engine (`LcncReduction`, `ReductionCarrier`,
    `LcncCarrierAlg`, `LcncKeyAlg`, `LcncValueAlg`, `LcncPhaseAlg`,
    `LcncSupport`, `LcncReductions`, `ConfixReducers`, `ForgeReducers`,
    `CrmsReducers`) was extracted from `forge/`, `parse/confix/`, and the
    old CRMS tree ("extracted from ForgeWorkspaceImpl"; "extracted from
    the Confix parser"; "extracted from the CRMS fold"). It is misfiled
    under `lcnc/reduction/`. Functional homes: move to `reduction/`
    package at the root of `commonMain`, OR fold concrete adapters
    (`ConfixReducers`, `ForgeReducers`, `CrmsReducers`) back into their
    consumer packages and keep only the algebras in `reduction/`.
  - Targets: `commonMain` package shape.
  - Evidence: `rg 'borg.trikeshed.lcnc' src/` outside `lcnc/` and tests →
    only reduction/* imports; after the move, only `reduction/*` imports,
    LCNC taxons (Associative, Taxonomy, Grid, Ingest) are LCNC-shaped.

- [ ] **T29. Decide one of: implement or de-stub the aspirational
      PropertyType cases**
  - `LcncAssociative.PropertyType` lists TITLE, TEXT, NUMBER, SELECT,
    MULTI_SELECT, DATE, PEOPLE, FILES, CHECKBOX, URL, EMAIL, PHONE_NUMBER,
    FORMULA, RELATION, ROLLUP, CREATED_TIME, CREATED_BY, LAST_EDITED_TIME,
    LAST_EDITED_BY. Of these, only TITLE/TEXT/NUMBER/SELECT/CHECKBOX/DATE
    have any downstream treatment — and even those have minimal ingest /
    no editor. The remaining cases are vocabulary promises with no backing.
  - Decision: either implement via T22-T27 or remove the unimplemented
    cases from the enum (keeping one COMMENT note per removed value about
    what it once meant) to keep the surface honest.

## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)

- elastic/ removed — was a CRIT structural shadow of `interface Join` /
  `typealias Series` with zero importers in `src/`.
- `classfile/slab/**` excluded from `commonMain` compile path — entire layer
  of ~20 `TODO()` stubs (GraalJS eval / DuckDB c-interop / FacetedCursor /
  MiniDuck contract) with zero non-test consumers; files preserved on disk.
- `ConfixClassfileDir.kt`: dead helpers (`mkSeries`, `withFacet`, `inMode`,
  `tagged`, `ChildRowVec`, `childRowVec`) removed — they depended on the
  excluded slab layer. Real entry points (`pathOf`, `nodeVal`) remain.
- `CircularQueue` `TODO("...")` → `error(...)` in `poll`/`peek`/`iterator.remove`
  — silent-hollow stub is now loud at the call site instead of silently
  returning or throwing a misleading message.
- NUID algebra (T-NUID-1) — `src/commonMain/.../context/nuid/Nuid.kt`:
  Capability sealed hierarchy with family wildcard roots; Subnet
  concentric containment; Nonce RandomBytes + Derived (causal chain);
  `Nuid = Join<Capability, Join<Nonce, Subnet>>`; TraitSpace +
  Workgroup.canHandle; NuidElement as CCEK bearer. No platform IO,
  compiles across Macos / JVM / JS / WasmJs.
- T-CCEK-FANOUT-2 — `src/commonMain/.../context/nuid/NuidFanoutElement.kt`:
  concentric-narrowing dispatcher. Owns a registry of Workgroups; on
  `dispatch(nuid)` filters by `scope contains nuid.subnet` AND
  `TraitSpace.can(nuid.capability)`, sorts by scope level ascending,
  offers the Claim to candidates at the request's level, escalates
  outward on timeout up to `escalationBudget + 1` levels. CCEK
  lifecycle owner. Same shape as HtxElement / SctpElement.

## Running Kanban live — RGA-anchored task list (Jul 2026 audit)

The "real Kanban server driven by Hermes-donor traces → LCNC" milestone
requires the cuts in dependency order below. Gaps identified in the
post-NUID/CCEK audit. Each task is single-best-debt-reduction sized
(1-3 files, real verification, non-goals explicit).

- [ ] **T-KANBAN-HTTP-1. `KanbanHttpServerJvm` in jvmMain — closes G01+G02+G06**
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanHttpServerJvm.kt`
  - Builds on the existing `HtxReactorElement` (no new HTTP machinery).
  - Mounts `POST /api/submit` for markdown ingest (calls
    `ForgeKanbanIngest.persistMarkdown` and returns the resulting
    `causalKey`s) and `GET /api/board` for the projected
    `ForgeAppState` JSON.
  - Owns one `NuidFanoutElement` registered with workgroups wrapping
    the existing reducers (`Process`, `Cas`, `Wireproto`).
  - Has a `KanbanServerMain` entrypoint that takes `--port` and `--donor`
    and runs forever (`runBlocking` + structured concurrency).
  - Verification: `./gradlew compileKotlinJvm`, then
    `./gradlew jvmRun -PmainClass=...KanbanServerMain --args="--port 8888 --donor /tmp/hi"`
    followed by `curl -s localhost:8888/api/board | jq . && curl -s
    -X POST -d @/tmp/hi localhost:8888/api/submit`.
  - Non-goals: do NOT add a new HTTP framework; do NOT change
    `RfxHttpServerJvm`; do NOT touch the websocket surface
    (T-KANBAN-PUSH-4 covers that).

- [ ] **T-KANBAN-LCNC-2. Replace `LcncEntityDTO` projection with
      `LcncBlock` construction (closes G05)**
  - File: `src/commonMain/kotlin/borg/trikeshed/forge/forgeAppStateLcnc.kt`
    (new) and a small edit in `src/commonMain/.../forge/ForgeApp.kt`.
  - Goal: replace the `LcncEntityDTO { entityId, lcncKind, lane, facet,
    causalKey, title, description }` factory at `ForgeApp.kt:277-289`
    with a `LcncEntity`-shape constructor. `LcncTaxonomy.kt:30-63` is
    the canonical type.
  - Verification: `./gradlew compileKotlinJvm compileKotlinMacos`,
    plus the existing `LcncReductionCoreTest` continues to compile (no
    removed surface).
  - Non-goals: do NOT add a `Database` / `PropertySchema` editor; do
    NOT change the PWA HTML; do NOT change `LcncTaxonomy` itself.

- [ ] **T-KANBAN-DONOR-3. Donor trace ingestion path (closes G04)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/donor/HermesDonorTrace.kt`
    + a small reader on the side.
  - Goal: accept either a sidecar markdown file
    (`--donor /path/to/trace.md` already supported) OR a SQLite donor
    over `~/.hermes/kanban.db` (read-only — Python's
    `~/.hermes/hermes-agent/hermes_cli/kanban_db.py` shape). One format
    per `--donor-format=md|sqlite`.
  - Verification: `curl -X POST localhost:8888/api/donor?format=sqlite`
    replays the SQLite board into the local-first `IngestStateElement`
    cycle.
  - Non-goals: do NOT import the Python module; do NOT write to the
    donor DB; do NOT change the Hermes-MCP path.

- [ ] **T-KANBAN-PUSH-4. Server-side reactive push to the PWA
      (closes G14)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanPushBus.kt`
    + a `/api/stream` WebSocket handler in the existing
    `KanbanHttpServerJvm`.
  - Goal: every successful `POST /api/submit` publishes a
    `BlackboardSurface` patch over `/api/stream` so connected PWA
    instances update without reload.
  - Verification: open PWA in browser, then `curl -X POST -d
    @/tmp/hi localhost:8888/api/submit` and observe the board change
    in the browser without a page refresh.
  - Non-goals: do NOT add a queue framework; do NOT reach into the
    PWA's IndexedDB; do NOT change the seed-JSON path.

- [ ] **T-KANBAN-LCNFANOUT-5. `LcncFanoutElement` merging LCNC +
      NUID surfaces (closes G08)**
  - File: `src/commonMain/.../context/lcnc/LcncFanoutElement.kt`.
  - Goal: extend `NuidFanoutElement` so that `dispatch(nuid, payload)`
    returns `ReducerRegistry.runFor(winningCapability, payload)`
    (closes G10 in the same cut).
  - Non-goals: do NOT replace `NuidFanoutElement`; do NOT change the
    polling primitive (T-KANBAN-FANOUT-6 covers that).

- [ ] **T-KANBAN-FANOUT-6. Replace scalar polling with
      `MutableSharedFlow` (closes G11)**
  - File: edit to `NuidFanoutElement.kt`, ~30 lines.
  - Goal: replace the `pollForWinner(...)` poll-loop with a
    `MutableSharedFlow<Claim>` and `withTimeoutOrNull`.
  - Verification: unit test for 16+ candidates, expecting sub-100ms
    selection under fanout.
  - Non-goals: do NOT change the concentric narrowing logic.

- [ ] **T-KANBAN-WAL-7. WAL for causal chain recovery (closes G12)**
  - File: `src/jvmMain/.../forge/persistence/CausalWal.kt`.
  - Goal: append causal updates to a `.wal` log keyed by `causalKey`;
    on daemon restart, replay into the in-memory graph.
  - Verification: start daemon, submit twice, kill mid-dispatch,
    restart, observe both submissions present.

- [ ] **T-KANBAN-LCNCPIPE-8. `LcncIngestPipeline` producing
      `Series<LcncEntity>` from Paste / FileStream / Link (closes G07)**
  - File: `src/commonMain/.../lcnc/reactor/LcncIngestPipeline.kt`.
  - Goal: implement `IngestCodec.decode(IngestSource, IngestFormat):
    Series<LcncEntity>` and publish through `IngestStateElement`
    lifecycle (CREATED → OPEN → ACTIVE → DRAINING → CLOSED) with
    `Channel<ReactorAction>` fanout, not a `mutableListOf` accumulator.

- [x] **T-KANBAN-PERSIST-9. Pick a persistence surface (closes G09)**
  - Decision only — either port the Hermes SQLite schema to Kotlin
    (~300 lines) or officially adopt the JSON / ConfixDocStore path
    and document it. No code in this task — sign-off only.

- [ ] **T-KANBAN-REDUCER-10. `ReducerRegistry` for the fanout mix
      (closes G10 if not already done in T-KANBAN-LCNFANOUT-5)**

- [ ] **T-KANBAN-CROSS-11. Single submission format shared between
      Forge path and Hermes-donor path (closes G15)**

## Storage unification — projection registry (2026-07-19)

From `doc/rewire.md` §0 (one CID, five lenses). The blackboard causal
graph is in-memory; making it CAS-backed unifies the five lenses
(auxiliary CAS / materialized / reified / btrfs content / graph trees)
under one `project(cid)` path.

- [ ] **T-CAS-PROJ-1. Projection registry — `project(cid): Lens`**
  - File: `src/commonMain/kotlin/borg/trikeshed/job/CasProjection.kt`.
  - Goal: sealed class `Lens = Raw | Cursor | BtreePage | CausalNode |
    Manifest`; `project(cid, kind)` reads `cas.get(cid)`, parses via
    `confixDoc(bytes)`, dispatches on the doc's `kind`/`tag` field.
  - Uses existing `ConfixIndexK<R>` facet machinery — no new storage,
    no new formats.
  - Verification: store a btree page, a causal node, and a manifest;
    `project` each and confirm the correct lens resolves.

- [ ] **T-CAS-PROJ-2. Blackboard causal graph → CAS-backed**
  - File: `src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt`.
  - Goal: every causal node becomes a Confix doc `{causalKey, deps: [CID...],
    payload}` stored in CAS. Edges are CIDs, not object references.
  - Traversal: `cas.get(dep) → confixDoc → recurse`. Force-directed
    layout consumes CID=identity, deps=edge-list.
  - Snapshot: record the root CID. COW: new page on every edit,
    re-point parent path to root.
  - Depends on: T-CAS-PROJ-1.
  - Verification: submit two linked jobs, snapshot the root CID,
    restart, traverse from root CID and recover both nodes + the edge.

- [ ] **T-CAS-PROJ-3. `MmapCasStore` (closes T4 from `doc/taste.md`)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt`.
  - Goal: `get(cid)` returns a mapped slice (io_uring / Panama
    MemorySegment), not a heap copy. Composes: mmap file →
    `Series<Byte>` → Confix index over mapped bytes without copy.
  - Verification: store 1MB blob, read via mapped slice, confirm
    zero heap allocation on the read path.

## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)

## T-REWIRE-3 Follow-up Cuts (from doc/rewire.md §9)

These are the separated follow-up tasks from T-REWIRE-3 (Cuts 1 and 7 landed in T-REWIRE-3).

- [ ] **T-REWIRE-3b. Modelmux kanban agent**
  JobCommand handler routing cards through modelmux.

- [ ] **T-REWIRE-3c. UPnP workspace discovery**
  Workspace announce payload over mDNS/SSDP.

- [ ] **T-REWIRE-3d. SSH mesh transport**
  SSH tunnel over litebike Tls carrying Confix docs.

- [ ] **T-REWIRE-3e. IPFS/IPNS bridge**
  CAS blocks as IPFS blocks, IPNS names = manifest CIDs.

- [ ] **T-REWIRE-3f. Progressive rendering**
  Jules jobs reading TreeDoc archives into ForgeDoc.

=====================================
=== upstream-creeper-node-book.md ===
=====================================
# TrikeShed Upstream Creeper Node Specification

The **Creeper Node** is a capability-limited Forge agent deployed on constrained upstream environments (like OpenWrt Linux routers). It acts as a local-first participant in the TrikeShed ecosystem, maintaining discovery, routing to VPS resources via deterministic eligibility, and handling assignment-bound key leases without serving as a central vault or packet inspector.

## Chapter 1: Current Code Inventory

This inventory maps the core components of the Creeper Node architecture to the live codebase.

*   **KeyMux / ModelMux**: Coordinates capability evaluation and state multiplexing.
    *   `src/commonMain/kotlin/keymux/KeyMux.kt:159`
    *   `src/commonMain/kotlin/modelmux/ModelMux.kt:94`
*   **NUID (Node Unique Identifier)**: Capability and identity envelopes.
    *   `src/commonMain/kotlin/borg/trikeshed/context/nuid/Nuid.kt:281`
    *   `src/commonMain/kotlin/borg/trikeshed/context/nuid/NuidFanoutElement.kt:50`
*   **Litebike Transport**: Multi-protocol mesh and listener routing.
    *   `src/commonMain/kotlin/borg/trikeshed/litebike/LitebikeListenerElement.kt:40`
    *   `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmLitebikeBindAdapter.kt:50`
    *   `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt:54`
*   **Reactor Streams**: Action/Result wire protocols and async endpoints.
    *   `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorCodec.kt:11`
    *   `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorEndpoint.kt:13`
*   **CAS and Confix**: Object storage and facet parsing.
    *   `src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt:9`
    *   `src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt:16`
    *   `src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixIndexK.kt:25`
    *   `src/commonMain/kotlin/borg/trikeshed/lcnc/reduction/ConfixReducers.kt:10`
*   **Forge Agent / Application State**:
    *   `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt:72`
*   **Creeper Node Implementation** (New):
    *   `src/commonMain/kotlin/borg/trikeshed/creeper/CreeperNode.kt`
    *   `src/commonTest/kotlin/borg/trikeshed/creeper/CreeperNodeTest.kt`

## Chapter 2: Control, Data, and State Planes

The Creeper Node separates operational concerns into distinct planes:

*   **Control Plane (Live)**: Facilitates capability distribution and topology discovery via `NuidFanoutElement`. NUIDs dictate whether an agent is allowed to process a request block.
*   **Data Plane (Mixed)**: Built on Reactor pipelines and Litebike listener channels. Uses non-blocking multiplexing to stream chunks (e.g., HTX/SSH payloads). Direct data paths avoid unnecessary decryption or central staging.
*   **State Plane (Live)**: Anchored by `MmapCasStore` and Confix structural sharing. State transitions are purely functional transformations of content addresses (CIDs), eliminating global mutable state across the router and its peers.

## Chapter 3: Peer Discovery and Expiring Capability Advertisements

Creeper nodes do not rely on static IP tables or a central discovery service. Instead, they leverage the NUID layer to broadcast capabilities over localized subnets or mesh links.

*   **Capabilities**: Advertisements are mapped to NUID subnet markers. The `CreeperNode` subscribes to the `NuidFanoutElement` for discovery packets.
*   **Expiration**: Credentials lease handles include built-in TTLs. NUID fanouts automatically discard expired or structurally invalid advertisements via continuous suspension routines (`consume()` rather than `tryTake()`).

## Chapter 4: Assignment-Bound Key Leases

The Creeper Node holds no persistent root authority or primary keys. It only possesses keys bounded to its immediate assignments.

*   **Opaque Handles**: Key material is represented as opaque CIDs in the CAS.
*   **Holder-Sealed Payloads**: Jobs delegated to the Creeper Node are sealed for its specific ephemeral key. The router uses `KeyMux` to unwrap the job. The `CreeperNode` initializes a `KeyMux` targeting its `CasStore` provider.

## Chapter 5: ModelMux Eligibility and Deterministic Routing

When an event arrives at the `LitebikeListenerElement`, the Creeper Node uses `ModelMux` to determine eligibility.

1.  **Eligibility**: Does the Creeper Node possess the NUID and capability to execute the reduction?
2.  **Local Execution**: If eligible and constrained (e.g., small Confix parsing), the agent processes it locally on the OpenWrt constraint.
3.  **VPS/Pooled Routing**: If the task exceeds local capacity, the deterministic routing algorithm forwards it via Litebike to pooled VPS resources. Local node acts merely as the ingress mesh, not a bottleneck.

### Implementation Specification

The `CreeperNode` must implement:
```kotlin
interface CreeperNodeOperations {
    suspend fun accept(payload: ByteArray, nuid: Nuid): ReactorResult
}
```

It maintains a `ModelMux` and a `KeyMux`. The routing logic is deterministic:
* Map incoming action payload to a NUID context.
* Query `ModelMux` for capabilities.
* Route to local CAS if Nuid matches local assignments and TTL is valid.
* Otherwise, forward to remote mesh peer via injected `TunnelEndpoint`.

## Chapter 6: SSH / Litebike Transport

The router handles inbound multi-protocol connections using `ProtocolDetector`.
*   Connections mapping to SSH or Socks5 are tunneled via `LitebikeListenerElement`.
*   The transport layer treats byte streams as `ReactorAction` sequences, maintaining encrypted boundaries. The router can bridge disjoint networks without ever peeking into the payload.

## Chapter 7: Git and Prebuilt Node Bundle Install

Creeper nodes on OpenWrt often lack JVM access.
*   **Prebuilt JS Bundle**: TrikeShed compiles to JS (`NodeForgeWindowManagerTest` proves Node.js compatibility).
*   **Git Upgrades**: The router maintains a shallow Git clone. Updates are pulled as signed tags.
*   **Deployment**: The deployment script uses native OS primitives (like `wget` or `git`) to fetch the new JS bundle and replaces the executing process.

## Chapter 8: procd Lifecycle, Rollback, and Watchdog

On OpenWrt, the agent integrates with `procd`.
*   **Watchdog**: A `/api/health` channel (serviced by `JvmKanbanServer` analogs in JS/Native) responds to `procd` polls.
*   **Rollback**: If a new Git bundle fails health checks, `procd` kills the process. A startup shell script detects the exit code and rolls back to the previous CAS bundle CID.

## Chapter 9: CAS, Confix, and Git Object Flow

All configuration, job definitions, and artifacts are stored in `CasStore`.
*   **Confix Parsing**: The router parses configurations on-demand without memory inflation (`ConfixReducers`).
*   **Git Integration**: Git objects are inherently content-addressed. The Creeper Node's CAS seamlessly overlays Git packs for configuration syncs without requiring a local Git daemon.

## Chapter 10: Failure Recovery and Direct-Peer Survival

Creeper nodes are designed to fail safely.
*   **Recovery**: On reboot, the B+Tree checkpoint is mapped via `MmapCasStore`. Missing pages fail visibly.
*   **Survival**: If the internet uplink dies, local subnets continue discovering the Creeper Node via Litebike and evaluating local CAS jobs.

## Chapter 11: Threat Model and Redaction

*   **No Central DB**: An attacker rooting the Creeper Node only gains access to currently active assignment leases.
*   **Redaction**: The "One CID, Five Lenses" rule means raw data can be stripped from the local CAS while leaving structural CIDs intact, effectively redacting history while maintaining cryptographic proof of execution.

## Chapter 12: Operating Runbook

1.  **Bootstrap**: `git clone <repo> && ./install_creeper.sh`
2.  **Monitor**: Connect to `Litebike` metrics port or view local CAS index.
3.  **Wipe**: `rm -rf /var/lib/creeper/cas` — the node will re-sync required context upon next NUID capability lease.

## Chapter 13: Wire and Control Shapes

Data structures over the wire are serialized as CBOR via Confix (`ConfixCborEmitter`).
*   **Control Envelope**: `Join<Nuid, Join<Verb, Payload>>`
*   **Wire Proto**: Framed via standard TLS or SSH transport, deserialized by Reactor codecs into lazy `Series` to prevent OOMs on the router.

## Chapter 14: Implementation Cuts and Executable Acceptance Tests

All architectural claims are backed by executable tests:
*   `CreeperNodeTest`: Proves the deterministic routing algorithm chooses VPS over local when appropriate.
*   `ProcessReactorEndpointJvmTest`: Verifies constrained execution capabilities.
*   `MuxReactorHudJvmTest`: Verifies live reactor states and deterministic routing.
*   `JobRepositoryRecoveryTest`: Proves zero-loss state recovery from the CAS block store.
*   `ConfixSerializationTest`: Proves multi-format (JSON/CBOR/YAML) resilience on edge devices.

===================================
=== wasm_guest_investigation.md ===
===================================
# Investigation: WASM Guest Execution on JS/WASM Targets

## Objective
Extend guest language capabilities beyond the JVM-only GraalVM Polyglot surface. Specifically, we want a mechanism where WebAssembly (WASM) modules can act as guest reducers for `Cursor` data across JS and WASM targets.

## Current State
- The `Cursor` structure is Kotlin multiplatform (commonMain).
- Guest execution today is JVM-only, utilizing GraalVM Polyglot (e.g. `GraalVmCursorHost`).
- In JVM, we use `org.graalvm.polyglot.proxy.ProxyArray` and `ProxyObject` to share the memory layout of the `Cursor` with JavaScript zero-copy.

## Future Architecture for WASM Guests
To support JS/WASM targets, we must evaluate the possibility of injecting a WASM module that consumes and returns cursors.

### Options
1. **WASM-in-JS (via `jsMain`)**:
   - WebAssembly APIs (`WebAssembly.instantiate`) are natively available in the browser.
   - The primary challenge is memory sharing. Kotlin/JS (or Kotlin/WasmJS) memory is not inherently directly shareable with another WebAssembly module without a common WASM memory object.
   - **Data Passing**: A `Cursor` is an abstract Kotlin tree (`Series<RowVec>`). To pass this to a WASM guest without deep copying, the guest must be able to call back into imported host functions (e.g., `get_row_count()`, `get_double(row, col)`) provided by the Kotlin/JS host, OR the cursor must be serialized into a flat `ByteBuffer` (Linear Memory) that the WASM module can read directly.

2. **WASM-in-WASM (via `wasmJsMain` / `wasmWasiMain`)**:
   - In a pure Wasm environment, the guest could be instantiated using WASI or similar embeddings.
   - Host functions (imports) can be provided by the Kotlin runtime to the guest.
   - Again, linear memory access is the most performant. If the `Cursor` can be backed by a contiguous `ByteArray` or `MemorySegment`, it could be shared directly with the guest's linear memory.

### Required Shape
The API should mirror `GraalVmCursorHost`:

```kotlin
// In commonMain or jsMain
interface WasmCursorHost {
    /**
     * Instantiates a WASM module from bytes, invokes the 'reduce' exported function,
     * passing a host-provided linear memory segment or imported function bindings,
     * and returns the resulting Cursor.
     */
    suspend fun reduceCursor(cursor: Cursor, wasmBytes: ByteArray): Cursor
}
```

### Recommendation for Next Steps
1. **Memory Layout**: Ensure `Cursor` can be backed by flat binary (e.g. using `IOMemento.IoBytes` or Confix serialization) rather than just heap objects, as WASM modules cannot read JS/Kotlin objects directly.
2. **Host Bindings**: Create a Kotlin/JS wrapper around `WebAssembly.instantiateStreaming` that injects `{ env: { get_cell: ... } }`.
3. **Guest Implementation**: Write a simple C/Rust guest that takes a `row_count` and `col_count` and calls `get_cell(r, c)`.


## Architectural Decision Record (ADR): T-KANBAN-PERSIST-9

**Date:** 2024-07-21
**Decision:** Adopt JSON / ConfixDocStore path for Kanban persistence.
**Status:** Accepted

**Context:**
We evaluated whether to port the Hermes SQLite schema to Kotlin (approx. 300 LOC) or officially adopt the JSON / ConfixDocStore path for Kanban persistence.

**Decision:**
We officially adopt the JSON / ConfixDocStore path. CouchStore combined with ConfixDoc storage natively supports the Causal Graph and Kanban features without requiring an embedded relational database dependency like SQLite across all KMP targets. The `ConfixPersistence` and `JsonFilePersistence` implementations are already functional, tested (e.g. `ConfixPersistenceTest`), and aligned with the overarching architecture of using content-addressed JSON/Confix stores on top of our custom CAS and IO bindings.

