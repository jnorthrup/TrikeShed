# TrikeShed TODO — Optimized & Coalesced

## Active Cuts (Current Sprint)

- [ ] **Causal-buildup movie — single-pass, no pantomime**
  - Push node → await fire → reduce FSM → move card → capture frame → next
  - No replay, no hardcoded step list
  - ReteAgent fires on real Panama DAG entries from `PanamaInduction.resolveDagEntries()`
  - `KanbanFSM.reduce()` is the single source of truth for board state
  - Verify: `./gradlew :libs:forge-ui:runPanamaKanbanMovie` → MP4 shows causal chain in real time

- [ ] **Model access wired for real LLM calls**
  - Keymux leases keys → authoritatively from `~/.hermes/auth.json` (read as DATA, not Hermes CLI)
  - Modelmux cache hits/misses drive actual provider selection
  - No fake telemetry in narration — HUD reads live reactor state
  - Verify: cache hit/miss mix (not 100% hits), leased key visible in HUD

- [ ] **Forge UI root on Node.js — Notion-like atlas emits on `node TrikeShed.js`**
  - `src/commonMain/.../forge/ForgeAtlas.kt` → `src/jsMain/.../ForgeNodeMain.kt`
  - `./gradlew jsNodeProductionRun` prints HTML atlas
  - Atlas includes: outline, kanban terrain, blackboard summary, transition rail, Node.js launcher mention

## Next Cuts (Queued)

- [ ] **GraalPy agent runtime** — move Forge core onto GraalVM + GraalPy
  - Python logic runs in GraalPy with clean fallback for heavy native libs
  - Kanban = projection over Blackboard + algebraic operators + cursors (not separate system)
  - `ClassfileTaxonomy` → `BlackboardEvent` emission bridge

- [ ] **Panama classpath induction — real neighbor project interrogation**
  - Forge reads panama's 3 services (gswormk TS brain, cache-tier Hazelcast, columnar ISAM)
  - CodebaseTool induction produces DAG entries → ReteAgent consumes
  - Classpath separation enforced: forge project state (panama) vs master blackboard (TrikeShed)

- [ ] **Hazelcast analog for Reactor/CCEK** — distributed cache tier
  - Compatible with `MuxReactorElement` keymux/modelmux
  - Warm cache between columnar (cold) and engine (hot)

- [ ] **CodebaseTool → Notion metaphor integration**
  - Panama symbols → ConfixBlackboard DAG → Notion blocks via `SoftwareKanbanNotionProfile`
  - `NotionKanbanBridge.projectTaxonomyEvents` → `KanbanFSM.reduce()` → board moves

## Resolved / Done

- ✅ `PanamaInduction` — 3 services (gswormk, cache-tier, columnar) with real manifests/entrypoints
- ✅ `PolyglotBlackboardAdapter` — TS/JVM/Python → `BlackboardDagEntry`
- ✅ `WidgetShowcaseStatic` removed (broken); `WidgetShowcaseMovie` runner works
- ✅ `ReteAgent` compiles & fires on `CausalGraphNode` via Channel sink
- ✅ `ReteCausalBridge` projects `NodePlanning` → `CausalGraphNodeIndex` + `ReteFact.NodeFact`
- ✅ `KanbanFSM` + `MuxReactorElement` keymux/modelmux wired, tests pass
- ✅ Root JS target emits Forge atlas HTML on Node (`jsNodeProductionRun`)
- ✅ `PanamaKanbanMovie` runs, renders MP4, but still uses replay (needs single-pass fix above)

## Non-Goals (Explicit)

- No Hermes Agent runtime features (gateway, cron, Python tools, LLM dispatch, messaging)
- No Python for data path — Kotlin/JVM + GraalPy only
- No `~/.hermes` access via Hermes CLI/API — read as local files only
- No Kanban owning keymux/modelmux state — reactor owns it, Kanban consumes events
- No demo/pantomime code — every frame must reflect real state transition

## Architecture Invariants (Guardrails)

| Layer | Owns | Does NOT Own |
|-------|------|--------------|
| Reactor (`MuxReactorElement`) | keymux, modelmux, cache, `kanbanEvents: SharedFlow` | Kanban board, Notion blocks |
| Kanban FSM | `KanbanState` derived from `KanbanEvent` stream | Keys, leases, model cache |
| Forge UI | Renders `KanbanState` + `BlackboardSurface` cursor | Event production, model calls |
| Panama Induction | Read-only subject (DAG entries) | Master blackboard classpath |
| Master Blackboard | TrikeShed CCEK jars, ConfixBlackboard | Panama project state |

---

*Generated from session journal — 46 directives coalesced into 5 active cuts, 4 queued, 10 resolved, 5 non-goals.*