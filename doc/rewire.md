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

- **Not a chat UI with a kanban skin.** The kanban is a projection over
  committed job state. The agents are job executors. The board is the
  ledger, not the interface.

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
