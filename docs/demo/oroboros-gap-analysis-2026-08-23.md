# Oroboros gap analysis — 2026-08-23

Target (Jim, 2026-08-23): the Forge PWA is hosted **and hoisted** by an m2m persistence store of
TrikeShed shape, carrying the **Couch 1.6–1.7 banner** forward — not as a secure database but as an
**embodiment platform**: the whole install/tooling replicates machine-to-machine to a sales laptop;
file-watching ReportServers do transforms, quantitative/qualitative metrics and solver tasks;
agent environments and their colocated history live in the same replicated document set; for
TrikeShed this extends to in-flight pointcuts and JVM AOT dumps.

Companion to `docs/oroboros-service-spec.md` (C1–C7, WP1–WP9). This document re-cuts the gap
along the *refined* target rather than the spec's contracts, and records what six tree surveys
found on this date. Line numbers are as surveyed; treat as pointers, not proofs.

## 0. One-paragraph verdict

The store exists and is richer than the spec admits: MVCC ingress with CAS-derived revs, a
replayable `_changes` log, a Confix-DSL ViewServer with proof receipts, a 1.6.2-shaped HTTP
surface, file watchers, git/worktree absorbers, a coordinator, a CAS, a Pijul CRDT, a VM tier
API, a hot-swap javaagent and JEP-484 pointcut extraction. What is missing is almost entirely
**wiring and wire-format**, not substrate: the Couch surface is not mounted on the production
server; nothing serves bytes *out of* the store; the `_changes` log has no HTTP feed; there is no
`_replicate`/`_bulk_docs`/`_revs_diff`/`_local`, so no second node and no browser replica; agent
homes and runtime classes sit outside the absorbed tree; and there is no AOT artifact at all. The
critical path to "PWA hoisted by the store" is two routes and one mount, not a rewrite.

## 1. Couch 1.6–1.7 surface

| 1.x feature | State | Where |
|---|---|---|
| `_id`/`_rev`, MVCC conflict | ✅ | `couch/ConfixDocStore.kt`, `couch/ProductionCouchIngress.kt` (rev = `gen-cidprefix`) |
| Head + changes projections | ✅ | `couch/CouchHeadProjection.kt`, `couch/CouchChangesProjection.kt` (`afterSequence`, subscribe) |
| `GET/PUT/DELETE /{db}/{id}`, db info, `_view` with full 1.6.2 params | ✅ | `utils/rfxhttp/CouchHttpSurface.kt:116-204` |
| Batched RequestFactory envelope | ✅ | `utils/rfxhttp/CouchRequestFactory.kt` |
| Views: map/reduce, `_count/_sum/_stats`, proof receipts, Graal JS host | ✅ | `couch/ViewServer.kt`, `viewserver/MapReduceProofReceipt.kt`, `couch/viewserver/GraalVmViewServer.kt` |
| `_rev` in `N-hash` shape | 🟡 | three RevPolicies, none the 1.x shape; ingress's `gen-cid` is closest |
| `_attachments` wire form (stub/inline/multipart) | ❌ | metadata only in `util/oroboros/CouchAttachmentGateway.kt` |
| `GET /{db}/{id}/{attname}` | ❌ | — |
| `_all_docs`, `_bulk_docs`, `_revs_diff`, `_local` | ❌ | — |
| `_changes?feed=longpoll\|continuous` over HTTP | ❌ | projection exists, no wire |
| `_replicate` (pull/push) | ❌ | — |
| `_design/*/_show|_list|_update|_rewrite` | ❌ | — |
| **Mounted on the production server** | ❌ | `CouchHttpSurface` rides `RfxHttpServerJvm`; production is `litebike/JvmKanbanServer.kt` (:8888) — two servers, no shared mount |

**Gap shape:** the 1.x *replication protocol* (`_changes` feed + `_revs_diff` + `_bulk_docs` +
`_local` checkpoints) is the banner's actual payload and is 0% on the wire. Everything it needs
underneath (monotonic sequence log, CAS revs, tombstones) is done.

## 2. PWA hoisting (CouchApp)

Today the shell exists in **three copies**: `docs/index.html` (baked by `bakeForgePages`,
`build.gradle.kts:666-725`), classpath `web/*` served by `JvmKanbanServer.kt:460-474`, and
`ForgeAssets.kt` byte-arrays generated at `build.gradle.kts:798-887`. `ForgeIngestServer.kt:54-59`
serves `docs/` from the filesystem. Kanban state is `~/.local/reactor/kanban/<user>.json`
(`ForgeBoardPersistence.kt:26-28`), outside the repo; the causal WAL is `.causal.wal` in cwd.

Nothing serves bytes from the store. But `WorktreeCouchGateway` (prefix `projects/trikeshed/`)
already absorbs `docs/**` when reconcile runs — the attachments *are* in the store; there is no
route to read them.

Browser side: state in `localStorage` (`forge.workspace.v2`), offline queue in IndexedDB posting to
`/api/invoke` (`sw.js:43-138`). This is a hand-rolled half of replication; a PouchDB-shaped
`_bulk_docs`/`_changes` pair would replace it and make the browser a replica.

**Gap list:** attachment GET route; `_rewrite` (or a fixed rule) `/ → projects/trikeshed/docs/index.html`;
`_design/forge` shows for `/api/*`; kanban and causal WAL as docs in the store rather than home-dir
files; browser replication instead of the sync-queue.

## 3. Absorber (filesystem ↔ store ↔ git/pijul/ipfs)

Live: three `JvmFileWatchReactorElement`s (`daemon/OroborosDaemon.kt:327-386`: `.git/**`, jules WAL,
worktree), `GitStateCache`, `GitCouchGateway` (mirrors `.git/**` as opaque bytes), `WorktreeCouchGateway`,
`CouchAttachmentGateway`, `OroborosCoordinator` (single ingress, idempotent by `(agent,path,cid)`),
`FileCasStore` sharded `sha256/xx/…`, `PijulCrdt` + `FunnelResidualMerge`, `IpfsBridge` (in-memory IPNS).

Ambiguous: `GitReconcileElement` and `WorktreeReconcileElement` are **wired into the daemon**
(`OroborosDaemon.kt:357-375`) yet carry the header "intentionally inert pending a complete CCEK
implementation". Whether reconcile actually fires on a watch event needs a runtime check, not a read.

Missing: git object codec (loose zlib / pack+idx) — note this is only needed for *history as
queryable docs*; byte-mirroring `.git/` is already sufficient for *replicating* history. Multihash/CIDv1
(`ContentId` is `sha256:` only; `htx/client/ipfs/CidAndStore.kt` is on the JS debt list). Live IPFS
and `CasReplicationElement` inert. Pijul materialization inert (CRDT live). Path grammar disagrees:
code says `projects/trikeshed/<rel>`, spec says `project/<id>/attachments/*`; there is no `Project` doc.

**Decision needed (cheap):** adopt one path grammar. Everything downstream (rewrite rule, agent homes,
replication filter) keys on it.

## 4. ReportServers: transforms, metrics, solvers on file change

Pieces exist: `couch/CouchReportReactorElement.kt` (MapEmitted/Reduced/PointcutObserved bus),
`ViewServer`, `lib/cascade/Cascade.kt` (types only), `reduction/ReducerRegistry.kt`, `metrics/Metrics.kt`
+ `FlywheelMetrics`, `FlywheelDriver` cycle POLL→…→DISPATCH with `CycleReport`, solvers
`dag/ReteNetwork.kt`, `narsese/NarseseBag.kt`, `job/JobSupervisorElement.kt` (WAL→CAS→reducer→Rete),
quality barriers `PatchAstLinter`, `EntropyPathScanner`; twelve `flywheel/cli/*Cli.kt` manual triggers.

Missing end-to-end: **file change → view invalidation → recompute → served**. Views run whole-store
on demand; no `_local` `(seq, indexCid)` checkpoints; no incremental fold from `_changes`; no
`?stale=ok`. No repo-resident source-metrics scanner (the heat-zone / duplicate-class / rule-violation
numbers in memory came from ad-hoc sessions, not from a view). Rete/Narsese are not fed by watchers.

**First ReportServer to build (recommended):** an identifier-vocabulary view over the worktree —
every declared class/fun/val name, with edit-distance clusters — which is both the duplicate-class
metric and the mechanical form of the "new vocabulary is a typo" rule.

## 5. Embodiment: agent environments + colocated history

Exists: `vm/` (tiers, `Teleported` with CID, `/api/vm/*` + SSE in `forge/server/VmWire.kt`), Jules
conductor + `JulesBoardStoreJvm` WAL, `jules/ui/JulesBlackboardAdapter.kt`, key leases in
`userspace/reactor/MuxReactorElement.kt`, provider admission in `context/nuid/ModelWorkgroups.kt`,
`MemoryBridge` projecting `.md` to `/memories/projects/trikeshed/…`. In-repo homes `.hermes/`, `.jules/`,
`.zenith/mailbox/*.jsonl`, `.agents/skills/`, `.codex/` are inside the absorbed worktree.

Gaps: homes outside the repo (`~/.hermes`, `~/.local/reactor/kanban`, `~/.claude/projects/*/memory`,
opencode/kilo session stores) are not absorbed — "colocated history" is currently Jules-only.
No `session`/`environment` document schema beyond `JulesSessionCard`. VM guests leave no durable doc
(`VmEvent` replay is in-memory). `.claude/worktrees/` (15 clones) would be absorbed as raw files — a
filter is needed. No second node: no `_replicate`, no peer discovery; `TorrentElement`
(`OroborosDaemon.kt:262-267`) is the obvious bulk-CAS transport but is unconnected to the store.

## 6. Full-tooling replication to a sales laptop

Exists: `gradlew` + wrapper jar, `bin/jvmMain/*` binaries, `com.trikeshed.oroboros.plist`,
`stageDaemonLib` → `build/staging/lib`, `hotswapFeed` → `build/live/classes`.

Gaps: the runtime classpath (`build/live`, `build/staging`) is **excluded** from the absorber, so
the store cannot carry the thing that runs it. No JDK/GraalVM 25 tarball, gradle dist, or Maven
cache as attachments. No bootstrap that needs less than "JVM + gradle + network". Today "replicate
the install" = `git clone && ./gradlew …` online.

**Shape of the fix:** staged jars and a runtime tarball as CAS attachments (dedup makes this cheap),
plus a ~50-line bootstrap that needs only a JVM and pulls from `_changes`. A native-image daemon
would reduce bootstrap to one binary + CAS, but see §7.

## 7. Pointcuts + JVM AOT

Exists: `daemon/HotSwapAgent.kt` (premain, `retransformClasses` on `build/live/.generation`),
`hotswapAgentJar` task; `cursor/ClassfileTaxonomy.java` (JEP 484 pointcut coordinates, 14 kinds);
`pointcut/PointcutBlackboardAdapter.kt`; `pointcut/PointcutCouchProjection.kt` (observations → Couch docs —
this *is* the in-flight-pointcut replication hook); ASM 9.7; `subvmHarnessNative` native-image task
(`build.gradle.kts:546-564`). Toolchain is **JDK/GraalVM 25**, Kotlin 2.4.20-Beta2.

Gaps: `META-INF/services/...TruffleInstrumentProvider` registers `PolyglotPointcutInstrument`, which
does not exist — a latent service-load failure. No AOT artifact of any kind: no JDK 25 AOT cache
(`-XX:AOTMode=record` → `-XX:AOTCache=…`, JEP 483), no CDS, no CRaC, no native-image metadata for
the daemon. Pointcut observations are projected to Couch but not replicated (no `_replicate`).

**Cheapest AOT dump on this toolchain:** JEP 483 AOT cache of the daemon, stored as an attachment,
re-recorded by a ReportServer when `build/live/.generation` changes.

## 8. Process gap: vocabulary

Rule now in memory (2026-08-23): unfamiliar nouns/verbs in a prompt are typos until proven.
Nothing enforces it mechanically; the 34 duplicate classes are the symptom. See §4 for the view
that would enforce it.

## 9. Critical path (ordered by leverage / cost)

1. **One server.** Mount `CouchHttpSurface` + `CouchRequestFactory` on `JvmKanbanServer` (spec WP2).
2. **Hoist.** Attachment GET route + `/ → projects/trikeshed/docs/index.html`. With the absorber
   already filing `docs/**`, this alone makes "PWA hosted by the store" true.
3. **Replication wire.** `_changes?feed=`, `_bulk_docs`, `_revs_diff`, `_local`. Browser becomes a
   replica (drop the sw.js queue); a second daemon becomes possible.
4. **`_replicate`** pull between two daemons → sales laptop. Gate: same path grammar on both ends.
5. **Verify/un-inert reconcile**; bring `~/.local/reactor/kanban`, causal WAL, and agent homes
   under the store; filter `.claude/worktrees/`.
6. **Lazy views** with `_local` checkpoints → first ReportServer (vocabulary view), then metrics/solvers.
7. **Tooling as attachments** + bootstrap; JEP 483 AOT cache as attachment; fix the phantom
   Truffle instrument.

Items 1–2 are days, not weeks, and produce the demo-able claim. Items 3–4 are the banner.

## 10. Addendum — same day, after the first server

Critical-path items 1–4 were built on 2026-08-23 (see `src/commonMain/kotlin/borg/trikeshed/couch/`
`CouchDatabase.kt`, `CouchWireRouter.kt`, `CouchChangesFactElement.kt`, `replicate/CouchReplicator.kt`;
`src/jvmMain/kotlin/borg/trikeshed/forge/server/CouchWire.kt`; daemon wiring in `OroborosDaemon.kt`;
spec `resources/openapi/couch-oroboros.openapi.yaml`):

| Gap (§) | Now |
|---|---|
| §1 one server | `CouchWire` mounted on `JvmKanbanServer` via the new binary-safe `rawRoutes` seam; `/trikeshed/…`, `/`, `/api/v0/block/…` and `/api/…` on one listener |
| §2 hoist | `_design/forge.rewrites` vhost: `/` → `projects/trikeshed/docs/index.html`, `/x` → `docs/x`; `GET /{db}/{id}/content`; `_attachments` stubs with `cid` |
| §1/§3 replication wire | `_changes` (normal/longpoll/continuous), `_all_docs`, `_revs_diff`, `_bulk_docs new_edits=false`, `_local`, `_cas/{cid}`, `_cas/_bulk`, `POST _replicate` (pull/push/continuous) over `HtxElement` |
| §3 CAS collapse | `CouchStoreFactory.casBacked`: rev hash = CID of the canonical-CBOR body blob; JSON is wire-only |
| §6 build as attachments | `build/live/classes` + `build/staging/lib` absorbed (5,243 attachments), re-absorbed on change; class bytes served from the store |
| §4/§5 tendon | `CouchChangesFactElement`: every committed frame → `CouchReportEvent.Committed` + Rete assert/modify/retract |
| §3 gateway bug | `CouchAttachmentGateway` passes the head rev; re-reconcile of changed files no longer a silent conflict |

Verified live: node :8901 (this checkout) = 12,418 docs; empty node :8902 pulled it over HTX; same revs both
ends; `cafebabe` class bytes served from node 2. Remaining from the original list: un-inert reconcile check
(§3), agent homes outside the tree (§5), `_local`/store durability (in-memory, rebuilt on boot), JEP 483
AOT cache as attachment (§7), phantom Truffle instrument (§7), lazy views (§4), vocabulary view (§8).
