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

- [x] **T1. Reactor algebra in commonMain** (DRAINED 2026-07-20, commit 114f5314)
  - ChannelMessage / ChannelResponse / ReactorConfig / ReactorError / SessionState / TransformCode / WamBlock landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (T-jules session `13631575799754534320`); agent did not push a PR.
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

- [x] **T4. ReactorEndpoint / confix wire transport in commonMain** (DRAINED 2026-07-20, commit faa2619d)
  - `ConfixEnvelopeCodec` + `ReactorEnvelopAction` landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (Jules session `5891915718907135319`).
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

- [x] **T7. Browser storage backend** (DRAINED 2026-07-20, commit 9f2ab178)
  - `OpfsVolume`, `IndexedDbVolume`, `BlockDevice`, browser-storage test landed in `src/commonMain/kotlin/borg/trikeshed/browser/storage/`.
  - Recovered via missing-PR pattern (Jules session `15876474675057978179`).
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

- [x] **T9. Mesh / SCTP transport** (DRAINED 2026-07-20, commit 19a84b2d)
  - `MeshActionFrame`, `MeshErrorCode`, `MeshActionResult`, `MeshConfig`, `SctpReactorEndpoint`, `MeshReactorEndpoint` landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (Jules session `13098165998827396591`).
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

- [x] **T11. CAS worker** (DRAINED 2026-07-20, commit 42f3b209)
  - `BlockIndex` (and supporting CAS worker types) landed in `src/commonMain/kotlin/borg/trikeshed/cas/`.
  - Recovered via missing-PR pattern (Jules session `6719119381933539177`).
  - Content-addressed store (`CasStore`) on `Volume`.
  - Manifest CIDs, deterministic archives, replication hooks.
  - Targets: `commonMain` + platform backends.
  - Evidence: `ContentId` round-trip, manifest CID deterministic across runs.

- [x] **T12. Process worker** (DRAINED 2026-07-20, commit f1ee66394)
  - `ProcessCapability` / `ProcessResult` / `ProcessSpec` / `ProcessWorker` / per-platform Factories (`Jvm`, `Native`) and `ProcessWorkerContractTest` landed in `src/{commonMain,jvmMain,nativeMain}/kotlin/borg/trikeshed/userspace/nio/process/`.
  - Recovered via missing-PR pattern (Jules session `9179777146483861444`).
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

## Resume ingest → causal Kanban → ModelMux fulfillment — RGA 2026-07-20

Target runtime:

`resume + job-requisition bytes → CAS/extraction evidence → semantic/Narsese
signal bags → Couch reducer multiverse → causal facts → card reducers →
ModelMux work descriptors → fulfillment facts → NUID concentric dispatch →
Forge projections`

Multiverse model (../couchdbcascade as reference): the Narsese signal layer is
the multiverse center that several document sources feed and that Kanban reads.
Each source (`resumes | listings | coverletters`) is a Couch document domain
whose map/reduce reducers run as `ViewServer` tools and emit evidence-backed
Narsese signals into the shared signal bags. `kanban` consumes those signals
through the same reducer pipeline rather than holding a second truth. The
reference `Atlas<C,P>` / `Chart<C,P>` manifold-atlas code stays in history
(`96e0d7b0`); it is not revived for this work.

Ingest stack: local-first PWA. Tika handles office formats (PDF/DOCX/PPTX,
image OCR via Tesseract with an ffmpeg preprocessing hook). GDoc export is
pulled through the same Tika/filters pipeline once it lands as bytes. Camel
routs files only when a transport-mediated path is needed. Filters normalize
and classify the extracted streams before they reach the evidence reducers.
Non-Tika targets (plain text, markdown, JSON) are read directly. Ingest UX is
either a drag-and-drop onto a blackboard coordinate or a dialog, both of which
produce the same evidence submission. A web-scraper plugin lands later and
shares the same evidence submission contract; it is out of scope for the first
vertical.

UI contract: Kanban and the force-directed causality graph remain separate,
first-class views over the same causal/card identity. The user switches between
them; do not collapse them into a hybrid canvas. Selection, camera focus, and
card/node identity survive the switch.

Resumes and job requisitions occupy a signalling panel beside those views. The
panel is not a third mutable truth: it projects two evidence-backed bags and
their relations. Resume signals say what the candidate can evidence; requisition
signals say what the role requires or prefers. Matches, gaps, contradictions,
and missing evidence are derived signals that open/focus the same causal card.

The pieces exist, but this runtime does not. Evidence below is from live source
and `./gradlew jvmTest --tests ConcentricKanbanDemoTest --tests
LcncFanoutElementTest --tests ModelMuxTest` on 2026-07-20; compilation failed
before tests on the cited merged-source errors.

| ID | Severity | Live backing | Gap |
|---|---|---|---|
| RSM-01 | CRIT | `JvmTikaIngestAdapter.extractToMarkdown` extracts PDF/DOCX/image text (`src/jvmMain/.../kanban/JvmTikaIngestAdapter.kt:53-94`) | `ForgeKanbanIngest` accepts only a literal `6. Work packages` section with `A1 — title` headers (`ForgeKanbanIngest.kt:247-269`). An ordinary resume therefore extracts successfully and then fails with `no work packages found`; no resume facts, spans, or evidence IDs are produced. |
| RSM-02 | CRIT | `JvmKanbanServer` creates `ingestPath` for Tika output (`JvmKanbanServer.kt:178-191`) | The computed path is never consumed; line 192 calls donor ingest with the original `donorPath`. `/api/submit` writes every body to `/tmp/hi` and treats it as the board source (`JvmKanbanServer.kt:277-297`). Resume ingest is not a live endpoint. The shell has a drop zone (`resources/web/index.html:48-58`) but it only enqueues files locally; it never produces evidence, signals, or a reducer submission. |
| RSM-03 | CRIT | `LitebikeListenerElement`, `NuidFanoutElement`, and three workgroups are constructed (`JvmKanbanServer.kt:91-140`) | Wire fanout only logs and returns true (`:223-229`); it never calls `NuidFanoutElement.dispatch`. The HTTP worker looks up `slotOf("wireproto")` (`:203-220`) although registration used `kanban-wireproto-lan` (`:107-114`), so the worker returns immediately. No broadcast-node request reaches a reducer. |
| RSM-04 | HIGH | `ForgeKanbanIngest.reduce` creates cards, Rete facts, causal nodes, and correlations (`ForgeKanbanIngest.kt:105-244`) | It constructs a derived `KanbanBoard` directly. It does not submit `JobCommand`s through the durable single-writer supervisor, so card transitions are not replayable reducer outcomes. |
| RSM-05 | HIGH | `JobReducer` supports submit/start/complete/fail/retry/progress/block/move (`JobCommand.kt:9-93`, `JobReducer.kt:50-163`) | No production Kanban ingress calls it. `ConcentricKanbanDemoTest` manually copies board cards after reducer calls (`ConcentricKanbanDemoTest.kt:102-129`), proving projection and reducer are adjacent but disconnected. |
| RSM-06 | HIGH | `ForgeKanbanDaemon` can queue and execute `ModelCallDescriptor`s (`ForgeKanbanDaemon.kt:57-147`) | It has zero production callers. Results are truncated into an in-memory board copy; there is no causal output CID, fulfillment fact, or `JobCommand.Complete/Fail` lowering. WAL replay only iterates records (`:33-38`). |
| RSM-07 | HIGH | `ModelMux` performs chat/embed and reactor cache/lease handling (`modelmux/ModelMux.kt:124-279`) | Kanban uses an explicit model ID rather than capability + NUID route selection. `CreeperNode`, the only proposed bridge, is uncalled and currently fails compilation against the live NUID/AcpAction/ModelMux API (`CreeperNode.kt:36-70`). |
| RSM-08 | HIGH | `NuidFanoutElement` implements concentric eligibility and outward escalation (`NuidFanoutElement.kt:205-263`) | A winning claim means only that a worker consumed its inbox. The server workers discard every accepted claim (`JvmKanbanServer.kt:116-129`); no reducer result or fulfillment is returned to the originating connection. |
| RSM-09 | MED | `LcncFanoutElement` and `ReducerRegistry` map process/cas/wireproto capabilities to reductions (`LcncFanoutElement.kt:14-43`, `ReducerRegistry.kt:5-26`) | The registry is duplicated, generic, and disconnected from the server. It has no card reducer, resume evidence reducer, fulfillment reducer, or typed output envelope. |
| RSM-10 | HIGH | CAS identities and causal nodes exist | Resume evidence has no exact source-span contract. Tika emits one flattened string, and `ForgeKanbanIngest` hashes whole task bodies. Model enrichment could not cite or replay the resume evidence that justified a card or fulfillment. |
| RSM-11 | HIGH | The standalone board renderer is complete enough to display and mutate cards (`resources/web/script.js:440-493`); the standalone deterministic force layout is implemented and tested (`forge/blackboard/ForceLayout.kt:16-134`, `ForceLayoutTest.kt:13-48`) | The current shell exposes a Graph sidebar button (`resources/web/index.html:32-37`) but has no graph view container, render function, or click handler. `setView` recognizes only `doc` and `board` (`script.js:495-511`). The two individually useful views are not switchable and do not consume one shared selected-card/node state. |
| RSM-12 | HIGH | Root `ManifoldConcept` already carries semantic angular identity plus priority/durability/quality, and `NarsBag` supports recall/near-recall (`manifold/ManifoldConcept.kt:68-176`). The mothballed `libs/nars3/Nars3Machine.kt` adds budget decay, refeeding atoms, and pair derivation. Canonical `collections.associative.FunnelHashMap` provides the needed tiered lookup (`:25-218`). | `NarsBag` is currently a `MutableList` with no production consumers or stable evidence key. The old engine depends on the retired `libs/narsive` parser/Kursive surface. We want its typed budget/derivation behavior, not another parser dependency. There is no Narsese funnel bag, concentric work scorer, pair of source bags, match/gap reducer, or signalling-panel projection. |

### Single best debt reduction: T-RESUME-FOUNDATION-1

Build one deterministic resume/job-requisition evidence vertical before adding
model fanout:

- Add a commonMain evidence contract for `RESUME` and `JOB_REQUISITION` sources:
  source CID, extracted-text CID, stable evidence ID, exact character span,
  section/kind, normalized value, and extraction version. Raw/extracted bytes
  remain in CAS; cards and signals carry references.
- Add a deterministic ingest reducer: extracted text → evidence `Series` → one
  parent `JobCommand.Submit` plus child submits for evidence-backed sections.
  Child identity derives from `sourceCid|span|kind`; dependencies point to the
  parent. No LLM is called in this pass.
- Route those commands only through `JobSupervisorElement.commands`; publish
  causal/card projections only after durable acceptance. Do not mutate
  `KanbanBoard` directly.
- Add one JVM adapter from `JvmTikaIngestAdapter.extract` to the common contract;
  preserve the original source CID and extracted-text CID. Do not feed resume
  text into the `6. Work packages` parser.
- Add one PWA-side drop path: the existing `drop-zone` ships the file to the
  JVM ingest endpoint and shows a deterministic result; the dialog path shares
  the same submission contract.
- Verify with a real `.docx` or `.pdf` fixture: repeated ingest yields identical
  evidence/job IDs; every card resolves to exact source text; parent completion
  releases children; restart replay reconstructs the same projection.

This cut creates the canonical operand needed by every later layer. ModelMux,
fulfillment, and broadcast work remain downstream until it lands:

1. `T-RESUME-NARSESEBAG-2`: extract only the useful mothballed engine behavior
   into a root `NarseseBag<K, P>` shaped as
   `FunnelHashMap<K, ManifoldConcept<P>>`. Required operations are keyed
   upsert/get/remove, `recall()` by budget energy, `recallNear()` by angular
   Hamming distance, immutable `seal()`, decay/reinforce, and deterministic
   pair derivation. Port the existing `Nars3Budget`/derivation semantics into
   the root manifold algebra; do **not** restore `libs/nars3`, `libs/narsive`,
   their Gradle modules, or new Kursive imports. Narsese enters as a typed signal
   payload produced by reducers; text parsing remains outside this bag.
2. `T-RESUME-SIGNALS-3`: define `SemanticSignal` and typed Narsese statement
   payloads keyed by evidence ID. Maintain separate resume and requisition
   `NarseseBag<SignalId, Signal>` instances. Angular distance is semantic
   proximity; `BudgetCoord(p,d,q)` controls attention, retention, and evidence
   quality. A reducer emits `MATCH`, `GAP`, `CONTRADICTION`, and
   `MISSING_EVIDENCE` relations without changing source statements. Seal each
   committed revision into a deterministic energy-sorted `Series` for the UI.
   Similarity proposes work; it never establishes truth.
3. `T-RESUME-CONCENTRIC-SCORE-4`: score eligible work after NUID capability and
   subnet admission. Rank by local-first scope distance, semantic/angular
   distance, evidence quality, priority, durability, and worker/model health.
   The score selects among already-authorized workgroups; it never broadens
   capability or subnet authority. Persist the score components and source
   signal IDs as a causal analysis fact so the choice is replayable.
4. `T-RESUME-MODELMUX-5`: capability-routed enrichment consumes evidence/signal
   IDs and emits attributable result CIDs; it never edits cards directly. Model
   output may propose or reinforce a signal only through a validated reducer;
   ModelMux uses the concentric score after its hard eligibility filter.
5. `T-RESUME-FULFILLMENT-6`: fulfillment reducer lowers model/tool outcomes to
   accepted `Complete`, `Fail`, `Block`, or child `Submit` commands with causal
   evidence references.
6. `T-RESUME-BROADCAST-7`: Litebike ingress derives a NUID, dispatches the typed
   command envelope to concentric workgroups, executes the reducer, and writes
   the correlated result to the originating connection. Local wins first;
   unclaimed work escalates outward within budget.
7. `T-RESUME-VIEWS-8`: add a third `graph` view to the existing shell, rendering
   the same causal nodes and dependency edges as the board through
   `forceLayout`. `btn-graph` selects it; Board selects the unchanged Kanban
   renderer. Keep one `selectedCausalKey` and map it to card ID/node ID in both
   directions. A graph click followed by Board highlights the same card; a card
   click followed by Graph focuses the same node. Preserve independent board
   scroll and graph camera state. No force layout inside Kanban and no columns
   inside the graph.
8. `T-RESUME-SIGNAL-PANEL-9`: render the two bags as facing resume/requisition
   lanes with the derived relations between them. Selecting a signal highlights
   its exact source span and corresponding card/node; selecting a card filters
   both bags to its causal evidence. The panel displays budget and provenance,
   not opaque model scores. It shares `selectedCausalKey` with Board and Graph.

Non-goals: no second board truth, no model call during deterministic ingest, no
credential values in cards/claims, no unbounded channel, no detached daemon
scope, no separate scheduler beside the existing Job/Rete dependency DAG, and
no combined graph/Kanban renderer.

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

## Drain cycle — 2026-07-20 (T01-T12 vertical slice backfill)

Sixteen Jules sessions dispatched 2026-07-20 between 18:45-19:00 UTC
covering the gaps in the Foundation (T1, T4), Storage (T7), Transport
(T9), and Workers (T11, T12) layers. All sessions completed; six landed
via the missing-PR recovery path (the agent did not push a branch or
PR — patches were pulled, sanitized for trailing whitespace, applied
directly, committed, and pushed):

| Task | Session                    | Commit      |
|------|----------------------------|-------------|
| T01  | 13631575799754534320       | 114f5314    |
| T04  | 5891915718907135319        | faa2619d    |
| T07  | 15876474675057978179       | 9f2ab178    |
| T09  | 13098165998827396591       | 19a84b2d    |
| T11  | 6719119381933539177        | 42f3b209    |
| T12  | 9179777146483861444        | f1ee66394    |

Other in-flight tasks from the same batch (T05 Native Volume, T06
Btrfs JBOD, T08 Node proxy, T10 litebike gate, T13 Wireproto/Confix,
T24 LCNC ROLLUP reducer) are still IN_PROGRESS at the time of writing;
they will land via either PR-merge cycle (preferred) or the same
missing-PR fallback when each session reaches `state: COMPLETED`.

**Catalog status (post-drain):**

- 12 sessions dispatched in the 18:45-19:00 burst
- 6 recovered via missing-PR + 0 still awaiting
- 6 still in flight (above)
- 0 awaiting user feedback (after three per-round-trip replies to T09,
  T12, T13)

**Wrapper fix shipped this session:** `bin/trikeshed-jules` had a
silent `jq` compile error from a renamed `--arg starting_branch` to
`--arg startingBranch` (commit 220e8acb). Without the fix, every
`create` invocation returned `jq: $startingBranch is not defined` and
no session could be dispatched. The fix restores the predicate-gated
dispatch path.
