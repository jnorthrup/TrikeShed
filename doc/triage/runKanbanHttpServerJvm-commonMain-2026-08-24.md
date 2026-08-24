# triage: runKanbanHttpServerJvm → commonMain closure

date: 2026-08-24
scope: `tasks.register<JavaExec>("runKanbanHttpServerJvm")` (`KanbanServerMain` → `JvmKanbanServer` + `BlackboardWire` + `VmWire`) vs gh-pages featureset (`generateForgePages`/`ForgeIngestServer`/`docs/`)

principle: **not all usecases require the VM harness to concord.** The VM is one capability tier (`VmHost`/`GraalVM polyglot`). The durable investments are the top-level Kotlin usecases that already render without a live host — `ForgeApp.renderHtml`, `ForgeKanbanIngest`, `FlywheelMetrics`, `JulesBlackboardAdapter`, `ConfixBlackboard` — and that GH Pages bakes at `generateForgePages`. VM concord is additive, not prerequisite.

---

## 1. current composition

```
KanbanServerMain.run(port, donor)         jvmMain  (3 wires on 1 blackboard)
 ├─ ConfixBlackboard.empty()              commonMain (ConfixDoc CAS)
 ├─ BlackboardWire(blackboard, scope)     jvmMain wrapper  → /blackboard/…
 ├─ HypervisorVmHost(Hypervisor(bb))→VmSupervisor  jvmMain/Graal  → VmWire
 ├─ VmWire(host, scope)                   jvmMain wrapper  → /api/vm/… + /vm-terminal
 └─ JvmKanbanServer(extraRoutes=[vmWire,wire])  jvmMain — the bind
      ├─ LitebikeListenerElement + JvmLitebikeBindAdapter  (the ONE TCP bind)
      ├─ NuidFanoutElement (process/cas/wireproto workgroups) — CCEK
      └─ routeHttp(payload) — hand-rolled HTTP branch
```

`ForgeIngestServer` (`serveForgePages`) is a *second* server: `com.sun.net.httpserver.HttpServer` on `docs/` + `POST /ingest` via `JvmTikaIngestAdapter`. Not mounted on the litebike listener. GH Pages bakes `ForgeApp.renderHtml(userId, julesSurface, flywheelReport, bundles, vmHost)` into `docs/index.html` via `ForgeBakePages` + `generateForgeAssets` (commonMain `ForgeAssets.*` byte-arrays). The browser `docs/script.js` polls the live server when present, otherwise falls back to the baked seed + in-browser shapes (`POST /ingest` → local fallback `unsupported here → ./gradlew serveForgePages`).

## 2. usecase inventory — every live route/feature today

| # | method+path | owner | what it does | GH Pages fate |
|---|-------------|-------|--------------|---------------|
| 1 | `GET /`, `/index.html` | `JvmKanbanServer.forgeShellHtml()` → `ForgeApp.renderHtml` | shell + `{{SEED}}` (board, causalGraph, conceptGraph, sheets, blackboardSeed, hosts, dashboards) | **baked** by `ForgeBakePages` → `docs/index.html`; static copy is ground truth |
| 2 | `GET /styles.css /script.js /sw.js /manifest…` | `JvmKanbanServer.staticAssets` from `commonMain/resources/web/*` | PWA assets | **baked** by `generateForgePages` `from web/` |
| 3 | `GET /api/health` | inline | `{ok,server:"kanban",now}` | live-only probe |
| 4 | `GET /api/cap` | inline | protocols + capabilities | live-only probe |
| 5 | `GET /api/board` | `boardJson()` → `ForgeKanbanIngest.loadProjection(userId)` → `ForgeKanbanReduction` as `ForgeAppState` JSON | board columns/cards | baked into seed `board`; live endpoint is fetchable too — **redundant** |
| 6 | `GET /api/metrics` (+`?format=json`) | `FlywheelMetrics` | Prometheus or JSON | live-only |
| 7 | `GET /api/jules/surface` | `JulesBlackboardAdapter.projectFullSurface(activeSessions)` + `lastReactiveReport` + `FlywheelMetrics` + slots | Jules sessions + cycle + throughput + slots | baked as `blackboardSeed.jules` when driver present; live is fresher — 10s `surfaceCache` |
| 8 | `GET /api/jules/events` SSE | `FlywheelDriver.events` | flywheel events | live-only SSE |
| 9 | `POST /api/submit /api/donor /api/invoke` | `submit(text)` → Tika extract → `ForgeKanbanIngest.persistMarkdown(user, path)` | ingest markdown/doc → kanban | `ForgeIngestServer POST /ingest` is the GH Pages analog (richer: `X-Forge-Name`, `persist`, ffmpeg+tesseract); two ingest paths, no shared gate |
| 10 | `GET /blackboard/facts?since=` SSE | `BlackboardWire` | bounded ring 256 + `ConfixBlackboard.changes` SSE | no Pages analog — board seed is snapshot |
| 11 | `POST /blackboard/assert` | `BlackboardWire` | `blackboard.put(k,v,"ide")` | no Pages analog |
| 12 | `GET /blackboard/sites?owner=` | `BlackboardWire` | `keys().filter(prefix)` | no Pages analog |
| 13 | `GET /api/vm` | `VmWire` | VM sheet (`VM_COLUMNS` rows) | baked as `hosts.vms` sheet in seed; live duplicate |
| 14 | `POST /api/vm/spawn` | `VmWire` → `VmHost.spawn(VmSpec)` | {id,facet,trust,budget}→{id,tier,terminal} | live-only |
| 15 | `POST /api/vm/{id}/eval` | `VmWire` → `VmHandle.eval` + `VmTerminalRegistry` causal binding | {cid,value: Teleported} | live-only |
| 16 | `POST /api/vm/{id}/revoke` | `VmWire` → `VmHost.revoke` + `commandChannels.remove` | ok | live-only |
| 17 | `GET /api/vm/events` SSE | `VmWire` → `VmHost.events` | VmEvent replay+stream | live-only |
| 18 | `GET /vm-terminal?id=` | `VmWire` | `web/vm-terminal.html` VT220 page | live-only (no baked analog) |
| 19 | `GET /api/vm/{id}/terminal` | `VmWire` → `VmTerminalRegistry.get(id).snapshotMap()` | snapshot | live-only |
| 20 | `POST /api/vm/{id}/terminal/input` | `VmWire` → per-vm `Channel<TerminalCommand>(64)` → `executeTerminal` | {text,mode:eval|stdin}→{accepted,signal} | live-only |
| 21 | `POST /api/vm/{id}/terminal/resize` | `VmWire` | columns×rows → patches | live-only |
| 22 | `GET /api/vm/terminals` | `VmWire` | snapshots list | live-only |
| 23 | `GET /api/vm/terminal/events` SSE | `VmWire` → `VmTerminalRegistry.events` | VmTerminalEvent stream | live-only |
| 24 | `POST /ingest` (ForgeIngestServer only) | `ForgeIngestServer.ingest` → `JvmTikaIngestAdapter.extractToMarkdown` + `ForgeKanbanIngest.persistMarkdown` | {name,markdown,plan,persisted} | GH Pages local ingester; not on `JvmKanbanServer` |
| 25 | `GET /ingest` static `docs/` tree | `ForgeIngestServer` static handler | file serve | `generateForgePages` stages `jvm[,js,wasm]` bundles into `docs/{js,wasm}/`; GH Pages serves same tree statically |

openapi sink: `openapi/forge-host.openapi.yaml` declares the intended canonical surface (KanbanServerMain = litebike + BlackboardWire + VmWire); parity test `ForgeHostSpecParityTest` enforces server↔spec agreement.

## 3. concordance split — what needs the VM and what does not

### A — VM-orthogonal (portable today, highest long-term return)

These are pure Kotlin value transforms + WAL/blackboard. They already run in `ForgeApp.renderHtml` and bake correctly on Pages. The server merely re-exposes them over HTTP. Moving their *routing* to commonMain closes 80% of the usecase surface without ever touching Graal.

- **Shell rendering** (#1) — `ForgeApp.renderHtml` + `ForgeAssets` + `ForgeAppState`/`ForgeGalleryRenderer`. Already commonMain. The JVM `forgeShellHtml()` wrapper is one line.
- **Board** (#5) — `ForgeKanbanIngest.loadProjection/fallbackReduction` + `ForgeAppState`. The `/api/board` live fetch is redundant with the baked seed; make it a commonMain `BoardRoute`.
- **Health/cap/metrics** (#3,#4,#6) — trivial maps; `FlywheelMetrics.toJsonMap/toPrometheusFormat` is commonMain.
- **Jules surface projection** (#7) — `JulesBlackboardAdapter.projectFullSurface` + `ForgeBlackboardView` + `forceLayout` are commonMain. Only the *fetch* of `activeSessions` comes from `FlywheelDriver` (JVM). Cache+TTL logic belongs in commonMain with an injected `JulesSessionSource` interface.
- **Blackboard** (#10-12) — `ConfixBlackboard` (CAS + `changes` flow) is commonMain. `BlackboardWire`'s ring+SWS is pure channel logic; HTTP framing is platform. Extract `BlackboardRoutes` to commonMain `expect fun serveSse`.
- **Ingest shape gate** (#9 core) — `ForgeKanbanIngest.isPlan/persistMarkdown` + `IngestFormat/IngestSource/LcncIngestPipeline` are commonMain. Only `JvmTikaIngestAdapter.extractToMarkdown` (Tika + ffmpeg+tesseract) is JVM. Common `IngestGate { detect, extract, isPlan, persist }` with `expect extract()` isolates the one JVM piece; `POST /api/submit` and `POST /ingest` then share the gate.
- **Static assets + PWA** (#2,#25 assets) — `generateForgeAssets` already bakes `web/*` into commonMain byte arrays; `openapi/*`, `confix/job-nexus.schema.json` via `ForgeResourceBundle`. Serve them from commonMain `AssetTable`.
- **Host/nio dashboard** — `forgeHostsSeed/nioSeed` (nio `NioCapabilityReport`, `Discontinued`) is commonMain.

### B — VM-concordant (requires live `VmHost`, but seed projection is already commonMain)

- **Spawn/eval/revoke/events** (#14-17) — `VmHost` + `VmSpec/VmBudget/VmTrust/VmFacet` + `VmEvent` is commonMain *interface*, but `HypervisorVmHost` binds `org.graalvm.polyglot` (InProcessIsolate/ProcessIsolate/Hypervisor) in jvmMain. Routing (`VmWire.ROUTES`) can be commonMain route table; execution stays `expect`.
- **Terminal** (#18-23) — `Vt220Terminal` emulation + `MediaPatchPanel` + causal signals are commonMain; `VmTerminalRegistry` is already in commonMain-adjacent code. Only the `Channel<TerminalCommand>` per-vm dispatch + `Host.get(id).eval` evaluation needs the live host. Page shell `vm-terminal.html` should be an `AssetTable` entry.

Key: `forgeHostsSeed` already projects `VmHost` without requiring it to be alive — `VmSupervisor.reports`, `VM_COLUMNS` rows, `Discontinued`, `NioCapabilityReport` all render on static Pages (dead host shows `discontinued: [vm.spawn…]`). VM routes add liveness; the seed does not need it.

## 4. loop not yet closed — gaps

1. **Two servers, one ingest.** `POST /api/submit` (kanban) and `POST /ingest` (forge ingester) run different adapters and produce the same `persistMarkdown`. GH Pages drop-zone in `index.html:48-58` still falls back to `unsupported here → ./gradlew serveForgePages`. The working ingest on the kanban port is not the one the PWA posts to.

2. **`routeHttp` is JVM-only.** `JvmKanbanServer.routeHttp(payload: ByteArray): HttpResponse` parses `method/path/text` from raw bytes and threads `extraRoutes/rawRoutes/streamingPaths`. No commonMain `ForgeRouteRegistry` exists; `jsMain/wasmJsMain` cannot mount the same table, so `jsTargetDebt` keeps cutting files out.

3. **`BlackboardWire` and `VmWire` HTTP framing is in the wire.** SSE headers, `respond` callbacks, `TerminalCommand` queue — all inline in `JvmKanbanServer`'s httpSlot loop. Nothing is `commonMain` testable without a socket.

4. **Bake vs live drift.** `ForgeBakePages` seeds `julesSurface=null, flywheelReport=null, vmHost=dead` by default; live `GET /` re-renders per request with fresh hosts. There is no single `ForgeSeedFactory(userId, sources) → String` shared by both.

5. **Spec sink vs route table.** `openapi/forge-host.openapi.yaml` is hand-written from working routes; drift is caught only by `ForgeHostSpecParityTest`. The route table is not generated from the spec nor vice-versa.

## 5. target — commonMain-sourced decomposition

```
commonMain
 ├─ forge/server/ForgeRoutes.kt          ← route table value (no I/O)
 │    data class ForgeRequest(method, path, headers, body: ByteArray)
 │    data class ForgeResponse(status, headers, body: ByteArray)
 │    interface ForgeHandler { suspend fun handle(req: ForgeRequest): ForgeResponse? }
 │    object ForgeRouteRegistry {
 │        val PHASES = listOf(
 │           HEALTH, CAP, BOARD, METRICS, JULES_SURFACE,    // Phase A — no host
 │           BLACKBOARD_FACTS, BLACKBOARD_ASSERT, BLACKBOARD_SITES,
 │           INGEST,                                        // Phase A+b — gate
 │           VM_SHEET, VM_SPAWN, VM_EVAL, VM_REVOKE, VM_EVENTS, // Phase B — needs host
 │           VM_TERMINAL_PAGE, VM_TERMINAL_SNAPSHOT, VM_TERMINAL_INPUT, VM_TERMINAL_RESIZE
 │        )
 │    }
 ├─ forge/server/RouteAdapters.kt        ← expect fun bindAndServe(registry, port) / actual Jvm/JS
 ├─ forge/server/ForgeSeedFactory.kt     ← shared bake+live: (userId, BoardSrc, JulesSrc, NioSrc, VmSrc, BundleList) → seedJson
 ├─ forge/ingest/IngestGate.kt           ← expect fun extract(bytes,name): String  + isPlan/persist in common
 ├─ vm/VmHost.kt (already) + VmTerminalEmulation.kt  ← already mostly common
 └─ generated/ForgeAssets + ForgeResourceBundle (already)
jvmMain actuals
 ├─ litebike/JvmLitebikeBindAdapter  (the ONE bind)
 ├─ graal/subvm/*  (HypervisorVmHost actual)
 ├─ kanban/JvmTikaIngestAdapter  (expect extract actual)
 └─ forge/server/JvmSse.kt + JvmStaticAssets.kt
jsMain/wasmJsMain actuals
 ├─ Fetch/Http adaptation for non-litebike targets (or WASI wasi-http)
 └─ IndexedDB/resource bundle for bake artifacts
```

Rules: (a) `JvmKanbanServer` stops owning route strings — it owns only the listener/bind fanout. (b) Every route registers in `ForgeRouteRegistry` with its OpenAPI path — parity test iterates the registry, not a hand list. (c) `ForgeSeedFactory` is the single call site for `htmlShell(seed,bundles)` used by both `ForgeBakePages` and the live `GET /`.

## 6. ordering — most powerful first (no VM needed)

**P0 — close the docs loop without the VM.** Extract `HEALTH/CAP/BOARD/METRICS/INGEST( shape gate)/STATIC/ASSETS` into `ForgeRoutes`. Wire `JvmKanbanServer.routeHttp` to delegate to it; verify `GET /`, `/api/board`, `/api/health`, `/api/cap`, `POST /api/submit` serve identically through `generateForgePages` baked seed + live fallback, with no `VmHost` constructed. Proof: `jvmMainClasses` green, `ForgeHostSpecParityTest` still passes, Pages static build unchanged, `curl :8888/api/board` == seed board.

**P1 — unify ingest.** Merge `POST /api/submit` and `ForgeIngestServer POST /ingest` into `IngestGate`. Mount the unified `POST /ingest` on the litebike listener (or keep both aliases) so the PWA drop-zone posts to the kanban port directly; deprecate the second `HttpServer` or keep `serveForgePages` as a pure static fallback for offline dev. This removes the `unsupported here` branch.

**P2 — blackboard.** Lift `BlackboardWire` ring+flow logic to commonMain; keep only SSE header framing as `expect`. Add parity: `GET /blackboard/facts?since=` replays from same ring on JS.

**P3 — seed factory.** Introduce `ForgeSeedFactory` so `ForgeBakePages` and `GET /` call one function. Bake-time and live JSON become diffable; add `ForgeSeedFactoryTest` that asserts `bake(seedFactory(liveSources))` round-trips through `JsonSupport`.

**P4 — VM routes as common table, jvm execution.** Move `VmWire.ROUTES` strings + validation (`VM_ID` regex, facet/trust/budget parsing) to commonMain `VmRoutes`. Keep `host.spawn/eval/revoke` as `actual`. Terminal VT220 emulation already common; only `executeTerminal`'s `host.get(id).eval` stays jvm.

**P5 — spec generation.** Generate `openapi/forge-host.openapi.yaml` from `ForgeRouteRegistry` (or vice-versa) and delete the hand drift; `ForgeHostSpecParityTest` becomes a build-generated check.

## 7. what NOT to do

- Do not make the kanban HTTP server depend on a live `VmHost` to boot. `KanbanServerMain` should start and serve the whole Phase-A table with `VmHost.dead` (already the GH Pages behavior); VM routes 405/503 gracefully when dead.
- Do not preserve `ForgeIngestServer`'s separate `com.sun.net.httpserver.HttpServer` as the canonical ingester long-term; it violates the CCEK single-bind rule and is the LitebikeListener violation flagged in gap analysis.
- Do not port Tika/ffmpeg/tesseract to commonMain; isolate that one `expect extract()` actual and keep the shape gate common.

## 8. acceptance — "leave no usecase unintegrated"

- Every row #1-25 above maps to either a `ForgeRouteRegistry` entry (with phase + target) or is marked retired with a drain note in the table. No row stays jvm-only prose.
- `jvmMainClasses` + `jsCompileKotlinJs` + `wasmJsCompile` green with no new `js-target-debt.excludes` entries; the debt file shrinks.
- `generateForgePages` then `serveForgePages` serves the same `GET /`, `GET /api/board`, `POST /ingest` contract as `runKanbanHttpServerJvm`; `docs/script.js` live-note (`served live — spawn/eval`) lights on either port.
- Parity test enumerates the registry: adding a route without an entry fails the build.

## 9. immediate next action (one cycle)

Pick P0 only: create `src/commonMain/kotlin/borg/trikeshed/forge/server/ForgeRoutes.kt` with `HEALTH/CAP/BOARD` (3) + `STATIC` as first slice; patch `JvmKanbanServer.routeHttp` to delegate for those paths; leave `extraRoutes/rawRoutes` seam intact for Phase B. Land a 4-test `ForgeRoutesTest` (health json, cap json, board == seed, 404) in `commonTest`.
