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

- [ ] **T3. Volume / BlockArray / BootBlock interface in commonMain**
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

- [ ] **T19. Reposition `manimwm-tk` as a native render/composit layer**
  - `manimwm` keeps its SPI (`ManimWmSpi`) but is no longer the window manager.
  - Native desktop: the HTML window manager requests frames/textures from `manimwm` and positions them in the DOM via a canvas or WebGL surface.
  - Browser: `manimwm` can render to a `<canvas>`/WebGL if ported, or the browser uses its own animation layer.
  - Targets: `commonMain` interface; native/JVM implementations.
  - Evidence: a native desktop build shows the HTML shell with a manim-rendered canvas panel inside it.

- [ ] **T20. Add missing targets to Gradle build**
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

- [ ] **T-KANBAN-PERSIST-9. Pick a persistence surface (closes G09)**
  - Decision only — either port the Hermes SQLite schema to Kotlin
    (~300 lines) or officially adopt the JSON / ConfixDocStore path
    and document it. No code in this task — sign-off only.

- [ ] **T-KANBAN-REDUCER-10. `ReducerRegistry` for the fanout mix
      (closes G10 if not already done in T-KANBAN-LCNFANOUT-5)**

- [ ] **T-KANBAN-CROSS-11. Single submission format shared between
      Forge path and Hermes-donor path (closes G15)**

## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)
