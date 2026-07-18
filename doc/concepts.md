# TrikeShed Concept Map — for the Kotlin Maintainer

> One place a new maintainer who only knows Kotlin can read end-to-end.
> Covers the architecture spine, data algebra, runtime contracts, and the integration seams you will touch.

---

## 0. Quick Orientation

```
TrikeShed/
├── src/                    ← single source root (KMP: JVM / JS / WASM / native)
├── utils/htxc/             ← standalone CLI (composite build, see 8.3)
├── utils/ingest/           ← catalog tool (composite build)
├── build.gradle.kts        ← locked: Kotlin 2.4.10, Gradle 9.6.1, JDK 25, GraalVM CE 25.0.2
├── settings.gradle.kts     ← compose plugin, prefer-project repos
├── gradle.properties       ← jvmargs, native ignore
├── docs/                   ← GitHub Pages output (wasmJsBrowserProductionWebpack)
└── PRELOAD.md              ← kernel algebra cheatsheet (read first)
```

**Toolchain** — JDK 25 (GraalVM CE 25.0.2), Kotlin 2.4.10, Gradle 9.6.1.  
**No libs/ subprojects** — everything lives in `src/`.  
**Confix** — the only portable serializer (desired boundary: commonMain allows only `kotlinx-serialization-core`; currently `kotlinx-serialization-json` is a direct `commonMain` dep in `build.gradle.kts` — the boundary is aspirational, not enforced).  
**License** — AGPLv3 (effective 2017). Do not change.  
**Task ledger** — `doc/todo.md` (LCNC T22–T29, Kanban-live T-KANBAN-* queues).  
**Compiled-out layers** — `classfile/slab/**` is excluded from `commonMain` compile in `build.gradle.kts` (~20 `TODO()` stubs: GraalJS eval, DuckDB c-interop, `FacetedCursorContract`, `MiniDuckContract`; files preserved on disk). `CircularQueue.poll/peek/iterator.remove` converted from `TODO()` to `error(...)` — loud hollow, not silent stub.

---

## 1. Kernel Algebra (the mental model)

All shapes collapse to `Join<A,B>`:

```kotlin
interface Join<A, B> { val a: A; val b: B }
infix fun <A,B> A.j(b: B): Join<A,B> = object : Join<A,B> { override val a = this@j; override val b = b }

typealias Twin<T>    = Join<T, T>
typealias Series<T>  = Join<Int, (Int) -> T>          // size + index function
typealias Series2<A,B> = Series<Join<A,B>>             // split-storage specialization
typealias Cursor     = Series<RowVec>                  // columnar dataframe
typealias RowVec     = Series2<Any, () -> RecordMeta>  // value + metadata supplier
```

Key operators (in `lib/Join.kt`, `lib/Series.kt`):

| Symbol | Meaning |
|--------|---------|
| `a j b` | infix `Join` constructor |
| `s.α { it → it*2 }` | lazy projection (map) over a `Series` |
| `x.`↺`` | left-identity anchor — constant supplier `() -> x` |
| `s[i]` / `s[i0 until i1]` / `s[1,3,2]` | index, range, reorder |
| `s_ [1,2,3]` | Series literal |
| `join(c1,c2)` | widen columns (Series2) |
| `combine(c1,c2)` | concat rows |

**Cursor rules** — prefer projection over mutation; range selection is composition, not control flow; preserve metadata through transforms; widen/combine explicitly; keep transforms pure.

**Read** `PRELOAD.md` and `src/README.md` before touching code — they are the algebra contract.

---

## 2. Architecture Spine (runtime layers)

```
┌──────────────────────────────────────────────────────────────────────┐
│  FORGE / KANBAN / BLACKBOARD   (user-facing surfaces)               │
│  - ForgeDoc block tree, ForgeBoardFSM, KanbanFSM                    │
│  - CCEK choreography (channels, projections, agents)                │
│  - Gallery / blackboard 2.5D/3D spatial layout                      │
│  - BlackboardSurface projection: `confixDoc(persistedJson)` → `BlackboardSurface.project(...)` → seed rows; the `ForgeAppState` DTO family was removed (commit `1e8fd692`) │
│  - ManimWM RTS camera: momentum, tilt, translucent marble/jade      │
├──────────────────────────────────────────────────────────────────────┤
│  NUID / CCEK FANOUT   (authorization + dispatch)                    │
│  - Nuid = Join<Capability, Join<Nonce, Subnet>>                     │
│  - NuidFanoutElement: concentric narrowing, escalation, CAS claim   │
│  - Workgroup: scope + TraitSpace → canHandle(request)               │
├──────────────────────────────────────────────────────────────────────┤
│  LITEBIKE LISTENER   (multiprotocol CCEK listener)                  │
│  - LitebikeListenerElement: protocol-keyed channel slots            │
│  - JvmLitebikeBindAdapter: sole socket bind, bytes → CCEK accept    │
│  - JvmMulticastAdapter: mDNS/SSDP join + SO_REUSEPORT fallback      │
│  - JvmKanbanServer: daemon, no framework, hand-rolled HTTP          │
├──────────────────────────────────────────────────────────────────────┤
│  JOB NEXUS   (durable work orchestration)                           │
│  - JobSupervisorElement — bounded command channel + reactor         │
│  - JobReducer (pure) — idempotency, optimistic revision, lifecycle  │
│  - CasStore (CAS), JobLog (WAL), JobIndex, Checkpoint              │
│  - ReteNetwork — production rule engine (alpha/beta/agenda/refraction)│
│  - JobKanbanProjection / ForgeKanbanJobSink — Kanban as projection  │
├──────────────────────────────────────────────────────────────────────┤
│  COUCH / ISAM   (content-addressable persistence)                   │
│  - CouchStore (in-memory, pluggable persistence)                    │
│  - DurableAppendLog / WalFrame — frame format with CRC32C           │
│  - JobRepository — recovery from checkpoint + tail replay           │
│  - ConfixDocStore, ViewServer cascade rollups                       │
├──────────────────────────────────────────────────────────────────────┤
│  DAG / RETE   (causal + rule engine)                                │
│  - ReteWorkingMemory, Alpha/Beta memories, Agenda, Refraction       │
│  - BlackboardDagCausalGraph, BlackboardDagFabric                    │
│  - ReteAgent — CCEK bridge                                          │
├──────────────────────────────────────────────────────────────────────┤
│  COLLECTIONS   (index algebra)                                      │
│  - LinearHashMap, FunnelHashMap, ElasticHashIndex, RadixTree        │
│  - MultiIndexK (exact/order/range/prefix) with stable IndexSpecId   │
│  - COW B+Tree (btree/) — deterministic pages in CasStore            │
├──────────────────────────────────────────────────────────────────────┤
│  CONFIX   (schema-driven config oracle)                             │
│  - ConfixDoc / ConfixCell — index-first, reify-later                │
│  - ConfixFacetPlan — compiled from job-nexus.schema.json            │
│  - JSON / YAML / CBOR single parser (Syntax enum)                   │
├──────────────────────────────────────────────────────────────────────┤
│  CHOREOGRAPHY / REACTOR   (structured async)                        │
│  - AsyncContextElement (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)        │
│  - NioSupervisor / LiburingElement / FanoutDispatcherElement        │
│  - ChannelRunner — RelaxFactory inner loop → coroutines             │
│  - MuxReactorElement — keymux/modelmux/taxonomy/kanban events       │
├──────────────────────────────────────────────────────────────────────┤
│  TRANSPORT / HTX   (version-agnostic HTTP)                          │
│  - HtxMessage blocks (ReqSl·Hdr·EOH·Data·EOT·EOM)                   │
│  - HtxClientReactorElement — channelized client                     │
│  - DHTX_REQ/DHTX_RES for non-HTTP protocols                         │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL SURFACES   (expect/actual)                                  │
│  - FileImpl, LiburingImpl, FilesImpl, ChannelsImpl                  │
│  - FunctionalUringFacade wraps UserspaceChannelBackend              │
│  - ByteBuffer / ByteRegion / ByteSeries — zero-copy IO path         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Job Nexus — the durable work spine

### 3.1 Command → Event Pipeline (in `JobSupervisorElement`)

```
JobCommand (Submit/Start/Complete/Fail/Retry/Progress/Block/Cancel/Move/Ack/Retract)
   │
   ├─ 1. Schema validation  (ConfixFacetPlan)
   ├─ 2. Canonical CBOR      (CanonicalCbor.encode → deterministic bytes)
   ├─ 3. CasStore.put        (SHA-256 CID, digest verification on get)
   ├─ 4. JobLog.append       (sequence + payload; monotonic)
   ├─ 5. Durability barrier  (flush/fsync contract)
   ├─ 6. JobReducer.reduce   (idempotencyKey + expectedRevision → JobSnapshot)
   └─ 7. Committed → JobEvent.Accepted/Rejected → channels
```

**Invariants**  
- `idempotencyKey` deduplication (first wins, later rejected)  
- `expectedRevision` optimistic concurrency (stale → Rejected)  
- Commands enter **only** through the bounded `Channel<JobCommand>` (SUSPEND on overflow)  
- Failed step leaves **no visible snapshot** and does **not** advance committed sequence

### 3.2 Core Types (`job/IdentityTypes.kt`, `JobSnapshot.kt`)

```kotlin
@Serializable data class JobId(val value: String)
@Serializable data class Revision(val value: Long)
@Serializable data class KanbanColumnId(val value: String)
@Serializable data class Sequence(val value: Long)

data class JobSnapshot(
  val jobId: JobId,
  val revision: Long,
  val causalKey: String,
  val lifecycle: String,              // submitted | ready | active | blocked | failed | closed | moved | acknowledged | retracted
  val dependencies: List<JobId>,
  val attemptCount: Int = 0,
  val attemptId: String = ""
)
```

**Lifecycle derivation** — in `JobReducer.deriveLifecycle`; blocked if any dependency failed; ready if all deps closed.

### 3.3 ReteNetwork (production rule engine)

```
ReteWorkingMemory  ← assert/modify/retract by FactId + version CID
ReteAlphaMemory    ← shared single-condition nodes (predicate sharing)
ReteBetaMemory     ← equality join (leftFacetId = rightFacetId) with token memory
ReteAgenda         ← salience↓, sequence↑, activationId↑ deterministic pop
ReteRefraction     ← one firing per (ruleVersion, sorted supportCIDs)
ReteNetwork        ← owns all above; runs on bounded SendChannel<JobCommand>
```

**Rules currently encoded** (see `ReteNetwork.evaluateRules`):
- all deps `closed` → `Start` command
- any dep `failed`  → `Block` command with support evidence

Actions **never** mutate Kanban/Couch/snapshots directly — they enqueue `JobCommand` via the reactor ingress channel.

### 3.4 Projections (read models)

| Projection | Purpose |
|------------|---------|
| `JobKanbanProjection` | Kanban cards from committed snapshots (`applyCommit` + `rebuild`) |
| `ForgeKanbanJobSink`  | Monotonic sequence gate → projection |
| `CouchHeadProjection` | revision string stored raw; CID-derived `_id`/`_rev` not yet implemented, MVCC |
| `CouchChangesProjection` | Strict monotonic `_changes` stream |
| `CowBPlusTree` | Persistent ordered/range index (pages in CasStore) |
| `JobCheckpoint` | Committed sequence + root CID + schema CID |

---

## 4. Confix — the config oracle

**Single parser** (`Syntax` enum: JSON, CBOR, YAML) → `ConfixIndex` (flat token array + `FlatIndex`) → lazy `reify()`.

```kotlin
typealias ConfixDoc  = Join<ConfixIndex, Series<Byte>>
typealias ConfixCell = Join<RowVec, Series<Byte>>
```

**Navigation** (`ConfixKit.kt`):
```kotlin
doc.value("operation")                 // scalar
doc.docAt("dependencies", 0)?.get("jobId")  // nested
cell.reify()                           // Any? with tag dispatch
doc.navigate(jsPath)                   // typed JsPath (String | Int steps)
```

**ConfixFacetPlan** — compiled from `src/commonMain/resources/confix/job-nexus.schema.json`:
- operation enums, frame families, required fields, primitive/array constraints
- stable facet/index IDs, exact/order/range/prefix index policies
- validation errors include schema/document path

**No second hand-maintained field table** — the schema resource is the contract.

---

## 5. Couch — content store + projections

```
CouchStore (in-memory, pluggable CouchPersistence)
  ├─ put/get/delete  → MutationEvent (Inserted/Updated/Deleted)
  ├─ query()         → Cursor (row = doc, cols = _id + fields)
  ├─ subscribeMutations → MutableSeries observer
  └─ CouchHeadProjection / CouchChangesProjection  (built from committed Job frames)
```

**Head/Changes semantics** — revision string stored raw by the projection; stale revision rejected; delete = tombstone; `_changes` resumes after sequence without gaps. CID-derived `_id`/`_rev` is an integration gap, not the current state.

---

## 6. Collections — index algebra

| Component | Purpose |
|-----------|---------|
| `LinearHashMap` | open-addressing base (mixed hash bits, bounded probes) |
| `FunnelHashMap` / `FunnelHashIndex` | Krapivin 2025 funnel hashing (tiered geometry) |
| `ElasticHashIndex` | append-only, deterministic split |
| `RadixTree` / `Trie` | prefix queries, deterministic order |
| `MultiIndexK / MultiIndexContainer` | stable `IndexSpecId`, unique/non-unique, txn add/modify/retract, immutable snapshots |
| `CowBPlusTree` | COW pages in CasStore, deterministic page CID, checkpoint validation + tree hydration + tail replay |

**MultiIndex transition** — lambda-identity keys replaced by explicit `IndexSpecId`; incremental order/range (binary insert), no full-store resort.

---

## 7. Choreography / Reactor — structured async

### 7.1 Element lifecycle

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Every IO component extends `AsyncContextElement` and installs its `CoroutineContext.Key`:

```kotlin
object NioUserspaceKey    : Key<NioUserspaceElement>
object LiburingKey        : Key<LiburingElement>
object FanoutDispatcherKey: Key<FanoutDispatcherElement>
object BtrfsCodecKey      : Key<BtrfsCodecElement>
```

### 7.2 NioSupervisor (root registry)

```kotlin
open class NioSupervisor : AsyncContextElement() {
  internal val services = mutableListOf<CoroutineContext.Element>()
  fun <T: CoroutineContext.Element> service(): T?
  // opens platform providers in CREATED→OPEN→ACTIVE
}
```

### 7.3 ChannelRunner (RelaxFactory → coroutines)

```kotlin
suspend fun readAsync(fd: Int): Int { ... }      // CompletableDeferred per fd
suspend fun writeAsync(fd: Int) { ... }          // FIFO queue per fd
fun run(scope, pollTimeout, onSignal) { ... }    // CQE loop → dispatch
```

### 7.4 MuxReactorElement (keymux/modelmux/taxonomy/kanban events)

- Owns `ModelApiCache`, `SharedFlow<KanbanEvent>`, `StateFlow<MuxReactorState>`
- Kanban FSM **consumes** `kanbanEvents`; it never owns the stream
- External callers `ingestTaxonomyEvents` / `lookupModel` / `cacheModel` — reactor is the single writer

---

## 8. Surfaces a maintainer will touch

### 8.1 Forge / Kanban / Blackboard (user-facing)

```
ForgeDoc          ← block tree (PAGE, HEADING, TEXT, BULLET, TODO, CODE, …)
ForgeBoardFSM     ← board/card FSM (BoardLoaded, CardMoved, CardCreated, Drag*)
ForgeKanbanIngest ← /tmp/hi markdown → Rete facts + causal nodes + Kanban cards
ForgeGalleryCatalog/Renderer ← widget catalog (sections LAYOUT..CAS, preview tokens)
ForgePersistenceScript.kt ← browser IndexedDB/localStorage/Cache + force-directed graph
ForgeApp.kt       ← HTML template + seed JSON injection (forge-seed)
```

**Gallery on GitHub Pages** — `jsNodeProductionRun` prints exact HTML to stdout; `generateForgePages` captures it into `docs/`. The seed embeds `ForgeBoardFSM.current()`, `KanbanFSM.current()`, `ForgeSpatialState`, and `ForgeGalleryCatalog.toJsonValue()`.

**Blackboard-as-Confix-cursor** — the target architecture. A single JSON file is the blackboard; `confixDoc(json)` → `Cursor` → `BlackboardSurface.project(cursor)` → UI renders cursor slices by path/offset/facet. No parallel DTO truth. `BlackboardSurface` joins `LcncEntitySurface` + `CausalGraphNodeIndex` into a deterministic `Cursor` of `BlackboardSurfaceRow`s (`card_id`, `lane`, `phase`, `facet`, `provenance`, `causalKey`, `lcncKind`). Facet drilldown = child cursor projections from the same doc.

**ManimWM 2.5D RTS surface** — `ForgeBlackboardCamera` carries momentum (`vx`, `vy`, `vz`), tilt (2.5D parallax), and bounded zoom. The blackboard is the VFS; cursors are the files; facets are drilldown views. The PWA hydrates from a cursor seed and animates through the camera model. Marble-and-jade translucent motif for the builder-tool gallery.

### 8.1a NUID / CCEK Fanout (authorization + dispatch)

```
Nuid = Join<Capability, Join<Nonce, Subnet>>
  - Capability: sealed hierarchy (Process/Cas/Wireproto/Sctp/Model/BlackBoard/Custom + wildcard family roots)
  - Subnet: concentric containment (core < process < local < lan.localhost < mesh.worker.* < global.relay)
  - Nonce: RandomBytes + Derived (causal chaining)

NuidFanoutElement
  - CCEK lifecycle (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)
  - Concentric narrowing: filter by scope⊇subnet AND TraitSpace.can(capability), sort by scope.level ascending
  - Escalation: timeout at request level → walk outward up to escalationBudget+1 levels
  - Claim: first WorkgroupSlot.tryTake() matching claimId wins; losers stand down

Workgroup
  - name + scope: Subnet + traits: TraitSpace
  - canHandle(request: Nuid) = traits.can(capability) && (scope contains subnet)
```

### 8.1b Litebike Listener (clean-room Kotlin port — no FFI)

```
LitebikeListenerElement
  - CCEK element; registry keyed by Protocol.id (UByte)
  - register(protocol) → ChannelWorkgroupSlot; slot.consume() suspends for ChannelMessage
  - accept(protocol, bytes) → offers to slot, fires LitebikeFanoutEvent to CCEK subscribers
  - Protocol enum: Http(1) Socks5(2) Tls(3) Dns(4) Json(5) Http2(6) WebSocket(7) Bonjour(8) Upnp(9)
  - IDs 1-7 match litebike taxonomy.rs conceptually; 8-9 are TrikeShed-local extensions

JvmLitebikeBindAdapter
  - The ONLY place that opens AsynchronousServerSocketChannel
  - Reads bytes → ProtocolDetector.detect(head) → listener.accept(protocol, bytes)
  - No HtxReactorElement, no com.sun.net.httpserver, no RfxHttpServerJvm

JvmMulticastAdapter
  - Joins mDNS 224.0.0.251:5353 and SSDP 239.255.255.250:1900 via DatagramChannel
  - SO_REUSEPORT-first fallback for macOS mDNSResponder port conflict
  - Tracks Jobs + MembershipKeys; close() cancels all read loops and drops groups

JvmKanbanServer
  - Daemon entrypoint (--port, --donor)
  - Owns one LitebikeListenerElement; registers Http/Json/Socks5/Tls/Bonjour/Upnp slots
  - HTTP worker consumes httpSlot, hand-parses request line, routes to /api/health|cap|board|submit|donor
  - Bonjour/Upnp consumers parse minimal mDNS/SSDP headers, emit JSON to Json slot
  - No server framework; CCEK fanout all the way down
```

### 8.2 CCEK (choreography for Forge)

```
ArticulatedNode
  signalIn: Channel<ForgeSignal> (AppendBlock/UpdateText/DeleteBlock/MoveCard)
  projections: SharedFlow<ForgeDocument>, <KanbanBoard>, <String>
  agents: Map<String, (ForgeSignal) -> Unit>
  fanOutJob: structured concurrency dispatch
```

Signals flow **into** `ArticulatedNode`; projections flow **out** via `SharedFlow`. No dual-mutable truth.

### 8.3 HTX / htxc (CLI utility)

```
utils/htxc/          ← composite build (includeBuild("../.."))
  - bin/htxc         ← shell launcher, exact arg forwarding, preserves exit code
  - HtxAria2CliArgs  ← aria2-compatible switches (dir/out/split/max-conn/continue/checksum)
  - HtxAria2Engine   ← range/HEAD + chunked download via HtxClientReactorElement
```

---

## 9. Build & Deploy (what you will run)

```bash
# Environment
export JAVA_HOME=/Users/jim/.sdkman/candidates/java/25.0.2-graalce
export PATH="$JAVA_HOME/bin:$PATH"

# Full build + test
./gradlew build --console=plain

# Focused test suites
./gradlew jvmTest --tests "borg.trikeshed.dag.*"
./gradlew jvmTest --tests "borg.trikeshed.job.*"
./gradlew jvmTest --tests "borg.trikeshed.collections.multiindex.*"

# GitHub Pages deploy (manual capture)
./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 \
  | awk '/^<!doctype html>/,/^<\/html>/' > docs/index.html
git add docs/index.html && git commit -m "feat: deploy Forge workspace" && git push

# Verify deploy
gh api repos/jnorthrup/TrikeShed/pages/builds -X POST
gh api repos/jnorthrup/TrikeShed/pages/builds/latest
```

**Common tasks registered** (`build.gradle.kts`):
- `jmh`, `jmhJoin`, `jmhConfix`, `jmhWal` — JMH benches
- `benchmarkJoin`, `benchmarkSequence`, `benchmarkVector`, `benchmarkMath`, `benchmarkConfix`
- `printForgeGallery` — JVM text grid of catalog + blackboard
- `runForgeJvm` — Compose Desktop shell
- `generateForgePages` — Sync task (WASM target → docs/)

---

## 10. Reading / Recovery Paths (where to look next)

| Need | Files |
|------|-------|
| Algebra cheat sheet | `PRELOAD.md`, `src/README.md`, `lib/Join.kt`, `lib/Series.kt` |
| Job Nexus end-to-end | `JobSupervisorElement.kt`, `JobReducer.kt`, `JobNexusFactory.kt`, `JobNexusBindings.kt` |
| Rete rule engine | `dag/ReteNetwork.kt`, `dag/Rete*.kt`, `dag/BlackboardDag*.kt` |
| Schema → Confix plan | `resources/confix/job-nexus.schema.json`, `ConfixFacetPlan.kt`, `ConfixKit.kt` |
| Couch projections | `couch/CouchStore.kt`, `couch/*Projection.kt`, `couch/ConfixRepositoryView.kt` |
| COW B+Tree | `collections/btree/*`, `JobRepository.kt`, `JobCheckpoint.kt` |
| MultiIndex | `collections/multiindex/*.kt`, `collections/associative/trie/RadixTree.kt` |
| Forge surfaces | `forge/ForgeDoc.kt`, `forge/ForgeBoardFSM.kt`, `forge/ForgeKanbanIngest.kt`, `forge/ForgePersistenceScript.kt` |
| Reactor / choreography | `userspace/reactor/MuxReactorElement.kt`, `context/AsyncContextElement.kt`, `userspace/nio/channels/ChannelRunner.kt` |
| NUID / CCEK fanout | `context/nuid/Nuid.kt`, `context/nuid/NuidFanoutElement.kt` |
| Litebike listener | `litebike/LitebikeListenerElement.kt`, `litebike/ProtocolDetector.kt`, `litebike/taxonomy/Taxonomy.kt`, `jvmMain/litebike/JvmLitebikeBindAdapter.kt`, `jvmMain/litebike/JvmMulticastAdapter.kt`, `jvmMain/litebike/JvmKanbanServer.kt` |
| Blackboard-as-cursor | `blackboard/BlackboardSurface.kt`, `parse/confix/Confix.kt`, `parse/confix/ConfixKit.kt` |
| ManimWM RTS camera | `forge/blackboard/ForgeBlackboardCamera.kt`, `forge/blackboard/ForgeBlackboardInteraction.kt`, `manimwm/` |
| Transport / HTX | `htx/Htx*.kt`, `cli/htx/HtxAria2*.kt` |
| Gallery / Pages | `forge/gallery/*.kt`, `ForgeApp.kt`, `build.gradle.kts` (`generateForgePages`) |

---

## 11. Common Pitfalls (don't relearn these)

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| `Series[T]` vs `MatchGroupCollection.get` collision | `Unresolved reference … receiver type mismatch` | `import borg.trikeshed.lib.get` + `import borg.trikeshed.lib.size` (or `.b(i)` for raw) |
| CursorDriven empty-page placeholder child | `first()` returns empty TEXT block | filter by `kind` (`HEADING_1`, `BULLET`) or add `pageHeading(state)` helper |
| Compose Desktop import fragility | 70-80 import lines; patch breaks | prefer `git checkout <file>` + tiny patches, or `write_file` full rewrite |
| Coroutine polling in `runBlocking` + `Dispatchers.Default` | Only 1 of N events fires | Replace with `Channel<T>` — `trySend` / `for (item in channel)` |
| Dual-truth (Kotlin state + JS mutation) | Silent fork | One runtime authoritative (JVM); other mirrors via reactor/event channel |
| Orphaned submodule (gitmode 160000, no .gitmodules) | CI checkout fails silently | `git rm --cached <path>` |
| `build.gradle.kts` checkout from ref | Local commits lost | Never `git checkout <ref> -- build.gradle.kts` |
| `rm -rf` untracked `??` dirs | Sibling Jules jobs destroyed | Never — they are active work, not stubs |
| macOS mDNS bind with only `SO_REUSEADDR` | `EADDRINUSE` on port 5353 | Try `SO_REUSEPORT` first (runCatching), fall back to `SO_REUSEADDR` |
| `Random.Default` / `nextBits` in commonMain | Native compile failure | Use `Random(0L)` + `nextInt(0, 256)` — KMP-safe |
| `System.currentTimeMillis()` in commonMain | Deprecated / KMP-unsafe | Use `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` |
| `@Volatile` / `synchronized` in commonMain | KMP compile failure | Use `Mutex` + `withLock` — kotlinx-coroutines is KMP-safe |
| `Charsets.US_ASCII` in commonMain | JVM-only constant | Use `CharArray(n) { bytes[i].toInt().toChar() }.concatToString()` |
| `for (msg in channel)` on `Channel<T>` | Ambiguous iterator / compile error | Use `while (true) { val msg = slot.consume() }` or `channel.consumeEach { }` |
| `runCatching { subscriber.javaClass.methods... }` in commonMain | `javaClass` unresolved on JS/Wasm | Use explicit interface (`LitebikeFanoutEventSink`), no reflection |
| `toSortedMap()` on `groupBy` result | JVM-only stdlib | Use `.keys.sorted()` explicitly |
| `String(bytes, 0, len, charset)` in commonMain | JVM-only constructor | Decode via `CharArray` + `concatToString()` |
| `HtxReactorElement` used as server listener | Exchange-only, does not bind | Use `LitebikeListenerElement` + `JvmLitebikeBindAdapter` — Htx is client-side only |
| `com.sun.net.httpserver` for Kanban server | Framework dependency, not CCEK | Hand-rolled HTTP worker on `LitebikeListenerElement` slot — zero framework |
| Parallel DTO truth (`ForgeAppState` vs Confix doc) | Dual-truth seam, silent fork | Single JSON file → `confixDoc()` → `Cursor` → `BlackboardSurface.project()` — one canonical source |

---

## 12. Contribution Contract (how work lands)

1. **Vertical slice** — failing contract test → minimal production wiring → adjacent/full verification.
2. **Exclusive file ownership** — Jules tasks declare owned paths + forbidden paths; no overlap.
3. **No libs/ references** — root-only, composite builds consume via `includeBuild("../..")`.
4. **No FFI / no Rust linkage** — litebike is conceptual inspiration only; ports are clean-room Kotlin with TrikeShed-local conventions.
5. **Pre-commit** — `git diff --check`, verify no `kotlinx-serialization-json/cbor` in commonMain, run focused tests.
6. **Evidence** — real test output, generated artifact proof (HTML/WASM), branch + PR with exact commands.

---

## 13. Quick Start Checklist for Day 1

```bash
# 1. Toolchain
sdk install java 25.0.2-graalce
sdk use java 25.0.2-graalce
./gradlew --version   # Gradle 9.6.1

# 2. Read the algebra
cat PRELOAD.md
cat src/README.md

# 3. Run a focused test
./gradlew jvmTest --tests "borg.trikeshed.lib.JoinTest" --console=plain

# 4. Inspect the Job Nexus spine
cat src/commonMain/kotlin/borg/trikeshed/job/JobSupervisorElement.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobReducer.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobNexusFactory.kt

# 5. Browse the gallery (local)
./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 \
  | awk '/^<!doctype html>/,/^<\/html>/' > /tmp/index.html
open /tmp/index.html
```

---

*End of concept map. When you land a change, update the relevant section above — this doc is the maintenance lineage.*