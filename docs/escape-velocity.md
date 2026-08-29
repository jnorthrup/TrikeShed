# Escape Velocity

The independence story: oroboros self-hosts its own git-CAS blobs, with a CRDT pijul gateway carrying patch lineage.

> **Launch prerequisite:** to spot-check any route mentioned here, boot a daemon. See [guide-daemon-launch.md](guide-daemon-launch.md).

## Bedrock Premise

Oroboros must achieve **escape velocity**: git objects served from its own content-addressed store, not an external host. This is the intrinsic premise of the whole program — the self-hosting use case is not "replication docs," it is the independence story. Wave 1 documents the current substrate honestly; wave 2 builds the target.

## What Exists Today

The existing specs are the ground truth:

- **[oroboros-service-spec.md](oroboros-service-spec.md)** — the seven contracts (C1–C7) and nine work packages (WP1–WP9). This page fronts that spec; it never reproduces its tables or paragraphs.
- **[oroboros-gap-analysis-2026-08-23.md](oroboros-gap-analysis-2026-08-23.md)** — the refined target cut along refined seams. Read it for the gap-by-gap survey.

## Substrate Inventory

### CAS Substrate (live)

The content-addressed store is the pivot — every identity column indexes the same bytes.

| Path | Role |
|------|------|
| `job/CasStore.kt` | CAS store interface |
| `cas/VolumeCasStore.kt` | LBA volume CAS |
| `jvmMain cas/MmapCasStoreJvm.kt` | Memory-mapped CAS (JVM) |
| `cas/LineCas.kt` | Line-level CAS: `contentCid`, `linkedKey`, `spineCid` |
| `cas/TreeCas.kt` | Fanout-k Merkle tree over line CAS |
| `cas/CasManifest.kt` | CAS manifest (IPNS → manifest CID) |

> **Status:** verified-live — all paths exist in-tree.

### Pijul Substrate (CRDT live, gateway shells out)

| Path | Role |
|------|------|
| `pijul/PatchPrimitives.kt` | Commutative patch primitives |
| `pijul/PatchStorage.kt` | Patch persistence |
| `pijul/PijulChannel.kt` | Channel model (branches) |
| `pijul/PijulDiffParser.kt` | Diff parsing |
| `crdt/PijulCrdt.kt` | Pijul CRDT over attachments — **live** |
| `cas/FunnelResidualMerge.kt` | N-way spine merge |

The `PijulCrdt` is live — it applies patches and produces spine CIDs. However, `PijulVersionGateway` (in `util/oroboros/VersionGateway.kt`) shells out to an external `pijul` CLI for init/record operations. This is the current limitation.

> **Status:** verified-live — `PijulCrdt.kt` exists and is used; `VersionGateway.kt` CLI dependency is honest.

### Git Lane (partially live)

| Path | Role |
|------|------|
| `util/oroboros/GitCouchGateway.kt` | Mirrors `.git/**` as opaque bytes into Couch |
| `util/oroboros/element/GitReconcileElement.kt` | Reconcile step — **inert** |
| `util/oroboros/VersionGateway.kt` | Pijul-flavored init/record |
| `util/oroboros/element/WorktreeReconcileElement.kt` | Worktree reconcile — **inert** |

The absorber exists (`GitCouchGateway`, `WorktreeCouchGateway`) but `GitReconcileElement` and `WorktreeReconcileElement` carry the header:

```
Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
```

> **Status:** degraded — reconcile elements are inert pending CCEK. See [ccek-covenant.md](ccek-covenant.md).

### IPFS Lane (inert)

| Path | Role |
|------|------|
| `cas/IpfsBridge.kt` | CAS blocks as IPFS blocks — in-memory IPNS, live |
| `cas/IpfsAdapter.kt` | Live IPFS via Kubo HTTP API — **inert** |
| `htx/client/ipfs/CidAndStore.kt` | CID + BlockStore |
| `couch/CouchWireRouter.kt` | `/api/v0/block/*` → `_cas/*` aliases (commonMain) |
| `src/commonMain resources/openapi/couch-oroboros.openapi.yaml` | OpenAPI spec |

`IpfsAdapter.kt` line 1:
```
Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
```

`CasReplicationElement.kt` line 1:
```
Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
```

Both are inert pending a complete CCEK implementation. The `IpfsBridge` (in-memory IPNS registry) is live for local use, but the Kubo-backed `HtxIpfsAdapter` does not connect to a running IPFS daemon.

> **Status:** degraded — IpfsAdapter and CasReplicationElement are inert. IpfsBridge is live for local-only use.

## The Absorber (incomplete)

Per the gap analysis (2026-08-23, §3), the absorber's reconcile step between gateway and store is marked inert. The file watchers fire, the gateway mirrors bytes, but the reconcile step that would commit those bytes into the Couch store as attachment docs is not wired. The gap analysis recommends: "Verify/un-inert reconcile."

> **Status:** degraded — Absorber incomplete per gap analysis.

## CAS Routes on the Wire

The `_cas/{cid}` route serves raw CAS blocks. The IPFS-compatible alias `/api/v0/block/{cid}` routes to the same handler (defined in `CouchWireRouter.kt`, commonMain). Both return the raw bytes for a given CID.

> **Status:** verified-live — route aliases exist in `CouchWireRouter.kt`.

## Wave-2 Target Statement

The headline deliverable for wave 2: **git blobs self-served from the CAS store, improved pijul gateway.**

This means:
1. Git objects (blobs, trees, commits) decoded from the absorbed `.git/**` bytes and served as CAS citizens — no external `git` process.
2. The pijul gateway no longer shells out to a CLI; patch operations run through `PijulCrdt` directly.
3. The inert CCEK elements (`IpfsAdapter`, `CasReplicationElement`, reconcile elements) are un-inerted by giving them a CCEK owner.

See [ccek-covenant.md](ccek-covenant.md) for why CCEK completion is the unblock for wave 2.
