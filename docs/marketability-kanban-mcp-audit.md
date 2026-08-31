# Oroboros marketability and MCP Kanban audit

> **Status:** current repository audit, 2026-08-29
>
> **Scope:** documentation and live-source inspection only; no external market survey, code change, live-board mutation, or flywheel dispatch
>
> **Decision:** the proposed marketability backlog below is inert until a human explicitly imports or submits it
>
> **Update 2026-08-30:** the MCP half of the implementation backlog
> (KMFSM-004/005/006) is built, tested, and documented — see
> [Delivery record](#delivery-record--2026-08-30) and
> [`guide-mcp-kanban.md`](guide-mcp-kanban.md). The MKT-001…015 market backlog
> is unchanged and still inert.

## Executive finding

LCNC already owns Kanban as a user asset. `LcncKanbanExperience` provides the
active-sheet projections and the `kanban.submit`, `kanban.move`, and
`kanban.activeSheets` runners; `KanbanModule` composes and mounts that experience
inside Oroboros. Under it, one WAL-backed `BoardStoreElement` owns durable card
state, serialized intake, CAS-addressed raw commands, idempotency, optimistic
revisions, WIP enforcement, dependency-cycle rejection, restart replay, and
committed receipts.

What the LCNC Kanban asset lacks is **marketable packaging and an MCP
projection**, not a new home. The existing `McpServerHandler` is a read-only
memory-search handler, has no production transport mount, and cannot discover
or invoke LCNC Kanban runners. The markdown import route turns each bullet into
only a generated id and title. The board persists tags, dependencies, owner,
and priority, but its public projections omit useful metadata; it has no
description, acceptance evidence, customer, claim, deliverable, target date,
effort, impact, or confidence fields.

The shortest sound path is therefore **MCP exposure of the existing LCNC
Kanban experience, not an MCP-owned Kanban and not a store-level bypass**. MCP
should project the LCNC sheets as resources and expose the existing
`kanban.submit` and `kanban.move` runners as tools. Those runners already lower
into the same `BoardIntake`/`BoardStoreElement`, so LCNC users retain the asset
and every caller receives the same durable semantics. Until that projection
and a richer LCNC task envelope exist, the marketability work can be recorded
here but cannot exist faithfully as live cards.

## Corpus reconciliation

The corpus currently tells several true stories at different ages. This table
sets their authority and prevents another board model from being inferred from
old prose.

| Corpus surface | What remains authoritative | What is stale or narrower than the live system |
|---|---|---|
| `README.md` | Kernel algebra, Job Nexus, CCEK discipline, one-process quickstart intent | Still names legacy `ForgeBoardFSM` beside the production board and describes several UI projections without clearly naming the one store |
| `docs/oroboros-service-spec.md` | Contract decomposition for the Oroboros service | Predates the 2026-08-23 Couch wiring addendum; live-state tables must be read with the later gap analysis |
| `docs/oroboros-gap-analysis-2026-08-23.md` | Refined embodiment and sales-laptop target; same-day addendum records Couch mount, hoist, replication, CAS collapse, and build attachments | The opening verdict describes gaps closed in its own addendum; remaining gaps are durability, external homes, reconcile proof, AOT, lazy views, and vocabulary view |
| `docs/escape-velocity.md` | Independence story and CAS/Pijul/Git inventory | A strategic page, not an onboarding or buyer-proof plan |
| `docs/guide-kanban-board.md` | Current LCNC Kanban runners/sheets, HTTP routes, command lowering, WAL store, and guards | No MCP projection; walkthrough remains unverified; old field description said `column` where the live projection emits `status` |
| `doc/taste.md` Kanban section | Historical reasoning and task provenance | July task list predates `BoardStoreElement` consolidation and must not be used as current board ownership |
| `JULES_TASK_TREES.md` / `PACKAGE_JOBS.md` J12 | One top surface and no second mutable model | References `ForgeBoardFSM` as the source; live source deprecates it in favor of `BoardStoreElement` |
| `design/fs-memory-jules/prong-5-mcp-server-element.md` | Read-only citation-search intent and proposed MCP route concept | Design prong only; no `McpServerElement`, production mount, or Kanban tools exist |
| `doc/todo.md` | Current flywheel intake: five unchecked tasks at audit time | It is live execution input, not a place to stage an unapproved marketing backlog |
| `kanban-evolution.yaml` | Historical 80/20 scheduling intent | Names task ids and flat-file sources that do not match the current `doc/todo.md`; it is not a reliable snapshot of live work |
| `doc/INDEX.md` | Historical map of the July consolidation | Counts, session metrics, and active-task categories are historical, not current status |

### Canonical asset and state ownership after reconciliation

```text
OroborosDaemon
  -> KanbanModule (composition and route mount)
      -> LcncKanbanExperience (LCNC user asset: runners + live sheets)
          -> BoardStoreElement (one durable card-state owner)
              -> InvokeLowering -> JobReducer
              -> CAS raw payload + .kanban WAL
              -> BoardCursor / JobKanbanProjection / causal graph / receipts
```

LCNC owns the composition, vocabulary, gestures, runners, and user-facing sheet
experience. `BoardStoreElement` owns durability and mutation ordering. Those
are complementary responsibilities; naming the store as state owner must never
be used to strip Kanban out of LCNC.

Two similarly named types are not alternative owners:

- `kanban/ForgeBoardFSM.kt` is deprecated legacy visual state.
- `userspace/reactor/KanbanFSM.kt` is a compact telemetry reducer for mux,
  taxonomy, cycle, patch, dispatch, and slash-command events. It is not card
  CRUD and must not become the MCP board.

## Repository marketability audit

This is an audit of what the repository can substantiate today, not a claim
about market size or competitors. External interviews and alternative-product
research are tasks, not invented findings.

| Dimension | State | Repository evidence | Deficiency to close |
|---|---|---|---|
| Product spine | Amber | Oroboros absorbs worktree/git/build deltas into CAS/Couch, exposes LCNC Kanban and VM surfaces, and has a live replication wire | The first screen says JSON scanner/database, real-mess corpus engine, Forge workspace, embodiment platform, and escape-velocity host without choosing one buyer outcome |
| LCNC Kanban asset | Green architecture; Amber packaging *(was Red — updated 2026-08-30)* | `LcncKanbanExperience` composes operational sheets and existing submit/move runners over the durable store; `/api/mcp` projects both to any MCP client, and `guide-mcp-kanban.md` carries the quickstart and a restart-proof walkthrough | Still no named LCNC-user outcome or evidenced reason to adopt it over an ordinary board — the remaining gap is a claim, not a surface |
| Beachhead user | Red | README asks people with large messy corpora to try it | No named ideal user, urgent job, disqualifier, or buying trigger |
| Demonstrable outcome | Amber/Green *(updated 2026-08-30)* | One process exposes `/`, `/graal`, board, Couch, corpus, LCNC, and VM routes; `scripts/demo-mcp-kanban.sh` is a frozen 15-check proof — scratch home, port 8899, MCP write → board parity → CAS receipt → stale-revision refusal → WIP limit → restart replay — exiting non-zero when a claim stops holding | The frozen proof covers the LCNC Kanban/MCP workflow, not the corpus or VM surfaces; clean-machine timing (MKT-005) is still unmeasured |
| Claim evidence | Amber *(narrowed 2026-08-30)* | Guides use live/stub/unverified markers; the Couch gap addendum records a two-node pull; `scripts/demo-mcp-kanban.sh --evidence proof.json` emits a claim-to-command-to-artifact bundle (15 claims with expected/observed/verdict, git rev, dirty flag, JDK, platform, timings) | The bundle covers the LCNC Kanban/MCP claims only. Corpus, VM, Couch and replication claims still have no captured artifact, and the README's high-level claims are unreconciled |
| Onboarding | Amber *(was Amber/Red — updated 2026-08-30)* | Quickstart and daemon launch guide exist; `scripts/demo-mcp-kanban.sh` self-times a trial — **22s to first value** (boot → card on the board), **43s** total including restart-and-replay, 428M of build artifacts, 3.9M of forge state for the run, on JDK 25.0.4.1 | Measured on ONE machine with warm Gradle/build caches. A true clean-machine number (cold clone, cold Gradle, first compile) and a second machine are still unmeasured — MKT-005 asks for both |
| Reliability | Amber | Board WAL replay, CAS payloads, single writer, idempotency, revisions, and guards are strong | Store/replication durability is uneven; live demo recovery and two-node acceptance evidence are not a release gate |
| Trust boundary | Red for remote use; Amber for local demo | Gap analysis explicitly says Couch 1.6 shape is an embodiment platform, not a secure database; NUID capability substrate exists | No concise deployment boundary, threat model, authenticated remote-board policy, or MCP write authorization contract |
| API/product integration | Amber *(was Amber/Red — updated 2026-08-30)* | HTTP board routes are live, LCNC routes are mounted, and an MCP contract exists at `/api/mcp` with a route-manifest parity gate covering its paths | Forge OpenAPI still omits module/import/LCNC routes and documents `/api/invoke` as 200 while the module returns 202; MCP tool/resource schemas have no parity gate |
| Work representation | Red for audit work | Cards have title, column, revision, sequence, priority, order, dependencies, tags, owner | Public reads omit dependencies/tags; no description, evidence, acceptance criteria, customer, deliverable, scoring, dates, or artifact links |
| Packaging | Red | AGPLv3 is stated; wrapper and launchers exist | No release artifact, supported platform matrix, version promise, upgrade/backup contract, or support boundary |
| Positioning and alternatives | Red | Architecture differentiators are documented | No evidence-based comparison against the buyer's current alternative, including “keep using files/scripts/hosted tools” |
| Pricing and value | Red | No supported claim found in the inspected corpus | No value metric, price hypothesis, cost-to-serve estimate, or willingness-to-pay interviews |
| Validation | Red | “Run it in anger” feedback links exist | No design-partner list, interview record, trial funnel, activation event, or pilot exit criteria |

### Marketability verdict

The repository is **demonstrable substrate, not yet a marketable package**.
LCNC Kanban is a credible asset with a packaging and proof deficit; it should
not be recast as a standalone board product or removed from LCNC. The
best-supported marketability hypothesis is:

> LCNC Kanban turns plans and executable LCNC operations into a durable,
> inspectable board and concentric sheets, while Oroboros preserves the task
> record, receipts, and local replicas behind that experience.

That sentence is a hypothesis to test, not approved homepage copy. It preserves
the distinctive evidence in the repository without requiring every substrate
to be sale-ready at once.

### Marketability is a camera bookmark, not an architectural exclusion

The marketability backlog chooses a comprehensible entry point; it does not
narrow the ultimate system. The long-range design remains the unified grand
blackboard envisioned by the project: the actual CCEK reactor is the city,
LCNC supplies its facets and compositions, and realtime semantic zoom reveals
the full channelized process topology. Kanban is the LCNC work facet. Graal RTS
is the runtime/storage terrain facet. They must become dimensionally coherent
views through the same camera, identities, containment, causality, time, flow,
pressure, and scale coordinates.

Oroboros supplies the material continuity underneath those views: file,
class/resource, git, agent/environment, and runtime deltas are absorbed into
CAS/Couch identities and ordered changes that can be replicated. A marketable
demo should therefore prove not merely that a card moves, but that the source
or runtime delta, LCNC operation, card/receipt, blackboard fact, and replica are
one traceable chain.

Accordingly:

- a beachhead claim is a saved camera position into the grand blackboard, not
  permission to delete or fork the rest of the city;
- market-specific task fields are an optional facet, not a restriction on LCNC;
- MCP is another access lens into LCNC and the blackboard, not a state owner;
- a marketable Kanban demonstration should expose its connection to live CCEK
  flow and receipts rather than presenting an ordinary isolated task board.

The north-star contract and fortification sequence are canonical in
[`doc/rewire.md`](../doc/rewire.md#north-star--the-ccek-city-and-the-unified-grand-blackboard).

## Proposed marketability audit backlog

These are proposed records, not unchecked markdown checkboxes, because
`doc/todo.md` is executable flywheel intake. Priorities use `0` as the highest.
“Done evidence” is deliberately observable so an agent cannot close a card by
producing more prose.

| Id | Priority | Proposed task | Done evidence | Depends on |
|---|---:|---|---|---|
| MKT-001 | 0 | Choose one marketable outcome for the LCNC Kanban asset without recasting it as a standalone board | Approved claim, named excluded claims, LCNC-user context, and one live route or artifact proving every phrase | — |
| MKT-002 | 0 | Define the first ideal user, urgent job, buying trigger, and disqualifiers | One-page profile with five interview candidates who match it | MKT-001 |
| MKT-003 | 0 | Freeze the ten-minute LCNC Kanban demo: plan or LCNC operation → active sheets → guarded move → restart | Script run on a clean machine; timestamped outputs, screenshots, and restart proof stored as artifacts | MKT-001 |
| MKT-004 | 0 | Build the claim-evidence matrix | Each marketed claim maps to a command, expected result, source path, and captured artifact; unsupported claims are removed | MKT-001, MKT-003 |
| MKT-005 | 1 | Measure clean-machine trial friction | Recorded prerequisites, install time, first-value time, download/build size, failure modes, and recovery steps on two machines | MKT-003 |
| MKT-006 | 1 | State the deployment and trust boundary | Published local-only/default exposure, data locations, auth status, backup/restore behavior, and explicit non-claims | MKT-004 |
| MKT-007 | 1 | Reconcile user-facing route documentation | One generated or parity-tested route inventory covering core, module, LCNC, Couch, VM, and MCP status with correct response codes | MKT-004 |
| MKT-008 | 1 | Consolidate the public narrative | README and guide entry points lead through problem, proof, trial, limits, and deeper architecture without contradictory ownership/status claims | MKT-001, MKT-004, MKT-006 |
| MKT-009 | 1 | Define the release package | Versioned artifact, supported platforms, state-directory contract, upgrade/rollback, license, and support policy | MKT-005, MKT-006 |
| MKT-010 | 2 | Audit the buyer's current alternatives | Evidence table for local scripts/files, hosted knowledge tools, project boards, and agent hosts; interviews validate switching reasons | MKT-002 |
| MKT-011 | 2 | Test value and pricing hypotheses | Three value metrics and price bands tested in at least five interviews; objections and willingness-to-pay recorded | MKT-002, MKT-010 |
| MKT-012 | 2 | Recruit design partners | At least three qualified operators agree to supply a real corpus and trial the frozen workflow | MKT-002, MKT-003 |
| MKT-013 | 2 | Define activation and pilot exit gates | Machine-readable gates for install, corpus ingest, first card/receipt, restart recovery, and replica proof, with pass/fail thresholds | MKT-003, MKT-005 |
| MKT-014 | 2 | Run and summarize pilots | Per-pilot evidence bundle plus consolidated friction, failure, retention-intent, and next-decision report | MKT-012, MKT-013 |
| MKT-015 | 3 | Decide launch, narrow, or stop | Written decision cites pilot evidence and names the next funded scope; no architecture work substitutes for the decision | MKT-014 |

No “narrow” decision may create parallel operational truth or remove Kanban,
Graal, or another district from the grand-blackboard ontology. It can narrow
only the first marketed workflow and the camera position used to demonstrate
it.

## Why those tasks cannot live faithfully in the current board

They can be imported as titles, but that is not the same as carrying the
audit. The loss points are structural:

1. **No mounted MCP transport.** `McpServerHandler` has no production caller,
   `/mcp` route, stdio bridge, or daemon lifecycle owner.
2. **Wrong MCP domain.** The handler exposes read-only memory search. It cannot
   discover LCNC contracts, invoke `LcncKanbanExperience` runners, or project
   its active sheets, cards, revisions, columns, and receipts.
3. **Import destroys the record.** `/api/board/import` extracts bullet text,
   generates `card-*` ids, and submits only id/key/title. Dependencies,
   priority, owner, evidence, completion criteria, and stable task ids are lost.
4. **Card envelope is too thin.** The store's `CardRow` cannot represent the
   audit fields needed to judge completion. Raw maps remain in CAS, but fields
   outside the projection are inaccessible as ordinary board state.
5. **Read projection is thinner still.** `/api/board` omits persisted tags and
   dependencies and offers no single-card read, filter, pagination, or lookup
   by tag/owner/dependency.
6. **No explicit transition policy.** `BoardCol` supplies seven canonical
   states, WIP, and ordering, but `move` can target any recognized state. Job
   lifecycle commands and board moves are related through projection rather
   than one documented transition table.
7. **Three “FSM” narratives invite a fork.** The production store, deprecated
   UI FSM, and telemetry reducer are named close enough that a new adapter could
   attach to the wrong one.
8. **No MCP write authorization boundary.** The read-only handler avoids this
   question. Board writes require a declared local-only or NUID capability
   policy before remote exposure.
9. **No current MCP protocol envelope.** The handler switches on four bare
   method strings with string parameters. It does not implement the current
   stateless per-request metadata/version contract, HTTP method/name headers,
   optional `server/discover`, capability/tool schemas, cache hints, or protocol
   errors. Any compatibility mode for pre-2026 clients is also undeclared.
10. **Contract drift is already visible.** Forge OpenAPI does not cover current
    Kanban module and LCNC routes, and its `/api/invoke` response code disagrees
    with the module. Adding MCP without a parity gate would create another
    untrusted description.
11. **No atomic structured import.** `/api/invoke` accepts a batch but applies
    commands one by one and reports partial results. That is useful, but a task
    manifest needs an explicit partial/atomic policy and a replayable receipt.
12. **No market-work LCNC view.** Existing operational sheets group by status
    and priority, but there is no LCNC projection for claim, customer,
    evidence, confidence, or gate status. The asset cannot answer “what
    prevents a market decision?” without reconstructing documents manually.

## Target: simple MCP exposure of the LCNC Kanban FSM inside Oroboros

“Simple” should mean a small wire contract over the existing LCNC asset and
durable system—not simplified durability, a new state owner, or removal of
Kanban from the LCNC palette.

### Ownership rule

```text
MCP request
  -> Oroboros-mounted MCP projection of LCNC
  -> LcncKanbanExperience runner or active-sheet projection
  -> the same BoardIntake / BoardStoreElement
  -> the same WAL, CAS, reducer, guards, projections, and receipts
```

The adapter may describe and expose the FSM, but LCNC continues to own its
composition and `BoardStoreElement` continues to own its durable state. MCP's
optional Tasks extension concerns asynchronous tool execution; it is not a
replacement for the LCNC Kanban card/sheet contract and is unnecessary for the
first synchronous submit/move cut.

### Minimum MCP surface

Resources provide reads:

| Resource | Meaning |
|---|---|
| `oroboros://lcnc/kanban/schema` | LCNC contract plus column vocabulary, WIP limits, field schema, supported runners, and transition policy |
| `oroboros://lcnc/kanban/sheets` | The current `kanban.activeSheets` family, including board, status, priority, and orchestration projections |
| `oroboros://lcnc/kanban/cards/{jobId}` | Full projected card, dependencies, metadata, latest revision/sequence, and evidence links |
| `oroboros://lcnc/kanban/receipts/{sequence}` | Committed or rejected command receipt with causal/CAS reference |

Only two mutation tools are needed initially:

| Tool | Required behavior |
|---|---|
| `kanban.submit` | Expose the existing LCNC runner; validate a structured task, require stable `jobId` and `idempotencyKey`, and return its verdict, sheets, revision, sequence, and receipt/CID |
| `kanban.move` | Expose the existing LCNC runner; require `jobId`, `toColumn`, `expectedRevision`, and `idempotencyKey`, preserving the same guards and receipt/sheet result |

That is an initial MCP exposure limit, not a reduction of the LCNC palette.
LCNC users retain every current and future Kanban runner, sheet, gesture, and
composition path whether or not MCP exposes it.

List/get do not need duplicate tools when MCP resources implement them cleanly.
Batch import can follow only after single-card semantics and a partial/atomic
contract are proven.

### Minimum task envelope

| Field | Current state | Minimum target |
|---|---|---|
| `jobId`, `title`, `priority`, `owner`, `tags`, `dependencies` | Persisted, though not all are publicly projected | Preserve and expose all |
| `description` | Legacy UI type has one; production row does not | Required for audit-grade tasks |
| `acceptanceCriteria` | Missing | Required, structured list |
| `doneEvidence` / `artifactUris` | Missing | Required before transition to done |
| `workstream` / `category` | Tags only | Stable field or documented tag convention |
| `customer` / `persona` | Missing | Optional generally; required for market validation work |
| `impact`, `confidence`, `effort` | Only integer priority exists | Optional scored fields with declared scale |
| `sourceUri` / `provenance` | Raw CAS command and receipts exist indirectly | Project an explicit source and receipt link |
| `targetDate` | Missing | Optional ISO date |
| `revision`, `sequence`, `lastMoveMs` | Live | Preserve as server-authored concurrency/audit fields |

The market-specific fields belong in an optional LCNC/Confix facet and its
derived sheets. They must not bloat the base Kanban card, become prerequisites
for ordinary LCNC users, or narrow what LCNC can compose.

### FSM policy decision

The seven canonical states already exist:

```text
triage -> todo -> ready -> running -> done -> archived
                    \       |
                     -> blocked
```

That diagram is a proposed happy path, not current enforcement. Before MCP
writes ship, one task must decide and test whether moves are:

- open, matching today's recognized-column behavior; or
- policy-checked, with explicit reopen/unblock/archive transitions.

Whichever policy is chosen must be returned by the schema resource and applied
inside the same LCNC runner/lowering/store boundary for HTTP, LCNC, UI, and MCP
callers.

### Implementation backlog that unblocks the marketability cards

These are design tasks only and are not dispatched by this document.

| Id | Proposed task | Acceptance gate |
|---|---|---|
| KMFSM-001 | ✅ **Static gate done 2026-08-30.** Declare `LcncKanbanExperience` as the user-facing asset and `BoardStoreElement`/`BoardCol` as its durable state contract; classify legacy FSMs as adapters or telemetry | Architecture test or static gate proves MCP reaches Kanban through LCNC and no write reaches legacy `ForgeBoardFSM` or telemetry `KanbanFSM` |
| KMFSM-002 | Decide and publish the legal transition table as part of the LCNC Kanban contract | Every state/verb pair has an allow/reject fixture shared by LCNC, HTTP, UI, and MCP |
| KMFSM-003 | Define an optional marketability Confix facet and LCNC sheet projection without changing the base Kanban contract | Round-trip preserves the marketability manifest, including acceptance and evidence fields, across restart and active-sheet reprojection; ordinary LCNC cards remain valid unchanged |
| KMFSM-004 | ✅ **Done 2026-08-30.** Expose LCNC Kanban sheets, cards, and receipts as MCP resources | Resource reads return the active-sheet family, watermark, revision, dependencies, metadata, and provenance without scanning raw WAL files |
| KMFSM-005 | ✅ **Done 2026-08-30.** Expose existing `kanban.submit` and `kanban.move` LCNC runners as MCP tools | Tool calls delegate to the LCNC registry; duplicate keys and stale revisions preserve the existing rejection and sheet-result semantics |
| KMFSM-006 | ✅ **Done 2026-08-30.** Mount MCP in the Oroboros lifecycle | One daemon serves the current stateless MCP version, supports capability discovery and required request metadata/header routing, and drains transport without a second server or nested blocking; any older handshake compatibility is explicit |
| KMFSM-007 | Define the write security boundary | Local-only default is enforced or a NUID board-write capability is required and tested; read/write capabilities are separate |
| KMFSM-008 | Add LCNC query and manifest semantics | Filtered sheet resources and a documented partial/atomic batch policy can import all MKT records without field loss |
| KMFSM-009 | ✅ **Done 2026-08-30.** Establish route/protocol parity | OpenAPI/MCP schemas and live route/tool registries fail tests when status codes, paths, fields, or capabilities drift |
| KMFSM-010 | ✅ **Done 2026-08-30.** Prove restart and concurrent-client behavior | Integration test covers submit, stale transition, duplicate retry, restart replay, and two MCP clients racing one revision |

## Delivery record — 2026-08-30

KMFSM-004, KMFSM-005, and KMFSM-006 shipped. The audit's ownership rule is now
executable rather than aspirational, and the user-facing surface it enables is
documented in [`guide-mcp-kanban.md`](guide-mcp-kanban.md).

| What | Where |
|---|---|
| The projection (JSON-RPC, tools, resources, schema) | `src/commonMain/kotlin/borg/trikeshed/mcp/LcncKanbanMcp.kt` |
| Read port + receipt index | `src/commonMain/kotlin/borg/trikeshed/mcp/BoardKanbanReadPort.kt` |
| Daemon mount at `GET`/`POST /api/mcp` | `src/jvmMain/kotlin/borg/trikeshed/kanban/module/KanbanModule.kt` |
| Manifest entries (drift gate) | `src/jvmMain/kotlin/borg/trikeshed/lcnc/RouteManifest.kt` |
| 19 behavioural cases against a live WAL board | `src/jvmTest/kotlin/borg/trikeshed/mcp/LcncKanbanMcpTest.kt` |
| Static ownership gate (KMFSM-001) | `src/jvmTest/kotlin/borg/trikeshed/mcp/McpKanbanOwnershipTest.kt` |
| Concurrent-client races (KMFSM-010) | `src/jvmTest/kotlin/borg/trikeshed/mcp/McpKanbanRaceTest.kt` |
| MCP surface + guide parity (KMFSM-009) | `src/jvmTest/kotlin/borg/trikeshed/mcp/McpSurfaceParityTest.kt` |
| OpenAPI status-code parity (KMFSM-009) | `src/jvmTest/kotlin/borg/trikeshed/reactor/openapi/ForgeHostSpecStatusParityTest.kt` |
| Frozen runnable proof (MKT-003, partial) | `scripts/demo-mcp-kanban.sh` |
| Captured evidence bundle (MKT-004, partial) | `scripts/demo-mcp-kanban.sh --evidence <path>` |
| Mount + write + restart + read-back over real HTTP | `KanbanModuleHttpTest.mcpIsMountedOnTheDaemonAndItsBoardSurvivesRestart` |

Two findings from the body of this audit were closed as a side effect:

- **The write side of "read projection is thinner still."** `/api/board` had
  already been enriched with owner/dependencies/tags (`KanbanModule.boardJson`'s
  `enrich`), so the *read* gap named in the table above is closed. The **write**
  was still losing them: the LCNC `kanban.submit` runner dropped tags,
  dependencies, and owner even though `BoardStoreElement.advanceRow` has always
  persisted all three — so an LCNC-submitted card could never carry a
  dependency, and the cycle guard had nothing to guard. The runner now forwards
  them, which fixes the panels canvas and `/api/lcnc/run` at the same time.
  Repaired inside LCNC rather than routed around, per the ownership rule.
  (The MCP card resource adds single-card addressing and a receipt link, which
  `/api/board` does not offer — not a different field set.)
- **KMFSM-002's policy decision** is answered by publishing the policy the store
  *enforces* (`open`, plus four named guards) in the schema resource, rather
  than a happy-path diagram nothing checks.

### Deliberate deviations from the sketch above

- **Submit does not require a client-minted `jobId`/`idempotencyKey`.** Both
  have deterministic defaults — the id is the title's content hash, the key is
  `submit#<jobId>` — so stable ids and retry-safety are preserved without
  making a client invent them. Every accepted write still *returns* both.
- **Tool results do not echo the full sheet family.** They carry the verdict,
  the compact `boardView`, and a `sheetsResource` pointer; the whole family is a
  resource. A client pays for the board once rather than on every mutation.

### Still open

KMFSM-002 as a *narrower* policy if one is ever wanted, KMFSM-003
(marketability facet), KMFSM-007 (**write authorization — the daemon binds
`0.0.0.0` with no auth on `/api/*`; MCP adds no new exposure class, since
`/api/invoke` was already an unauthenticated write, but it does add an eager
caller**), and KMFSM-008 (filtered queries, atomic import).

MKT-001 through MKT-015 remain untouched and inert: they need a human's market
decisions and interviews, not code.

## Gate for importing the proposed marketability backlog

Do not import MKT-001 through MKT-015 merely because a route accepts bullet
text. Import is faithful only when all of these are true:

1. Stable ids survive unchanged.
2. Dependencies survive and are returned on read.
3. Acceptance criteria and done evidence survive a restart.
4. Every mutation returns idempotency key, revision, sequence, and a durable
   receipt reference.
5. A rejected or partially applied batch is explicit.
6. The client can query the critical path through LCNC Kanban resources without
   reading this markdown.
7. The board's MCP writer is local-only or capability-authorized.

Until then, this page is the consolidated audit and proposed task manifest;
`doc/todo.md` remains the only live flywheel intake.

## Inspected sources

- `README.md`, `PRELOAD.md`, `doc/concepts.md`, `doc/INDEX.md`, `doc/todo.md`,
  `doc/taste.md`, `doc/rewire.md`, `kanban-evolution.yaml`
- `docs/oroboros-service-spec.md`,
  `docs/oroboros-gap-analysis-2026-08-23.md`, `docs/escape-velocity.md`,
  `docs/guide-kanban-board.md`, `docs/forge-substrate-plan.md`,
  `docs/forge-ui-gap-analysis.md`, `docs/guides-index.md`
- `JULES_TASK_TREES.md`, `PACKAGE_JOBS.md`,
  `design/fs-memory-jules/prong-5-mcp-server-element.md`
- Live board/MCP/module source and relevant route specifications under
  `src/commonMain` and `src/jvmMain`, inspected read-only on 2026-08-29
- [MCP 2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28)
  and official release notes, used for the stateless request, discovery,
  resource, tool, cache, and authorization contract
