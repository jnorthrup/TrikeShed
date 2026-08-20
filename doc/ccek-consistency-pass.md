# CCEK Consistency Pass — default-Kotlin cruft vs reactor idiom

Date: 2026-08-20. Extends README §7.5 (CCEK adaptation table) with a fresh
scan. Intended idiom: `AsyncContextElement` lifecycle (CREATED→OPEN→ACTIVE→
DRAINING→CLOSED), Channel/Flow not callbacks, Mutex not synchronized, Join/
Series not eager List, suspend not blocking, zero-cost marks not string
identity. Findings note cruft; nothing here is a cleanup mandate — see
PRELOAD prohibited patterns and `midpoint-map.md` for how seams get drawn.

## 1. Reference assemblies (the idiom, working)

- `NuidFanoutElement` — Mutex registry, buffered Channel slots, lifecycle
  gates on every entry point, snapshot-cached read side. **The template.**
- `QuotaTelemetry.recordSnapshot` — write API requires
  `MuxReactorElement.Key` in coroutine context: reactor-internal by type.
- `LitebikeListenerElement`, `LcncFanoutElement` — kotlinx Clock, channel
  slots, CCEK keys. Clean.
- `KeyMux.leaseBacking` — mutable map, but documented single-writer
  (reactor/test) with lazy Series lease view. Deliberate, not cruft.

## 2. Cruft: blocking/JVM leakage in commonMain

| Site | Cruft | Idiom owed |
|------|-------|-----------|
| `metrics/FlywheelMetrics.kt` (throughout: 22, 33–100, 56, 117, 131, 233) | `synchronized` ×2, `@Volatile` ×8, `System.currentTimeMillis` ×5, `java.time.Instant`, `java.util.concurrent.atomic.AtomicLong` — **all in commonMain**; compiles only because JVM is the sole exercised target | Mutex + `kotlinx.datetime.Clock`; or move to jvmMain and expect/actual the surface. Largest single violator in the ring files. |
| `kanban/ForgeKanbanIngest.kt:253` | `runBlocking` in production commonMain path | suspend through the caller; RGA 2026-07-20 N3 already flags this file (bypasses JobSupervisor) — same seam, two debts |
| `util/oroboros/element/GitReconcileElement.kt:47,56` + `WorktreeReconcileElement.kt:52,64` | `System.currentTimeMillis` as WAL sequence, `System.err.println` | Clock + error channel/log element; these ARE CCEK elements — the leakage is inside the assembly |
| `memory/MemoryStore.kt:186` | `java.util.concurrent.atomic.AtomicLong` seq counter in commonMain | Mutex-guarded Long or atomicfu |
| `cas/FunnelResidualMerge.kt:569` | `System.nanoTime()` in merge-key minting | Clock or injected entropy |
| `jules/BrainClient.kt:79` | `@Volatile private var lastGoodModelId` in commonMain | Mutex or single-writer reactor confinement (README table lists BrainClient ✅ otherwise) |

## 3. Cruft: callback lists where channels are the idiom

| Site | Pattern | Note |
|------|---------|------|
| `graal/ConfixBlackboard.kt:19,27,36` | `mutableMapOf` store + provenance + `mutableListOf<(ConfixDoc) -> Unit>` subscribers, sync `put` | Frontier component (midpoint-map Ring 2): `state` IS a ConfixDoc so the doc-truth is safe, but the subscriber list is pre-CCEK — a `SharedFlow<ConfixDoc>` or element-key subscription is the reactor form; no lifecycle states |
| `forge/ccek/ccek/SupervisorJob.kt:17` | `callbacks = mutableListOf<(T) -> Unit>()` — in a file literally named for the idiom | channel/Flow |
| `userspace/reactor/FanoutDispatcherElement.kt:25` | `mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>` handlers | CompletableDeferred per op (ChannelRunner already does this correctly — internal inconsistency between sibling reactor files) |
| `dag/BlackboardDagFabric.kt:433–435` | mutable node/handler lists + `(BlackboardEvent) -> Unit` handlers | the "event fabric for the entire system" runs on callbacks; ReteAgent beside it uses Channel — same package, both idioms |
| `jules/ui/JulesBlackboardAdapter.kt:92` | `sessionCache: MutableMap` unsynchronized | fine if reactor-confined; nothing declares the confinement (contrast KeyMux's documented single-writer) |

## 4. Cruft: vocabulary ahead of implementation

- `modelmux/RoutingStrategy.kt` — Priority/Weighted/CostOptimized/RoundRobin/
  Auto are **all identity functions**. Sealed hierarchy is the right taxonomy
  shape; the bodies are `taste.md` T29-class promises (implement or de-stub).
- `modelmux/CausalRoutingRule.kt` — takes `List<ModelCatalogEntry>` +
  `.filter/.sortedByDescending` (stdlib List), while sibling `AutoFallbackRule`
  correctly consumes a Cursor projection. Same package, two data algebras.

## 5. Cruft: type demotion (Series → List round-trips)

- `cursor/CursorOps.kt:64`, `cursor/SpanMatcher.kt:61`, `modelmux/ModelMux.kt:371`
  — `(0 until X.size).filter { … }` index loops; PRELOAD α-recipe applies.
- Eager `.toList()` hotspots (count): `CowBPlusTree` 11, `ViewServer` 7,
  `JobCheckpoint` 6, `forge/ccek/CCEK.kt` 6, `ModelMux` 3. Some are genuine
  List boundaries; the btree/checkpoint counts suggest demotion habits.

## 6. Reading

Per-file verdicts extend, not replace, README §7.5's table (which already
tracks BtrfsCasStore/ForgeReactorExample/LcncIngestPipeline/ModelCallLeaf/
ConfixEnvelopeCodec/ModelResponse gaps). Net: the assemblies at the seams
(NUID fanout, mux reactor, litebike) hold the idiom; cruft concentrates in
(a) metrics/observability written JVM-first, (b) blackboard-adjacent
subscriber lists predating the Channel discipline, (c) modelmux strategy
stubs. All three clusters sit exactly on midpoint-map seams M1–M3 — drawing
those midpoints is the natural moment each gets its reactor form.
