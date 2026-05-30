# OG1 CRMS Plugin — TODO

## Status: ✅ COMPILING — Both og1 and cascade compile clean

```
:libs:og1:compileKotlinJvm     → BUILD SUCCESSFUL
:libs:cascade:compileKotlinJvm → BUILD SUCCESSFUL
```

### Fixed (this session)

| File | Fix |
|------|-----|
| `ObservableConfixOracle.kt` | `twin.a.size` → `twin.a.a`; `CowSeriesHandle` import; `Integer.valueOf()` |
| `ConfixOracleService.kt` | `CowSeriesHandle` import, `.size` → `.a`, `Integer?` boxing, Series iteration |
| `TypeDefOracle.kt` | `MatchResult` destructuring → `mr.groupValues[2]` |
| `TrikeShedLib.kt` | Removed broken `c["close"]` stub |
| `CrmsTypes.kt` | `expect/actual` for UUID/time; `List` not `Tuple1` |
| `FanoutPlan.kt` | WirePool string pool; no `Tuple1`; `const val` → lazy init |
| `CrmsState.kt` | Pure data classes; no `Tuple1` |
| `PyEngine.kt` | `PyOutcome` (no `kotlin.Result` clash) |
| `LivePyEngine.kt` | `ProcessOperations` SPI; non-sealed interface |
| `WatermarkCursor.kt` | `item.b(col).a` for RowVec cell access |
| `IsAOwnership.kt` | Manual Series iteration (no `.any`) |
| `Readings.kt` | `m` Long→Int cast |

---

## og1 Plugin — Delivered Files

```
libs/og1/
  build.gradle.kts                    — Kotlin multiplatform, api(project(":"))
  src/commonMain/kotlin/borg/trikeshed/og1/
    og1.kt                             — Module index, initOg1()
    fanout/FanoutPlan.kt               — FanoutPlan, Story, WirePool, Payloads (12 wire keys)
    state/CrmsState.kt                 — CrmsState FSM, CrmsPhase, VoterFacet, QuorumState
    shape/ShapeCursor.kt               — Shape, ShapeCursor, ShapeCursorBox, Blackboard,
                                         ShapeToCursor, ShapeSchema.Cascade (5 eigenspace bases),
                                         EigenResult, stub eigensolvers
    cron/CrmsCron.kt                   — CrmsCron FSM runner, tick(), ingest(), ingestAll()
    shape/RingSeries.kt                — RingSeries synapse, pulse(), shims/data facets
    shape/RealtimePipeline.kt           — ingestAndTick(), ringSize(), currentPhase()
    shape/CrmsEigensolver.kt           — eigensolve per phase (GAP/KMEANS/QUORUM/DELIVER/MONITOR)
    voter/FacetVoterPanel.kt           — FacetVoterPanel, QuorumAccumulator, k-means cluster
    fanout/DeliveryRound.kt            — parallel wireproto execution with backpressure
    types/CrmsTypes.kt                 — VerdictKind, Branch, BrainstormState, validateStoryDependencies
    repl/PyEngine.kt                  — PyEngine (open interface), PyOutcome, PyEngineKind
  src/jvmMain/kotlin/borg/trikeshed/og1/
    types/CrmsTypes.kt                 — actual implementations (UUID, time)
    repl/LivePyEngine.kt              — LivePyEngine, SimulatedPyEngine, ScriptedPyEngine
```

---

## Cascade — Delivered Files

```
libs/cascade/
  build.gradle.kts                    — api(project(":"))
  src/commonMain/kotlin/borg/trikeshed/cascade/
    cascade.kt                         — Module index
    Readings.kt                       — Column ordinals, dateAxes(), rowVec()
    StatsReduce.kt                    — StatsAccum monoid, StatsReduce, statsGroupBy()
    CascadeViews.kt                   — byEntity, byGroup3/2/1/0, allViews()
    WatermarkCursor.kt                — versioned handle-body volatile cursor
    IsAOwnership.kt                   — TypeToken lattice, ownsEntity()
```

---

## Remaining Work

### P1 — FSM-driven kanban integration

- [ ] Wire og1 into Hermes cron-job experiment runner
- [ ] CrmsState FSM transitions driven by cron events (not agent delegation)
- [ ] DeliveryRound loop: worker goal → exec → critic goal → exec → verdict → advance/EDIT/RESTART

### P2 — Gap analysis / debt triage cron job

- [ ] Cron job: gap_analysis → debt triage → kanban card creation
- [ ] Use Payloads.GAP_ANALYSIS to scan typedef debt patterns
- [ ] Use Payloads.DEBT_TRIAGE to sort by severity + status
- [ ] Use Payloads.PANEL_VOTE for facet-k-means-seated voting

### P3 — Quorum FS

- [ ] Implement Payloads.QUORUM aggregation
- [ ] CRMS bare algebraic rules competing on performance + efficacy
- [ ] Quorum threshold: configurable confidence cutoff

### P6 — Hermes plugin SKILL.md + cron registry ✅

### P5 — Shape → Cursor blackboard (CRMS eigensolver)

- [x] `ShapeCursor` data class: `(shape: Shape, cursor: Series<RowVec>, version: Long)`
- [x] `Blackboard` class: `register(shape)`, `fetch(shape)`, `ingest(shape, rows)`, `eigensolve(phase)`
- [x] `ShapeToCursor` typealias: `(Shape) -> Series<RowVec>` — the fundamental projection operator
- [x] `ShapeSchema.validate(shape)` — dimensions must be distinct, >= 2
- [x] `Shape = IntArray` — first k columns = key axis, remaining = date axes (Y,M,D,H,min), metrics = reduce targets
- [x] `ShapeSchema.Cascade` — 5 canonical shapes (byEntity, byGroup3/2/1/0) as eigenspace bases
- [x] `CrmsCron` — FSM runner with `tick()`, `ingest()`, `ingestAll()` wiring cascade shapes to eigensolve
- [x] `RingSeries` — pulsing synapse with shims/data facets, `appendOrEvict`, `pulse()`, `resetShims()`
- [x] `RealtimePipeline` — `ingestAndTick()`, `ringSize()`, `currentPhase()`

### P6 — Hermes plugin SKILL.md + cron registry

- [x] og1 SKILL.md for Hermes plugin loader
- [x] Resumable script using Hermes delegation to most recent successful quotas
- [x] FSM-driven not agent-driven kanban analysis

### P7 — Tensor shapes in TrikeShed

- [x] `CrmsEigensolver` — eigensolve per phase: GAP (correlation + power iteration), KMEANS (k-means cluster assignment), QUORUM (dominant eigenvalue collapse), DELIVER (eigenvalue=1), MONITOR (eigenvalue=0)
- [x] `FacetVoterPanel` — faceted k-means-seated voter panel with QuorumAccumulator
- [x] `DeliveryRound` — parallel wireproto execution with backpressure
- [ ] Tensor.zipped(shape) for multi-cursor operations

---

## Architecture Notes

### Wireproto String Pool

Payloads live in `WirePool` — constant-sized index references, decoded on demand:

```
WirePool.register("worker_goal", pythonSource)
Payloads.WORKER_GOAL  // constant wire key — 12 bytes
Payloads["worker_goal"]  // decode → python string (lazy)
```

### CRMS as Spectral Eigenspace Decomposition

```
BRAINSTORM  →  GAP  →  KMEANS  →  QUORUM  →  DELIVER  →  MONITOR
   │           │        │          │          │            │
   │           │        │          │          │            └── eigenvalue: 0 (absorbing)
   │           │        │          │          └── eigenvalue: 1 (converged)
   │           │        │          └── eigenvalue: dominant → selects winner
   │           │        └── eigenvector: cluster assignment
   │           └── eigenvectors: gap/typedef correlations
   └── eigenvectors: branch/scope similarity matrix
```

### ShapeCursor as versioned handle-body

```kotlin
data class ShapeCursor(
    val shape: Shape,           // stable handle (IntArray dimensions)
    val cursor: Series<RowVec>, // volatile body
    val watermark: Long,         // monotonic version
)

class Blackboard {
    private val shadows = mutableMapOf<Shape, ShapeCursor>()

    fun project(shape: Shape, readings: Series<RowVec>): ShapeCursor {
        val grouped = readings.groupBy(shape) { acc, v -> StatsReduce(acc, v) }
        return ShapeCursor(shape, grouped, advanceWatermark()).also { shadows[shape] = it }
    }

    fun eigensolve(phase: CrmsPhase): Map<Shape, EigenResult> =
        shadows.mapValues { (_, sc) ->
            when (phase) {
                CrmsPhase.GAP    -> eigenvectorCorrelation(sc.cursor)
                CrmsPhase.KMEANS -> kMeansCluster(sc.cursor, sc.shape)
                CrmsPhase.QUORUM -> dominantEigenvector(sc.cursor)
                else             -> emptyResult()
            }
        }
}
```

### Cascade Views as Shape projections

```
byEntity()   → Shape([entity_id, Y, M, D, H, min])         — Level 1
byGroup3()   → Shape([group_3, entity_id, Y, M, D, H, min])  — Level 2
byGroup2()   → Shape([group_2, entity_id, Y, M, D, H, min])  — Level 3
byGroup1()   → Shape([group_1, entity_id, Y, M, D, H, min])  — Level 4
byGroup0()   → Shape([group_0, entity_id, Y, M, D, H, min])  — Level 5
```

### Cascade Lattice

```
                  Group0
                /   |    \
          Group1  Group2  Group3
                \   |    /
                Entity
```

Transitive closure via `IsALattice`: Entity IS-A Group3/2/1/0.

---

## Gradle Notes

- `settings.gradle.kts`: og1 removed from `excludedLibs`
- `build.gradle.kts`: uses `kotlin("multiplatform")` without explicit version (inherits root's 2.4.0-RC2)
- og1: `api(project(":"))` — depends on root project for ProcessOperations
- cascade: `api(project(":"))` — depends on root for Cursor/RowVec/Series/TypeSubsumption