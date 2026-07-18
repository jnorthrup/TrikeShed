# `doc/concepts.md` Gap Analysis

## Scope

This audit compares the concept map at its creation commit (`cebea1da`, tagged `concept-map-v1`) with current `master` (`638fb71b`). The post-map delta is 49 files, 4,013 insertions, and 3,364 deletions. The substantive source work groups into six areas:

| Work cluster | Post-map delta | Coverage in `concepts.md` | Finding |
|---|---:|---|---|
| Oroboros storage/network/workspace | 15 files, +1,449 | Absent | Largest documentation gap |
| CAS B+tree and repository recovery | 5 files, +887/-1 | Named repeatedly | Coverage overstates recovery completeness |
| Couch CQRS projections | 5 files, +404/-108 | Partially described | Architecture and revision semantics are stale |
| Funnel map, stringpool, WAL logger | 8 files, +442/-1 | Funnel map only | Stringpool/logger path absent; durability overstated by names |
| View-server runtimes | 5 files, +147/-12 | One phrase plus Forge sample usage | Runtime topology absent; two incompatible implementations exist |
| Build/source-set changes | mixed | Build section exists | Current verification commands do not configure successfully |

## Priority gap map

### G1 — Oroboros is a substantial undocumented subsystem

Current implementation spans three distinct layers that do not appear in the architecture spine, maintainer surfaces, build section, or recovery paths:

1. Workspace and change capture
   - `ForgeHome` confines agent paths under a per-agent root and rejects unsafe path components: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/ForgeHome.kt:8`.
   - `FileEWatcher` provisions and reconciles filesystem state into bounded `FileEvent` traffic using content IDs: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/FileEWatcher.kt:17-27`, `:32-47`, `:53-108`.
   - Git and Pijul implementations of `VersionGateway` initialize and record workspace revisions through `ProcessOperations`: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/VersionGateway.kt:5-20`, `:23-73`, `:75-116`.

2. Durable content and attachment surfaces
   - `FileCasStore` shards SHA-256 content paths and verifies bytes on both write and read: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/Sha2CasBus.kt:12-56`.
   - `Sha2CasBus` enforces durable-before-visible publication through a finite suspending channel: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/Sha2CasBus.kt:59-85`.
   - `CouchAttachmentGateway` stores attachment metadata in Couch and bytes in CAS, with revision-checked deletion and a `Series2` manifest: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/CouchAttachmentGateway.kt:12-101`.
   - `OroborosStorageK` exposes typed CAS, attachment, event, manifest, and status facets: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/OroborosStorage.kt:10-24`.

3. Content retrieval
   - `OroborosNetworkK` defines DHT, IPFS, stream, HTX, and fanout facets: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/OroborosNetwork.kt:29-37`.
   - DHT lookup, digest-checked IPFS retrieval, stream framing, HTX retrieval, and first-success fanout are implemented in `OroborosNetwork.kt:39-209`.
   - `NgSctpGateway` publishes the existing `SctpElement` as a typed facet: `src/commonMain/kotlin/borg/trikeshed/util/oroboros/NgSctpGateway.kt:7-22`.

Evidence classification: mixed, not a live end-to-end runtime. Tests instantiate the individual components, but production code outside `util/oroboros` does not compose them. The network payload extractor explicitly calls itself a test mock and contains placeholder extraction (`OroborosNetwork.kt:53-64`). The concept map should document this as a tested component library awaiting a composition root, not as an operational Forge path.

Required `concepts.md` change: add an Oroboros layer or maintainer surface, show workspace event → CAS/attachment → version gateway and DHT/IPFS/HTX/SCTP retrieval, and explicitly mark the missing production composition root and real HTX payload extraction.

### G2 — Couch CQRS documentation claims semantics the implementation does not provide

The concept map currently says:

- Couch is an in-memory store with pluggable persistence (`concepts.md:218-225`).
- head `_id` is the head CID and `_rev` is a generation-CID prefix (`concepts.md:228`).
- Couch projections are built from committed Job frames (`concepts.md:185-186`, `:225`).

The landed CQRS shape is different:

- `CouchStore` is a facade over an injected `CouchIngress`, `CouchHeadProjection`, and `CouchChangesProjection`: `src/commonMain/kotlin/borg/trikeshed/couch/CouchStore.kt:42-58`.
- Reads come only from the head projection; writes only submit intents: `CouchStore.kt:66-116`.
- The only factory-provided ingress is named `SyncTestIngress`; it generates `uuid-*` revisions, applies projections directly, and calls persistence directly: `CouchStore.kt:214-265`.
- `flush`, `drain`, and `close` do not drive a durable reactor: `CouchStore.kt:159-179`.
- `CouchHeadProjection` indexes by the original document ID and stores the supplied revision string; it does not derive `_id` or `_rev` from a content ID: `CouchHeadProjection.kt:13-18`, `:24-56`, `:83-116`.
- `CouchChangesProjection.applyCommit` appends frames but does not itself enforce increasing sequence values or provide an `afterSequence` resume API: `CouchChangesProjection.kt:10-33`.

Evidence classification: the projection split and no-precommit read boundary are real and tested (`CouchProjectionTest.kt:13-125`), but Job-reactor ingress, CID-based revisions, and resumable strict sequencing are contract-only. The current prose promotes intended semantics to implemented semantics.

Required `concepts.md` change: describe the actual ingress/head/changes split, label `SyncTestIngress` as the current factory path, and move Job-frame/CID/replay claims into an explicit integration gap until a production ingress backs them.

### G3 — B+tree implementation is real, but “checkpoint + tail recovery” is incomplete

The concept map already names `CowBPlusTree`, `JobCheckpoint`, and checkpoint/tail recovery in the architecture spine and reading paths (`concepts.md:81`, `:92`, `:187-188`, `:241`, `:371`). It does not explain the substantial landed mechanics:

- deterministic node codec and CAS-backed copy-on-write insertion: `src/commonMain/kotlin/borg/trikeshed/collections/btree/CowBPlusTree.kt:53-247`, `:249-429`;
- exact and range reads over immutable roots: `CowBPlusTree.kt:267-325`;
- CAS-before-WAL snapshot commits with read-back verification: `src/commonMain/kotlin/borg/trikeshed/job/JobRepository.kt:18-39`;
- checkpoint/schema/tree/metadata verification during replay: `JobRepository.kt:45-96`.

The stronger problem is semantic. On seeing a checkpoint, recovery clears all preceding snapshots, verifies the B+tree, then returns only snapshots reconstructed from later WAL records (`JobRepository.kt:52-96`). It never traverses B+tree values to hydrate checkpointed `JobSnapshot` state. The test pins that behavior by expecting pre-checkpoint jobs to be absent (`JobRepositoryRecoveryTest.kt:53-90`).

Evidence classification: `CowBPlusTree` is live as an isolated CAS collection and has deterministic/snapshot/range tests (`CowBPlusTreeTest.kt:14-146`). Repository checkpoint validation and tail replay are live, but recovery of checkpointed job heads is not.

Required `concepts.md` change: split “checkpoint validation + tail replay” from “checkpoint state restoration.” Do not call the current path complete recovery until checkpoint tree values restore the repository head before tail application.

### G4 — Funnel hashing is documented; stringpool and WAL logging are not

`concepts.md:237` names `FunnelHashMap` and `FunnelHashIndex`, but omits the landed consumers:

- `FileBackedStringpool` memoizes string-to-offset entries through `FunnelHashMap`: `src/commonMain/kotlin/borg/trikeshed/couch/isam/Stringpool.kt:21-49`.
- `ReactorLogger` implements the local SLF4J facade, stores log templates in the stringpool, and appends a compact binary payload to `JobLog`: `src/jvmMain/kotlin/borg/trikeshed/reactor/logging/ReactorLogger.kt:7-47`.

Evidence classification: mixed. The logger payload format and template memoization have a focused test (`ReactorLoggerTest.kt:12-58`). The “file-backed” stringpool is currently an in-memory simulation: comments say the file/WAL path is future work and all values live in maps (`Stringpool.kt:18-49`). `ReactorLogger` appends with `System.nanoTime()` as the WAL sequence and does not flush in `logCas` (`ReactorLogger.kt:22-47`), so “CAS-based WAL logger” should not be documented as a durable production logger without qualification.

Required `concepts.md` change: add the template → stringpool offset → binary `JobLog` record path under persistence/observability, and label the stringpool backing and durability boundary as incomplete.

### G5 — The view-server runtime is almost absent from the concept map and currently forks into two APIs

The map only mentions “ViewServer cascade rollups” in the persistence layer (`concepts.md:82`) and a Forge sample-grid call (`ForgeApp.kt:101-105`, indirectly represented by `concepts.md:298-303`). It omits:

- the analyzable mapper model and CouchDB built-in reduce/rereduce support in `src/commonMain/kotlin/borg/trikeshed/viewserver/CommonViewServer.kt:15-125`;
- the `CouchDbCascadeTool` view/metric rollup contract in `src/commonMain/kotlin/borg/trikeshed/viewserver/CouchDbCascadeTool.kt:7-104`;
- the Node JSON-lines entry point in `src/viewServerJsMain/kotlin/borg/trikeshed/viewserver/NodeViewServer.kt:53-102`;
- the GraalVM host boundary in `src/viewServerJvmMain/kotlin/borg/trikeshed/couch/viewserver/GraalVmViewServerHost.kt:9-47`;
- the restricted standalone Graal evaluator in `src/jvmMain/kotlin/borg/trikeshed/couch/viewserver/GraalVmViewServer.kt:7-18`.

There are also two incompatible definitions with the same package/type names:

- common source uses `addFunction`, typed `ViewValue`, and working map/reduce/rereduce;
- `viewServerCommonMain` uses `addTool`, raw JSON strings, returns `[]` from `mapDoc` and `rereduce`, and depends directly on `kotlinx-serialization-json`: `src/viewServerCommonMain/kotlin/borg/trikeshed/viewserver/CommonViewServer.kt:1-54`.

The dedicated view-server tree is not attached to a source set or task. `build.gradle.kts:18` sets an unused `viewServerNodeSlice = false`, and the only configured source sets are the standard KMP sets (`build.gradle.kts:128-178`).

Evidence classification: commonMain engine is used by Forge and has common tests; the dedicated Node/Graal host files are disconnected source trees. The host path is therefore contract-only in the current build.

Required `concepts.md` change: document the common engine separately from host adapters, and state that Node/Graal launch is not available until the duplicate API is collapsed and its source sets/tasks are wired.

### G6 — The concept map’s build and serializer contracts are not currently executable truths

Two current contradictions affect every documented subsystem:

1. `concepts.md:24` says commonMain permits only `kotlinx-serialization-core`, but `build.gradle.kts:128-138` directly adds `kotlinx-serialization-json` to `commonMain`.
2. The documented full and focused Gradle commands (`concepts.md:335-341`) fail during build-script compilation. The current failure is at `build.gradle.kts:97-98`: `sourceSets` and `compose.runtime` are referenced through an invalid implicit receiver inside `wasmJs`. No selected test executed.

This audit attempted the Couch projection, B+tree, repository recovery, and reactor logger suites together. Gradle exited before task execution with 10 build-script diagnostics. Consequently, source and test evidence above establishes definitions and intended assertions, not a fresh green runtime baseline.

Required `concepts.md` change: do not refresh command examples or claim a green baseline until the build configures. Preserve the serializer rule as the desired boundary, but identify the current dependency violation rather than describing it as enforced.

## Coverage disposition

| Concept-map area | Disposition |
|---|---|
| Oroboros | Add a new section; mark components tested but uncomposed |
| Couch | Rewrite current-state semantics; separate live CQRS projection from intended Job/CID ingress |
| B+tree/recovery | Expand mechanics; downgrade complete checkpoint recovery claim |
| Collections | Keep Funnel coverage; add stringpool consumer and its simulated backing status |
| Observability | Add reactor logger path with explicit non-durable boundaries |
| View server | Add engine/adapter topology; call out disconnected duplicate host implementation |
| Build | Correct only after Gradle configuration is green; record serializer dependency mismatch |

## Best debt-reduction cut

Collapse the view-server fork into the tested commonMain `CommonViewServer`/`CouchDbCascadeTool`, wire one Node source set and one JVM `JavaExec` host task to that API, and delete or supersede the disconnected raw-JSON implementation. This removes duplicate truth, converts a merged feature from contract-only to runnable, and gives `concepts.md` one honest runtime path to document.

The next architectural cut is a production `CouchIngress` backed by the committed Job sequence. That would make the existing Couch prose true by replacing `SyncTestIngress` as the default and deriving head/changes state from one durable source.