# Forge Hinge Adjacency: Blackboard ↔ Confix Wiring

> Purpose: articulate the per-hinge type-level contract connecting existing kernel types
> (`BlackboardOverlay.kt`, `ConfixIndexK.kt`) to each of the 10 Forge hinges.
>
> This is the layer between the gap statement and implementation — the proof that each hinge
> can be expressed as PRELOAD algebra using types that already exist in the codebase.

The existing gap contract (`2026-06-12-forge-preload-gap-contract.md`) names the hinges and
the general lowering chain. This document makes it specific: for each hinge, what exact
`OverlayRole`, `Provenance`, `Evidence`, `DependencyHandle` values ride on each column, and
what exact `ConfixIndex` facade shape and `Series<Byte>` body content the hinge carries.

---

## Kernel types already in the repo

### BlackboardOverlay.kt — available now

```kotlin
OverlayRole         // OBSERVATION | DERIVED | AGGREGATE | HYPOTHESIS | GROUND_TRUTH | CONTROL | METADATA | PROVENANCE
Provenance          // source, timestamp, transformations: List<String>, creator: String?
Evidence            // confidence: Double, errorMargin, supportCount, notes
DependencyHandle    // CellRef | ColumnRef | ExternalCellRef | ExternalResource | Composite
CellOverlay<T>      // value + role + provenance + evidence + dependencies
ColumnOverlay       // name + defaultRole + provenance + evidence + constraints + description
BlackboardContext   // id + columnOverlays: Map<Int, ColumnOverlay> + provenance + tags
```

### ConfixIndexK.kt — available now

```kotlin
ConfixIndexK.Spans          → Series<Twin<Int>>    // byte offsets per token
ConfixIndexK.Tags           → Series<IOMemento>    // type discriminant per token
ConfixIndexK.Depths         → Series<Int>          // nesting depth per token
ConfixIndexK.DirectChildren → (Int) → Series<Int>  // parent → child indices
ConfixIndexK.TreeCursor     → Cursor               // recursive tree of RowVec
ConfixIndexK.KeyToChild     → (CharSequence) → Int? // key name → token index
```

### ConfixDoc — the object-body split

```kotlin
typealias ConfixDoc = Join<ConfixIndex, Series<Byte>>
// facade = ConfixIndex  (stable parsed/indexed shape)
// body   = Series<Byte> (swappable byte series)
```

---

## Per-hinge adjacency

### Hinge 1 — ForgeCorpusCursor

**What it is**: files / context chunks / external sources as cursor rows.

**RowVec columns**:

```text
0: id          String         METADATA
1: kind        String         METADATA     (file | chunk | url | generated)
2: path        String         METADATA
3: mime        String         METADATA
4: bodyRef     String         CONTROL      (content-addressed ref into ConfixStore)
5: hash        String         PROVENANCE
6: facets      Series<String> METADATA     (tags/labels)
```

**Blackboard wiring**:

| Column | OverlayRole | Provenance | Evidence | Dependencies |
|--------|------------|------------|----------|--------------|
| id | METADATA | source: "ingest" | — | — |
| kind | METADATA | — | — | — |
| bodyRef | CONTROL | source: "workspace-put" | — | — |
| hash | PROVENANCE | source: "sha256" | confidence: 1.0 | — |

**Cursor-level BlackboardContext**:

```kotlin
BlackboardContext(
  id = "forge-corpus",
  columnOverlays = mapOf(
    0 to ColumnOverlay("id", METADATA),
    1 to ColumnOverlay("kind", METADATA),
    4 to ColumnOverlay("bodyRef", CONTROL),
    5 to ColumnOverlay("hash", PROVENANCE),
  ),
  provenance = Provenance(source = "workspace-ingest"),
  tags = mapOf("cursor" to "corpus")
)
```

**Confix adjacency**:

- Each row's `bodyRef` points into `ForgeConfixStore`
- **facade** (`ConfixIndex`): parsed structure of the file
  - For Markdown: `Spans` = heading/paragraph/code-block spans, `Tags` = IOMemento token types, `Depths` = nesting
  - For JSON/YAML: `Spans` = object/array/string boundaries, `Tags` = JSON token types, `KeyToChild` = field lookup
  - For code: `Spans` = statement/block boundaries, `Tags` = syntax classes, `Depths` = brace depth
- **body** (`Series<Byte>`): raw file bytes
- Frontmatter in Markdown → `KeyToChild` facet on the facade for field selection without materialization
- `DirectChildren` → section/subsection navigation for chunk projection

**CellOverlay for a body cell**:

```kotlin
CellOverlay(
  value = bodyRef,
  role = CONTROL,
  provenance = Provenance(source = "workspace-put", creator = "forge-user"),
  dependencies = listOf(ExternalResource("file://$path"))
)
```

---

### Hinge 2 — ForgePromptCursor

**What it is**: prompt templates as cursor rows.

**RowVec columns**:

```text
0: id              String    METADATA
1: name            String    METADATA
2: version         Int       PROVENANCE
3: templateBodyRef String    CONTROL
4: paramSchemaRef  String?   METADATA
5: tags            Series<String> METADATA
```

**Blackboard wiring**:

| Column | OverlayRole | Provenance | Evidence | Dependencies |
|--------|------------|------------|----------|--------------|
| id | METADATA | — | — | — |
| version | PROVENANCE | source: "prompt-lifecycle" | — | ColumnRef(3) (ties version to body) |
| templateBodyRef | CONTROL | source: "prompt-edit" | — | — |
| paramSchemaRef | METADATA | — | — | ExternalResource if external schema |

**Confix adjacency**:

- **facade**: parsed template structure
  - `Spans` = literal text vs `{{parameter}}` interpolation boundaries
  - `Tags` = LITERAL | PARAM_REF | PARAM_DEFAULT
  - `KeyToChild` = parameter name → token index (fast lookup of `{{format}}`, `{{readings}}`)
  - `DirectChildren` = nested conditional blocks if template has branching
- **body**: raw template text as `Series<Byte>`
- The `paramSchemaRef` column can point to a separate ConfixDoc whose facade is the JSON Schema parsed structure

**Key insight**: ConfixIndex `KeyToChild` facet makes `prompt.parameters["readings"]` a path
selection, not a string scan. The parameter schema lives in the facade, not in a separate Map.

---

### Hinge 3 — ForgeWorkflowGraphCursor

**What it is**: workflow steps + edges as two cursor projections.

**Step cursor columns**:

```text
0: stepId      String    METADATA
1: kind        String    CONTROL     (llm | code | agent | transform | cascade | conditional | parallel)
2: bodyRef     String    CONTROL     (step config body in ConfixStore)
3: inputSchema String?   METADATA
4: outputSchema String?  METADATA
5: policyRef   String?   CONTROL
```

**Edge cursor columns**:

```text
0: fromStep     String   PROVENANCE
1: outputKey    String   METADATA
2: toStep       String   PROVENANCE
3: inputKey     String   METADATA
4: transformRef String?  DERIVED
```

**Blackboard wiring**:

| Cursor | Column | OverlayRole | Rationale |
|--------|--------|------------|-----------|
| Step | kind | CONTROL | step type is config, not data |
| Step | bodyRef | CONTROL | step parameters are config |
| Step | policyRef | CONTROL | tool/capability policy |
| Edge | fromStep | PROVENANCE | edge lineage — where data came from |
| Edge | toStep | PROVENANCE | edge lineage — where data goes |
| Edge | transformRef | DERIVED | transform between steps is computed |

**Confix adjacency**:

- Step `bodyRef` → ConfixDoc
  - For LLM steps: facade parses model/temperature/max_tokens; body is JSON config bytes
  - For agent steps: facade parses task/tools/allowedTools; body is task description + context
  - For code steps: facade parses language/timeout; body is source code as `Series<Byte>`
  - `TreeCursor` facet → nested conditional/parallel structure as recursive RowVec
- Edge `transformRef` → optional ConfixDoc with a single expression facade
- The full workflow graph is not one ConfixDoc but a *cursor of ConfixDoc references*.
  The graph structure lives in the edge cursor, not in a tree.

**Key insight**: Workflow graph topology is cursor algebra (step cursor + edge cursor = relational
join), not tree-shaped ConfixDoc. ConfixDoc is for the *body* of each step, not the graph itself.

---

### Hinge 4 — ForgeExecutionCursor

**What it is**: append-only event log per run.

**RowVec columns**:

```text
0: seq           Int       PROVENANCE
1: runId         String    METADATA
2: stepId        String    METADATA
3: phase         String    PROVENANCE   (started | streaming | tool_call | tool_result | completed | failed)
4: eventKind     String    PROVENANCE
5: bodyRef       String?   DERIVED      (event payload in ConfixStore)
6: artifactRef   String?   DERIVED
7: timestamp     Long      PROVENANCE
```

**Blackboard wiring**:

| Column | OverlayRole | Provenance | Evidence | Dependencies |
|--------|------------|------------|----------|--------------|
| seq | PROVENANCE | source: "execution-engine" | — | — |
| phase | PROVENANCE | — | — | CellRef(step row in WorkflowGraphCursor) |
| bodyRef | DERIVED | source: "step-output" | confidence varies by step | ExternalResource(tool output) |
| artifactRef | DERIVED | source: "step-artifact" | — | CellRef(artifact row in ArtifactCursor) |

**CellOverlay for a tool_call event**:

```kotlin
CellOverlay(
  value = bodyRef,
  role = DERIVED,
  provenance = Provenance(
    source = "agent:codex",
    transformations = listOf("tool:read", "step:3")
  ),
  evidence = Evidence(confidence = 0.85),
  dependencies = listOf(
    ExternalCellRef("forge-corpus", row = 0, column = 4), // source file bodyRef
    CellRef(row = 2, column = 0)                          // stepId in workflow cursor
  )
)
```

**Confix adjacency**:

- `bodyRef` → event-specific ConfixDoc
  - For LLM responses: facade parses completion/usage/tokens; body is response text
  - For tool calls: facade parses tool_name/args/result; body is tool I/O
  - For errors: facade parses error_type/message; body is stack trace
  - `Spans` → structured fields within the event payload
  - `Tags` → discriminated union tags (tool_call vs tool_result vs streaming_chunk)
- Append-only discipline: execution cursor rows are never mutated.
  Each row's `CellOverlay.role` stays at DERIVED or PROVENANCE forever.
  No HYPOTHESIS → GROUND_TRUTH transitions here (that happens on workspace rows, not events).

**Key insight**: The execution cursor is pure PROVENANCE/DERIVED — no OBSERVATION or CONTROL.
Inputs are OBSERVATION (in corpus), config is CONTROL (in workflow). The execution cursor only
records what happened, not what should happen.

---

### Hinge 5 — ForgeAgentElement

**What it is**: CCEK lifecycle element wrapping a coding agent.

This hinge is not a cursor. It is an `AsyncContextElement` that *produces* execution cursor rows.

**Blackboard wiring at the element level**:

```text
Lifecycle transitions:
  CREATED  → role: CONTROL     (config loaded)
  OPEN     → role: CONTROL     (session established)
  ACTIVE   → role: DERIVED     (emitting events)
  DRAINING → role: AGGREGATE   (collecting final outputs)
  CLOSED   → role: PROVENANCE  (run complete, audit locked)
```

**Event rows emitted during ACTIVE phase**:

Each `AgentEvent` becomes a row in `ForgeExecutionCursor` with:

```kotlin
CellOverlay(
  value = eventPayload,
  role = DERIVED,
  provenance = Provenance(
    source = "agent:$agentType",
    transformations = listOf("tool:$toolName")
  ),
  evidence = Evidence(confidence = 1.0), // tool outputs are factual, not inferred
  dependencies = toolInputDeps // ExternalResource or CellRef pointing to source corpus rows
)
```

**Confix adjacency**:

- Agent task description → ConfixDoc with `Spans` marking instruction boundaries
- Tool call args/result → each as ConfixDoc
  - `KeyToChild` facet enables `args["filePath"]` path selection
  - `TreeCursor` facet gives structured view of nested JSON args
- Agent's proposed patch → ConfixDoc with `Spans` marking diff hunks
  - Before state: `ExternalResource(uri="file://$path", selector="lines[$start..$end]")`
  - After state: body bytes of the patch
  - `CellOverlay.role = HYPOTHESIS` (agent proposed, not yet accepted)

**Key insight**: The agent element is the boundary where HYPOTHESIS enters the system.
Everything inside the agent is CONTROL/DERIVED. Everything the agent *outputs* to the
workspace starts as HYPOTHESIS and must be accepted (→ GROUND_TRUTH) or rejected.

---

### Hinge 6 — ForgeArtifactCursor

**What it is**: build/inference outputs as content-addressed blackboard entries.

**RowVec columns**:

```text
0: artifactId     String    METADATA
1: kind           String    METADATA     (report | patch | dataset | export)
2: bodyRef        String    DERIVED
3: inputSnapshot  String?   PROVENANCE
4: workflowHash   String?   PROVENANCE
5: promptHash     String?   PROVENANCE
6: agentRun       String?   PROVENANCE
7: outputHash     String    PROVENANCE
8: sharePolicy    String?   CONTROL
```

**Blackboard wiring**:

| Column | OverlayRole | Provenance | Evidence | Dependencies |
|--------|------------|------------|----------|--------------|
| artifactId | METADATA | — | — | — |
| bodyRef | DERIVED | source: "artifact-builder" | confidence from Evidence chain | Composite of all inputs |
| inputSnapshot | PROVENANCE | — | — | ExternalResource("snapshot://$id") |
| workflowHash | PROVENANCE | — | — | CellRef in WorkflowGraphCursor |
| promptHash | PROVENANCE | — | — | CellRef in PromptCursor |
| agentRun | PROVENANCE | — | — | ExternalCellRef("execution", row, 0) |
| outputHash | PROVENANCE | source: "sha256" | confidence: 1.0 | — |
| sharePolicy | CONTROL | — | — | — |

**Full CellOverlay for an artifact body cell**:

```kotlin
CellOverlay(
  value = bodyRef,
  role = DERIVED,
  provenance = Provenance(
    source = "artifact-builder",
    transformations = listOf(
      "workflow:$workflowId",
      "prompt:$promptId",
      "agent:$agentRunId",
      "step:$stepId"
    ),
    creator = "forge-pipeline"
  ),
  evidence = Evidence(
    confidence = 0.92, // inherited from HYPOTHESIS acceptance
    supportCount = 3,  // number of input sources
    notes = listOf("human-accepted")
  ),
  dependencies = listOf(
    Composite(listOf(
      ExternalResource("snapshot://$inputSnapshotId"),
      ExternalResource("workflow://$workflowId"),
      ExternalCellRef("forge-execution", agentRunSeq, 5), // event bodyRef
      CellRef(corpusRow, 4)                               // source file bodyRef
    ))
  )
)
```

**Confix adjacency**:

- Artifact body → ConfixDoc
  - For reports: facade parses sections/subsections; body is Markdown/HTML
  - For patches: facade parses diff hunks; body is unified diff bytes
  - For exports: facade parses manifest structure; body is ZIP/tar
  - `TreeCursor` facet → recursive section structure for TOC/outline generation
- Export manifest → separate ConfixDoc
  - `KeyToChild` → `"fileCount"`, `"totalSize"`, `"workflowId"` fields
  - `Spans` + `Tags` → structured JSON parse of manifest

**Key insight**: Artifact is where full dependency provenance crystallizes. Every other
hinge contributes PROVENANCE or dependency handles here. The artifact row's
`CellOverlay.dependencies` is the convergence point — a `Composite` handle linking back
to corpus rows, prompt rows, workflow rows, execution event rows, and agent runs.

---

### Hinge 7 — ForgeMutationJournal

**What it is**: durable command/mutation rows for workspace changes.

**RowVec columns**:

```text
0: seq       Int      PROVENANCE
1: actor     String   PROVENANCE
2: command   String   CONTROL      (put | delete | patch | accept | reject | snapshot)
3: target    String   METADATA     (rowId.column or collection key)
4: patchRef  String?  DERIVED      (ConfixStore ref for diff body)
5: beforeRef String?  PROVENANCE   (pre-mutation body ref)
6: afterRef  String?  DERIVED      (post-mutation body ref)
7: timestamp Long     PROVENANCE
8: policyRef String?  CONTROL
```

**Blackboard wiring**:

| Column | OverlayRole | Rationale |
|--------|------------|-----------|
| seq | PROVENANCE | ordering is audit trail |
| actor | PROVENANCE | who/what caused the mutation |
| command | CONTROL | mutation type is config |
| patchRef | DERIVED | the computed diff |
| beforeRef | PROVENANCE | pre-state is historical fact |
| afterRef | DERIVED | post-state is the change result |
| policyRef | CONTROL | authorization for this mutation |

**Confix adjacency**:

- `patchRef` → ConfixDoc where:
  - `Spans` = diff line boundaries (unified diff hunks)
  - `Tags` = ADD | REMOVE | CONTEXT
  - `Depths` = nesting level within the patch
  - `KeyToChild` = hunk header → line range
- `beforeRef` / `afterRef` → existing ConfixDoc bodies in `ForgeConfixStore`
  - The mutation journal doesn't store bodies, it stores references to bodies
  - The diff is derived: `patch = computePatch(store[beforeRef], store[afterRef])`

**Role transition tracking**:

The mutation journal records HYPOTHESIS → GROUND_TRUTH transitions:

```text
command = "accept"
target  = "row-0.col-2"    // the summary cell
before  = { role: HYPOTHESIS, value: "CPU anomalies..." }
after   = { role: GROUND_TRUTH, value: "CPU anomalies..." }
patch   = CellOverlay(role: DERIVED, provenance: "accepted-by:human")
```

This is the only place where blackboard role transitions are recorded as first-class events.

**Key insight**: Mutation journal is where `OverlayRole` transitions become durable.
Every "accept agent output" or "reject hypothesis" is a mutation row with before/after
CellOverlay snapshots. This gives the system full audit of epistemic state changes.

---

### Hinge 8 — ForgeWorkspaceElement

**What it is**: CCEK lifecycle element owning the whole workspace backend.

This hinge is not a cursor. It is the `AsyncContextElement` that owns:

```text
ForgeConfixStore        (body/facade persistence)
ForgeMutationJournal    (append-only command log)
ForgeCorpusCursor       (current state, projected from mutations)
ForgeArtifactCursor     (derived from executions)
```

**Blackboard wiring at the element level**:

```text
Element lifecycle:
  CREATED  → allocate ConfixStore, empty cursors
  OPEN     → load from persistence, replay mutation journal
  ACTIVE   → accept mutations, update cursors, fanout to subscribers
  DRAINING → flush pending mutations, snapshot cursors
  CLOSED   → persist final state, release resources
```

**Fanout subscribers**:

```text
1. CollaborationSync    → remote users via websocket
2. ArtifactIndexer      → watches for DERIVED rows, builds artifact entries
3. AuditSink            → logs every mutation to PROVENANCE store
4. LCNCViewUpdater      → invalidates cursor projections on mutation
5. AgentEventConsumer   → routes agent HYPOTHESIS rows to accept/reject workflow
```

**Confix adjacency**:

- On OPEN: replay mutation journal → rebuild corpus/artifact cursors
  - Each mutation's `afterRef` → materialize from ConfixStore
  - Cursor state = fold over mutations, not mutable map snapshot
- On ACTIVE: every `put(file)` becomes:
  1. Write body to ConfixStore → get bodyRef
  2. Append mutation row to journal with command=put, afterRef=bodyRef
  3. Update corpus cursor (rebuild or append row)
  4. Fanout to subscribers

**Key insight**: The workspace element is where cursor discipline replaces mutable maps.
`ForgeWorkspaceImpl` currently uses `mutableMapOf`. The PRELOAD-compliant version
uses `fold(mutationJournal, initialCursor, applyMutation)`. Snapshots are just
named checkpoints of the fold position, not full Map copies.

---

### Hinge 9 — ForgeLcncAdapter

**What it is**: projection layer — views/forms/boards over Forge cursors.

This hinge is pure projection. It adds no new rows. It consumes existing cursors and
produces LCNC view specifications.

**Blackboard wiring**:

LCNC views project the blackboard metadata that already exists on cursor rows:

```text
Table view     → Cursor.columns + ColumnOverlay.description + ColumnOverlay.constraints
Board view     → Cursor grouped by OverlayRole (OBSERVATION | HYPOTHESIS | GROUND_TRUTH lanes)
Inspector      → CellOverlay for focused cell (role + provenance + evidence + dependencies)
Form view      → mutation command builder targeting a specific RowVec
Status chips   → OverlayRole → color/status badge mapping
Filter bar     → overlay role, provenance source, evidence confidence range
```

**Confix adjacency**:

- File preview → `ConfixIndex.TreeCursor` facet projected as collapsible tree
- Prompt template editor → `ConfixIndex.Spans` + `Tags` for parameter highlighting
- Workflow canvas → edge cursor → DAG layout → `ConfixIndex` not used here (graph ≠ tree)
- Artifact inspector → `ConfixIndex.KeyToChild` for manifest field navigation
- Mutation diff view → `patchRef` ConfixDoc with `Tags` = ADD/REMOVE/CONTEXT → syntax highlighting

**Key insight**: LCNC is not a state layer. It is a set of projection functions:

```kotlin
typealias LcncTable = (Cursor, BlackboardContext) -> RenderedTable
typealias LcncBoard = (Cursor, (RowVec) -> OverlayRole) -> RenderedBoard
typealias LcncInspector = (RowVec, Int, BlackboardContext) -> RenderedOverlay
typealias LcncDiff = (ConfixDoc, ConfixDoc) -> RenderedPatch
```

No new `CellOverlay` or `Provenance` is created by LCNC. It only reads what the other hinges wrote.

---

### Hinge 10 — ForgeConfixStore

**What it is**: persistent body/facade store.

This hinge is the substrate that all other hinges with `bodyRef` columns depend on.

**API surface**:

```kotlin
interface ForgeConfixStore {
  // Write
  fun put(body: Series<Byte>): String                                    // returns bodyRef (content hash)
  fun put(doc: ConfixDoc): String                                        // stores body, indexes facade, returns ref

  // Read
  fun body(ref: String): Series<Byte>                                    // raw bytes
  fun facade(ref: String): ConfixIndex                                   // parsed index
  fun doc(ref: String): ConfixDoc                                        // facade + body joined
  fun cell(ref: String, path: JsPath): Any?                             // path selection through facade

  // Facet access (using ConfixIndexK facets)
  fun <R> facet(ref: String, key: ConfixIndexK<R>): R?                  // typed facet projection
  fun spans(ref: String): Series<Twin<Int>>                              // shorthand for Spans facet
  fun tags(ref: String): Series<IOMemento>                               // shorthand for Tags facet
  fun treeCursor(ref: String): Cursor?                                   // shorthand for TreeCursor facet

  // Diff
  fun diff(before: String, after: String): ConfixDoc                     // structured diff as ConfixDoc
}
```

**Blackboard wiring**:

The ConfixStore itself doesn't carry `OverlayRole` or `Evidence`. It is pure substrate.
The blackboard metadata lives on the cursor rows that *reference* the store.

But the store does carry implicit `Provenance`:

```text
bodyRef = sha256(body)   → content-addressed = identity proof
facade is deterministic   → same body always produces same index
```

So `Provenance(source = "confix-store", transformations = listOf("sha256", "parse"))` is implicit.

**Dependency flows through the store**:

```text
Corpus row.bodyRef    ──→ ConfixStore ──→ facade parse ──→ LCNC tree preview
                           │
                           └──→ body bytes ──→ agent context input
Prompt row.bodyRef    ──→ ConfixStore ──→ facade parse ──→ parameter extraction
Execution row.bodyRef ──→ ConfixStore ──→ facade parse ──→ event structure
Artifact row.bodyRef  ──→ ConfixStore ──→ facade parse ──→ manifest navigation
Mutation.patchRef     ──→ ConfixStore ──→ facade parse ──→ diff syntax highlight
```

Every `bodyRef` column in every cursor resolves through this single store.

---

## Adjacency matrix

Which hinges directly depend on which kernel types:

```text
                     BlackboardOverlay     ConfixIndex/Doc     CCEK Element
                    Role Prov Evid Dep    Facade Body Path    Lifecycle Fanout
H1 CorpusCursor      ✓    ✓    ·    ✓      ✓      ✓    ✓       ·        ·
H2 PromptCursor      ✓    ✓    ·    ✓      ✓      ✓    ✓       ·        ·
H3 WorkflowGraph     ✓    ·    ·    ·      ✓      ✓    ·       ·        ·
H4 ExecutionCursor   ✓    ✓    ✓    ✓      ✓      ✓    ✓       ·        ·
H5 AgentElement      ✓    ✓    ✓    ✓      ✓      ✓    ·       ✓        ✓
H6 ArtifactCursor    ✓    ✓    ✓    ✓      ✓      ✓    ✓       ·        ·
H7 MutationJournal   ✓    ✓    ·    ·      ✓      ✓    ·       ·        ·
H8 WorkspaceElement  ·    ✓    ·    ·      ✓      ✓    ·       ✓        ✓
H9 LcncAdapter       ✓    ✓    ✓    ✓      ✓      ·    ✓       ·        ·
H10 ConfixStore      ·    ·    ·    ·      ✓      ✓    ✓       ·        ·
```

Reading the matrix:
- Every hinge uses ConfixDoc facade/body. (Confix is universal substrate.)
- `OverlayRole` is used by 8/10 hinges. (Blackboard roles are pervasive.)
- `Provenance` is used by 9/10 hinges. (Only the ConfixStore itself doesn't need it — it IS it.)
- `Evidence` is used by 4/10 hinges. (Confidence/evidence matters where inference meets decisions.)
- `DependencyHandle` is used by 7/10 hinges. (Lineage links are the backbone.)
- CCEK lifecycle/fanout is used by 2/10 hinges. (Only the two element types are lifecycle-bound.)

---

## Cross-hinge dependency chains

These are the actual data-flow paths that the adjacency enables:

### Chain A: File ingest → agent → artifact

```text
1. H1 CorpusCursor: row-0, col-4 (bodyRef)
   → OverlayRole.CONTROL
   → Provenance(source="workspace-put")
   → DependencyHandle.ExternalResource("file://README.md")

2. H5 AgentElement: reads bodyRef via H10 ConfixStore
   → emits execution row

3. H4 ExecutionCursor: row-7, col-5 (bodyRef = agent output)
   → OverlayRole.DERIVED
   → Provenance(source="agent:codex", transformations=["tool:read","step:3"])
   → DependencyHandle.CellRef(corpus.row-0, col-4)

4. H6 ArtifactCursor: row-0, col-2 (bodyRef = final report)
   → OverlayRole.DERIVED
   → Provenance(source="artifact-builder", transformations=[...all steps...])
   → DependencyHandle.Composite([ExternalResource("file://README.md"),
                                  ExternalCellRef("execution", 7, 5),
                                  CellRef(corpus, 0, 4)])
```

### Chain B: Hypothesis → acceptance → ground truth

```text
1. H5 AgentElement: proposes patch
   → ExecutionCursor row with CellOverlay(role=HYPOTHESIS)

2. H7 MutationJournal: "accept" command
   → target = "corpus.row-0.col-2"
   → beforeRef = old body (role=OBSERVATION or empty)
   → afterRef  = new body (role=GROUND_TRUTH)
   → patchRef  = diff ConfixDoc

3. H1 CorpusCursor: row-0, col-2 updated
   → CellOverlay(role=GROUND_TRUTH)
   → Provenance(transformations=["hypothesis", "accepted-by:human"])
   → Evidence(confidence=0.92)

4. H9 LCNC: renders the cell with GROUND_TRUTH status badge
```

### Chain C: Snapshot → diff → restore

```text
1. H7 MutationJournal: "snapshot" command
   → records current mutation seq as checkpoint

2. H8 WorkspaceElement: on snapshot request
   → current corpus cursor = fold(journal[0..checkpoint])
   → persist as named checkpoint

3. H7 MutationJournal: subsequent mutations
   → can compute diff: fold(journal[checkpoint..now])

4. H10 ConfixStore: diff(before, after)
   → structured ConfixDoc with Spans/Tags for diff rendering
```

---

## What this adjacency proves

1. **No new types needed**. The 10 hinges can be expressed entirely with
   `OverlayRole`, `Provenance`, `Evidence`, `DependencyHandle`, `CellOverlay`,
   `ColumnOverlay`, `BlackboardContext`, `ConfixIndex`, `ConfixIndexK` facets,
   and `ConfixDoc`. All exist in the repo today.

2. **The lowering chain is real**. Every hinge has a concrete mapping:
   - RowVec columns → OverlayRole assignments
   - bodyRef columns → ConfixDoc facade/body
   - Cross-hinge links → DependencyHandle variants
   - Confidence → Evidence
   - History → Provenance.transformations

3. **Manager islands are provably unnecessary**. The adjacency shows that
   `ForgeWorkspaceImpl`'s `mutableMapOf<ForgeFileId, ForgeFile>` can be replaced
   by `ForgeCorpusCursor` (a `Series<RowVec>`) where each `RowVec` cell carries
   `CellOverlay` metadata and each body lives in `ForgeConfixStore`.

4. **Hypothesis → Ground Truth is first-class**. The mutation journal records
   role transitions. No other state machine is needed.

5. **ConfixIndexK facets cover all body types**. Markdown, JSON, YAML, code,
   diffs, agent payloads — all have Spans, Tags, Depths. The `TreeCursor` facet
   handles recursive structures. `KeyToChild` handles field lookup. No new
   parsing infrastructure is needed.
