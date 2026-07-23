# TrikeShed doc/ Index — Curated (2026-07-22)

Source: `doc/archives/archive-2026-07-22.md` (13,066 lines, 762KB) — consolidated below.

---

## 1. Core Architecture Docs (LIVING)

| Doc | Purpose | Key Sections |
|-----|---------|--------------|
| **Concept Map** (`concepts.md`) | One-place Kotlin maintainer guide | Kernel algebra, Architecture spine (12 layers), Job Nexus, Confix, Couch, Collections, Choreography, Build/Deploy, Reading paths, Pitfalls |
| **Rewrite** (`rewire.md`) | User-centric Forge workspace architecture | Storage unification (1 CID, 5 lenses), Unified surface, Storage layer (btrfs), VCS gateway (pijul/git/IPFS), Agent layer (Modelmux/Kanban/Jules), Ingest pipeline, Mesh layer (Litebike/SSH/UPnP) |
| **Taste** (`taste.md`) | High-performance hierarchical UI engine principles | 10 principles, Meta-finding, Cut list (T1–T10 ranked by unblocking power) |

---

## 2. Audit & Gap Analysis (HISTORICAL)

| Refresh | Date | Key Findings |
|---------|------|--------------|
| **Concepts Gap Analysis** | 2026-07-19 | N1–N7 applied to `concepts.md`; N6 (LCNC) deferred; G1 (Oroboros) OPEN; G2 (Couch CID) PARTIAL; G5 (View server fork) CLOSED |
| **RGA: Resume→Narsese→Couch→Rete→Kanban** | 2026-07-20 | N1: 3 compile blockers (ViewServer, CreeperNode, LinearHashMap); N2: Manifold zero consumers; N3: ForgeKanbanIngest bypasses JobSupervisor; N4: Resume ingest dead; N5: Kanban parallel truth; N6–N8: ViewServer/LinearHashMap/CreeperNode bugs |

---

## 3. Merge Summaries (HISTORICAL)

| Date | Merges | Highlights |
|------|--------|------------|
| **2026-07-19** | 2 branches | ProcessReactorEndpoint (T12); Forge assets → `resources/web/` + `generateForgeAssets` |
| **2026-07-20** | 7 branches | IsamConfigStore/FeatureFlags/HotReload; HermesDonorTrace; BlackboardDag CAS-backed; GraphQuery/CausalGraphAdapter; JulesSyncConductor; Http3Session/Mplex/WsHttp3Mux; LcncFanoutElement |
| **Drain cycle** | 2026-07-20 | T01–T27 backfill; T-KANBAN-PERSIST-9 ADR |

---

## 4. Specifications (DESIGN DOCS)

| Spec | Scope |
|------|-------|
| **Upstream Creeper Node** | Capability-limited Forge agent on constrained envs (OpenWrt); KeyMux/ModelMux, NUID, Litebike, Reactor streams, CAS/Confix, Forge agent |
| **Libpijul KMP Port + Git Gateway** | Pure Kotlin CRDT patches; Git↔Pijul bidirectional sync; CasStore integration |
| **KeyMux + ModelMux Mesh** | Assignment-bound credential leasing (T-MESH-1), Mesh resource ads + ModelMux routes (T-MESH-2), WRT sentinel (T-MESH-3) |
| **Ingest: Confix/Cursor/CAS** | TreeDoc archive, Tika4all portable ingest, Camel decision, Jules split (J1 treedoc, J2 tika4all) |
| **Oroboros** | Forge state + CouchDB tree record; 1 project = 1 CouchDB record; reactor-bound file watcher |
| **PropertyType Decision** | Gap matrix + decision on LCNC PropertyType enum cases |
| **WASM Guest Investigation** | WASM guest execution on JS/WASM targets; ADR: T-KANBAN-PERSIST-9 |
| **T06 Btrfs Userspace JBOD** | Repository binding, BtrfsSuperblock, ChunkTree, DeviceTree, BtrfsVolume, test requirements |

---

## 5. Task Lists (EXECUTION)

| List | Location | Status |
|------|----------|--------|
| **Active todo** | `doc/todo.md` | 39 landable TDD tasks (80% project / 20% kanban-evolution) |
| **Accomplishment pins** | `doc/dont-redo` | 39 checked items — verified closures, merged branches, decisions |
| **Kanban evolution config** | `kanban-evolution.yaml` | 80/20 split, MAX_LIVE=15 → 12 project : 3 pin slots |

### Task Categories (from `doc/todo.md`)

**GATE-CONFIX-CBOR** (6) — CBOR encoder/decoder test vectors, round-trip, classpath boundary
**GATE-NGSCTP** (5) — TLV parser, bounded channel, association scope, partial reliability, liburing facade
**WIRE-* Zero-Consumer Packages** (6) — DHT, CRDT, Btrfs, DuckDB, Animation, Flags
**STUB-* Fill TODO() Stubs** (6) — CCEK, ISAM, Blackboard
**LCNC Reducers** (4) — RELATION, PEOPLE, FILES, Package split (T28)
**Build/Platform** (2) — Linux PosixProcessOperations, Series import shadow
**Kanban Evolution** (7) — Snapshot reads todo/dont-redo, land→done marking, dedup fingerprints, ANSWERER conventions file, DECOMPOSER role, git-based landed count, state.json checksums

---

## 6. Flywheel Operational State

| Metric | Value |
|--------|-------|
| MAX_LIVE | 15 |
| Live sessions | 15 |
| Queue depth | 13 |
| Landed (verified) | 6 |
| Harvested (dedup ledger) | 114 |
| State file | `~/.local/forge/flywheel/state.json` |

---

## 7. Quick Navigation

- **Start here (new maintainer):** `concepts.md` §0 Quick Orientation → §1 Kernel Algebra → `PRELOAD.md`
- **Architecture decisions:** `rewire.md` §0 Storage Unification → §9 Immediate Cuts
- **Performance gaps:** `taste.md` Cut list (T1–T3 core promise, T4–T6 storage/cursor, T7–T10 refinement)
- **Current work pool:** `doc/todo.md` (39 unchecked items)
- **What's done (don't redo):** `doc/dont-redo` (39 checked items)
- **Flywheel ratios:** `kanban-evolution.yaml`
- **Full history:** `doc/archives/archive-2026-07-22.md`