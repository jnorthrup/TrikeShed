# Forge Gap Through the PRELOAD Contract

> Purpose: articulate the missing hinges for the proposed LLM workflow tool using the TrikeShed `PRELOAD.md` kernel contract: `Join`, `Series`, `Cursor`, Confix, blackboard, LCNC, and Forge.

The short version: Forge currently has the **product nouns** but not the **PRELOAD lowering**. The gap is not another UI feature or another data class. The gap is the missing path that reifies every Forge thing as cursor algebra, persists it as Confix/blackboard state, and runs it through CCEK lifecycle/fanout.

---

## 1. PRELOAD ground truth

From `PRELOAD.md`, the contract is:

```kotlin
Join<A, B>          // base binary composition
typealias Series<T> = Join<Int, (Int) -> T>
Series<T>.size
Series<T>.get(i)
Series<T>.view
series α { ... }    // lazy projection
value.↺             // constant / left identity anchor
```

Cursor is only a specialization of this:

```kotlin
typealias RowVec = Series2<Any?, () -> ColumnMeta>
typealias Cursor = Series<RowVec>
```

Rules that matter for Forge:

```text
composition over inheritance
ranges/projections over mutable loops
explicit algebra over opaque helpers
lazy views first, materialization later
typealiases compress semantics, not substance
metadata is part of the algebra, not an afterthought
side effects live at userspace boundaries with lifecycle and fanout
```

So a Forge object is not complete when it has a `data class`. It is complete when it can answer:

```text
What is its RowVec?
What is its Cursor projection?
What metadata rides with each value?
What Confix body/facade represents it?
What blackboard role/provenance/evidence/dependencies does it carry?
What AsyncContextElement owns its side effects?
```

---

## 2. The actual gap

The current Forge surface has nominal contracts:

```text
ForgeFile
ForgeSnapshot
ForgePrompt
ForgeWorkflow
WorkflowStep
StepResult
ForgeArtifact
CollaborationEvent
ForgeAgentRunner
OperationalCascade
```

But the PRELOAD-compliant system needs cursor contracts:

```text
Files       -> Cursor of Confix-backed document/context rows
Prompts     -> Cursor of parameterized template rows
Workflows   -> Cursor graph of action rows and dependency edges
Runs        -> Cursor of execution-event rows
Agents      -> CCEK elements that emit tool/event rows
Artifacts   -> Confix/blackboard entries with provenance and dependencies
Collab      -> mutation cursor + fanout, not local callbacks
Snapshots   -> facade/body checkpoint over Confix cursors, not full Map copies
LCNC UI     -> views over these cursors, not bespoke product state
```

The missing hinge is therefore:

```text
Forge nominal API
  -> LCNC row/schema/view algebra
  -> Cursor / RowVec / ColumnMeta
  -> ConfixDoc facade + Series<Byte> body
  -> BlackboardEntry role/provenance/evidence/dependencies
  -> CCEK AsyncContextElement lifecycle + fanout
```

Without that lowering chain, Forge is a good interface sketch but not yet a TrikeShed-native workflow fabric.

---

## 3. Requirement-by-requirement gap

### Requirement A — manage input files plus general context

Product ask:

```text
Ability to manage a set of input files: Markdown or similar, plus general-purpose context.
```

Current Forge shape:

```kotlin
data class ForgeFile(
    val id: ForgeFileId,
    val path: String,
    val content: String,
    val mimeType: String,
    val metadata: Map<String, String>
)
```

PRELOAD-compliant shape:

```text
Forge corpus = Cursor
row = file/context item
columns = id, path, mime, bodyRef, hash, role, provenance, dependencies, metadata
body = Series<Byte>
parsed facade = ConfixIndex
cell navigation = ConfixCell / RowVec.step / JsPath
```

The missing hinge:

```text
ForgeFile is not yet a Confix-backed blackboard row.
```

Needed primitive:

```text
ForgeCorpusCursor
  source rows: files, markdown chunks, external context, generated artifacts
  metadata: mime, path, hash, token budget, source role, provenance
  body: Series<Byte>
  parsed facade: ConfixDoc when structured
```

PRELOAD test:

```text
Can I project all Markdown source rows with α?
Can I range-select context chunks without copying?
Can I preserve metadata through transforms?
Can I reify a structured source via Confix path selection?
```

If not, it is just a file map.

---

### Requirement B — real-time collaboration and snapshots/VCS

Product ask:

```text
Real-time collaboration, maybe snapshots or VCS integration.
```

Current Forge shape:

```text
MutableSharedFlow<CollaborationEvent>
MutableList<ForgeSnapshot>
Map<ForgeFileId, ForgeFile> copies
```

PRELOAD-compliant shape:

```text
Collaboration = mutation cursor + CCEK fanout
Snapshot      = checkpoint of cursor facade/body state
VCS           = lineage cursor over checkpoint rows
```

A collaboration event should not be a detached callback. It should be a row:

```text
CollabEventRow = RowVec(
  seq,
  actor,
  scope,
  operation,
  target,
  patch/bodyRef,
  timestamp,
  provenance
)
```

A snapshot should not primarily be a full object graph copy. It should be:

```text
SnapshotRow = RowVec(
  snapshotId,
  parentId,
  corpusRootHash,
  workflowRootHash,
  promptRootHash,
  artifactRootHash,
  message,
  author,
  timestamp
)
```

The missing hinge:

```text
No durable mutation cursor; no CCEK collaboration element; no Confix checkpoint discipline.
```

Needed primitive:

```text
ForgeJournal : Cursor              // append-only mutation/event rows
ForgeSnapshotCursor : Cursor       // checkpoint lineage
ForgeCollabElement : AsyncContextElement
```

The CCEK contract says side effects belong at a userspace boundary with lifecycle:

```text
CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED
fanoutSubscribers = websocket, local UI, artifact indexer, audit sink
```

That is the hinge missing from `MutableSharedFlow`.

---

### Requirement C — inference workflows and stored prompts

Product ask:

```text
Create/manage inference workflows and a stored set of prompts.
```

Current Forge shape:

```kotlin
data class ForgePrompt(...)
data class ForgeWorkflow(... steps: List<WorkflowStep>)
sealed interface WorkflowStep
```

PRELOAD-compliant shape:

```text
Prompt registry = Cursor of prompt rows
Workflow graph  = Cursor of step rows + Cursor of edge rows
Execution plan  = projection over workflow graph plus input bindings
```

Prompt row:

```text
id | name | version | templateBodyRef | parameterSchema | tags | provenance
```

Workflow step row:

```text
id | kind | inputBindings | outputSchema | toolRef | guardrailRef | retryPolicy | metadata
```

Workflow edge row:

```text
fromStep | outputKey | toStep | inputKey | transformRef
```

The missing hinge:

```text
WorkflowStep exists as sealed classes, but not as a cursor-addressable build graph.
```

Needed primitive:

```text
ForgeWorkflowCursor
ForgePromptCursor
ForgeBindingCursor
ForgeWorkflowCompiler
```

`ForgeWorkflowCompiler` should lower:

```text
prompt rows + step rows + edge rows + input corpus cursor
  -> executable build plan cursor
```

This is the Autotools analogy:

```text
configure.ac / Makefile.am / source files
  -> generated build plan
  -> artifacts
```

Forge equivalent:

```text
prompt cursor / workflow cursor / corpus cursor
  -> execution cursor
  -> inference artifacts
```

---

### Requirement D — access to coding agents, not just chat models

Product ask:

```text
Access to general-purpose coding agents.
```

Current Forge shape:

```kotlin
interface ForgeAgentRunner {
    fun runAgent(...): ReceiveChannel<AgentEvent>
}
```

`ForgeStepRunnerImpl` currently returns strings like:

```text
"Agent CODEX completed task: ..."
```

PRELOAD-compliant shape:

```text
Agent = AsyncContextElement + event cursor producer
Tool call = RowVec
Tool result = RowVec
Patch = Confix/byte body + provenance
Agent run = cursor of events, artifacts, mutations
```

The missing hinge:

```text
Agents are not CCEK elements and their events are not persisted as cursors.
```

Needed primitive:

```text
ForgeAgentElement : AsyncContextElement
ForgeAgentEventCursor : Cursor
ForgeToolCallCursor : Cursor
ForgePatchArtifact : BlackboardEntry
```

Agent events should lower to rows:

```text
seq | runId | agent | phase | eventKind | tool | argsRef | resultRef | timestamp | evidence | provenance
```

Allowed tools should not be ad hoc strings. They should be policy rows:

```text
principal | scope | action | allow/deny | budget | approvalGate | provenance
```

That is how coding agents become LCNC/blackboard-native rather than shell subprocesses hidden behind a string.

---

### Requirement E — compiled outputs / inference results shareable externally

Product ask:

```text
Compiled outputs/inference results worth saving and sharing externally.
```

Current Forge shape:

```kotlin
data class ForgeArtifact(
    val files: List<ForgeFile>,
    val workflowId: ForgeWorkflowId?,
    val executionId: ForgeExecutionId?,
    val isPublic: Boolean
)
```

PRELOAD-compliant shape:

```text
Artifact = blackboard entry + content-addressed bodies + provenance/dependencies
Artifact index = Cursor
Export bundle = projection/materialization of cursor rows
Share route = policy row + route row
```

The missing hinge:

```text
Artifact does not encode full dependency provenance as cursor algebra.
```

Needed row:

```text
ArtifactRow = RowVec(
  artifactId,
  kind,
  bodyRef,
  inputSnapshotId,
  workflowVersion,
  promptVersions,
  agentRunIds,
  sourceHashes,
  outputHash,
  sharePolicyRef,
  evidence,
  provenance
)
```

The blackboard overlay already has the right vocabulary:

```text
OverlayRole.PROVENANCE
DependencyHandle.ExternalResource
DependencyHandle.CellRef
Evidence(confidence, supportCount, notes)
```

Forge should use those, not invent another artifact metadata island.

---

## 4. LCNC as the view layer, not the state layer

LCNC should not become a pile of UI objects. LCNC is the reusable view/action surface over cursor state.

PRELOAD-compatible LCNC:

```text
LCNC View = Cursor projection + ViewSpec
LCNC Form = mutation command builder over a RowVec
LCNC Button = ActionRef + binding row
LCNC Table = Cursor + ColumnMeta
LCNC Board = Cursor grouped by facet
LCNC Inspector = Blackboard overlay projection
LCNC Flowchart = workflow graph projection
```

That means the product UI is just projections:

```text
Files panel      = projection over ForgeCorpusCursor
Prompt library   = projection over ForgePromptCursor
Workflow canvas  = projection over ForgeWorkflowStep/Edge cursors
Run console      = projection over ForgeExecutionEventCursor
Artifacts panel  = projection over ForgeArtifactCursor
Collab presence  = projection over ForgePresenceCursor
```

The missing hinge:

```text
No shared LCNC schema/view/action layer over Forge cursors yet.
```

---

## 5. Confix as the document/body bridge

Confix gives the correct split:

```kotlin
typealias ConfixDoc = Join<ConfixIndex, Series<Byte>>
val ConfixDoc.facade: ConfixIndex get() = index
var ConfixDoc.body: Series<Byte> get() = src
```

Read that as:

```text
facade = stable parsed/indexed shape
body   = swappable byte series
```

For Forge:

```text
Markdown file body         -> Series<Byte>
frontmatter/JSON/YAML      -> ConfixDoc
workflow definition        -> ConfixDoc + WorkflowCursor projection
prompt template            -> body + parameter schema facade
artifact manifest          -> ConfixDoc
run log                    -> ConfixDoc/NDJSON cursor
external connector payload -> ConfixDoc
```

The missing hinge:

```text
Forge serializes to JSON strings/ByteArrays, but does not treat ConfixDoc as the primary object-body split.
```

The Forge storage layer should be:

```text
BlackBoardEntry(
  doc = ConfixDoc(index, body),
  role = OBSERVATION | DERIVED | AGGREGATE | POINTCUT_STATS | ...,
  timestamp,
  provenance
)
```

Then LCNC views can project over the facade while artifact exports materialize body slices.

---

## 6. Blackboard as the semantic contract

Blackboard answers: what role does this value play in the evolving computation?

Existing vocabulary:

```text
OverlayRole.OBSERVATION      raw input
OverlayRole.DERIVED          model/workflow output
OverlayRole.AGGREGATE        rollup/cascade result
OverlayRole.HYPOTHESIS       agent proposal
OverlayRole.GROUND_TRUTH     accepted/verified value
OverlayRole.CONTROL          config/prompt/workflow parameter
OverlayRole.METADATA         schema/facet data
OverlayRole.PROVENANCE       audit trail
```

Forge should map directly:

```text
input file/chunk       -> OBSERVATION
prompt/workflow config -> CONTROL
agent proposed patch   -> HYPOTHESIS
accepted patch         -> DERIVED or GROUND_TRUTH
cascade rollup         -> AGGREGATE
run log                -> PROVENANCE
schema/view metadata   -> METADATA
```

The missing hinge:

```text
Forge metadata is string maps; blackboard semantics are not attached to cells/columns/runs.
```

Needed primitive:

```text
ForgeBlackboardContext
  column overlays for every Forge cursor
  provenance chains for every derived artifact
  dependencies from outputs back to input rows and agent events
```

This is the difference between “stored result” and “computed artifact with lineage.”

---

## 7. The hard gap statement

The gap is:

```text
Forge is currently noun-complete but algebra-incomplete.
```

More explicitly:

```text
Forge has data classes for the product surface,
but it lacks the cursor-native, Confix-backed, blackboard-annotated,
CCEK-owned implementation that would make it a TrikeShed system.
```

A PRELOAD-valid Forge must be able to express every product surface as:

```text
Join composition
  -> Series projection
  -> Cursor row/metadata
  -> Confix facade/body
  -> Blackboard role/provenance/evidence/dependencies
  -> CCEK lifecycle/fanout for side effects
```

If a feature bypasses that chain, it is outside the TrikeShed contract.

---

## 8. Missing hinges, named as implementation targets

### Hinge 1 — `ForgeCorpusCursor`

All files/context/chunks/external sources as one cursor.

```text
id | kind | path | mime | bodyRef | hash | role | provenance | dependencies | facets
```

### Hinge 2 — `ForgePromptCursor`

Prompt registry as rows, not map entries.

```text
id | name | version | templateBodyRef | paramSchemaRef | tags | provenance
```

### Hinge 3 — `ForgeWorkflowGraphCursor`

Workflow nodes and edges as cursor projections.

```text
steps: id | kind | bodyRef | inputSchema | outputSchema | policyRef
edges: from | output | to | input | transformRef
```

### Hinge 4 — `ForgeExecutionCursor`

Every run as an append-only event cursor.

```text
seq | runId | stepId | phase | eventKind | bodyRef | artifactRef | timestamp | provenance
```

### Hinge 5 — `ForgeAgentElement`

Coding agent subprocess/API bridge as CCEK element.

```text
open -> start process/API session
active -> emit AgentEvent rows
drain -> collect final patch/artifacts
close -> terminate/cleanup
fanout -> UI, artifact indexer, audit log, workspace mutator
```

### Hinge 6 — `ForgeArtifactCursor`

Build outputs as content-addressed blackboard entries.

```text
artifactId | kind | bodyRef | inputSnapshot | workflowHash | promptHash | agentRun | outputHash | sharePolicy
```

### Hinge 7 — `ForgeMutationJournal`

Document/workspace changes as durable commands/mutations.

```text
seq | actor | command | target | patchRef | beforeRef | afterRef | timestamp | policyRef
```

### Hinge 8 — `ForgeWorkspaceElement`

The whole workspace backend as an `AsyncContextElement`.

```text
key = ForgeWorkspaceKey
state = CREATED/OPEN/ACTIVE/DRAINING/CLOSED
fanoutSubscribers = collab, indexes, artifact builder, external sync
```

### Hinge 9 — `ForgeLcncAdapter`

LCNC tables/forms/boards/inspectors as projections over Forge cursors.

```text
ViewSpec + Cursor -> UI
ActionRef + RowVec -> command
Mutation -> refreshed projection
```

### Hinge 10 — `ForgeConfixStore`

Persistent body/facade store.

```text
bodyRef -> Series<Byte>
bodyRef -> ConfixIndex facade
bodyRef + path -> ConfixCell
```

---

## 9. Product architecture after the hinges

```text
                         ┌─────────────────────────┐
                         │       LCNC UI           │
                         │ views/forms/actions     │
                         └───────────┬─────────────┘
                                     │ projects
                                     ▼
┌────────────────────────────────────────────────────────────────┐
│                         Forge cursors                          │
│ Corpus | Prompts | WorkflowGraph | Executions | Artifacts       │
└───────────┬──────────────┬──────────────┬──────────────┬───────┘
            │              │              │              │
            ▼              ▼              ▼              ▼
      ConfixDoc       ConfixDoc       Event Cursor   BlackboardEntry
   facade + body    facade + body      RowVec log    provenance/deps
            │              │              │              │
            └──────────────┴──────┬───────┴──────────────┘
                                   ▼
                        ForgeWorkspaceElement
                        CCEK lifecycle + fanout
                                   │
             ┌─────────────────────┼─────────────────────┐
             ▼                     ▼                     ▼
       AgentElement          ArtifactBuilder        CollaborationSync
       Codex/Claude/etc      exports/routes         websocket/replay
```

That is the actual “GNU Autotools × Notion” shape in TrikeShed terms:

```text
source/context cursor
+ prompt/workflow graph cursor
+ agent/tool execution elements
+ mutation/event journal
+ artifact cursor
+ LCNC projections
```

---

## 10. Non-goals / traps

Do **not** solve this by adding more product-specific data classes.

Bad direction:

```text
ForgeFileMap
PromptManager
WorkflowManager
ArtifactManager
CollaborationManager
```

Those are manager islands.

Good direction:

```text
Cursor projections over shared Confix/blackboard substrate
```

Do **not** make Mermaid, JSON, Markdown, or chat transcripts the model.

They are codecs/bodies:

```text
Markdown -> body codec
Mermaid  -> diagram codec
JSON     -> Confix syntax/body codec
Chat     -> event stream projection
```

The model is:

```text
Join / Series / Cursor / Confix / Blackboard / CCEK
```

---

## 11. Minimal vertical slice that proves the contract

Build the smallest real thing that crosses every boundary:

1. Store two Markdown files as `ForgeCorpusCursor` rows.
2. Parse frontmatter/body into `ConfixDoc` facade/body.
3. Store one prompt as `ForgePromptCursor` row.
4. Store one workflow as `ForgeWorkflowGraphCursor`: `summarize -> critique -> artifact`.
5. Execute through one real `ForgeAgentElement` or shell-safe fake that emits event rows, not just strings.
6. Append all step events to `ForgeExecutionCursor`.
7. Create one `ForgeArtifactCursor` row with dependencies back to source rows, prompt row, workflow row, and event rows.
8. Expose LCNC views:
   - corpus table
   - workflow graph
   - run log
   - artifact inspector
9. Snapshot the cursor roots.
10. Export the artifact manifest/body.

Definition of done:

```text
No central mutableMap island.
Every product object can be shown as RowVec/Cursor.
Every byte body has Confix or bodyRef discipline.
Every derived value has blackboard provenance/dependencies.
Every side effect is owned by an AsyncContextElement.
```
