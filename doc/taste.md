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
