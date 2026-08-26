# TrikeShed Production Centers and IO Adjacencies

This document maps the production centers (long-running CCEK reactor elements) in the TrikeShed daemon and their input/output adjacencies — channels, flows, blackboard projections, WAL, and CAS. All paths are single-writer, backpressured, and CAS-anchored where durability matters.

---

## Center Map

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│ OROBOROS DAEMON (single runBlocking scope)                                          │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐   ┌─────────────┐  │
│  │  BeliefBag      │──▶│  CausalityRete  │──▶│  CuratorImpulse │   │  TurnReview │  │
│  │  (NARS bag)     │   │  (live law)     │   │  (teaching)     │   │  (induction)│  │
│  └────────┬────────┘   └────────┬────────┘   └────────┬────────┘   └──────┬──────┘  │
│           │                     │                     │                     │         │
│           │                     │                     │                     │         │
│           ▼                     ▼                     ▼                     ▼         │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐│
│  │                    DAEMON BLACKBOARD (ForgeBlackboardView)                      ││
│  │  narsese/curation/*          narsese/rete/firing/*        memory/*            ││
│  └─────────────────────────────────────────────────────────────────────────────────┘│
│           │                     │                     │                     │         │
│           ▼                     ▼                     ▼                     ▼         │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐   ┌─────────────┐  │
│  │  ProjectDB      │   │  Module         │   │  PatchWire      │   │  Kanban     │  │
│  │  Tendon         │   │  Supervisor     │   │  (KeyMux/Model) │   │  HTTP       │  │
│  └────────┬────────┘   └────────┬────────┘   └────────┬────────┘   └─────────────┘  │
│           │                     │                     │                             │
│           └─────────────────────┼─────────────────────┘                             │
│                                 ▼                                                     │
│                    ┌────────────────────────┐                                        │
│                    │  MemoryStore + Index   │                                        │
│                    │  (CAS + Couch + ISAM)  │                                        │
│                    └───────────┬────────────┘                                        │
│                                │                                                     │
│                    ┌───────────▼───────────┐                                        │
│                    │  Couch / CAS / IPFS   │                                        │
│                    └───────────────────────┘                                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 1. BeliefBagElement — NARS Belief State

**File**: `src/commonMain/kotlin/borg/trikeshed/narsese/BeliefBagElement.kt`

### Inputs (intake channel)
| Channel | Type | Capacity | Source |
|---------|------|----------|--------|
| `intake: Channel<BeliefIntake>` | MPSC, buffered | 256 | CausalityReteElement.fireLive, CuratorImpulseElement.teach, TurnReviewElement, external callers |

**BeliefIntake variants**:
- `Mint(signal, budget, receiptCid?, evidenceBasis?)` — new/revised belief
- `Reinforce(angular, delta, receiptCid?)` — evidence delta only
- `Attend(angular, budget)` — attention rekey
- `DecayTick` — curation pulse (daily)

### Outputs (emitted flows)
| Flow | Type | Replay | Consumers |
|------|------|--------|-----------|
| `beliefEvents: SharedFlow<BeliefEvent>` | broadcast | 0, extraBuffer 1024 | Daemon blackboard projection, TurnReviewElement, external render |

**BeliefEvent variants**:
- `Minted(angular, receiptCid)`
- `Revised(angular, evidence)`
- `Attended(angular, budget)`
- `Evicted(angular, spillCid)`
- `Contradicted(angular, subjectCid)`

### Durability
- **WAL**: `DurableAppendLog` at `.oroboros/belief.wal` (CRC frames, group commit every `flushEvery=32` and at DecayTick)
- **CAS**: victim spills write canonical bytes to `CasStore` (same forge-home CAS as MemoryStore)
- **Revival**: `cas != null` → `reviveFromCas` on angular miss before mint

### Internal State
- `HijackBeliefBag` — funnel-probe geometry × stochastic hijack replacement (capacity 4096)
- `basisByAngular: HashMap<Long, EvidenceBasis>` — exact ancestry (leaves as ContentId[])
- `receiptByAngular: HashMap<Long, ContentId>` — receipt per angular for overlap-safe revision
- `MomentField` — self-model, rebuilt lazily on `momentsDirty` flag (derived state, never WAL'd)

---

## 2. CausalityReteElement — Live Rete over the Bag

**File**: `src/commonMain/kotlin/borg/trikeshed/narsese/CausalityReteElement.kt`

### Dependencies
| Dependency | Type | Provided by |
|------------|------|-------------|
| `bag: BeliefBagElement` | constructor | OroborosDaemon |
| `rules: Series<EternalRule>` | constructor | callers (forge routes / curation pulses) |
| `discount: Float = 0.5f` | constructor | weak-rule haircut |
| `minSupport: Long = Nal.UNIT/4` | constructor | floor for minting |

### Internal State
| State | Type | Purpose |
|-------|------|---------|
| `rete: CausalityRete` | pure data + folds | alpha network (frozen rules), fire logic |
| `terms: LinkedHashMap<Long, Twin<String>>` | mutable map | angular → (subject, obj) term registry |
| `seenFirings: HashSet<ContentId>` | mutable set | duplicate firing suppression |
| `_firings: MutableSharedFlow<ReteFiring>` | broadcast | replay 0, buffer 1024 — Curator/Forge explanation |
| `evaluator: ContentId` | constant | `ContentId.of("causality-rete")` for receipts |

### Inputs (via bag)
| Path | Mechanism |
|------|-----------|
| Live bag assertions | `bag.snapshot()` in `projectLive()` |
| Term registration | `register(angular, subject, obj)` called by minting sources (Curator, TurnReview) |

### Outputs
| Output | Type | Destination |
|--------|------|-------------|
| `firings: SharedFlow<ReteFiring>` | broadcast | Daemon blackboard (`narsese/rete/firing/*`), external render |
| Bag mints | `bag.intake.send(Mint(...))` | Back into BeliefBagElement (discounted budget) |

### ReteFiring record
```kotlin
data class ReteFiring(
    val firingCid: ContentId,           // SHA256 of rule|matched|consequent
    val rule: EternalRule,              // frozen admitted rule
    val matched: ReteAssertion,         // premise from bag
    val consequentAngular: Long,        // angular of derived consequent
    val support: EvidenceCoord,         // discounted support
    val dependence: Dependence,         // DEPENDENT (never OBSERVATIONAL)
    val floored: Boolean,               // hit minimum-understanding floor
)
```

### Fire loop (daemon-owned)
```kotlin
launch {
    while (isActive) {
        causalityRete.fireLive()  // projects, fires, mints, registers
        delay(250L)
    }
}
```
- 250 ms fixed interval inside reactor scope
- Each firing: receipted, discounted, registered under its own subject term
- Duplicate `firingCid` suppressed; self-loop prevented by registering consequent under `(consequent, antecedent)`

---

## 3. CuratorImpulseElement — Teaching Recipient

**File**: `src/commonMain/kotlin/borg/trikeshed/narsese/CuratorImpulseElement.kt`

### Dependencies
| Dependency | Type | Provided by |
|------------|------|-------------|
| `bag: BeliefBagElement` | constructor | OroborosDaemon |
| `rete: CausalityReteElement?` | constructor | optional, for term registration |
| `mintBudget: BudgetCoord` | constructor | `BudgetCoord(0.6f, 0.5f, 0.5f)` |

### Internal State
| State | Type | Purpose |
|-------|------|---------|
| `knowledgeBank: KifKnowledgeBase` | accumulating | SUMO spine + assessed impulses as predicate logic |
| `evaluator: ContentId` | constant | `ContentId.of("curator-impulse")` |

### Input
| Method | Signature | Source |
|--------|-----------|--------|
| `teach(impulses, scenarios)` | `suspend fun teach(impulses: Series<CuratorImpulse>, scenarios: Series<ReplayScenario>): List<Join<Long, String>>` | CuratorImpulseFeeder.backfill (boot), forge routes (ongoing) |

### Processing
1. `CuratorImpulseRecipient.assess(impulses, scenarios)` — hindsight replay, verdicts
2. `CuratorImpulseRecipient.bank(assessments)` — KIF construction, accumulates in `knowledgeBank`
3. `CuratorImpulseRecipient.signalsWith(assessments, sourceCid)` — projects to `SemanticSignal`
4. `bag.intake.send(Mint(...))` — mints with receipt, discounted budget
5. `rete?.register(...)` — term registration for later Rete chaining

### Outputs
| Output | Type | Destination |
|--------|------|-------------|
| Landed signals | `List<Join<Long, String>>` | Returned to caller for render captioning |
| Bag mints | `bag.intake.send(...)` | Into BeliefBagElement |
| Banked KIF | `knowledgeBank.assert(expr)` | Accumulated, queryable via `queryBank(kifPattern)` |

---

## 4. TurnReviewElement — Induction Review

**File**: `src/commonMain/kotlin/borg/trikeshed/narsese/TurnReviewElement.kt`

### Dependencies
| Dependency | Type |
|------------|------|
| `bag: BeliefBagElement` | constructor |
| `intakeCap: Int = 128` | constructor |

### Inputs
| Channel | Type |
|---------|------|
| `intake: Channel<TurnReviewIntake>` | MPSC, capacity = intakeCap |

**TurnReviewIntake variants**:
- `Observation(signal, budget)` — external observation
- `Induction(antecedent, consequent, confidence)` — weak induction candidate
- `ReviewComplete` — drain signal

### Outputs
| Output | Type | Destination |
|--------|------|-------------|
| Bag mints | `bag.intake.send(Mint/Reinforce)` | Into BeliefBagElement |
| Events | internal | Drained by bag's `beliefEvents` flow |

### Draining Semantics
- In-flight intakes complete via bag's serial channel
- Nothing hard-cancelled; `drain()` awaits channel empty + one settle tick

---

## 5. Daemon Blackboard — ForgeBlackboardView

**Mechanism**: `ConfixBlackboard` + `ForgeBlackboardView` (commonMain)

### Narsese Projections (from daemon)
| Key Prefix | Source | Payload |
|------------|--------|---------|
| `narsese/curation/minted/<angular_hex>` | BeliefBag.beliefEvents | `{event, angular, actor="curator-pure"}` |
| `narsese/curation/revised/<angular_hex>` | BeliefBag.beliefEvents | `{event, angular, actor="curator-pure"}` |
| `narsese/curation/attended/<angular_hex>` | BeliefBag.beliefEvents | `{event, angular, actor="curator-pure"}` |
| `narsese/curation/evicted/<angular_hex>` | BeliefBag.beliefEvents | `{event, angular, actor="curator-pure"}` |
| `narsese/curation/contradicted/<angular_hex>` | BeliefBag.beliefEvents | `{event, angular, actor="curator-pure"}` |
| `narsese/rete/firing/<firingCid_hex>` | CausalityReteElement.firings | `{event="dependent-rete-firing", firingCid, ruleCid, antecedent, consequent, dependence}` |

### Other Projections
| Key Prefix | Source |
|------------|--------|
| `memory/*` | HermesMemoryFiles, MemoryStore, MemoryIndexLayer |
| `project-db/*` | ProjectDbRegistry, CouchChangesFactElement |
| `module/*` | ModuleSupervisor, ModuleWire |
| `git/*` | GitStateCache, WorktreeReconcileElement, GitReconcileElement |
| `jules/*` | JulesWalWatcher, BrainClient |

---

## 6. BeliefWire — HTTP Surface for Bag

**File**: `src/commonMain/kotlin/borg/trikeshed/forge/server/BeliefWire.kt` (inferred)

### Routes
| Method | Path | Handler |
|--------|------|---------|
| GET | `/belief/snapshot` | `bag.snapshot()` |
| GET | `/belief/events` | SSE over `bag.beliefEvents` |
| POST | `/belief/mint` | `bag.intake.send(Mint(...))` |
| POST | `/belief/reinforce` | `bag.intake.send(Reinforce(...))` |
| POST | `/belief/attend` | `bag.intake.send(Attend(...))` |
| POST | `/belief/decay` | `bag.intake.send(DecayTick)` |

---

## 7. CuratorImpulseFeeder — Boot Backfill

**File**: `src/jvmMain/kotlin/borg/trikeshed/narsese/CuratorImpulseFeeder.kt`

### Inputs (blocking IO on Dispatchers.IO)
| Source | Path | Type |
|--------|------|------|
| Hermes curator ledger | `<profileDir>/skills/.curator_ledger.jsonl` | JSONL lines |
| Hermes state.db | `<profileDir>/state.db` (messages table) | SQLite |

### Processing
1. Reads ledger lines → `CuratorImpulse` objects
2. Reads transcript messages → `ReplayScenario` objects
3. Calls `curatorImpulse.teach(impulses, scenarios)`
4. Returns landed signal count

### Trigger
- Single boot pass in `OroborosDaemon` (lines 636-650)
- `HERMES_PROFILE` env var selects profile directory

---

## 8. ProjectDB Tendon — Couch Changes → Rete

**Mechanism**: `CouchChangesFactElement` per mounted project DB

### Input
| Source | Mechanism |
|--------|-----------|
| CouchDB `_changes` feed | Long-polling, since-seq tracking |

### Output
| Destination | Mechanism |
|-------------|-----------|
| `ReteNetwork` (dag) | `admit = { true }` — every change becomes a fact |

### Wiring (OroborosDaemon lines 741-753)
```kotlin
projectDbRegistry.onMount = { pdb ->
    val tendon = CouchChangesFactElement(
        db = pdb.db,
        rete = rete,                    // dag.ReteNetwork
        report = reportReactorForWires,
        admit = { true },
    )
    moduleScope.launch { tendon.open() }
}
```

---

## 9. ModuleSupervisor — Dynamic Module Loader

**File**: `src/commonMain/kotlin/borg/trikeshed/module/ModuleSupervisor.kt` (inferred)

### Inputs
| Source | Mechanism |
|--------|-----------|
| `config.modules: List<String>` | FQCNs from daemon config |
| Default: `KanbanModule` | Attached at boot if not opted out |

### Outputs
| Destination | Mechanism |
|-------------|-----------|
| `ModuleRouteRegistry` | Routes registered by module `attach()` |
| Daemon blackboard | Receipt events: `$event/$id` |

### ModuleContext (provided to modules)
| Capability | Source |
|------------|--------|
| `couchDb` | CouchDB instance |
| `rete` | dag.ReteNetwork |
| `productions` | ReteProductionRegistry |
| `beliefBag` | BeliefBagElement |
| `turnReview` | TurnReviewElement |
| `blackboard` | daemonBlackboard |
| `casStore` | CasStore |
| `attachments` | AttachmentGateway |
| `routes` | ModuleRouteRegistry |
| `scope` | SupervisorJob + Dispatchers.Default |
| `clock` | `{ System.currentTimeMillis() }` |
| `stateDir` | forgeHome |

---

## 10. Kanban HTTP Server — External API

**File**: `src/jvmMain/kotlin/borg/trikeshed/forge/server/JvmKanbanServer.kt` (inferred)

### Port
- Default: 8765 (configurable via `--port`)

### Routes (aggregated)
| Source | Prefix |
|--------|--------|
| `graalWire` | `/graal/*` |
| `vmWire` | `/vm/*` |
| `hermesWire` | `/hermes/*` |
| `beliefWire` | `/belief/*` |
| `patchWire` | `/patch/*` |
| `moduleWire` | `/module/*` |
| `couchWire` | `/$COUCH_DB_NAME/*` (raw Couch 1.6 surface) |
| `projectDbWire` | `/project-db/*` |

### Streaming Paths
- Couch changes feed
- Graal VM events
- VM lifecycle
- Hermes console

---

## 11. MemoryStore + MemoryIndexLayer — CAS/Couch/ISAM

**Files**: `src/commonMain/kotlin/borg/trikeshed/memory/MemoryStore.kt`, `MemoryIndexLayer.kt`

### MemoryStore
- Composes `CasStore` + `CouchStore`
- `put(path, content)` → per-line spines + IPFS publication (via `IpfsBridge`)
- `get(cid)` → CAS fetch

### MemoryIndexLayer
- Subscribes to MemoryStore mutations
- Maintains ISAM routes:
  - `IndexKind.Taxonomy` — term → cids
  - `IndexKind.Temporal` — time → cids
  - `IndexKind.Provenance` — evaluator → cids

### CouchIndexBridge
- `attachmentGateway` → `memoryIndex` subscription
- Projects attachment mutations into ISAM

---

## 12. Git/Worktree Watchers — Reactive Filesystem

| Watcher | Root | Globs | Output |
|---------|------|-------|--------|
| `gitWatcher` | repoDir | `.git/**` | `cycleTriggers.trySend(Unit)` |
| `julesWalWatcher` | forgeHome | `jules-board.wal` | `cycleTriggers.trySend(Unit)` |
| `worktreeWatcher` | repoDir | all except .git/.gradle/.idea/build/node_modules | `worktreeReconcileElement.worktreeDirty.trySend(Unit)` + `cycleTriggers` |
| `buildWatcher` | build/classes, stagingLib | all | `buildDirty.trySend(Unit)` |

### Reconcile Elements
| Element | Input | Output |
|---------|-------|--------|
| `WorktreeReconcileElement` | `worktreeDirty` channel | `worktreeCouchGateway`, `couchIndexBridge`, `memoryBridge` |
| `GitReconcileElement` | `awaitObjectsDirty()` | `gitCouchGateway`, `couchIndexBridge` |

---

## 13. Jules Integration — Causal WAL

### JulesWalWatcher
- Watches `forgeHome/jules-board.wal`
- Each event → `cycleTriggers.trySend(Unit)`

### BrainClient
- `JulesConductor` for session management
- Routes through `KeyMux` for credentials
- Error sink: `JvmBrainErrorSink(forgeHome)`

### PatchWire
- ComfyUI patch-panel backend
- Full `KeyMux`/`ModelMux` access (provider-neutral, key-leased)
- Multi-project scope mounting via `ProjectScopes`

---

## 14. CAS / Couch / IPFS — Storage Layer

### CasStore
- Content-addressed (SHA256 + Blake3 = `ContentId`)
- Used by: BeliefBag WAL spills, MemoryStore, IpfsBridge, attachmentGateway

### CouchStore
- CouchDB 1.6 protocol over userspace.nio
- Databases: main (`oroboros`), per-project (mounted)

### IpfsBridge
- `IpfsBridge(casStore)` — publishes CAS objects to IPFS
- Used by `MemoryBridge` for memory-eligible files

---

## IO Adjacency Matrix

| From \ To | BeliefBag | CausalityRete | CuratorImpulse | TurnReview | Blackboard | Couch/CAS | ModuleSupervisor | KanbanHTTP |
|-----------|-----------|---------------|----------------|------------|------------|-----------|------------------|------------|
| **BeliefBag** | — | `projectLive()` snapshot | `intake.send(Mint)` | `intake.send(Mint/Reinforce)` | `beliefEvents` flow | WAL + CAS spills | `beliefBag` in ModuleContext | `/belief/*` via BeliefWire |
| **CausalityRete** | `intake.send(Mint)` | — | `register(term)` | — | `firings` flow | — | `rete` in ModuleContext | — |
| **CuratorImpulse** | `intake.send(Mint)` | `register(term)` | — | — | — | — | — | — |
| **TurnReview** | `intake.send(Mint/Reinforce)` | — | — | — | — | — | `turnReview` in ModuleContext | — |
| **ProjectDB Tendon** | — | `ReteNetwork.admit()` | — | — | — | Couch `_changes` | — | — |
| **Git/Worktree** | — | — | — | — | `cycleTriggers` | `CouchIndexBridge` | — | — |
| **Jules WAL** | — | — | — | — | `cycleTriggers` | — | — | `/patch/*` via PatchWire |
| **ModuleSupervisor** | — | — | — | — | receipt events | — | — | `/module/*` via ModuleWire |
| **KanbanHTTP** | — | — | — | — | — | — | routes from ModuleRouteRegistry | external clients |

---

## Backpressure & Boundedness Guarantees

| Component | Bound | Mechanism |
|-----------|-------|-----------|
| `BeliefBagElement.intake` | 256 | `Channel(256)` — caller suspends when full |
| `BeliefBagElement.hijack` | 4096 | `HijackBeliefBag(capacity)` — stochastic hijack at capacity |
| `CausalityReteElement._firings` | 1024 extra | `extraBufferCapacity = 1024` — drops oldest if consumer lag |
| `CuratorImpulseElement.teach` | synchronous | Returns landed list; caller decides pacing |
| `TurnReviewElement.intake` | 128 | `Channel(intakeCap)` — configurable |
| Kanban HTTP | 4096 | `maxRequestBatch = 4096` |
| ProjectDB tendon | since-seq | Couch long-poll naturally bounds |

---

## CAS Anchoring Points

| Artifact | CID Source | Verification |
|----------|------------|--------------|
| `SemanticSignal` | `ContentId.of(SignalCodec.encode(signal))` | Re-encode matches |
| `DerivationReceipt` | Canonical bytes of receipt struct | Deterministic encoding |
| `ReteFiring.firingCid` | `"${rule.ruleCid}|${matched.angular}|${consequentAngular}".encodeToByteArray()` | Deterministic |
| `RuleSetVersion` | Frozen alpha index + admission receipts | Immutable snapshot |
| `ContextBundle.bundleCid` | Canonical bytes of bundle content | Reconstructible |
| BeliefBag WAL frames | CRC32 + length prefix | Torn-tail truncate on replay |
| MemoryStore lines | Per-line spine + IPFS CID | Independent verification |

---

## Termination & Drain Order

All elements implement `AsyncContextElement` with `ElementState` lifecycle:
`CREATED → OPEN → ACTIVE → DRAINING → CLOSED`

Drain sequence (structured concurrency via parent `Job`):
1. Signal handlers cancel parent `Job`
2. `runBlocking` scope unwinds
3. Each element's `finally` block:
   - `BeliefBagElement.drain()` — flush WAL, await intake empty
   - `TurnReviewElement.drain()` — await in-flight reviews
   - `CausalityReteElement` — no explicit drain (stateless fire loop)
   - `CuratorImpulseElement` — no explicit drain
   - File watchers — `close()`
   - Kanban server — `kanbanJob.cancel()`
   - Module supervisor — `moduleScope.cancel()`
4. CAS/Couch connections close via `AutoCloseable`

---

## Future / Explicit Gaps

| Gap | Status | Notes |
|-----|--------|-------|
| Hermes production event → `CausalRecord` adapter | Not wired | `ProductionDoctrine.kt` types exist; adapter TBD |
| NARS insight → `ContextGapRequest` → ModelMux | Not implemented | Requires capability policy + receipt loop |
| Fibonacci/Huffman DAG layout | Rejected at compile time | `DagLayout.BINARY_POWER_OF_TWO` only; `FIBONACCI_HUFFMAN` throws |
| Real-time Rete → Curator explanation feedback | Partial | `firings` flow exists; Curator consumption not implemented |
| External tool execution from NARS | Forbidden by design | Must go through capability-gated ModuleSupervisor |

---

## Key Invariants (Tested)

- **Evidence never decays**: `BeliefBagElement` decays `BudgetCoord` only; `EvidenceCoord` immutable
- **Duplicate firing suppressed**: `CausalityReteElement.seenFirings` by `firingCid`
- **Rete self-loop prevented**: Consequent registered under `(consequent, antecedent)`
- **Overlap-safe revision**: `BeliefBagElement` uses `EvidenceBasis.leaves` exact ancestry + Bloom hint
- **Law admission requires receipt**: `CausalityRete.fromRuleSet(version)` requires `admissionReceipts.size > 0`
- **Dependent marking**: All Rete firings carry `dependence = DEPENDENT`; never `OBSERVATIONAL`
- **Binary DAG layout**: `DagBitTreeSkeleton.layout = BINARY_POWER_OF_TWO` serialized in RDF