# Concentric Scoped LCNC — the CCEK Mapping — Imperative Programming as LCNC

Status: SPEC + implementation (2026-08-27). Every name in this document
exists in the tree; nothing here is aspirational vocabulary. The razor applies:
one author per vocabulary, browser renders what it fetched, execution lives in
the daemon. ALL DATA IS CONFIX — a program, a receipt, a return map are Confix
documents, never toString-mangled or double-encoded JSON. The kmart browser
editor (`web/panels.html`, `/panels`, the `/api/panels/*` family, the `panels/`
attachment occupancy) was ROOTED OUT the same day this spec landed; the daemon
is the one executor. "Treesheet" means the PWA's TreeSheets-idiom sheets
renderer (`web/script.js`) — there is no second treesheet.

## 0. The claim

LCNC graphs in this tree are not a diagramming toy: an `LcncProgram` is an
imperative program in single-assignment normal form, and its scopes nest
concentrically the way stack frames do. CCEK's channelization —
**assembly → graph → job → block** — is the execution reading of that same
structure. This spec pins the mapping so each layer has exactly one meaning
and one owner, then the implementation sections state what enforces it.

## 1. The imperative reading (what each LCNC element IS)

| Imperative construct | LCNC element | Tree anchor |
|---|---|---|
| procedure | `LcncProgram` (a stored Confix document: `LcncPresets` offered doc, or `lcnc/<name>` CAS attachment) | `LcncProgramConfix` |
| statement | node (`LcncNode`) | `LcncContracts.all()` — one vocabulary author |
| value dependence (SSA def→use) | wire `{from:[node,port], to:[node,port]}` | `LcncWire` |
| statement order | topological sweep over wires | `LcncProgram.topo()` / `LcncRunner.runAll` |
| block / call | **scope node** — a node whose `subprogram` names another program | `LcncNode.subprogram` |
| formal parameter | **`scope.in`** node inside the child program (param `name`) | contract, this spec §4 |
| return value | **`scope.out`** node inside the child program (param `name`) | contract, this spec §4 |
| local variable | any inner node output that is NOT gathered by a `scope.out` — invisible to the caller | §3 encapsulation rule |
| iteration (bounded) | `KanbanEdgeMode.LOOP` with `maxIterations` | `KanbanGraph` (tribunal preset: argue ⇄ rebut ≤ 3) |
| selection | conditional edge (`KanbanEdge.condition`) / Content Based Router (EIP family) | orchestration sheet `conditions` |
| parallel block + join | `KanbanEdgeMode.FANOUT` / `JOIN` (`requiredBranches`) | kanban graph edges |
| exception / abort | `KanbanEdgeMode.ABORT` → structured-concurrency cancellation | `runAllIn` — cancelling the assembly's `CoroutineScope` cancels the walk |
| event / interrupt | source nodes (`timer`, `graal.events`, `vm.events`) and inbound webhooks (`/hook/<program>/<node>/<port>`) | `LcncContracts` sources, `CouchWebhookBindings.lcncHookIntake` |

Dataflow in single-assignment form IS imperative straight-line code; what makes
the language *structured* is the scope node. That is the piece this spec lands.

## 2. Concentric scoping: scope = frame = cid prefix

One structure, four coincident views. **Invariant: all four agree on the path.**

1. **Lexical nesting** — `LcncNode.subprogram` references a child program;
   children can themselves hold scope nodes, to `maxScopeDepth`.
2. **Dynamic nesting** — `LcncRunner.runAll` recursion: scope entry binds
   arguments, runs the child, gathers returns, exits (§3).
3. **Identity nesting** — `FrameIdChain` (`ProgramNavigator.frameChain`):
   `cid_child = ContentId(parent.cid.hex ++ scopeName)`. The chain is a pure
   function of the scope path, so the SAME path always mints the SAME cid —
   scope chain = prompt-cache prefix = task-address prefix = network route
   (the address grammar's one structure, four routers; wise-micali §Address
   grammar).
4. **Surface nesting** — `ProgramNavigator`'s dive stack and breadcrumb:
   diving into a scope walks the SAME chain the executor walks. Any future
   surface and the interpreter climb one ladder.

Scope entry pushes a frame; scope exit pops it. Frames are the obstack arena:
a warm base (the enclosing scope's outputs), a specialization envelope pushed
on top (the arguments), popped and reused serially.

## 3. Execution semantics (normative)

`LcncRunner.runAll(program)`, executing in the daemon (razor: the browser
never executes the graph; the one execution surface is `/api/lcnc/run`).

For a node `n` with `n.subprogram = S`:

1. **Load** `S` via `subprogramLoader` (production wiring:
   `ModuleContext.programLoader` — the offered `LcncPresets`, with
   `lcnc/<name>` attachments via `oroborosProgramLoader` when a store is
   composed in; tests: an in-memory map). A missing program throws
   `LcncUnknownNodeType(S)` — a data error, never a silent leaf.
2. **Depth**: entering a scope while already `maxScopeDepth` (16) deep throws
   `LcncScopeDepthExceeded(path)` — a reference cycle is a data error, not a
   stack overflow.
3. **Bind arguments**: for every `scope.in` node `p` in `S` with param
   `name = k`, seed `p`'s output port `value` with the caller's gathered input
   `k` on the scope node. `scope.in` never runs a runner; it IS the binding.
4. **Run** `S`'s nodes in topo order under the same cooperative-cancellation
   rules (`ensureActive()` between nodes; ABORT = scope cancellation).
5. **Gather returns**: the scope node's output map is
   `{ k → v }` for every `scope.out` node with param `name = k` whose input
   port `value` received `v`. **Nothing else crosses the boundary** — inner
   node outputs are locals; this encapsulation IS the "block" of
   assembly → graph → job → block.
6. **Frame**: entry appends `scopeName` to the walk's `FrameIdChain`; exit
   pops. The executor's chain equals `ProgramNavigator`'s chain for the same
   dive path (test-pinned).

A scope node with no `scope.out` children returns `{}` — a procedure, not a
function. A scope node's required inputs are the child's non-optional
`scope.in` names (`name` params not ending `?`).

## 4. Vocabulary (contracts — one author: `LcncContracts`)

| type | inputs | outputs | params | notes |
|---|---|---|---|---|
| `scope` | declared by child (`scope.in` names) — wire model: single optional `args?` map + per-name wires | declared by child (`scope.out` names) — surfaced as `returns` map + pass-through | `program` (subprogram name; mirrors `LcncNode.subprogram`) | the call |
| `scope.in` | — | `value` | `name` (param name), `default?` | legal only inside a subprogram |
| `scope.out` | `value` | — | `name` (return name) | legal only inside a subprogram |

Because the browser's wire UI needs declared ports, the `scope` contract
carries the generic pair `args?`(json in) / `returns`(json out): a caller may
wire one composed map in and take the composed returns map out. Per-name
wiring is the daemon's richer path (webhook dispatch, program-to-program).
Both spellings bind through §3.3 identically: `args?` merges under the
per-name inputs (per-name wins).

## 5. The CCEK mapping (channelization: assembly → graph → job → block)

| CCEK layer | LCNC realization | Enforced by |
|---|---|---|
| **assembly** | a deployed scope: `runAllIn(scope, program)` — the walk's `Deferred` is bound to the assembly's `Job`; cancelling the assembly cancels every nested scope at the next node boundary / suspension point | `LcncRunner.runAllIn`, `ensureActive()` |
| **graph** | `LcncProgram` — the Confix document (CAS-addressed, replicated, one author) | `LcncProgramConfix`, `lcnc/<name>` |
| **job** | one node execution: `LcncNodeRunner.run(node, inputs)` — "this signature IS a CCEK agent signature, hostable by ArticulatedNode's bounded fan-out" (W1.4) | composed `ctx.lcncRunners` registry |
| **block** | the encapsulated envelope crossing a scope boundary: bound arguments in, gathered returns out; serialized, it is the frame delta riding the cid chain | §3.3/§3.5 |

Corollaries:
- The webhook intake (`CouchWebhookBindings.lcncHookIntake`) already addresses
  `program/node/port` — that address is a scope-chain suffix; §2.3 makes it a
  cid-stable one.
- The kanban orchestration graph is the *scheduler's* view of the same
  program: lanes = skill stations, a card = a Claim Check reference to a
  staged context, lane transition = an edge firing (Routing Slip hop).
- `QuotaLegion` standings (`/api/mux/standings`) are the legion's admission
  face for assemblies that call `mux.chat` jobs: coordinated legions =
  many assemblies drawing keys/quota from one metered roster.

## 6. Surfaces

- **`POST /api/lcnc/run`** (KanbanModule): `{type, params?, inputs?}` runs ONE
  node (job) against the composed registry; `{program, inputs?}` runs a WHOLE
  stored program (procedure) via `LcncRunner` with subprogram recursion —
  imperative program execution as a service; `{name?, document, inputs?}` runs
  an inline ring from a posted document (not a stored program name) through
  the same `LcncProgramConfix` parse and `execute` path, a bad shape failing
  as a 400 rather than a silent flat sweep. Outputs: per-node map (program/
  document run) or the node's output map (job run).
- The browser editor was deleted 2026-08-27, then revived 2026-08-28
  (`46b073bda`) as the concentric canvas at `/panels`. It renders nothing it
  authors itself: vocabulary comes from `GET /api/lcnc/contracts`, lane
  assemblage from `GET /api/lcnc/concentric` (`ConcentricSurface.LANE_ASSEMBLAGE`),
  and save/load goes through `/api/panels/*` (CAS-addressed `panels/<name>`
  attachments, not localStorage) — never a hand-authored lane/type table.
  `RouteParityGate`'s "the revived editor stays honest" gate fails the build
  on any hand-authored table, elliptical child placement, or children-dropping
  serialize. Sheet projections outside the canvas still render through the
  PWA's TreeSheets-idiom sheets renderer (`script.js`), the one treesheet.
- **`GET /api/lcnc/contracts`** serves the scope vocabulary like every other
  contract; parity gates fail the build if the vocabulary forks. The palette
  is inspectable there — one author, no browser TYPES table, ever again.

## 7. Gates (tests pin the spec)

- arguments bind: caller input `k` reaches `scope.in name=k` consumers.
- returns gather: only `scope.out` names cross; inner locals do NOT.
- depth: a self-referencing scope throws `LcncScopeDepthExceeded`, listing the path.
- chain determinism: executor frame chain ≡ `ProgramNavigator` chain for the
  same path (bit-identical cids).
- presets gate (`LcncPresetsGateTest`): any preset using `scope*` types passes
  the one-vocabulary check.
- route: `/api/lcnc/run` `{program}` executes a stored two-level program and
  returns the outer `scope.out` values.

## 8. Non-goals (unchanged from wise-micali)

No second executor beside CCEK; no npm; no new deps for this step (Camel is
step 9, behind its one seam); the browser never executes the graph; SUMO/EIP
synthesis (step 12) sits on top of this, not inside it.
