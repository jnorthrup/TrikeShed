# HTX Reactor → Hermes Kanban Conduit Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Turn HTX-normalized reactor events into durable Hermes Kanban tasks so TrikeShed can use Hermes for management and planning without teaching the reactor to be a planner.

**Architecture:** Keep HTX/CCEK responsible for transport, normalization, and fanout only. Add a conduit at the `FanoutDispatcherElement` boundary that projects selected HTX events into a small planning-signal algebra, then crosses into Hermes through supported boundaries: first the `hermes kanban ...` CLI, optionally webhooks later for out-of-process producers. Do **not** write `kanban.db` directly.

**Tech Stack:** TrikeShed CCEK (`FanoutDispatcherElement`, `LiburingElement`), HTX block-table model, Hermes Kanban CLI/board/dispatcher, optional Hermes webhook gateway, local Forge `KanbanBoard` / `WorkflowStep.AgentInvocation` projection.

---

## Grounded discovery from this machine

### Repo context already present
- `docs/deep_dives/ccek-htx-block-table.md`
- `docs/references/PRELOAD.md`
- `docs/SWARM_UNIFIED_DESIGN.md`
- `libs/forge/src/main/kotlin/borg/trikeshed/forge/KanbanTypes.kt`
- `libs/forge/src/main/kotlin/borg/trikeshed/forge/ForgeTypes.kt`
- `src/commonMain/kotlin/borg/trikeshed/userspace/LiburingElement.kt`
- `src/commonMain/kotlin/borg/trikeshed/userspace/context/AsyncContextElement.kt`

### Live Hermes capabilities verified
These commands were checked live in this session:
- `hermes kanban --help`
- `hermes kanban create --help`
- `hermes kanban comment --help`
- `hermes kanban complete --help`
- `hermes kanban block --help`
- `hermes kanban swarm --help`
- `hermes kanban decompose --help`
- `hermes webhook subscribe --help`
- `hermes profile list`
- `hermes kanban boards list`
- `hermes gateway status`

### Current Hermes state
- Active board: `tshed` (empty)
- Available profiles: `default`, `cheap`, `codexy`, `kanban-worker`, `large`, `medium`, `tiny`, `ultra`
- `codexy` gateway: stopped
- `default` gateway: running

### Important consequence
Because the current board `tshed` already exists and is empty, the fastest first cut is to target `--board tshed` and use `kanban-worker` as the initial assignee lane. Do not invent profile names the dispatcher cannot spawn.

---

## Core design decisions

1. **HTX publishes facts; Hermes does planning.**
   The reactor should emit normalized operational signals, not become a long-horizon planner.

2. **Use supported Hermes boundaries only.**
   - Primary path: `hermes kanban ...` CLI
   - Optional remote ingress: `hermes webhook subscribe ...`
   - Never: direct SQLite writes into `~/.hermes/kanban.../kanban.db`

3. **Use idempotency keys derived from HTX identity.**
   Recommended shape:
   - `htx:<connectionId>:<streamId>:<seq>`
   - or for request-level cards: `htx:req:<requestHash>`

4. **Keep planning state on the board, not in the reactor.**
   The reactor should create cards, append comments, block, or complete. All decomposition, review, and human-loop behavior should happen in Hermes Kanban.

5. **Start deterministic, add agentic behavior second.**
   First make HTX → Kanban reliable with CLI commands and idempotency. Only then layer on `triage`, `decompose`, `swarm`, or webhook-driven orchestration.

---

## Proposed runtime shape

```text
NIO / io_uring
  → CCEK Reactor
  → HTX tokenizer / block table
  → HTX domain event
  → HtxPlanningSignal projector
  → HtxKanbanConduit
  → hermes kanban create/comment/block/complete
  → Hermes dispatcher / worker lane
  → Kanban comments, summaries, downstream planning
```

### Why this fits the current codebase
- HTX is already documented as the protocol-normalizing layer in `docs/deep_dives/ccek-htx-block-table.md`.
- `FanoutDispatcherElement` already exists as the central structured dispatch point in `src/commonMain/kotlin/borg/trikeshed/userspace/LiburingElement.kt`.
- `PRELOAD.md` already defines `FanoutDispatcherKey` and explicit lifecycle/fanout as the userspace boundary.
- Local Forge types already have a `KanbanBoard` projection and `WorkflowStep.AgentInvocation`, which means the Hermes board can later be reflected back into TrikeShed representations instead of remaining an external black box.

---

## Event model to project out of HTX

Do **not** project raw blocks directly into Kanban. First collapse them into a planning-signal algebra.

### Task 1: Define `HtxPlanningSignal`

**Objective:** Create a small, deterministic signal vocabulary between HTX and Hermes.

**Files:**
- Create: `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/HtxPlanningSignal.kt`
- Create: `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/HtxPlanningSignalProjector.kt`

**Suggested shape:**

```kotlin
package borg.trikeshed.userspace.reactor

sealed interface HtxPlanningSignal {
    val idempotencyKey: String
    val title: String
    val body: String
    val metadata: Map<String, String>

    data class NewIntent(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val workspace: String,
    ) : HtxPlanningSignal

    data class ProgressNote(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
    ) : HtxPlanningSignal

    data class NeedsHuman(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val reason: String,
    ) : HtxPlanningSignal

    data class Resolved(
        override val idempotencyKey: String,
        override val title: String,
        override val body: String,
        override val metadata: Map<String, String>,
        val taskId: String,
        val summary: String,
    ) : HtxPlanningSignal
}
```

**Projection rule:**
- HTX / reactor sees bytes, blocks, connection state, protocol transitions
- projector converts that into *planning-worthy facts*
- conduit converts those facts into Hermes commands

**Do not do this:**
- one card per raw HTX block
- one comment per byte-range event
- any LLM reasoning in the projector

**Verification:**
- Review the projector output in logs before touching Hermes
- Confirm each emitted signal has:
  - a stable `idempotencyKey`
  - a human-readable `title`
  - compact `metadata`

---

## Hermes boundary: deterministic CLI bridge first

### Task 2: Add a JVM `HermesKanbanCli` adapter

**Objective:** Cross into Hermes through the supported CLI, not by writing DB rows.

**Files:**
- Create: `src/jvmMain/kotlin/borg/trikeshed/userspace/reactor/HermesKanbanCli.kt`
- Create: `src/jvmMain/kotlin/borg/trikeshed/userspace/reactor/HtxKanbanConduit.kt`

**Implementation shape:**
- Use `ProcessBuilder` with argv arrays, not shell strings
- Parse `--json` output from `hermes kanban create`
- Keep the board explicit: `--board tshed`
- Keep the workspace explicit: `--workspace dir:/Users/jim/work/TrikeShed`

**Recommended adapter skeleton:**

```kotlin
package borg.trikeshed.userspace.reactor

import java.nio.file.Path

class HermesKanbanCli(
    private val workdir: Path,
    private val board: String = "tshed",
    private val assignee: String = "kanban-worker",
) {
    fun createTriageCard(title: String, body: String, idempotencyKey: String): String {
        val args = listOf(
            "hermes", "kanban", "--board", board,
            "create", title,
            "--body", body,
            "--assignee", assignee,
            "--workspace", "dir:${workdir.toAbsolutePath()}",
            "--idempotency-key", idempotencyKey,
            "--triage",
            "--json",
        )
        return runHermes(args)
    }

    fun comment(taskId: String, text: String) {
        runHermes(listOf(
            "hermes", "kanban", "--board", board,
            "comment", taskId, text,
        ))
    }

    fun block(taskId: String, reason: String) {
        runHermes(listOf(
            "hermes", "kanban", "--board", board,
            "block", taskId, reason,
        ))
    }

    fun complete(taskId: String, summary: String, metadataJson: String) {
        runHermes(listOf(
            "hermes", "kanban", "--board", board,
            "complete",
            "--summary", summary,
            "--metadata", metadataJson,
            taskId,
        ))
    }

    private fun runHermes(args: List<String>): String {
        val p = ProcessBuilder(args)
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out
    }
}
```

**Important detail:** `create` must use `--idempotency-key` so replayed HTX signals do not create duplicate cards.

**Verification commands:**

```bash
hermes kanban --board tshed create "HTX conduit smoke" \
  --body "smoke test from conduit plan" \
  --assignee kanban-worker \
  --workspace dir:/Users/jim/work/TrikeShed \
  --idempotency-key smoke:htx:1 \
  --triage \
  --json

hermes kanban --board tshed list
```

Expected result: one triage card created, and rerunning the exact same create command with the same idempotency key should return the same task id instead of a duplicate.

---

## Attach the conduit to CCEK fanout

### Task 3: Subscribe at `FanoutDispatcherElement`

**Objective:** Make the conduit a fanout subscriber, not a hard-coded reactor branch.

**Files:**
- Modify: `src/commonMain/kotlin/borg/trikeshed/userspace/LiburingElement.kt`
- Or create a focused installer in: `src/jvmMain/kotlin/borg/trikeshed/userspace/reactor/HtxKanbanInstall.kt`

**Pattern:**
- HTX or reactor code emits a typed `FanoutEvent`
- `FanoutDispatcherElement.registerHandler(eventType, handler)` delivers it
- handler projects event → `HtxPlanningSignal`
- conduit decides which Hermes CLI verb to call

**Suggested wiring:**

```kotlin
class HtxKanbanConduit(
    private val projector: HtxPlanningSignalProjector,
    private val hermes: HermesKanbanCli,
) {
    fun onEvent(event: FanoutEvent) {
        when (val signal = projector.project(event)) {
            is HtxPlanningSignal.NewIntent -> {
                hermes.createTriageCard(signal.title, signal.body, signal.idempotencyKey)
            }
            is HtxPlanningSignal.ProgressNote -> {
                hermes.comment(signal.taskId, signal.body)
            }
            is HtxPlanningSignal.NeedsHuman -> {
                hermes.block(signal.taskId, signal.reason)
            }
            is HtxPlanningSignal.Resolved -> {
                hermes.complete(signal.taskId, signal.summary, toJson(signal.metadata))
            }
            null -> Unit
        }
    }
}
```

**Key rule:** the reactor never reaches directly into Hermes state. It only publishes events into the conduit.

**Verification:**
- Register a single synthetic event type first
- Confirm one event produces one card
- Confirm replay with same idempotency key is stable
- Confirm block/complete mutate the same card, not a new one

---

## Kanban semantics for management and planning

### Task 4: Put planning behavior on the board

**Objective:** Use Hermes Kanban for actual planning, rather than just as a log sink.

**Files:**
- No code required first; this is operational behavior
- Optional later: project board rows into `libs/forge/.../KanbanTypes.kt`

### Recommended card lifecycle

#### 1. HTX detects a planning-worthy situation
Create a **triage** card:

```bash
hermes kanban --board tshed create "HTX: investigate degraded upstream route" \
  --body "Connection c17 stream s4 crossed timeout threshold after protocol normalization.\n\nMetadata:\n- protocol=http2\n- route=/api/search\n- upstream=search-a\n- retryBudget=0" \
  --assignee kanban-worker \
  --workspace dir:/Users/jim/work/TrikeShed \
  --idempotency-key htx:c17:s4:timeout \
  --triage \
  --json
```

This is the best default because it gives Hermes room to specify/decompose the work later.

#### 2. HTX learns more while the card is open
Append a comment:

```bash
hermes kanban --board tshed comment <task_id> \
  "[htx] retry exhausted; peer sent GOAWAY after HEADERS frame; correlationId=req-8841"
```

#### 3. A human or planner must intervene
Block the card:

```bash
hermes kanban --board tshed block <task_id> \
  "waiting for operator decision on whether to drain search-a from rotation"
```

#### 4. The issue is conclusively resolved
Complete the card:

```bash
hermes kanban --board tshed complete \
  --summary "search-a drained; requests rerouted to search-b; no new HTX timeout signals for 10m" \
  --metadata '{"connectionId":"c17","streamId":"s4","route":"/api/search","resolution":"drain-search-a"}' \
  <task_id>
```

### When to use `swarm`
Use `hermes kanban swarm` only when the HTX event should fan out into parallel work lanes immediately.

Example:
- worker 1: inspect transport / socket behavior
- worker 2: inspect protocol / HTX normalization behavior
- verifier: confirm both findings are consistent
- synthesizer: write final operator recommendation

The live CLI exists:

```bash
hermes kanban swarm "Diagnose HTX route degradation for /api/search" \
  --worker kanban-worker:transport-scan \
  --worker kanban-worker:protocol-scan \
  --verifier codexy \
  --synthesizer default \
  --json
```

Use this only after the basic create/comment/block/complete path is stable.

### When to use `decompose`
If the conduit creates a triage card and you want Hermes to split it into child tasks later:

```bash
hermes kanban --board tshed decompose <task_id>
```

That keeps decomposition on the Hermes side where it belongs.

---

## Optional remote ingress: webhook mode

### Task 5: Add webhook ingress only if HTX runs out-of-process

**Objective:** Accept HTX-derived planning payloads over HTTP without coupling the producer to local shell execution.

**Files:**
- No repo code required for the first spike
- Later, add an HTX emitter client if needed

**Important constraint:** webhook subscriptions trigger Hermes agent runs; they are **not** a direct Kanban API. Therefore webhook mode is best for:
- remote HTX producers
- chat notifications
- kicking off an orchestrator flow

It is **not** the first choice for deterministic card mutation.

### Minimum operational setup
The current `codexy` gateway is stopped, so webhook mode requires starting a gateway for the profile that should host the subscription:

```bash
hermes gateway start
```

Then create a subscription:

```bash
hermes webhook subscribe htx-planning \
  --prompt "New HTX planning event:\n{event.title}\n\n{event.body}\n\nUse Hermes Kanban on board tshed to create or update the appropriate task." \
  --description "HTX reactor planning ingress" \
  --skills "hermes-agent,kanban-orchestrator"
```

### Recommendation
Do **not** start here.
Start with the CLI conduit, then add webhooks later only if the HTX producer cannot run `hermes kanban` locally.

---

## Later projection back into TrikeShed / Forge

### Task 6: Reflect Hermes board state into local types

**Objective:** Avoid a permanent split-brain where Hermes is durable truth and TrikeShed has no typed projection.

**Files:**
- Create later: `libs/forge/src/main/kotlin/borg/trikeshed/forge/HermesKanbanProjection.kt`

**Why this is already a good fit:**
- `KanbanBoard.toCascadeGraph()` already exists
- `WorkflowStep.AgentInvocation` already exists
- `SWARM_UNIFIED_DESIGN.md` already treats Kanban as a durable graph with comments as blackboard state

**Projection idea:**
- Hermes task → local `KanbanCard`
- Hermes status column → local `KanbanColumn`
- Hermes parent link → local dependency edge
- Hermes comments/results → `metadata` / auxiliary ledger

That gives you:
- Hermes as operational truth
- TrikeShed as algebraic/visual projection

---

## Anti-patterns to avoid

1. **Writing directly to `kanban.db`**
   - bypasses dispatcher invariants
   - bypasses idempotency and audit semantics
   - will eventually drift from Hermes expectations

2. **Making the reactor the planner**
   - reactor should not decide multi-step work allocation
   - reactor should only identify facts worth planning around

3. **Projecting raw HTX blocks into cards**
   - far too granular
   - destroys signal-to-noise on the board

4. **Using made-up assignee names**
   - dispatcher silently cannot spawn unknown lanes
   - current discovered profiles are the only safe names to use

5. **Starting with webhook-only automation**
   - too much moving machinery
   - harder to debug than a local CLI bridge

---

## Recommended first implementation order

### Task 1: Define signal algebra
- Add `HtxPlanningSignal`
- Add `HtxPlanningSignalProjector`
- Verify stable idempotency keys

### Task 2: Add JVM CLI adapter
- Add `HermesKanbanCli`
- Implement `create/comment/block/complete`
- Smoke-test on board `tshed`

### Task 3: Attach conduit to fanout
- Register a single synthetic event type first
- Then wire real HTX-derived events

### Task 4: Operationalize planning
- Start with `--triage` cards
- Later add `decompose` or `swarm`

### Task 5: Optional webhook ingress
- Only if producer cannot invoke Hermes locally

### Task 6: Optional Forge projection
- Mirror Hermes tasks into local `KanbanBoard`
- Use `toCascadeGraph()` / Mermaid / DOT views

---

## Concrete success criteria

This work is successful when all of the following are true:

1. A synthetic HTX event creates exactly one triage card on board `tshed`
2. Replaying the same event with the same idempotency key does not duplicate the card
3. Follow-up HTX events append comments to the same card
4. A terminal HTX resolution completes the same card with structured metadata
5. A human can open the card in Hermes and continue management/planning there
6. No code writes directly to Hermes SQLite state

---

## Final recommendation

**Build this in two phases:**

1. **Now:** HTX → `HtxPlanningSignal` → `hermes kanban ...` on board `tshed`, assignee `kanban-worker`, mostly triage cards.
2. **Later:** add `decompose`/`swarm`, optional webhook ingress, and local Forge projection.

That keeps HTX as the conduit, Hermes as the manager/planner, and Kanban as the durable state machine.
