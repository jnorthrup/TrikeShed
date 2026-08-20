# Midpoint Map — landing the plane

Date: 2026-08-20. Additive map: nothing precludes, cleans house, or simplifies.
Stance: **code-forward**. The shipped frontier has overtaken parts of the
written canon (README concept map, `rewire.md`, `taste.md` Jul-2026 audit) —
the horses have left the gate. Where canon and frontier diverge, this map does
not rule for either side; it names the midpoint that joins them. Anchors are
`file:line` at time of writing.

## The five rings (what exists — canon + frontier)

### Ring 1 — Truffle pointcutting VMs
- `pointcut/SubgraalPointcutRunner.kt` (jvmMain) — GraalVM polyglot Context
  (python, js), `ExecutionListener` on enter/return, publishes into
  `TypedefProductionSystem` via the `java_trikeshed_publish` proxy.
- `pointcut/VmFacet.kt` — VM facet enum (JVM, GRAAL_JS, GRAAL_PYTHON, …).
- `pointcut/polyglot/PointcutPolyglotBlackboardTaxonomy.kt` — the contract:
  *child GraalCE VMs contribute pointcut coordinates, merged back into the
  central blackboard* (`ConfixBlackboard`); Kata sandbox registry included.
- `userspace/nio/ebpf/**` — eBPF JIT engine (X16 lineage: "eBPF-JIT → Truffle
  pointcuts, polyglot containment", `docs/dispatch/2026-08-18-*.session-map.md`).
- Canon hook: `rewire.md` names *K8s emulation via GraalVM pointcut server*.
- Deps locked: `org.graalvm.truffle:truffle-api` (build.gradle.kts:225).

### Ring 2 — The blackboard (two live truths, one midpoint owed)
- **Canon** (README §8.1): blackboard-as-Confix-cursor — one JSON doc →
  `confixDoc()` → `Cursor` → `BlackboardSurface.project(cursor)`; facet
  drilldown = child cursor projections; "no parallel DTO truth."
- **Frontier**: `graal/ConfixBlackboard.kt` — shipped, tested
  (`ConfixBlackboardTest`), provenance per key (language + timestamp),
  subscriber fanout — and it already keeps a `ConfixDoc` as its `state`.
- `dag/BlackboardDagFabric.kt` — `DagCoordinate` event fabric;
  `dag/ReteAgent.kt` — live rules against `CausalGraphNodeIndex`;
  `dag/ReteCausalBridge.kt` — sole causal→rete seam (locked).
- Job Nexus (README §3) — the durable work spine: `JobCommand` →
  validation → canonical CBOR → CAS → WAL → reducer → committed events.

### Ring 3 — Stateless agents (agentic live nodes)
- `jules/JulesRestClient.kt` — *"Stateless Jules REST client. Zero board
  state — the Kanban cards own all state."* KeyMux key per request,
  rotation on 429.
- `jules/BrainClient.kt` — routing via ModelMux, credentials via KeyMux
  (authoritative design), 15s timeout + rotation (README CCEK table: ✅).
- `modelmux/ModelMux.kt` + `modelmux/acp/AcpProtocol.kt` — ACP type algebra,
  `CapabilityRouter`; `ModelSelectionEvent` / `QuotaTelemetry` /
  `ProviderHealth` are ready-made observability payloads.
- `keymux/KeyMux.kt` — dotted-path keys, sealed sources, `LeaseMetadata`.
- `jules/ui/JulesBlackboardAdapter.kt` — the reference surface projection:
  facade state → `ForgeBlackboardSection3D` geometry as a TTL'd seed.
- Canon hook: `rewire.md` §4 — agent workflow is JobCommand transitions
  (Submit → triage card → Rete fires → `ModelMuxBuilder.route` → Start/…/Ack);
  Immediate Cut #2 is the "Modelmux kanban agent" JobCommand handler.

**Invariant (provider neutrality)**: the agent contract is runtime-agnostic.
Jules, opencode, kilo, OpenAI-compatible endpoints, Claude endpoints —
admission is capability (`CapabilityRouter`) + key lease (KeyMux), never
provider identity. No runtime is privileged in dispatch, provenance, or
surface projection.

### Ring 4 — Collaborative concentric networks under NUIDs
- `context/nuid/Nuid.kt` — bearer algebra `Capability j (Nonce j Subnet)`;
  concentric subnets; `Capability.Model` = "modelmux" (Nuid.kt:75).
- `context/nuid/NuidFanoutElement.kt` — concentric-narrowing dispatcher:
  innermost-first claim, outward escalation (README §8.1a).
- `dht/id/NUID.kt` + `dht/routing/RoutingTable` + `util/oroboros/
  OroborosNetwork.kt` — numeric XOR-metric overlay (DhtLookup/FanoutFetch).
- Canon hook: `rewire.md` §6 mesh — UPnP/SSDP discovery + SSH tunnels over
  litebike Tls, peers authenticated **via NUID**; Immediate Cuts #3/#4.
- `wireproto/ConfixWorker.kt` — NUID-borne `ReactorAction` over CBOR:
  the wire form already round-trips.
- `commonTest/kanban/ConcentricKanbanDemoTest.kt` — full stack demoed
  (NUID topologies + KeyMux + ModelMux + kanban) — test-only today.

### Ring 5 — Forge GUI + LCNC language (frontier ahead of audit)
- `forge/blackboard/ForgeBlackboardCamera.kt` — `ForgeBlackboardView.DEFAULT`,
  sections page/board/gallery/graph, 3D layout; `ForceLayout`, `LineCasGraph`.
- `lcnc/editor/BlockEditor.kt` / `PropertyEditor.kt` / `DatabaseView.kt` —
  **exist in-tree** although `taste.md` T22 (Jul audit) records them absent:
  frontier overtook the audit. Same for `lcnc/formula/LcncFormula.kt` (T23's
  AST) and `lcnc/rollup/**` (T24's spine).
- `lcnc/reactor/LcncIngestPipeline.kt` + Csv/Markdown codecs — ingest half;
  `rewire.md` §5 is the canonical seven-stage corpus pipeline it grows into.
- `lcnc/LcncGrid.kt` — *a Grid is exactly a Cursor*.
- `context/lcnc/LcncSpineMarks.kt` — the keystone: zero-cost byte marks
  aligning blackboard (`FacetMark`↔FacetTransitionType), causality
  (`CausalMark`↔CausalEdgeKind), and pointcut (`PointcutMark`↔FieldSynapse
  TPL_*) on one identity spine.
- `context/lcnc/LcncFanoutElement.kt` — NUID winner → reduction → marked
  result → `SignalFacetReduced` kanban event.

## The midpoints (seams to draw in)

### M1. Pointcut coordinates → blackboard, canon-and-frontier at once
Ring 1 publishes into `TypedefProductionSystem`; the taxonomy promises a merge
into `ConfixBlackboard`; canon's fabric coordinate is `DagCoordinate`.
**Midpoint**: one commonMain adapter
`PointcutEvent → DagCoordinate → ConfixBlackboard.put(key, value, language = VmFacet.id)` —
and because `ConfixBlackboard.state` *is already a ConfixDoc*, the same entry
is canon-compliant for free: `BlackboardSurface.project(confixDoc)` reads it
as cursor rows. The mutable map stays what it is — a cache over the doc, not
a second truth. Pointcut facts arrive content-addressed, provenance-stamped
(VmFacet id in the existing `language` slot), carrying their `PointcutMark`.

### M2. Two dispatch lanes, one claim: NUID selects *who*, JobCommand commits *what*
Canon: durable transitions enter **only** through the bounded
`Channel<JobCommand>` (README §3 invariants; Rete actions never mutate
directly). Frontier: `NuidFanoutElement` claim + `LcncFanoutElement.dispatch`
run in-process. These are not rivals — they are two lanes of one seam:
- **selection lane** (frontier): concentric NUID claim picks the workgroup —
  Jules facade, BrainClient/ModelMux, a kilo/opencode runner — statelessly;
- **commitment lane** (canon): the claimed work's transitions land as
  `JobCommand`s through `JobSupervisorElement` (idempotency, revision, WAL).
**Midpoint**: the claimed reduction's `SignalFacetReduced` event is already
kanban-shaped — route it into the JobCommand ingress, and `rewire.md`
Immediate Cut #2 (Modelmux kanban agent) becomes exactly this: a JobCommand
handler whose *worker selection* is the NUID claim. Registration of the
production facades as `Workgroup`s (today test-only in
`ConcentricKanbanDemoTest`) is the remaining wiring. Provider-neutrality
invariant applies verbatim.

### M3. `JulesBlackboardSurface` generalized to a surface-projection contract
Lift its shape (payload + sectionId + geometry + updatedAt + ttlMs) into a
section-agnostic `ForgeSurfaceProjection` so every ring projects identically:
pointcut facts → `graph`; rete fires + Job Nexus lifecycle → `board`;
ModelMux/KeyMux telemetry and leases → `gallery`; Jules sessions → `page`/
`board` (done). Render debt stays owned by `docs/forge-ui-gap-analysis.md`
(items 2/4/5); ingest canon stays owned by `rewire.md` §5.

### M4. Three concentric transports joined at escalation, not merged
Innermost: in-process NUID claim. Middle: `rewire.md` §6 mesh — UPnP/SSDP
discovery + SSH tunnels, NUID-authenticated (Immediate Cuts #3/#4). Outermost:
DHT/Oroboros content overlay (XOR-metric NUID + `FanoutFetch`). **Midpoint**:
an embedding — `Subnet.level ↔ NetMask.bits`, capability ↔ trait bits — so
`NuidFanoutElement`'s outward escalation hands unclaimed, CBOR-wire-formed
actions (ConfixWorker) first to mesh peers, then to the overlay. One unbroken
gradient from local claim to cross-host fanout; both NUID algebras intact.

### M5. LCNC formulas onto the Cursor substrate — completing T23/T24/T27 forward
The frontier already banked T22/T23/T24 partials the Jul audit lists as
absent. **Midpoint** (in `taste.md`'s own task vocabulary): T23's AST gains an
`evaluate(RowVec)` overload beside the Map form (LcncGrid is a Cursor); T24's
rollups admit formulas into `LcncReductions`; T27's ingest feeds the editor
views. Then a block in `BlockEditor` compiles to a reduction dispatched on
M2's selection lane and committed on its commitment lane — the LCNC language's
runtime *is* the dispatch algebra, its instruction identity *is*
LcncSpineMarks. A block naming a polyglot language routes to Ring 1 via
`PolyglotKataRegistry.suggest` — LCNC's escape hatch to full code is the same
pointcut taxonomy, not a new VM seam.

### M6. Authoritative cross-references (not duplicated here)
- Concept map / spine / pitfalls: `README.md` (§2 spine, §3 Job Nexus, §8 surfaces).
- Workspace architecture + immediate cuts: `doc/rewire.md` (§0 one-CID-five-lenses,
  §4 agents, §5 ingest, §6 mesh, §9 cuts).
- UI-engine principles + LCNC task ledger: `doc/taste.md` (T22–T29).
- Flywheel task intake: `doc/todo.md` — **live**: every unchecked line is
  auto-inducted and dispatched to a Jules session. Midpoints M1–M5 become
  work by adding `- [ ]` lines there — a deliberate act, not a side effect.
- Render debt: `docs/forge-ui-gap-analysis.md`. Drain contract:
  `docs/JULES_DRAIN_CONTRACT.md`. Pointcut/eBPF lineage:
  `docs/dispatch/2026-08-18-legion-counter-threat.session-map.md` (X16).

## Reading the map

```
        ┌──────────────────────────── Forge GUI (Ring 5) ───────────────────────────┐
        │   page / board / gallery / graph  ← M3 surface projections (TTL seeds)    │
        └────────────▲───────────────────────────────▲──────────────────────────────┘
                     │                               │
   LCNC blocks ── M5 ──► selection lane (NUID claim) ── M2 ──► commitment lane
   (formula→reduction)   NuidFanoutElement picks the          JobCommand → WAL/CAS
                     │   stateless facade (Ring 3)            → committed events
                     │        │
                     │        └── unclaimed ── M4 ──► mesh (SSH/UPnP) ──► DHT/Oroboros
                     ▼
        ConfixBlackboard.state = ConfixDoc ◄── M1 ── Truffle/Kata child VMs (Ring 1)
        └─ BlackboardSurface.project(doc) — canon and frontier read one truth
                     ▲
                ReteAgents (live rules; actions → JobCommands, never direct mutation)
```

One sentence: **stateless facades claim NUID-addressed work concentrically and
commit it durably through the Job Nexus, Truffle children contribute
provenance-stamped pointcut facts into the one Confix truth, everything
projects onto the forge blackboard through one surface contract, and the LCNC
language is the user-facing notation for exactly that dispatch algebra.**
