# TrikeShed LCNC, Forge, HTX, and CCEK Tasktree

This todo is distilled from the current direction for TrikeShed work:

- LCNC means low-code/no-code foundations built from user signals upward.
- Do not add to or curate `PRELOAD.md` from this tasktree; record tasktree drift here or in a dedicated follow-up only.
- Bring in one foundation layer at a time, with a thin user demo for each layer.
- Keep Rete, pointcutting, and blackboard behavior at boundaries where they are needed.
- TODO-only future contracts must remain `TODO()` stubs until implementation is explicitly requested.
- A checkbox is complete only when backed by a source path, a passing serial verification command, or an explicit compile-ready `TODO()` contract for TODO-only stages.

## Non-Negotiable Constraints

- [ ] Keep every layer safe to build, inspect, and back out independently.
- [ ] Avoid list-backed storage in core algebra when a `Series` surface can carry the shape.
- [ ] Prefer `Join`, `Series`, value classes, and typealiases for foundation algebra.
- [ ] Do not add Rete or pointcut fabric to demos, galleries, or foundations gratuitously.
- [ ] Put Rete/pointcutting at production boundaries: graph resolution, rule delegation, VM/object harnessing, or explicit event fabric.
- [ ] Keep JVM-bearing modules on JVM toolchain 25 for the SDKMAN GraalCE baseline.
- [ ] Disable unsupported x86 and linux-aarch native targets unless the user asks for them.
- [ ] Do not introduce TDD red scaffolding for the polyglot pointcut specializations yet.
- [ ] Do not turn placeholder TODO contracts into behavior unless that stage is explicitly selected.

## Stage 0 - Root Build and Contract Hygiene

- [x] Run the root build with full warning surfacing:

  ```bash
  ./gradlew --no-daemon build --warning-mode all
  ```

- [x] Delete all GEPA code from forge-ui and related modules.
- [x] Delete all disabled code (src.disabled directories, .disabled files).
- [x] Reintroduce user-signals module after replacing the broken inline-class facet path.
- [ ] Capture the warnings into a stable log path when doing a broad pass.
- [x] Review warnings before moving the next layer:
  - [x] Gradle Kotlin DSL delegated-property deprecations.
  - [x] `kotlin.native.cacheKind` deprecation.
  - [x] Project dependency notation deprecations.
  - [ ] Kotlin inline class deprecations in common code.
  - [x] Coroutine opt-in warnings.
- [x] Keep this tasktree independent from `PRELOAD.md`; do not add to PRELOAD for these stages.
- [x] Make root build verification serial; do not run overlapping Kotlin compile tasks against the same incremental caches.
- [x] If a daemon cache collision appears, verify again serially before treating it as a source failure.

## Stage 1 - User Signals Foundation

Goal: establish a user-signalling gallery that demonstrates LCNC from user signals upward.

Evidence: `libs/user-signals` is included by `settings.gradle.kts`; verified serially with `:libs:user-signals:runUserSignalsGalleryJvm`, `:libs:user-signals:jsNodeProductionRun`, and `:libs:user-signals:runDebugExecutableMacos` after fixing JS `currentTimeMillis()` and text-state rendering.

- [x] Keep `libs/user-signals` included in the root tasktree.
- [x] Host the user-signals demo on JVM.
- [x] Host the user-signals demo on JS Node.
- [x] Host the user-signals demo on the supported native host target only.
- [x] Keep unsupported x86 and linux-aarch target fallbacks disabled.
- [x] Keep 0D user-signal primitives visible:
  - [x] Toggle.
  - [x] Idiot light.
  - [x] Momentary button.
- [x] Keep 1D user-signal primitives visible:
  - [x] Slider.
  - [x] Knob.
  - [x] Level meter.
- [x] Keep text user-signal primitives visible:
  - [x] Text field state.
  - [x] Caret and selection state.
  - [x] Commit/focus state.
- [x] Provide a thin gallery render path for each host:
  - [x] JVM task.
  - [x] JS Node executable.
  - [x] Native executable on supported host.
- [x] Ensure gallery magnification exposes detail gradually:
  - [x] Level 1: signal labels and current values.
  - [x] Level 2: drilldown details.
  - [x] Level 3: signal type and foundation metadata.
- [x] Keep galleries free of Rete and pointcut mechanics.
- [x] If user-signal facets are needed, express them as `Series<Join<FacetKey, Signal<*>>>`.
- [x] Avoid using `List` as core facet storage.
- [x] Audit remaining user-signals factory APIs that still expose `List` for user-facing option sets.
- [x] Decide whether those option sets should remain ergonomic APIs or gain `Series` overloads.

## Stage 2 - LCNC Cursor Facets

Goal: move from raw data to cursor facets, then from cursor facets to user-signalling events.

Evidence: `src/commonMain/kotlin/borg/trikeshed/cursor/CcekChoreography.kt`, `src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt`, and `src/commonMain/kotlin/borg/trikeshed/classfile/slab/SlabKernel.kt` compile under `./gradlew --no-daemon compileKotlinJvm --warning-mode all`.

- [x] Define the minimal LCNC cursor facet vocabulary needed by Forge and user signals.
- [x] Reuse existing root cursor algebra:
  - [x] `Cursor`.
  - [x] `RowVec`.
  - [x] `ColumnMeta`.
  - [x] `FacetedCursor`.
  - [x] `SlabFacet`.
- [x] Identify which existing cursor facet code is compile-ready enough to intern into root contracts.
- [x] Keep raw blackboard overlays out of user-facing galleries.
- [x] Add only necessary LCNC facet handles:
  - [x] Logic.
  - [x] Computation.
  - [x] Notification.
  - [x] Coupling.
  - [x] Layout hint.
  - [x] DAG coordinate.
  - [x] WTK hint.
- [x] Define the data-flow chain:

  ```text
  data source
  -> Cursor
  -> FacetedCursor
  -> LCNC facet grouping
  -> user-signal event
  -> Forge/Kanban visible overlay
  ```

- [x] Provide a TODO-only root CCEK contract for lifting cursor facets into user-signal events.
- [x] Provide a TODO-only command-line choreography contract:

  ```text
  main(args)
  -> default SupervisorJob
  -> NioSupervisor
  -> LCNC cursor facets
  -> user-signalling events
  ```

- [x] Provide a TODO-only generated API choreography contract:

  ```text
  OpenAPI operation
  -> generated request cursor
  -> default SupervisorJob
  -> NioSupervisor
  -> LCNC cursor facets
  -> user-signalling events
  ```

## Stage 3 - HTX Client Review and Root Reactor Interning

Goal: review `libs/htx-client`, detail reactor choreography, and intern compile-ready concepts to root.

Evidence: root HTX request types and reactor elements exist in `src/commonMain/kotlin/borg/trikeshed/htx/`; `runHtxDemo` passed serially. Live replacement of `libs/htx-client` and fake transport/TLS verification remain open.

- [ ] Treat `libs/htx-client` as a temporary byte-stream client until root HTX is complete.
- [ ] Document current `libs/htx-client` behavior:
  - [ ] Parse URL into target.
  - [ ] Connect through `NetworkTransportSpi`.
  - [ ] Write rendered HTTP request bytes.
  - [ ] Parse response headers from byte chunks.
  - [ ] Return body or stream range bytes.
- [x] Prefer root `borg.trikeshed.htx` request/response types over `libs/htx-client` duplicates.
- [x] Intern root-ready request shapes:
  - [x] `HtxScheme`.
  - [x] `HtxTransportProtocol`.
  - [x] `HtxMethod`.
  - [x] `HtxFetchStyle`.
  - [x] `HtxRange`.
  - [x] `HtxTarget`.
  - [x] `HtxRequest`.
  - [x] `HtxResponse`.
- [ ] Retire duplicate `libs/htx-client` request parsing after root consumers are migrated.
- [ ] Replace opaque byte-stream transport with root reactor services:
  - [ ] `HtxElement`.
  - [ ] `HtxRouteService`.
  - [ ] `HtxReactorElement`.
  - [ ] `TlsElement`.
  - [ ] `TlsCodecBackend`.
  - [ ] `ChannelOperations`.
  - [ ] `NioSupervisor`.
- [x] Document root reactor choreography:

  ```text
  openHtxElement()
  -> resolve current NioSupervisor from CoroutineContext
  -> open supervisor if CREATED
  -> resolve HtxRouteService from supervisor
  -> dispatch HtxRequest
  -> HtxReactorElement.exchange()
  -> ChannelOperations socket/connect/read/write
  -> optional TlsElement for HTTPS
  -> emit HtxFrame series
  -> fanout subscribers receive planning-worthy events
  ```

- [x] `DefaultHtxRouteService` is `private` — intentional internal fallback. Supervisor registration proven reliable by `ccekHtxElementResolvesRouteServiceFromSupervisor` (HtxElementTest.kt:37).
- [ ] Install `HtxReactorElement` consistently in platform providers:
  - [ ] JVM.
  - [ ] macOS.
  - [ ] Linux.
  - [ ] JS Node.
- [ ] Keep unsupported native target policy aligned with this tasktree; do not update `PRELOAD.md`.
- [ ] Verify HTX with fake `ChannelOperations` and fake TLS backend before using live network tests.

## Stage 4 - Default SupervisorJob Main Scaffolding

Goal: all active TrikeShed mains enter through the same root SupervisorJob and CCEK/NIO context.

- [ ] Add a small root main scaffold in common or JVM-accessible code.
- [ ] The scaffold should provide:
  - [ ] `SupervisorJob`.
  - [ ] `NioSupervisor`.
  - [ ] Root coroutine context composition.
  - [ ] Open/drain/close lifecycle.
  - [ ] Structured error handling.
  - [ ] Optional registration hooks for Forge layers.
- [ ] Define the shape before wiring mains:

  ```text
  fun main(args: Array<String>) =
      trikeShedMain(args) { scope ->
          ...
      }
  ```

- [ ] Decide exact API names:
  - [ ] `trikeShedMain`.
  - [ ] `withTrikeShedSupervisor`.
  - [ ] `openDefaultNioSupervisor`.
  - [ ] `DefaultSupervisorScaffold`.
- [ ] Link active mains only.
- [ ] Do not modify disabled, generated, excluded, test-only, or scratch mains unless they are brought into the active tasktree.
- [ ] Candidate active mains to review:
  - [ ] `libs/openapi/src/jvmMain/kotlin/borg/trikeshed/openapi/GenerateSources.kt`.
  - [ ] `libs/forge/src/jvmMain/kotlin/borg/trikeshed/forge/demo/ForgeDemo.kt`.
  - [ ] `libs/user-signals/src/commonMain/kotlin/borg/trikeshed/usersignals/gallery/UserSignalsGallery.kt`.
  - [ ] `libs/forge-ui/src/main/kotlin/borg/trikeshed/forge/ui/Main.kt`.
  - [ ] `src/jvmMain/kotlin/borg/trikeshed/cursor/TypedefProductionSystemMain.kt`.
  - [ ] `libs/polyglot-bench/src/jvmMain/kotlin/borg/trikeshed/polyglot/bench/PolyglotBenchMain.kt`.
- [ ] Candidate root demonstration mains to review:
  - [ ] `src/jvmMain/kotlin/borg/trikeshed/lib/Files.kt`.
  - [ ] `src/jvmMain/kotlin/borg/trikeshed/common/Files.kt`.
  - [ ] `src/commonMain/kotlin/borg/trikeshed/lib/NetworkOrder.kt`.
  - [ ] `src/commonMain/kotlin/borg/trikeshed/common/collections/HashSet.kt`.
  - [ ] `src/commonMain/kotlin/borg/trikeshed/collections/HashSet.kt`.
- [ ] Decide which demonstration mains should remain mains, become examples, or move behind scaffolded demo tasks.
- [ ] Ensure generated OpenAPI supervisor jobs compose with the root default supervisor instead of creating isolated orphan jobs.

## Stage 5 - Forge Kanban Graph Labels and Overlays

Goal: graph labelling must be visible to users, queryable, and tied to ACL-backed overlays.

Evidence: `libs/forge/src/commonMain/kotlin/borg/trikeshed/forge/KanbanGraphOverlays.kt`; `:libs:forge:runKanbanBoardDemoJvm` passed serially. Magnification-specific overlay levels are still open.

- [x] Make graph labels accessible to users.
- [x] Make graph labels accessible to user queries.
- [x] Resolve user graph queries into ISAM column-oriented groupings per consuming method.
- [x] Add visible overlays for labels.
- [x] Ensure overlays can be rendered independently from base graph nodes.
- [x] Add placeholder Forge identity ACLs:
  - [x] User principal.
  - [x] Agent principal.
  - [x] Owner placeholder.
  - [x] Codex agent placeholder.
  - [x] Generic agent placeholder.
- [x] Add placeholder overlay profiles.
- [x] Define overlay permissions:
  - [x] Read label.
  - [x] Drilldown.
  - [x] Query grouping.
  - [x] Edit overlay.
- [x] Add overlay facet handles:
  - [x] LCNC facet handle.
  - [x] WTK hint.
  - [x] WTK layout.
  - [x] DAG coordinate.
- [ ] Make magnification reveal details:
  - [ ] Label only.
  - [ ] Label plus node identity.
  - [ ] Label plus ACL and facets.
  - [ ] Label plus ISAM grouping and consuming method.
- [x] Keep label overlays separate from core board data until persistence contracts are chosen.
- [x] Keep current `KanbanBoard` list-backed serialization shape as a compatibility boundary.
- [x] Do not introduce new list-backed core algebra below that boundary.

## Stage 6 - Forge Kanban, CCEK, Counters, Drains, and Rete

Goal: CCEK should be graph-node planning; Forge/Kanban should use counters, drains, and Rete delegate interaction where needed.

- [ ] Keep this stage TODO-only until implementation is requested.
- [ ] Add CCEK graph-node planning contracts as `TODO()` functions.
- [ ] Add Forge/Kanban counter interaction contracts as `TODO()` functions.
- [ ] Add Forge/Kanban drain interaction contracts as `TODO()` functions.
- [ ] Add Forge/Kanban Rete delegate interaction contracts as `TODO()` functions.
- [ ] Do not add tests yet for these placeholder contracts.
- [ ] Define graph-node planning inputs:
  - [ ] Board identity.
  - [ ] Graph node id.
  - [ ] Overlay series.
  - [ ] ACL profile.
  - [ ] Query grouping.
  - [ ] LCNC cursor facet handle.
- [ ] Define counter surfaces:
  - [ ] Node resolution attempts.
  - [ ] Overlay visibility checks.
  - [ ] ACL allow/deny results.
  - [ ] Query grouping matches.
  - [ ] Rete delegate activations.
- [ ] Define drain surfaces:
  - [ ] Drain pending graph resolution.
  - [ ] Drain pending overlay updates.
  - [ ] Drain pending user-signal emissions.
  - [ ] Drain pending generated API requests.
  - [ ] Drain pending command-line invocations.
- [ ] Define Rete delegate surfaces:
  - [ ] Alpha projection for board/card facts.
  - [ ] Beta join for dependency facts.
  - [ ] Production activation for visible overlays.
  - [ ] Production activation for LCNC hints.
  - [ ] Production activation for WTK layout changes.
- [ ] Keep Rete implementation outside foundation galleries.
- [ ] Reuse existing disabled Rete/kanban code only as reference until it is made active deliberately.

## Stage 7 - Forge Interaction Layers

Goal: support layers of interaction for Forge on top of root main scaffolding.

- [ ] Command-line interaction layer:
  - [ ] Parse args into a cursor or generated request shape.
  - [ ] Enter default SupervisorJob scaffold.
  - [ ] Resolve NioSupervisor.
  - [ ] Resolve Forge service layer.
  - [ ] Resolve LCNC cursor facets.
  - [ ] Emit user-signalling events.
  - [ ] Render graph overlays or drilldown.
- [ ] Generated API interaction layer:
  - [ ] Generate OpenAPI route operation ids.
  - [ ] Generate request cursor shapes.
  - [ ] Route each operation through default supervisor scaffold.
  - [ ] Convert request cursor to LCNC facets.
  - [ ] Emit user-signalling events.
  - [ ] Resolve Forge/Kanban graph overlays.
- [ ] UI interaction layer:
  - [ ] Hoist `forge-ui` gallery into root-visible run tasks.
  - [ ] Keep Forge UI wired through root CCEK scaffold.
  - [ ] Show labels as overlays, not hidden metadata.
  - [ ] Show drilldown as magnification increases.
  - [ ] Show placeholder ACL/profile status when relevant.
- [ ] Agent interaction layer:
  - [ ] Bind agent identities to Forge overlay principals.
  - [ ] Apply ACL checks before graph-label query results are exposed.
  - [ ] Keep placeholder ACLs until identity implementation is selected.
- [ ] LCNC interaction layer:
  - [ ] Use cursor facets as the low-code/no-code surface.
  - [ ] Let facets guide WTK hints and layouts.
  - [ ] Let facets carry DAG coordinates.
  - [ ] Let user signals surface changes back to users.

## Stage 8 - Forge UI Gallery Hoist

Goal: hoist the Forge and Forge UI galleries into root runs without hiding behind library-only tasks.

- [ ] Inventory existing Forge UI entry points.
- [ ] Decide which gallery is the root default demo.
- [ ] Add root run task for Forge UI if build topology supports it.
- [ ] Add root run task for text/CLI Forge overlay demo.
- [ ] Link UI main through default SupervisorJob scaffold.
- [ ] Ensure overlay labels are visible in the first demo.
- [ ] Ensure drilldown details appear as magnification increases.
- [ ] Ensure graph-node resolution is inspectable from the demo.
- [ ] Ensure LCNC/WTK/DAG facets are visible in overlay drilldown.
- [ ] Ensure ACL placeholder status is visible when querying overlays.

## Stage 9 - Graal ECMA Direction

Goal: move from Kotlin JS/Wasm target assumptions toward Graal ECMA where that is the runtime plan.

Evidence: no `GraalECMAContracts.kt` or `GraalECMADemo.kt` exists on disk yet; this stage remains open rather than weakly completed.

- [ ] Identify what currently depends on Kotlin JS browser or Wasm targets.
- [ ] Keep JS Node support for hostable demo slices where needed.
- [ ] Decide which ECMA behavior belongs in GraalVM instead of Kotlin JS/Wasm.
- [ ] Route generated API scripting and pointcut expressions through Graal ECMA contracts.
- [ ] Keep Graal ECMA work separate from user-signal foundation gallery.
- [ ] Add only TODO contracts until the runtime boundary is selected.
- [ ] Verify JVM 25/GraalCE availability before implementing runtime behavior.

## Stage 10 - Polyglot Classfile Pointcut Specializations

Goal: declare polyglot specialization points for harnessing VM objects and inserting TrikeShed/Forge objects.

Evidence: no `PolyglotContracts.kt` or `PolyglotPointcutDemo.kt` exists on disk yet; this stage remains open rather than weakly completed.

- [ ] Keep current Ruby specialization as `TODO()` only.
- [ ] Keep current Python specialization as `TODO()` only.
- [ ] Keep current JavaScript specialization as `TODO()` only.
- [ ] Keep current Clojure specialization as `TODO()` only.
- [ ] Do not add tests yet.
- [ ] Do not wire into a runtime yet.
- [ ] Later, define the classfile pointcut contract:
  - [ ] VM object handle.
  - [ ] Pointcut coordinate.
  - [ ] TrikeShed object insertion.
  - [ ] Forge object insertion.
  - [ ] Lifecycle under default SupervisorJob.
  - [ ] ACL and identity boundary when objects enter Forge.
- [ ] Later, decide whether each language is JVM-hosted, Graal-hosted, native-hosted, or JS Node-hosted.

## Stage 11 - OpenAPI Generated APIs

Goal: generated APIs should participate in the same CCEK choreography as command-line mains.

Evidence: `libs/openapi/src/commonMain/kotlin/borg/trikeshed/openapi/OpenApiChoreographyContracts.kt` and `:libs:openapi:runOpenApiChoreographyDemo` compile/run; generated-source integration remains open.

- [x] Add TODO-only OpenAPI choreography contracts outside generated output.
- [ ] Review generated `SupervisorJobs.kt` output.
- [ ] Replace isolated generated job creation with root-compatible parent job composition.
- [ ] Generate operation-specific request cursors.
- [ ] Add generated code path:

  ```text
  operationId
  -> generated request type
  -> request cursor
  -> LCNC cursor facets
  -> user-signalling events
  -> Forge/Kanban graph node planning
  ```

- [ ] Ensure generated APIs do not bypass overlay ACL checks.
- [ ] Ensure generated APIs do not bypass counter/drain/Rete delegate hooks once implemented.
- [x] Keep generated APIs compatible with JVM 25.
- [ ] Decide whether generated APIs belong in root, `libs/openapi`, or generated source only.

## Stage 12 - Blackboard and Classfile DAG Fabric

Goal: support an overarching pointcutting blackboard classfile DAG as the main event fabric, without gratuitous insertion.

Evidence: `src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagFabric.kt`; `runBlackboardDagDemo` passed serially. Runtime conversions into user signals, Forge overlay facets, Rete facts, and ACL gates remain open.

- [x] Define the blackboard classfile DAG as an event fabric contract.
- [x] Keep DAG coordinates available through overlay facets.
- [x] Use pointcutting only where it is the natural boundary:
  - [x] Classfile pointcut coordinate.
  - [x] VM object harnessing.
  - [x] Graph-node planning.
  - [x] Cursor facet transition.
  - [x] Rete production activation.
- [x] Avoid adding pointcuts inside passive data holders.
- [x] Avoid adding pointcuts inside user-signal gallery renderers.
- [ ] Decide how blackboard events become user-signalling events.
- [ ] Decide how classfile DAG coordinates become Forge overlay facets.
- [ ] Decide how Rete facts are projected from DAG events.
- [ ] Decide how ACLs gate DAG overlay visibility.

## Stage 12 (continuation) - Notion Clone → Kanban FSM Endgame Chain

Goal: bridge the Notion clone's creation surface to Kanban board state via a pure projection, so the first non-keymux KanbanEvent (TaxonomyNodeCreated) is consumed by the FSM.

Evidence:
- `libs/forge/src/commonMain/kotlin/borg/trikeshed/forge/notion/NotionKanbanBridge.kt`
- `libs/forge/src/commonMain/kotlin/borg/trikeshed/forge/notion/TaxonomyCreator.kt`
- `libs/forge/src/commonMain/kotlin/borg/trikeshed/forge/notion/CursorDrivenNotion.kt`
- `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/KanbanFSM.kt`
- `:libs:forge:jvmTest --tests 'borg.trikeshed.forge.notion.*'` — 8/8 pass
- `:jvmTest --tests 'borg.trikeshed.userspace.reactor.*'` — 4/4 pass

- [x] NotionKanbanBridge projects CursorNotionState mutations into TaxonomyNodeCreated events.
- [x] Non-creating mutations (position, title) do not emit TaxonomyNodeCreated.
- [x] TaxonomyCreator produces real CursorNotionState and feeds through the full spine.
- [x] KanbanFSM.reduce() handles TaxonomyNodeCreated (the first KanbanEvent not about keymux).
- [x] TaxonomyNodeCreated rolls into taxonomyNodeCount and recentTaxonomyNodes in KanbanState.
- [x] TaxonomyNodeCreated coexists with CredentialLoaded/KeyLeased/LeaseReclaimed without interference.

Non-goals (Stage 12 original):
- Runtime conversions into user signals, Forge overlay facets, Rete facts, ACL gates remain open.

## Stage 13 - ISAM Column Groupings

Goal: user graph queries resolve into ISAM column-oriented groupings per consuming method.

Evidence: `src/commonMain/kotlin/borg/trikeshed/isam/IsamColumnGroupings.kt`; `runIsamDemo` now renders node id, label, consuming method, ISAM columns, facets, and ACL status.

- [x] Define consuming method identifiers.
- [x] Define query result grouping shape.
- [x] Define column grouping for Kanban board columns.
- [x] Define column grouping for Kanban cards.
- [x] Define column grouping for Cascade graph nodes.
- [x] Define column grouping for overlay ACL rows.
- [x] Define column grouping for LCNC facet rows.
- [x] Decide whether grouping is persisted in ISAM immediately or kept as an in-memory contract first.
- [x] Keep query output renderable as:

  ```text
  node id
  label
  consuming method
  ISAM columns
  facets
  ACL status
  ```

## Stage 14 - Verification Matrix

- [x] Root build:

  ```bash
  ./gradlew --no-daemon build --warning-mode all
  ```

- [x] Root JVM compile:

  ```bash
  ./gradlew --no-daemon compileKotlinJvm --warning-mode all
  ```

- [x] Forge JVM compile:

  ```bash
  ./gradlew --no-daemon :libs:forge:compileKotlinJvm --warning-mode all
  ```

- [x] User-signals JVM demo:

  ```bash
  ./gradlew --no-daemon :libs:user-signals:runUserSignalsGalleryJvm --warning-mode all
  ```

- [x] User-signals JS Node compile:

  ```bash
  ./gradlew --no-daemon :libs:user-signals:compileKotlinJs --warning-mode all
  ```

- [x] User-signals host native compile on supported host:

  ```bash
  ./gradlew --no-daemon :libs:user-signals:compileKotlinMacos --warning-mode all
  ```

- [x] Forge Notion/Kanban FSM tests (Stage 12 continuation):

  ```bash
  ./gradlew --no-daemon :libs:forge:jvmTest --tests 'borg.trikeshed.forge.notion.*' --warning-mode all
  # 8/8 pass: NotionKanbanBridgeTest + TaxonomyCreatorTest
  ```

- [x] Kanban FSM / reactor tests:

  ```bash
  ./gradlew --no-daemon :jvmTest --tests 'borg.trikeshed.userspace.reactor.KanbanReactorFSMTest' --warning-mode all
  # 4/4 pass
  ```

- [ ] Do not run parallel Gradle compilations when touching shared Kotlin incremental caches.
- [ ] If a verification command fails from cache locking, rerun serially before changing source.
- [ ] If root build fails from unrelated pre-existing commonMain noise, filter errors to touched files first.
- [ ] Root `:jvmTest` has 2 pre-existing CacheStoreJvm failures (kotlinx.serialization, dead code, non-blocking). Run targeted tests instead of the full suite until resolved.

## Stage 15 - Existing Root TODOs Rehomed

These were the previous root TODO items and remain valid inside the tasktree:

- [ ] Implement LCNC integration for `ForgeWorkspace` with Miniduck/ISAM and websocket sync.
- [ ] Implement LCNC integration for `ForgeStepRunner` with the LCNC runtime.
- [ ] Add `AgentType.LCNC` to the Forge agent runner path.
- [ ] Add ACH exception workflow with LCNC backend.
- [ ] Add partner onboarding with LCNC compliance and credit checks.
- [ ] Add real-time collaboration UI.
- [ ] Add artifact marketplace and sharing protocol.

## Stage 16 - Definition of Done Per Layer

- [ ] The layer compiles in the narrowest relevant target.
- [ ] The layer has a thin user demo if it is user-facing.
- [ ] The layer has explicit CCEK choreography if it is an entry point.
- [ ] The layer uses root `SupervisorJob` scaffolding if it has a `main`.
- [ ] The layer does not introduce hidden list-backed foundation storage.
- [ ] The layer does not add Rete or pointcut behavior unless it sits at a necessary boundary.
- [ ] The layer records drift in `PRELOAD.md`.
- [ ] The layer has a clear follow-up item rather than speculative half-implementation.
