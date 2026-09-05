# Forge Rewire — User-Centric Workspace Architecture

> **What Forge is:** a local-first, mesh-native workspace where documents,
> boards, and knowledge graphs are the same thing. The blackboard IS the
> database. The Rete engine IS the inference layer. The force-directed
> graph IS the UI. The VFS is btrfs-on-JBOD with COW snapshots. The VCS
> gateway is pijul<->git over CAS/IPFS/IPNS.
>
> **What this document is:** the architecture rewire. Not vaporware —
> every claim maps to code that exists or is one focused cut away.

---

## North star — the CCEK city and the unified grand blackboard

This section is newly explicit, not a new intention. The unified grand
blackboard has been the project's direction since its 1990s lineage; the
current CCEK, LCNC, Confix, Graal, and camera vocabulary finally makes the
runtime contract precise enough to state directly.

The ultimate design is not a collection of dashboards. The **actual CCEK
reactor is the city**: contexts, elements, keys, channels, fanout, lifecycle,
backpressure, capabilities, jobs, guests, storage, pointcuts, and causal
traffic are the real process topology. LCNC is the compositional grammar and
view layer over that city. The grand blackboard is the inclusive fact and
projection plane through which the whole runtime becomes inspectable.

Oroboros is the city's **absorber, delta ledger, and replicator**. It is not a
web shell that happens to watch a directory. Its comprehensive role is to
observe file and runtime change planes, content-address the changed material,
land ordered Couch/CAS citizens and receipts, drive downstream facts/views,
and make the resulting state replicable to another Oroboros.

```text
actual runtime truth
  CCEK Context -> Element -> Key
       + channels / fanout / queues / lifecycle / pressure
       + jobs / agents / LCNC programs / VMs / storage / pointcuts
                         |
                         v
  typed realtime facts + causal coordinates + stable identities
                         |
                         v
              UNIFIED GRAND BLACKBOARD
                         |
                         v
       LCNC facets, sheets, reducers, and compositions
                         |
                         v
  one zoomable fractal camera and dimension system
       |- city / whole-runtime flow
       |- districts / rings / workgroups / subsystems
       |- elements / programs / boards / guests / stores
       |- channels / causal edges / queue pressure
       `- messages / receipts / pointcuts / bytes
```

### Oroboros absorption and replication invariant

```text
worktree/source/doc delta --+
.git identity/history delta -+
class/resource/jar delta -----+--> CAS content identity
agent/environment delta ------+         + Couch revision/sequence
CCEK/runtime event -----------+         + causal/blackboard fact
                                         + _changes frame
                                         `--> peer replica
```

**Every material delta becomes a named, content-addressed, causally located,
replicable citizen.** Reinstallable bulk or sensitive payloads may have an
explicit retention/redaction policy, but a filter is policy—not permission to
create an invisible second runtime. At minimum, the citizen and the fact that
its delta occurred remain representable in the grand-blackboard ontology.

Current code already establishes the major planes:

- a worktree watcher and reconcile path absorb source and document changes;
- a separate `.git/**` watcher/gateway preserves repository identity and
  history without confusing it with worktree content;
- narrow build-plane watchers re-absorb `build/live/classes` and
  `build/staging/lib`, while processed resources and the hotswap agent ride the
  bootable classpath manifest;
- initial agent-home reconciliation carries selected colocated history;
- Couch `_changes`, `_bulk_docs`, `_revs_diff`, `_local`, CAS bulk transfer,
  and `_replicate` make absorbed citizens portable between nodes;
- committed Couch frames already feed report events and Rete facts.

Comprehensive is the architectural invariant, not a claim that every plane is
finished today. External homes that are only reconciled once, ephemeral VM and
in-memory CCEK events, filtered runtime churn, and interrupted replication are
the remaining edges to make continuously observable and recoverable. A file
watcher alone cannot see an in-memory channel; CCEK fortification supplies
those runtime deltas to the same absorption/replication contract.

### Non-negotiable invariants

1. **Reactor truth precedes visualization.** The UI never invents a process
   topology. It projects the running CCEK elements and their real channels.
2. **LCNC remains the view/composition language.** Kanban, operational sheets,
   procedures, panels, and future facets are LCNC assets over reactor truth,
   not detached applications.
3. **One grand blackboard, no architectural exclusions.** If the runtime can
   name an element, channel, capability, job, guest, document, pointcut, or
   receipt, the blackboard can represent it. An unknown type degrades to a
   generic typed node; it never disappears because a bespoke renderer is
   absent.
4. **One dimension system.** Identity, containment, causality, time, flow,
   pressure, and scale have stable coordinates shared by every view.
5. **Graal RTS is a coherent facet, not another world.** Its terrain, density,
   zoom, pointcuts, heap, and CAS topology occupy the same coordinate and
   camera grammar as CCEK process flow, LCNC, Kanban, agents, and storage.
6. **Semantic zoom is fractal.** Zoom changes the projection aperture, not the
   truth: city -> district -> element -> channel -> event -> byte. Each level
   retains causal and containment links to the levels above and below.
7. **Realtime means committed deltas.** Views advance from ordered reactor,
   blackboard, WAL, and `_changes` facts. Polling snapshots may recover or
   hydrate, but they are not a parallel source of truth.
8. **Access control is a lens, not a fork.** Capabilities may redact payloads or
   actions for a viewer; they do not create a second ontology or state model.

### Dimensional coherence

The current consolidation contract and acceptance evidence are maintained in
[Landscape consolidation](landscape-consolidation.md). The north star above
states direction; that ledger distinguishes verified paths from open work.

Every projected citizen needs the same minimum coordinates:

| Dimension | Meaning |
|---|---|
| identity | Stable CCEK key, NUID, CID, job id, route id, or derived causal key |
| containment | Coroutine context, ring, workgroup, LCNC scope, VM, store, or project parent |
| causality | Producer/consumer, dependency, dispatch, commit, pointcut, and receipt edges |
| time | Monotonic sequence plus event/lifecycle time |
| flow | Channel direction, protocol, message class, and throughput |
| pressure | Capacity, depth, suspension, WIP, lease, stall, and failure state |
| scale | Fractal aperture at which the citizen aggregates or becomes individually visible |
| facet | LCNC/Confix projection vocabulary available for inspecting or operating it |

This is how the Graal RTS terrain and the process-flow city remain
dimensionally coherent: they share coordinates and camera behavior even when
their visual glyphs differ.

### CCEK fortification sequence

This is an architectural backlog, not live `doc/todo.md` intake:

| Id | Design cut | Proof obligation |
|---|---|---|
| GBB-000 | Make the Oroboros absorber census explicit across worktree, git, build/runtime, agent-home, and in-memory CCEK planes | Every configured plane reports coverage, exclusions/retention policy, last absorbed sequence, and replica watermark; an unclassified delta is a visible error |
| GBB-001 | Census every live CCEK element, key, lifecycle, and parent context with stable identity | Runtime census and blackboard census have matching identities |
| GBB-002 | Project channel/fanout topology and pressure as typed facts | Every bounded channel exposes producer, consumer, capacity, depth/suspension state, and causal sequence without changing channel ownership |
| GBB-003 | Unify lifecycle, dispatch, commit, failure, and drain events behind one causal coordinate contract | One event can be followed from ingress through elements/channels to receipt and durable frame |
| GBB-004 | Land the reactor census and deltas on the common blackboard contract | No subsystem-specific blackboard is required to see a live CCEK citizen |
| GBB-005 | Define LCNC facets over the common reactor/blackboard rows | LCNC can group, filter, compose, and operate the city without reconstructing state in a UI |
| GBB-006 | Provide realtime ordered delta and recovery projections | A late viewer hydrates once, follows committed deltas, and reaches the same watermark as the daemon |
| GBB-007 | Share semantic-zoom coordinates and camera contracts with Graal RTS | The same identity selected in terrain, process, Kanban, sheet, or pointcut view resolves to one citizen and causal neighborhood |
| GBB-008 | Make every specialized surface a facet of the grand blackboard | Kanban, Graal, agents, VMs, storage, documents, Rete, and routes pass a no-orphan projection census |
| GBB-009 | Prove no parallel mutable visual truth | UI state contains camera/selection only; operational state rehydrates entirely from reactor/blackboard facts |

The order matters: fortify and observe the real CCEK reactor first, project it
into the grand blackboard second, compose LCNC facets third, and render the
realtime fractal city last. The camera is allowed to be ambitious because it
never carries operational truth.

---

## 0. Storage Unification — One CID, Five Lenses

The foundational rule: **the bytes are the thing; the views are lenses,
not copies.** A CAS blob never gets materialized five ways. It gets stored
once as Confix bytes, and the tag inside the bytes decides which projection
applies. Everything else is a lazy read of the same content address.

```
cas.get(cid) → ByteArray                        (auxiliary CAS — the raw lens)
     │
     ├─ materialized   → the bytes exist in the store (LinearHashMap / mmap)
     │
     ├─ confixDoc(bytes) → ConfixIndex → cells   (reified — decode on demand)
     │
     ├─ tag == "btree-page"      → {keys[], values[], children[]}  (btrfs content)
     ├─ tag == "causal-node"     → {causalKey, deps[], payload}    (graph tree)
     └─ tag == "treedoc-manifest" → {docs[], frames[]}            (archive)
```

Three mechanisms make this work:

**1. Tag dispatch, not storage dispatch.** You don't decide "this blob is
a btree page" when you store it. You read the bytes, look at the Confix
tag/kind field, and project. This is the existing `ConfixIndexK<R>`
GADT-key pattern — `facet(TreeCursor)` gives a Cursor, `facet(CausalNode)`
gives a graph node, `facet(BtreePage)` gives a page. The key fixes the
result type; the bytes stay bytes. No parallel storage systems.

**2. Edges are CIDs, so the graph is free.** A graph node is a Confix doc
whose `deps` field is an array of CIDs. Traversal is
`cas.get(dep) → confixDoc → recurse`. The blackboard's causal graph becomes
CAS-backed for free — nodes are content addresses, edges are references
into the same store. Force-directed layout consumes this directly: CID =
node identity, deps = edge list. CAS dedup means two nodes sharing a
dependency literally share the blob — diamond structures are physical.

**3. btrfs semantics fall out of CIDs + COW discipline.** A btrfs tree is
a COW page tree whose root is a content address. `CowBPlusTree` already
does this: pages as Confix docs in CAS, root is a CID, checkpoint +
hydrate. Snapshot = record the root CID. Send/recv = walk two root CIDs
and emit pages reachable from one but not the other (shared pages have
identical CIDs). Compression is TreeDoc frame chunking. The "btrfs
content" isn't a separate format — it's Confix pages obeying the COW rule.

| Lens | Existing code | State |
|------|--------------|-------|
| auxiliary CAS | `CasStore.get(cid)` → bytes, digest-verified | done |
| materialized | `LinearHashMap<ContentId, ByteArray>` | done; `MmapCasStore` pending (T4) |
| reified | `confixDoc(bytes)` → index → `cell.reify()` | done |
| btrfs content | `CowBPlusTree` pages in CAS, root CID | done for job snapshots |
| graph trees | `BlackboardDagCausalGraph` | **in-memory, NOT CAS-backed** — the gap |
| Confix at rest | manifest via `cas.put(confixDoc)` | done (treedoc, job frames) |

**The one new piece:** a projection registry — `project(cid): Lens` where
`Lens = Raw | Cursor | BtreePage | CausalNode | Manifest`, dispatched on
the doc's tag. Sealed class + existing facet machinery. One file, no new
storage, no new formats. Turns "five systems that happen to share a CAS"
into "one store with five lenses." Task: T-CAS-PROJ-1 in `doc/todo.md`.

---

## 1. The Unified Surface (Blackboard + Rete + Types + UI)

The workspace is not a set of views over a database. It is one continuous
surface where the blackboard, the rule engine, the type system, and the
force-directed graph are the same thing seen from different angles.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE BLACKBOARD SURFACE                                              │
│                                                                      │
│  One Confix document. One Cursor. Every projection is a slice.       │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │
│  │   Page      │  │   Board     │  │   Graph     │  │  Gallery   │ │
│  │  (blocks)   │  │  (cards)    │  │  (force)    │  │  (widgets) │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────┬──────┘ │
│         └─────────────────┴────────────────┴────────────────┘        │
│                         BlackboardSurface                            │
│                    confixDoc(persistedJson) → Cursor                 │
│                         project(cursor) → rows                        │
├──────────────────────────────────────────────────────────────────────┤
│  RETE INFERENCE   (the rule engine IS the type system)               │
│                                                                      │
│  ReteWorkingMemory  ← facts are typed (TypeEvidence → IOMemento)    │
│  ReteAlphaMemory    ← predicates dispatch on type tags               │
│  ReteBetaMemory     ← joins on facet identity (leftFacetId=right)    │
│  ReteAgenda         ← salience↓, sequence↑, deterministic pop        │
│  ReteRefraction     ← one firing per (ruleVersion, supportCIDs)      │
│                                                                      │
│  The Rete engine does not just fire rules. It infers semantic types  │
│  from structure and dispatches work to the right handler:            │
│  - TypeEvidence.sample(bytes) → IOMemento type code                  │
│  - ConfixIndexK facet plan → typed ColumnMeta per column             │
│  - TypeDefOracle lattice → IsA edges → semantic subtyping            │
│  - Rete rules match on type + structure, not just value              │
│                                                                      │
│  Type dispatch is not a switch statement. It is the alpha memory     │
│  of the Rete network — predicates are type predicates, and the       │
│  network routes facts to the right beta node by type identity.       │
├──────────────────────────────────────────────────────────────────────┤
│  FORCE-DIRECTED UI   (the graph IS the workspace)                    │
│                                                                      │
│  BlackboardDagCausalGraph — nodes are cards, edges are causal links  │
│  ForgeBlackboardCamera — momentum, tilt, zoom, RTS surface           │
│  ForgeBlackboard3D — true 3D orbit, elevation per section            │
│                                                                      │
│  The force layout is not a visualization. It is the workspace.       │
│  Cards attract their dependencies. Documents repel their neighbors.  │
│  The camera momentum carries you through the graph. Sections sit at  │
│  different elevations (gallery above board above page).              │
│                                                                      │
│  Click a card → it expands into a document. Drag a card → it        │
│  reorders its column. Zoom out → the board becomes a constellation. │
│  The physics is the UX.                                               │
├──────────────────────────────────────────────────────────────────────┤
│  SEMANTIC TYPE INFERENCE   (types emerge from structure)             │
│                                                                      │
│  TypeEvidence.sample(Series<Byte>) → deduced IOMemento              │
│  - Statistical analysis of byte patterns                             │
│  - Deduces: IoByte, IoInt, IoLong, IoFloat, IoDouble, IoString,     │
│    IoChar, IoBoolean, IoByteArray, IoMap, IoArray                   │
│                                                                      │
│  TypeDefOracle — typedef lattice from Confix schemas                 │
│  - IsA edges: "Person isA Entity", "Employee isA Person"            │
│  - Lattice: transitive closure, supertypes, subtypes                 │
│  - Used by Rete for rule matching: "fire when fact isA Person"      │
│                                                                      │
│  ConfixFacetPlan — compiled from job-nexus.schema.json               │
│  - Typed columns: each column has IOMemento type + ColumnMeta        │
│  - Facet dispatch: ConfixIndexK<R> keys fix the result type          │
│  - No runtime casts at the call site — the key IS the type           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. The Storage Layer (btrfs-on-JBOD, VFS emulation)

The VFS is not a filesystem. It is a content-addressed block store with
btrfs semantics running on JBOD (just a bunch of disks). The kernel
algebra treats it as `Series<Byte>` all the way down.

```
┌──────────────────────────────────────────────────────────────────────┐
│  BTRFS(TINY) ON JBOD                                                 │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  VFS SURFACE   (what the workspace sees)                        │ │
│  │                                                                 │ │
│  │  /workspace/                                                    │ │
│  │  ├── documents/        ← TreeDoc archives (CAS + manifest)      │ │
│  │  ├── boards/           ← KanbanFSM state (Confix doc)           │ │
│  │  ├── graphs/           ← BlackboardDagCausalGraph (cursor)      │ │
│  │  ├── snapshots/        ← COW snapshots (btrfs-style)            │ │
│  │  └── volumes/          ← JBOD volume mounts                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  BTRFS SEMANTICS   (copy-on-write, snapshot, send/recv)         │ │
│  │                                                                 │ │
│  │  CowBPlusTree — COW pages in CAS                                │ │
│  │  - Every write is a new page, never in-place                    │ │
│  │  - Pages addressed by ContentId (SHA-256)                       │ │
│  │  - Checkpoint = root CID + sequence number                      │ │
│  │  - Recovery = hydrate from checkpoint + replay tail              │ │
│  │                                                                 │ │
│  │  Snapshot = freeze the root CID. The tree is immutable.          │ │
│  │  Send/recv = serialize the delta between two CIDs.               │ │
│  │  Compression = frame-level (TreeDoc maxFrameBytes chunks).       │ │
│  │  Deduplication = same bytes → same CID → stored once.           │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  JBOD ARRAYS   (arrays of arrays, no RAID)                      │ │
│  │                                                                 │ │
│  │  Volume { blockSize, capacity, read(lba, count), write(lba,    │ │
│  │  data), sync() }                                                │ │
│  │                                                                 │ │
│  │  JBOD = N volumes, each with its own block size and capacity.   │ │
│  │  The array of arrays is the storage topology:                   │ │
│  │  - Volume 0: NVMe SSD (fast, small) → hot CAS blocks            │ │
│  │  - Volume 1: HDD (slow, large) → cold CAS blocks                │ │
│  │  - Volume 2: tmpfs (ephemeral) → WAL tail, not yet committed   │ │
│  │                                                                 │ │
│  │  LiburingVolume — io_uring-backed block device                  │ │
│  │  PosixVolume — POSIX fd-backed block device                     │ │
│  │  IndexedDbVolume — browser IndexedDB block device               │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  CAS/IPFS   (content addressing as the naming layer)            │ │
│  │                                                                 │ │
│  │  CasStore — LinearHashMap<ContentId, ByteArray>                 │ │
│  │  - SHA-256 CID → blob                                           │ │
│  │  - Digest verification on read                                  │ │
│  │  - put(doc) → canonical CBOR → CID                              │ │
│  │                                                                 │ │
│  │  IPFS bridge (todo, jules):                                     │ │
│  │  - CAS blocks are IPFS blocks (same CID → same content)         │ │
│  │  - IPNS names point to CAS manifest CIDs                        │ │
│  │  - Workspace publish = IPNS update to latest ArchiveId          │ │
│  │  - Workspace sync = IPFS pin + IPNS resolve                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. The VCS Gateway (pijul ↔ git ↔ IPFS)

The workspace is versioned. Every document, every board state, every
graph is a snapshot in a COW tree. The VCS gateway bridges three
naming systems:

```
┌──────────────────────────────────────────────────────────────────────┐
│  VERSION CONTROL GATEWAY                                             │
│                                                                      │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │   pijul      │◄──►│     git      │◄──►│  IPFS/IPNS   │          │
│  │  (patches)   │    │  (objects)   │    │  (blocks)    │          │
│  └──────────────┘    └──────────────┘    └──────────────┘          │
│                                                                      │
│  pijul side:                                                         │
│  - Patches are Confix documents (typed, canonical, deterministic)   │
│  - Dependencies are causal edges in the blackboard graph            │
│  - Cherry-pick = cursor projection over the patch DAG               │
│  - The blackboard IS the patch repository                           │
│                                                                      │
│  git side:                                                           │
│  - Objects are CAS blobs (blob = content, tree = manifest,          │ │
│    commit = snapshot root CID)                                      │
│  - Refs are IPNS names (mutable pointer to immutable CID)           │
│  - git push = IPFS pin + IPNS update                                │
│  - git pull = IPNS resolve + IPFS fetch                             │
│                                                                      │
│  IPFS/IPNS side:                                                     │
│  - IPFS blocks are CAS blobs (same SHA-256 → same CID)              │
│  - IPNS names are workspace pointers (/ipns/workspace-alice)        │
│  - Pinning = keep the CAS block alive (don't GC)                    │
│  - Publishing = IPNS update to the latest snapshot CID              │
│                                                                      │
│  The gateway is not a sync tool. It is a naming bridge.             │
│  pijul names patches, git names objects, IPNS names workspaces.    │
│  The CAS is the common ground — same content, same CID.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. The Agent Layer (Modelmux + Kanban + Jules)

Modelmux agents are job executors that use LLMs for reasoning. The kanban
is the visible trace of their conversations. Jules is the remote executor.

```
┌──────────────────────────────────────────────────────────────────────┐
│  AGENT WORKFLOW                                                      │
│                                                                      │
│  User types "fix the login bug" into the board                       │
│    │                                                                 │
│    ├─ JobCommand.Submit(jobId, idempotencyKey)                      │
│    ├─ Kanban card appears in "triage" column                        │
│    │                                                                 │
│    ├─ ReteNetwork fires: "new card in triage → route to agent"      │
│    ├─ ModelMuxBuilder.route("chat", requiredCaps=["tools"])         │
│    ├─ Agent picks up the card                                       │
│    ├─ JobCommand.Start(jobId)                                       │
│    ├─ Card moves to "active"                                        │
│    │                                                                 │
│    ├─ Agent reads code, forms hypothesis                            │
│    ├─ JobCommand.Progress(jobId, 0.3)                               │
│    ├─ Card updates progress bar                                     │
│    │                                                                 │
│    ├─ Agent asks clarifying question                                │
│    ├─ JobCommand.Block(jobId, "need clarification")                 │
│    ├─ Card moves to "blocked", question appears in comments         │
│    │                                                                 │
│    ├─ User answers in the board                                     │
│    ├─ JobCommand.Progress(jobId, 0.7)                               │
│    ├─ Card unblocks, agent continues                                │
│    │                                                                 │
│    ├─ Agent writes fix, opens PR                                    │
│    ├─ JobCommand.Complete(jobId, prUrl)                             │
│    ├─ Card moves to "review"                                        │
│    │                                                                 │
│    ├─ CI passes, PR merged                                          │
│    ├─ JobCommand.Ack(jobId)                                         │
│    ├─ Card moves to "done"                                          │
│    │                                                                 │
│    └─ The blackboard graph grows a new causal node                  │
│       linking the card to the commit to the files changed           │
│                                                                      │
│  Jules sessions are the remote execution surface. Each Jules job    │
│  is a JobCommand with a typed payload (the ArchiveId of the work    │
│  package). The job reads from CAS, produces Confix rows, commits    │
│  through the supervisor. The projection rebuilds. The board moves.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. The Ingest Pipeline (Corpus → Workspace)

Drop a document. Get a workspace. Progressive rendering by Jules jobs.

```
Document dropped (PDF, markdown, CSV, image)
  │
  ├─ 1. DETECTION (suffix + magic bytes)
  │     Tika (JVM) or suffix-based fallback
  │     TypeEvidence.sample(bytes) → IOMemento type code
  │
  ├─ 2. STORAGE (TreeDocPipeline)
  │     Split into frames (deterministic maxFrameBytes)
  │     CAS.put(frame) → ContentId (SHA-256)
  │     Build manifest → ArchiveId
  │     Snapshot the workspace root CID (btrfs-style COW)
  │
  ├─ 3. TYPE INFERENCE (semantic, not just MIME)
  │     TypeDefOracle: extract IsA edges from structure
  │     ConfixFacetPlan: compile column types from schema
  │     ReteAlphaMemory: match on inferred type, not just value
  │
  ├─ 4. PROJECTION (ConfixDoc → Cursor → BlackboardSurface)
  │     Document cursor: path, mediaType, cid, firstFrameOrdinal, frameCount
  │     Frame cursor: docOrdinal, frameOrdinal, offset, length, chunkCid
  │     BlackboardSurface.project(cursor) → UI rows
  │
  ├─ 5. PROGRESSIVE RENDERING (Jules jobs, one per enrichment pass)
  │     Job 1: extract structure → ForgeDoc block tree
  │     Job 2: extract metadata → property database rows
  │     Job 3: extract references → causal graph edges
  │     Job 4: generate summary → card on the board
  │     Job 5: semantic typing → Rete facts (this isA that)
  │     Job N: cross-link → mesh sync, IPFS pin
  │
  ├─ 6. WORKSPACE UPDATE (the surface rebuilds)
  │     Page gets new blocks
  │     Board gets new cards
  │     Graph gets new nodes (force-directed layout adjusts)
  │     Gallery gets a preview card
  │     All projections rebuild from committed state
  │
  └─ 7. VERSIONING (pijul/git/IPFS gateway)
       Snapshot the workspace root CID
       Publish to IPNS (/ipns/workspace-alice)
       Push to git (objects = CAS blobs, refs = IPNS names)
       The corpus is versioned, the workspace is a snapshot
```

---

## 6. The Mesh Layer (Litebike + SSH + UPnP)

The mesh is how workspaces find each other and sync. Discovery is
passive (UPnP/SSDP announce). Transport is encrypted (SSH tunnels).
The litebike listener is the single bind point.

```
┌──────────────────────────────────────────────────────────────────────┐
│  MESH TOPOLOGY                                                       │
│                                                                      │
│  ┌─────────────┐         ┌─────────────┐         ┌─────────────┐  │
│  │  Laptop     │◄───────►│  Desktop    │◄───────►│  Server     │  │
│  │  (browser)  │  mDNS   │  (daemon)   │   SSH   │  (daemon)   │  │
│  └─────────────┘         └─────────────┘         └─────────────┘  │
│                                                                      │
│  Discovery: UPnP/SSDP on 239.255.255.250:1900                       │
│  - NOTIFY * HTTP/1.1                                                │
│  - NT: urn:trikeshed:workspace:1                                     │
│  - USN: uuid:workspace-<nuid>                                       │
│  - Each node announces presence + capability set                    │
│                                                                      │
│  Transport: SSH tunnels over litebike Tls protocol                  │
│  - Each node runs a lightweight SSH server                          │
│  - Mesh peers authenticate via NUID (capability + nonce + subnet)   │
│  - Sync is Confix document replication over the tunnel              │
│  - The tunnel is the wire, the Confix doc is the payload            │
│                                                                      │
│  The litebike listener is the only bind point:                       │
│  - JvmLitebikeBindAdapter opens one AsynchronousServerSocketChannel│
│  - ProtocolDetector detects Http/Tls/Socks5/Bonjour/Upnp           │
│  - Bytes route to the matching CCEK slot                            │
│  - No framework, no embedded server, no spring boot                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 7. The User Experience (What It Feels Like)

You open Forge. You see a **force-directed graph** of your workspace —
cards, documents, and references arranged by causal proximity. The graph
has momentum. You can orbit it, zoom into it, tilt it.

You click a card. It expands into a document with blocks (text, headings,
todos, code). You type a task. It becomes a kanban card. A modelmux agent
picks it up, asks a question, writes code, opens a PR. The card moves
through columns as the work progresses. You never dragged it.

You drop a PDF. It becomes a document with extracted blocks, a set of
kanban cards for follow-up work, and a graph node linked to the source.
Jules jobs progressively render and tabulate the corpus — each pass
enriches the projection without human intervention.

You open Forge on your laptop. It discovers your desktop via UPnP/mDNS,
syncs the workspace over the SSH mesh, and continues where you left off.
The graph is the same. The cards are the same. The documents are the same.
The CAS is the common ground.

---

## 8. What This Is Not

- **Not a Notion clone with a database backend.** The "database" is a
  Confix document. The "queries" are cursor projections. The "indexes"
  are MultiIndexK facets. There is no SQL, no ORM, no migration.

- **Not a chat UI with a kanban skin.** Kanban is the LCNC work asset and
  realtime facet over committed job state. Agents are job executors; cards,
  sheets, gestures, and receipts remain ledger-grounded without reducing the
  board to storage or creating parallel visual truth.

- **Not a cloud service with a local cache.** The workspace is local-first.
  The mesh is additive. The cloud is a deployment target for the static
  shell, not a runtime dependency.

- **Not a framework.** No spring, no ktor, no embedded server. The
  litebike listener is the only bind point. The CCEK lifecycle is the
  only choreography. The kernel algebra is the only vocabulary.

- **Not a filesystem.** The VFS is a content-addressed block store with
  btrfs semantics on JBOD. Files are TreeDoc archives. Directories are
  Confix documents. Snapshots are frozen root CIDs. The array of arrays
  is the storage topology, not a RAID level.

---

## 9. Immediate Cuts (What Lands Next)

1. **Confix ingest adapter** — `ForgeKanbanIngest` already parses markdown
   into Rete facts + causal nodes + Kanban cards. Extend to accept
   TreeDoc archives (the corpus path).

2. **Modelmux kanban agent** — a JobCommand handler that routes cards
   through modelmux, tracks conversation state in the card payload, and
   commits state transitions through the supervisor.

3. **UPnP workspace discovery** — `JvmMulticastAdapter` already joins
   mDNS/SSDP groups. Add a workspace announcement payload (NUID + port +
   capability set) and a peer registry.

4. **SSH mesh transport** — litebike Tls protocol already exists. Add an
   SSH tunnel layer that carries Confix document replication between
   workspace daemons.

5. **IPFS/IPNS bridge** — CAS blocks are IPFS blocks (same SHA-256).
   IPNS names point to CAS manifest CIDs. Workspace publish = IPNS
   update to latest ArchiveId. Workspace sync = IPFS pin + IPNS resolve.

6. **Progressive rendering** — Jules jobs that read TreeDoc archives
   from CAS and project them into ForgeDoc block trees, property
   database rows, and causal graph edges. Each job is a JobCommand
   with the ArchiveId as payload.

7. **btrfs snapshot/restore** — `CowBPlusTree` already does COW pages
   in CAS. Add snapshot (freeze root CID), send (serialize delta
   between two CIDs), recv (apply delta to a target tree).

8. **Force-directed blackboard** — `BlackboardDagCausalGraph` already
   has the causal edges. Add the force layout (spring/electrostatic
   simulation) and wire it into the `ForgeBlackboardCamera` momentum
   model.

---

*This document is the architecture rewire. The blackboard is the
database. The Rete engine is the inference layer. The force-directed
graph is the UI. The VFS is btrfs-on-JBOD with COW snapshots. The VCS
gateway is pijul<->git over CAS/IPFS/IPNS. Every claim maps to code
that exists or is one focused cut away.*
