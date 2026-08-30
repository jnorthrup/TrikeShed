# Oroboros as a service — spec

**Target (Jim, 2026-08-21):** *oroboros should be a Confix-hosting, couch-shaped, ReportServer /
RequestFactory-enabled, file-watching git-repo absorber defining `project/$_id/attachments/*` ↔ git ↔
ipfs ↔ pijul, with map-reduce lazy regen, capable of fully hosting transparent public projects like
TrikeShed is.*

This decomposes that sentence into contracts, maps each onto what is in the tree today (surveyed
2026-08-21), names the gap, and orders the work. `gates.md` still governs what is green.

The headline finding: **almost every noun in the sentence already has a type.** The service does not
need new subsystems; it needs the existing ones joined under one path grammar, and four elements
un-inerted. The word that is genuinely new is *lazy*.

---

## 1. One sentence, seven contracts

| # | Clause | Contract |
|---|--------|----------|
| C1 | *couch shaped* | Documents keyed `_id`/`_rev`; `_attachments`; `_changes`; design docs with views. CouchDB wire shape, so PouchDB / `curl` work unmodified. |
| C2 | *Confix hosting* | The document body **is** a Confix doc / cursor (`confixDoc(json) → Cursor`). No parallel DTO truth. Views are Confix DSL trees, not JS eval. |
| C3 | *ReportServer / RequestFactory-enabled* | RxF-lineage: a request is a factory-produced typed unit that resolves to a report (a `Cursor` projection) over the store; CCEK-owned, pointcut-observable. |
| C4 | *file-watching git repo absorber* | A git work tree is ingested by watching it; every path becomes an attachment; `.git` is read as data, never via a `git` process. |
| C5 | *`project/$_id/attachments/*` ↔ git ↔ ipfs ↔ pijul* | One canonical path grammar. The same bytes carry a git oid, an IPFS CID and a pijul change identity; CAS is the pivot and the service is the bijection. |
| C6 | *map-reduce lazy regen* | Views are never materialised eagerly. An index regenerates on read, incrementally from `_changes` since its checkpoint, with a proof receipt. |
| C7 | *fully hosting transparent public projects* | TrikeShed itself (repo, `docs/`, Forge PWA, kanban, Jules drain state) is a project in the store; `docs/` on Pages is one projection. Every transition is public and replayable. |

---

## 2. Substrate map — what exists, what is inert, what is missing

Legend: ✅ live · 🟡 live but partial · ⛔ present but **inert** (head-commented *"Rejected malformed
Pijul materialization; intentionally inert pending a complete CCEK implementation"*) · ❌ absent.

### C1/C2 — couch + Confix
| | Path | Role |
|-|------|------|
| ✅ | `couch/ConfixDocStore.kt` | `_id`/`_rev` store over `ConfixDoc`; `RevPolicy{UUID,TIMESTAMP,SEQUENTIAL}`; optional `ConfixWal`; `indexBy=["_id"]` |
| ✅ | `couch/CouchStore.kt` | `Document = Join<DocId, Series<Field>>`, COW, `query(ConfixCursor)`, `CouchIngress.putIntent/deleteIntent(expectedRev)`, `CouchPersistence` |
| ✅ | `couch/ProductionCouchIngress.kt` | CAS-derived revs → commit boundary; MVCC conflict on stale `expectedRev`; no pre-commit visibility |
| ✅ | `couch/CouchHeadProjection.kt`, `CouchChangesProjection.kt` | replayable head; strictly monotonic `CouchCommittedFrame(sequence,docId,rev,deleted,doc)` — **this is `_changes`** |
| ✅ | `couch/isam/*` (`ConfixWal`, `DurableAppendLog`, `WalFrame`, `Stringpool`, `ConfixIsamIsomorphism`), `couch/persistence/DurableCouchPersistence.kt` | durability |
| ✅ | `parse/confix/ConfixKit.kt` (`confixDoc`, `JsPath`), `Confix.kt` (JSON/CBOR/YAML zero-copy index) | the cursor form |
| 🟡 | `graal/ConfixBlackboard.kt` | blackboard-as-Confix-cursor; documented caveat: `changes` emits 1-key rebuilds, `remove` is a no-op |
| 🟡 | `util/oroboros/CouchAttachmentGateway.kt`, `ProjectTreeAttachments.kt` | `OroborosAttachmentRef(path,contentType,length,…)`, `ProjectTreeAttachment(contentType,length,digest,data)` — attachment *values* exist; no `_attachments` stub/inline wire form |
| ❌ | — | `_rev` is not `N-hash` shaped; no `_bulk_docs`, `_all_docs`, `_local`, `_replicate` |

### C3 — ReportServer / RequestFactory
*Delta 2026-08-30: the package `utils/rfxhttp` is now `relaxfactory`, and the ❌ below is closed —
the factory is mounted on the router the daemon actually serves, over the same `CouchDatabase` as
every other route. What is written here is the state after that.*

| | Path | Role |
|-|------|------|
| ✅ | `relaxfactory/RelaxHttpServer.kt` | `interface RelaxHttpServer { store; viewServer; handleRequest(HtxRequest) }`, `RequestFactoryHandler.processRequest(payload)` |
| ✅ | `relaxfactory/CouchRequestFactory.kt`, `RelaxHttpServerJvm.kt` | RequestFactory batched-operation envelope — **the RxF lineage** |
| ✅ | `relaxfactory/RelaxStore.kt` | the port the factory resolves against: `RelaxStore.of(CouchDatabase)` is canonical (CAS revisions, `_changes`, CAS lanes), `RelaxStore.of(ConfixDocStore)` is `CouchHttpSurface`'s document-only binding |
| ✅ | `relaxfactory/RequestFactoryProxy.kt` | commonMain client proxy: `RelaxTransport.local(db)` for server-side state, `RelaxTransport.http(exchange, base)` for client-side state; typed `RelaxOp`/`RelaxReceipt`; compiles jvm/js/wasm/native |
| ✅ | `couch/CouchWireRouter.kt` | mounts the envelope at `POST /{db}/_relax` (and the sniffed `POST /{db}`) beside `_changes`/`_replicate`/`_cas` |
| ✅ | `couch/CouchReportReactorElement.kt` | CCEK owner for report execution. Corrected 2026-08-30: `MapEmitted`/`Reduced` were **declared with no producer anywhere** — only `Committed` ever reached the bus, so no view execution was observable. `ViewServer(report)` now emits both, and the daemon mounts the element on the router, so the `_view` route and the envelope `query` report through one observed engine |
| ✅ | `couch/ViewServer.kt` `ReduceFunction.Cascade`, `CouchCascade` | the confix cascade **among the reducer options**: `"reduce": "_cascade"` or `{"cascade":{"metrics":[…]}}` from a design doc or the envelope. Its metric list reaches `ReducerIdentity`, so two rollups over different fields cannot mint one receipt. Rereduce added for it and for `rollup-count`, which had been returning raw partials at `group=false`. `CouchCascade.METRICS`/`VIEWS` is now the one definition the `CouchDbCascadeTool` and the jvm JS generator both read |
| ✅ | `litebike/JvmKanbanServer.kt` | the one bind; `/api/*` built-ins, `/` → forge shell, static assets; `CouchWire` routes the db surface onto it |
| ✅ | `reactor/*` (`ReactorEndpoint`, `ReactorAlgebra`, `ReactorCodec`, SCTP/TLS/SSH-mesh, `openapi/`) | transport algebra |
| ✅ | `relaxfactory/ViewRoute.kt` `handle(ddoc, name, ViewQuery)` | a report **is** addressable as `_design/<ddoc>/_view/<name>` through the envelope: the `view` operation runs the `_view` route's own body, so both askers give one answer and only the parameter dialect differs |
| ✅ | `couch/ProjectPath.kt`, `couch/Projects.kt` | **the grammar is parsed, in one place**, and a project is an entity: a manifest document at the namespace root plus `_design`/`_local` reserved inside it. Envelope ops `project_put`/`project_get`/`project_list`/`project_docs`; route `GET /{db}/_projects[/{id}][?under=]`; the daemon declares its own heading at boot. **Kept plural and flat** (`projects/<id>/<path…>`) — see §3 note |

### C4 — file-watching git absorber
| | Path | Role |
|-|------|------|
| ✅ | `util/oroboros/JvmFileWatchReactorElement.kt` | `WatchService` on IO; **daemon already runs three**: `.git/**`, `jules-board.wal`, working tree |
| ✅ | `daemon/GitStateCache.kt` | HEAD/refs/packed-refs/index-mtime read as data; no `ProcessBuilder` |
| ✅ | `util/oroboros/GitCouchGateway.kt`, `WorktreeCouchGateway.kt` | mirror `.git` DB and work tree → Couch attachments + CAS (`FileCasStore`, sharded `sha256/<2>/<rest>` under `$forgeHome/cas`) — **the absorber exists** |
| ✅ | `util/oroboros/OroborosCoordinator.kt` | single mutation ingress, idempotent by `(agent, path, ContentId)` |
| ⛔ | `util/oroboros/element/GitReconcileElement.kt`, `WorktreeReconcileElement.kt` | the reconcile step between gateway and store — inert, still constructed by the daemon |
| 🟡 | `daemon/OroborosDaemon.kt` (818 L) | wires all of the above, plus HTX, torrent, kanban server, memory bridge; but the watchers feed the **flywheel**, the store is `CouchStoreFactory.inMemory()` |
| ❌ | — | no git object codec: loose objects/packs are mirrored as opaque bytes, never inflated/parsed; `git` column of an attachment cannot be derived |
| ⚠ | `jules/FlywheelDriver.kt`, `JulesDrainDedupeCli`, `JulesSettlementCli`, `QaLaguna`, `flywheel/cli/GapReducerCli`, `util/oroboros/OroborosMain`, `OroborosGitRestore`, `VersionGateway` | still shell out to `git` via `ProcessOperations` — the "no git process" rule is the daemon's, not yet the repo's |

### C5 — git ↔ ipfs ↔ pijul
| | Path | Role |
|-|------|------|
| ✅ | `cas/LineCas.kt` (474 L), `TreeCas.kt`, `FunnelResidualMerge.kt` (768 L) | line CAS (`contentCid`, `linkedKey`, `spineCid`), fanout-k Merkle over it, N-way spine merge |
| ✅ | `cas/ContentAddress.kt`, `CasManifest.kt`, `BlockIndex.kt`, `VolumeCasStore.kt`, `job/CasStore.kt`, `MmapCasStore`, `BtrfsCasStore`, `btrfs/BtrfsReflinkStore.kt`, `util/oroboros/Sha2CasBus.kt` | CAS stores (memory, mmap, LBA volume, btrfs reflink, sharded files) |
| ✅ | `cas/IpfsBridge.kt` | CAS blocks as IPFS blocks; IPNS name → `CasManifest` CID (in-memory registry); used via `MemoryBridge` |
| ✅ | `util/oroboros/MemoryBridge.kt` | Markdown → per-line CAS spine + IPNS publish; Couch IDs `projects/trikeshed/<relative>` — **the path grammar already exists in embryo** |
| ✅ | `htx/client/ipfs/CidAndStore.kt` | `CID(bytes)`, `CID.sha256`, `BlockStore` — uses `MessageDigest` in commonMain (on the JS-debt list) |
| ⛔ | `cas/IpfsAdapter.kt` (`HtxIpfsAdapter` → Kubo `/api/v0/block/*`), `cas/CasReplicationElement.kt` | the live-IPFS and replication hooks — inert |
| ✅ | `pijul/{PatchPrimitives,PatchStorage,PijulDiffParser,PijulChannel}.kt`, `crdt/PijulCrdt.kt` | commutative patch DAG, Blake3 vertices, incremental alive-order; `util/oroboros/VersionGateway.kt` is pijul-flavoured `init/record` |
| ✅ | `torrent/*` (BT v2 + uTP over HTX) | second bulk replication fabric, already in the daemon |
| ❌ | — | no git oid codec (sha1/sha256 over `"<type> <len>\0"`), no multihash / CIDv1 / DAG-PB / DAG-CBOR / IPLD; `ContentId` is raw sha256 |

### C6 — map-reduce lazy regen
| | Path | Role |
|-|------|------|
| ✅ | `couch/ViewServer.kt` (554 L) | `ViewDefinition(ddoc,viewName,mapFn,reduceFn)`; `MapFunction{Emit,EmitEach}` over `KeyExpr/ValueExpr{DocField,DocId,Const,JsPathExpr}` — Confix DSL, design-doc-shaped, parsed with `confixDoc` |
| ✅ | `viewserver/MapReduceProofReceipt.kt`, `CouchDbCascadeTool.kt`, `forge/view/ForgeViewProofProjection.kt` | content-addressed proof of a deterministic run: `ViewDefinitionIdentity`, `ReducerIdentity`, ordered source CIDs |
| ✅ | `jvmMain couch/viewserver/GraalVm{ViewServer,ViewServerHost,CursorHost}.kt`, `CouchCascadeView.kt` | polyglot host for JS-authored views (jvm-only) |
| ❌ | — | views run whole-store on demand; no `(seq, indexCid)` checkpoint, no incremental fold from `_changes`, no stale-ok |
| ⚠ | `jvmTest **/ViewServerTest.kt` | compile-excluded red debt |

### C7 — hosting TrikeShed itself
| | Path | Role |
|-|------|------|
| ✅ | `kanban/ForgeBoardPersistence.kt` | one canonical markdown source with `contentId`; derived state rebuilt by `ForgeKanbanIngest` — already the couch discipline (no second truth) |
| ✅ | `forge/ForgeApp.kt`, `jvmMain forge/ForgeBakePages.kt`, Gradle `generateForgePages`/`serveForgePages` | `docs/` as a baked projection |
| ✅ | `utils/kanban/JulesBoardStore*.kt` (`~/.local/forge/jules-board.wal`), `docs/JULES_DRAIN_CONTRACT.md`, `docs/gates.md` | public, replayable process state |
| ✅ | `couch/Projects.kt` | the `Project` entity: a manifest document at `projects/<id>`, `list`/`undeclared`/`documents`/`summary`. `undeclared()` names namespaces in use that nobody declared — the normal state for a gateway-minted prefix, surfaced rather than hidden |
| ❌ | — | `docs/` is written by a JVM `main`, not served by the store; Forge/kanban do not read from couch |

**End-to-end proof:** `jvmTest litebike/RelaxServiceE2eTest.kt` drives all of the above through one
real loopback socket — HTX client → litebike → `CouchWireRouter` — asserting the envelope at
`_relax`, a stored view addressed through it, the cascade as a design-doc reducer, the report bus
observing both askers, and a VM world's bytes agreeing across `_cas`, the IPFS alias and the
attachment route. A 200 is not the assertion; the bytes and the rollup numbers are.

### Guest VM worlds — file-based btrfs hosting (added 2026-08-30)
| | Path | Role |
|-|------|------|
| ✅ | `btrfs/UserspaceBtrfs.kt` | CoW extents in a `ContentId` CAS, snapshots sized by file count, send/receive that fails closed — the same hash convention as the couch CAS above |
| ✅ | `btrfs/BtrfsWorldStore.kt` | where a `world = true` guest's subvolume lives. **`ofFiles` is the file-based host**: one btrfs root on a real filesystem, one subvolume per guest, one shared extent store — so two guests holding the same bytes hold one copy. `ofMemory` names the old behaviour and keeps its per-guest isolation |
| ✅ | `graal/subvm/TrikeShedGraalVfs.kt` (`GraalBtrfsSupervisor`), `Hypervisor.worldStore` | the store threaded to the guest; `liveSubvolume = <guest id>`, which is what keeps guests on one root from being one world |
| ✅ | `daemon/OroborosDaemon.kt` | guest worlds under `$forgeHome/vm-worlds` |
| ⚠ | — | Corrected 2026-08-30: `GraalBtrfsSupervisor` took `TrikeShedGraalVfs`'s **in-memory default**, so every "btrfs-hosted" VM was RAM-hosted — the world died with the process and `snapshot()` wrote something nothing could read back. `BtrfsVolumeTest`/`BtrfsUserspaceVolume` remain a separate directory-shaped emulation, not this path |
| ✅ | `btrfs/VmWorldTeleport.kt`, `forge/server/VmWire.kt` | **a guest world replicates.** `publish` sends the subvolume, lands the stream as a CAS block, and writes an ordinary attachment document `vm-worlds/<guest>` naming it — so `referencedCids` makes `_replicate` carry it, `_cas/{cid}` and the IPFS alias serve it, `GET …/vm-worlds/<guest>/content` streams it, and a RequestFactory client pulls it with `block_get`. No VM-specific lane. `restore` receives it on the far node; `GET /api/vm/worlds`, `POST /api/vm/{id}/world/{publish,restore}` |

### Structural debt touching this spec
- `org/trikeshed/oroboros/OroborosDaemon.kt` — `expect` with an empty `actual` ("TOP PRIORITY, DRAIN ALL JVM CODE"); on `gradle/js-target-debt.excludes`.
- `**/util/oroboros/**` is excluded from the non-JVM test set; `classfile/slab/**` excluded from commonMain.
- `gradle/wasm-target-debt.excludes` is referenced by `build.gradle.kts` but absent on disk.

---

## 3. Canonical path grammar

```
project/<_id>/                         project document; body = Confix manifest (name, head, ipns, channels)
project/<_id>/attachments/<path...>    one attachment per work-tree path; bytes in CAS
project/<_id>/_design/<ddoc>           design docs: views / shows / lists as Confix DSL
project/<_id>/_changes?since=<seq>     CouchChangesProjection, SSE when feed=continuous
project/<_id>/_local/<name>            per-replica state (view checkpoints, peer cursors); never replicated
```
`MemoryBridge` already mints `projects/trikeshed/<relative>`; this grammar is that, singularised and
with the couch reserved segments added. `_id` of the TrikeShed project = `TrikeShed`.

**Implemented 2026-08-30 as plural and flat, deliberately.** `ProjectPath` parses what the store
actually holds:

```
projects/<id>                  the project document (manifest)
projects/<id>/_design/<ddoc>   design docs scoped to the project
projects/<id>/_local/<name>    per-replica state, never replicated
projects/<id>/<path…>          content, one document per work-tree path
```

The reserved segments above are new and real; the singularisation and the `attachments/` segment are
not, because `WorktreeCouchGateway.WORKTREE_PREFIX`, `MemoryBridge` and the vhost rewrites all mint
and resolve the plural flat form, so changing it renames every document id already committed to a
running daemon's couch. That is a migration to run deliberately, not a grammar to redefine under a
live store — `ProjectPath.CONTENT_SEGMENT` names the segment so the change stays one line in one
file when someone chooses to run it.

Identity columns (C5) on every attachment:

| Layer | Identity | Status |
|-------|----------|--------|
| CAS | `ContentId = sha256(bytes)`; line spine `spineCid`; `TreeCas` root | exists |
| git | blob oid = H(`"blob <len>\0"` ‖ bytes), tree/commit likewise | **new codec (WP3)**; stored, never recomputed via `git` |
| ipfs | CIDv1 = multihash(sha2-256) over raw leaf (≤ 256 KiB) or DAG-PB root | **new encoder (WP5)** — same digest as CAS when raw-leaf, so it is a re-encoding, not a re-hash |
| pijul | change hash over hunks; line identity = `LineNode.linkedKey` | `PijulCrdt` exists; binding to attachments is **WP7** |

`_rev` = `"<seq>-<ContentId hex prefix>"` — content-derived (`ProductionCouchIngress` already does
this) and wire-compatible with couch's `N-hash`.

```json
{ "_id": "project/TrikeShed/attachments/src/commonMain/kotlin/borg/trikeshed/cas/LineCas.kt",
  "_rev": "417-9c8a1b66",
  "cas":   { "cid": "9c8a1b66…", "spine": "…", "tree": "…" },
  "git":   { "blob": "252ab944…", "mode": "100644", "head": "dcf437f3…" },
  "ipfs":  { "cid": "bafkrei…", "pinned": false },
  "pijul": { "change": null },
  "_attachments": { "content": { "content_type": "text/x-kotlin", "length": 18405,
                                 "digest": "sha256-…", "stub": true } } }
```
Bytes live once, in CAS; every other column is an index over them.

---

## 4. Request pipeline (C3)

```
bytes ─HTX request line/headers─▶ CouchRequestFactory.make(method, path, headers, body)
      ▶ sealed Request { GetDoc, PutDoc, GetAttachment, PutAttachment, Changes, View, Show, BulkDocs, Replicate }
      ▶ CouchReportReactorElement.execute(request)                 (CCEK-owned, pointcut-observed)
      ▶ Report = Cursor  ─ReactorCodec─▶ JSON | multipart/related | SSE (_changes continuous)
```
- `CouchRequestFactory` is the **only** place the path grammar is parsed; it is mounted on
  `JvmKanbanServer` under `project/`. Existing `/api/*` routes become `_design/kanban` shows.
- A report is a `Cursor`, so `_view`, `_changes`, `_show/index` (Forge shell) and the gallery are the
  same mechanism at different ddocs. `docs/index.html` ≡ `GET project/TrikeShed/_design/forge/_show/index`.

---

## 5. Lazy regen (C6)

Per `(project, ddoc, view)` keep `_local/view-<ddoc>-<view> = { seq, indexCid }`.
On read: `delta = _changes(since=seq)`; map only changed docs; fold into the index. The index is a
`LineCas` spine (each emitted row is a linked key), so an incremental update is append + re-hash and
the `MapReduceProofReceipt` **is** the new spine CID. Reduce re-runs only over touched groups.
No read ⇒ no work. No background view builders. `?stale=ok` serves the checkpoint as-is.

This is what *transparent* means operationally: every served view carries a receipt anyone can replay.

---

## 6. Absorber (C4) — event flow, as it should be

```
JvmFileWatchReactorElement(.git/**)   ─▶ GitStateCache.invalidate*                  (exists)
                                      ─▶ GitObjectReader: HEAD moved → walk commit→tree,
                                         upsert `git` column on attachment docs      (WP3, new)
JvmFileWatchReactorElement(worktree)  ─▶ WorktreeCouchGateway → LineCas → FileCasStore   (exists)
                                      ─▶ WorktreeReconcileElement → OroborosCoordinator
                                         → ProductionCouchIngress.putIntent(attachmentDoc)  (⛔ un-inert, WP1)
                                      ─▶ CouchChangesProjection.append(frame)        (exists)
                                         ├─▶ lazy-regen checkpoints invalidate       (WP4)
                                         ├─▶ CasReplicationElement hooks: HtxIpfsAdapter.putBlock,
                                         │   TorrentElement seed, VersionGateway.record   (⛔ un-inert, WP5/7)
                                         └─▶ FlywheelDriver reacts to docs under project/*/kanban|jules  (WP2)
```
The flywheel becomes a **consumer of `_changes`** rather than the thing the watchers feed directly.
The daemon keeps its single-bind, no-JDK-networking, no-`git`-process discipline.

---

## 7. Work packages (gap-to-debt order)

| WP | Deliverable | Builds on | Gate (observable) |
|----|-------------|-----------|-------------------|
| WP1 | Un-inert `GitReconcileElement`/`WorktreeReconcileElement` → `OroborosCoordinator` → `ProductionCouchIngress`; attachment doc model with `_attachments` stub/inline; `_rev` = `seq-cid` | gateways, coordinator, ingress | touching a file under the repo yields a new `_rev` on its attachment doc within one watch tick |
| WP2 | Mount `CouchRequestFactory` on `JvmKanbanServer` under `project/<id>/…`; `_changes` (normal + SSE continuous); `_all_docs`; `_bulk_docs`; move `/api/*` to `_design/kanban` shows; `FlywheelDriver` subscribes to `_changes` | relaxfactory, litebike, `CouchChangesProjection`, `CouchReportReactorElement` | PouchDB `replicate.from(url)` of project `TrikeShed` completes; `curl …/_changes?feed=continuous` streams frames |
| | *2026-08-30: the mount is done — `POST /{db}/_relax` on `CouchWireRouter`, over the same `CouchDatabase` as `_changes`/`_replicate`/`_cas`, so an envelope put mints a CAS revision and replicates. `_changes`/`_all_docs`/`_bulk_docs`/`_revs_diff`/`_local`/`_cas`/`_replicate` also answer as envelope operations, and `RequestFactoryProxy` drives them from any Kotlin target. Still open in WP2: the `project/<id>/…` grammar and moving `/api/*` to `_design/kanban` shows.* | | |
| WP3 | `GitObjectReader`: loose (zlib) + pack/idx decoding, oid for blob/tree/commit; `git` column; HEAD walk on ref change | `GitStateCache`, `.git/**` watcher | attachment `git.blob` == `git rev-parse HEAD:<path>` for every tracked file; zero `ProcessOperations` use in the daemon path |
| WP4 | Lazy views: `_local` checkpoints, incremental fold from `_changes`, spine-CID receipts, `?stale=ok`; un-exclude `ViewServerTest` | `ViewServer`, `MapReduceProofReceipt`, `LineCas` | after 1 doc change the next `GET _view` maps exactly 1 doc, and the receipt proves it |
| WP5 | multihash + CIDv1 + DAG-PB encoders in commonMain (no `MessageDigest`); un-inert `HtxIpfsAdapter`/`CasReplicationElement`; `ipfs` column; IPNS name = project manifest CID | `CidAndStore`, `IpfsBridge`, `CasManifest` | `ipfs block stat <cid>` agrees with the stored CID for a raw leaf; `CidAndStore` leaves the JS-debt list |
| WP6 | `_design/forge/_show/index` renders `ForgeApp.renderHtml` from the store; `generateForgePages` = `GET` + write; kanban reads `project/TrikeShed/kanban/*` docs instead of `~/.local/reactor/kanban` | `ForgeBakePages`, `ForgeBoardPersistence` | `docs/index.html` byte-identical from the Gradle task and from HTTP |
| WP7 | Bind `PijulCrdt` to attachments: hunk identity = `LineNode.linkedKey`; `VersionGateway.record` on `_changes`; `pijul` column; channels = branches | `pijul/*`, `crdt/PijulCrdt`, `FunnelResidualMerge` | a `FunnelResidualMerge` and a `PijulCrdt` apply of the same change set agree on the spine CID |
| WP8 | `_replicate` (pull/push over `_changes`) with CAS bulk path via ipfs/torrent hooks; second node | `CasReplicationElement`, `TorrentElement` | second node converges to the same project manifest CID |
| WP9 | Drain `org/trikeshed/oroboros/OroborosDaemon` expect/actual stub; `util/oroboros/**` back into the non-JVM test set; create `gradle/wasm-target-debt.excludes` | forge-substrate-plan Phase 1 | `compileKotlinJs` error count for `util/oroboros/**` = 0 |

Non-goals until WP8 is green: auth / ACLs, a JS view runtime on the public path (GraalVM host stays
jvm-only, opt-in), any Gradle dependency in the deploy loop, any GitHub Actions workflow.

---

## 8. Rules this spec inherits

- commonMain: no `System.currentTimeMillis`, no NIO/selectors, no `MessageDigest`
  (`ConfixDocStoreEntry.timestamp` and `CidAndStore` currently violate this — WP1/WP5 fix them by
  taking the clock from the ingress and the digest from `ContentId`).
- No `git` subprocess on the daemon path (`GitStateCache` precedent); the Jules CLIs that still
  shell out are migrated as WP3 lands, not before.
- One canonical truth per thing: bytes in CAS, revisions in `_changes`, everything else an index.
- `docs/` is a committed projection served as-is by Pages; no Actions workflows.
- Inert elements are un-inerted by giving them a CCEK owner, never by deleting the guard comment.
