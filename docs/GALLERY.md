# TrikeShed Gallery — Stack Visualization

> From user signals to forge workflows: a visual tour of the LCNC (Low-Code / No-Code) continuum.

---

## Layer 0: Kernel Algebra (PRELOAD.md)

Everything collapses to `Join<A, B>` — the base binary composition.

```
Join<A, B>     base product type (two fields, no overhead)
Series<T>      = Join<Int, (Int) -> T>    size + index oracle
Cursor         = Series<RowVec>           columnar dataframe
RowVec         = Series2<Any?, () -> ColumnMeta>   values + metadata
α              lazy projection over Series
j              infix constructor: a j b
```

```
┌─────────────────────────────────────────────────┐
│                  Join<A, B>                      │
│   ┌─────┐   ┌─────┐   ┌──────┐   ┌──────┐      │
│   │  a  │   │  b  │   │ Twin │   │Series│      │
│   └──┬──┘   └──┬──┘   └──┬───┘   └──┬───┘      │
│      └─────┬───┘           │          │          │
│         Join pair        Join<T,T>  Join<Int,(Int)->T>│
└─────────────────────────────────────────────────┘
```

---

## Layer 1: Use-Signals — Signal Primitives

The `Use × Carrier × Seam` taxonomy generates every user-facing signal.

```
┌──────────────────────────────────────────────────────────┐
│                     Signal<T>                             │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│   │ .value   │  │ .changes │  │ .map()   │              │
│   │ Observe  │  │Subscribe │  │  Query   │              │
│   └──────────┘  └──────────┘  └──────────┘              │
│         SignalSource<T>.emit()  →  Command               │
└──────────────────────────────────────────────────────────┘
```

### Signal Palette

| Widget | Signal Type | Carrier | Seam |
|--------|-------------|---------|------|
| `Toggle` | `Signal<Boolean>` | Finite(Bool) | Coupling/Logic |
| `IdiotLight` | `Signal<Boolean>` | Finite(Bool) | Logic |
| `MomentaryButton` | `Signal<Boolean>` | Finite(Bool) | Coupling |
| `RadioToggle<T>` | `Signal<T>` | Finite(T) | Coupling/Logic |
| `Slider` | `Signal<Double>` | Ordered([min,max]) | Coupling/Logic |
| `Knob` | `Signal<Double>` | Ordered([min,max]) | Coupling/Logic |
| `Dial<T>` | `Signal<T>` | Sequence(Finite(T)) | Coupling/Logic |
| `LevelMeter` | `Signal<Double>` | Ordered([0,1]) | Logic |
| `TextField` | `Signal<TextFieldState>` | Sequence ⊗ Product | Coupling/Logic/Notification |

### TextFieldState Carrier

```
TextFieldState = Product(
    Sequence(Char),   ← text body
    Finite(Int),      ← caret position
    Finite(Int),      ← selection start
    Finite(Int),      ← selection end
    Finite(Bool),     ← focused
    Finite(Bool)      ← committed
)
```

### Signal Algebra (Combinators)

```kotlin
SignalAlgebra.beside(left, right)     // → Tensor(A, B) @ Computation
SignalAlgebra.above(top, bottom)      // → Tensor(A, B) @ Computation
SignalAlgebra.overlay(bottom, top)    // → Tensor(A, B) @ Computation
SignalAlgebra.whenVisible(cond, comp) // → Finite(Bool) ⊗ A @ Computation
```

---

## Layer 2: Visual Templates & Rendering

Templates bind signal holes to visual output.

```
┌─────────────────────────────────────────────────┐
│              VisualTemplate                     │
│   holes: List<TemplateHole<*>>                  │
│   render(bindings) → TemplateOutput             │
└──────────────────────┬──────────────────────────┘
                       │
          ┌────────────┴────────────┐
          │  TemplateHole<T>        │
          │  "title" → Signal<String>│
          │  "count" → Signal<Int>   │
          └─────────────────────────┘
```

### Render Backends

| Backend | Target | Output |
|---------|--------|--------|
| `TextBackend` | Console/Terminal | Plain text |
| `JsonBackend` | API/Machine | JSON document |
| `AnsiBackend` | Terminal | Colored ANSI |
| `ComposeBackend` | Desktop/Web | Compose UI tree |

### ConsoleRenderer Example

```
┌─────────────────────────────────────┐
│ ● Forge Board      [3] [5] [2]     │  ← IdiotLight + LevelMeter
│ ┌─────────┐ ┌─────────┐ ┌────────┐ │
│ │ TODO    │ │ DOING   │ │  DONE  │ │  ← Toggle indicators
│ │ ▓▓▓░░░  │ │ ▓▓░░░░  │ │ ▓▓▓▓▓▓ │ │  ← LevelMeter fills
│ └─────────┘ └─────────┘ └────────┘ │
│ [Run All] [Pause] [Export]         │  ← MomentaryButton
└─────────────────────────────────────┘
```

---

## Layer 3: LCNC — Low-Code / No-Code Reduction

LCNC reduces signal flows through a four-algebra pipeline.

```
         Signal<T>                    LCNC Reduction                 Cursor
     (user-signals)          ┌─────────────────────────┐      (forge workspace)
          │                  │                         │              │
          ▼                  │  KeyAlg<K>              │              ▼
    ┌──────────┐             │  ├─ extract keys        │      ┌───────────┐
    │ Faceted  │             │  └─ build hierarchy     │      │ Faceted   │
    │ Signal   │ ──────────► │                         │ ───► │ Cursor    │
    └──────────┘             │  ValueAlg<V, Acc>       │      └───────────┘
                             │  ├─ fold (map)          │
                             │  └─ merge (reduce)      │
                             │                         │
                             │  PhaseAlg               │
                             │  ├─ MAP stage           │
                             │  ├─ REDUCE stage        │
                             │  └─ REREDUCE stage      │
                             │                         │
                             │  CarrierAlg<V>          │
                             │  ├─ Series carrier      │
                             │  ├─ Ring carrier        │
                             │  └─ Array carrier       │
                             └─────────────────────────┘
```

### Reduction Phases

```
Phase 1: MAP        ─ emit(key, value) pairs from input
    │
Phase 2: REDUCE     ─ fold values per key: (acc, v) → acc
    │
Phase 3: REREDUCE   ─ merge partials: Series<Acc> → Acc
    │
Phase 4: MATERIALIZE ─ project to output Cursor
```

### Builtin Reducers

```kotlin
enum class BuiltinReducer {
    SUM, COUNT, MIN, MAX, AVG, STDDEV,
    PERCENTILE_50, PERCENTILE_95, PERCENTILE_99,
    CONCAT, FIRST, LAST,
    // CRMS-specific
    PAIR_BEFORE_AFTER,
    EIGSORT_BY_DEPTH
}
```

---

## Layer 4: Forge — Workflow Fabric

Forge orchestrates typed DAG workflows over the workspace.

### Workflow Step Types

```
┌─────────────────────────────────────────────────────────────┐
│                    WorkflowStep                             │
│                                                             │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐              │
│  │  LlmCall  │  │  Code     │  │  Agent    │              │
│  │           │  │ Execution │  │ Invocation│              │
│  │ model     │  │ language  │  │ agentType │              │
│  │ promptId  │  │ code      │  │ task      │              │
│  │ inputs    │  │ inputs    │  │ context   │              │
│  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘              │
│        │              │              │                     │
│  ┌─────┴─────┐  ┌─────┴─────┐  ┌─────┴─────┐              │
│  │ File      │  │ Condition │  │ Parallel  │              │
│  │ Transform │  │ al        │  │           │              │
│  │ transform │  │ condition │  │ branches  │              │
│  │ output    │  │ then/else │  │ [[]]      │              │
│  └───────────┘  └───────────┘  └───────────┘              │
│                                                             │
│  ┌──────────────────────────────────────────┐              │
│  │ CascadeExecution                         │              │
│  │ cascadeId, inputs → map/reduce/rereduce  │              │
│  └──────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

### Patch Bay — Modular Signal Routing

Each workflow step becomes a module with typed ports:

```
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │ LlmCall │──┐   │  Code   │──┐   │ Agent   │
    │         │  │   │ Exec    │  │   │ Invoke  │
    │ ◦ text ─┼──┼──►│ ◦ summary┼──┼──►│ ◦ data  │
    │ ◦ model │  │   │ ◦ lang  │  │   │ ◦ tools │
    │   output┼──┘   │   output┼──┘   │   output┼──►
    └─────────┘       └─────────┘       └─────────┘
         │                                  │
         └──────────┐    ┌──────────────────┘
                    ▼    ▼
              ┌─────────────┐
              │ FileTransform│
              │   output     │──► Artifact
              └─────────────┘
```

### Patch Cable Shape Dimensions

| Dimension | Visual | Applies To |
|-----------|--------|------------|
| SIGNAL_FLOW | → | LlmCall, Code, Agent, File, Cascade |
| CONTROL_FLOW | ⚡ | Conditional, Parallel |
| STATE_MUTATION | 💾 | Code, Agent, Cascade |
| PARALLELISM | ⏱ | Parallel |
| EXTERNAL_IO | 🌐 | LlmCall, Agent, File |
| STOCHASTIC | 🎲 | LlmCall |
| FEEDBACK | 🔄 | Code, Agent |
| MODULATION | 〰 | LlmCall |
| COMPOSITE | 📦 | Cascade |
| SPECTRAL | 📊 | Cascade |

---

## Layer 5: Forge UI — Graphical Palette

### Color Palette

```
  Background     Surface        Primary       Accent
  ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐
  │#0B0F14 │    │#1E1E1E │    │#7AA2F7 │    │#BB9AF7 │
  │ ████   │    │ ████   │    │ ████   │    │ ████   │
  └────────┘    └────────┘    └────────┘    └────────┘

  Success        Warning        Error         Muted
  ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐
  │#9ECE6A │    │#E0AF68 │    │#F7768E │    │#AAAAAA │
  │ ████   │    │ ████   │    │ ████   │    │ ████   │
  └────────┘    └────────┘    └────────┘    └────────┘
```

### Component Library

```
┌─────────────────────────────────────────────────────┐
│  ForgeCard                                          │
│  ┌───────────────────────────────────────────────┐  │
│  │  Title (24sp, Bold)                           │  │
│  │  Subtitle (14sp, Muted)                       │  │
│  │                                               │  │
│  │  ┌─────────┐  ┌──────────┐  ┌─────────────┐ │  │
│  │  │ Metric  │  │ Progress │  │ Status Badge│ │  │
│  │  │ value   │  │ ▓▓▓░░░   │  │ ● Active    │ │  │
│  │  └─────────┘  └──────────┘  └─────────────┘ │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘

┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ ForgeButton │  │ ForgeToggle │  │ ForgeInput  │
│ [ Run ]     │  │ [ON / OFF]  │  │ [type here] │
└─────────────┘  └─────────────┘  └─────────────┘
```

---

## Layer 6: Starter Use Cases

### Use Case 1: Document Processing Pipeline

```
Input: "Quarterly report Q4 2026..."
    │
    ▼
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ LlmCall  │──►│ Code     │──►│ Agent    │──►│ Artifact │
│ Summarize│    │ Extract  │    │ Enhance  │    │ Export   │
│          │    │ entities │    │ metadata │    │ JSON     │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### Use Case 2: Cascade Analytics (Map-Reduce)

```
Input: JSONL machine telemetry
    │
    ▼
┌──────────────────────────────────────┐
│  Cascade Detection                   │
│  ┌─────────┐  ┌─────────┐           │
│  │ machine │  │ metric  │           │
│  └────┬────┘  └────┬────┘           │
│       │            │                 │
│  MAP:     emit([machine], {metric})  │
│  REDUCE:  sum/count/avg per machine  │
│  REREDUCE: merge across partitions   │
└──────────────────────────────────────┘
    │
    ▼
Output: CascadeOutputRow[
    { machine: "A", sum: 5500, count: 100, avg: 55.0 },
    { machine: "B", sum: 3200, count: 80,  avg: 40.0 }
]
```

### Use Case 3: Kanban Board with Dependencies

```
┌─────────────────────────────────────────────────┐
│  Kanban Board: "Sprint 42"                      │
│                                                 │
│  ┌─── TODO ───┐  ┌── DOING ──┐  ┌── DONE ──┐  │
│  │            │  │           │  │          │  │
│  │ [HIGH]     │  │ [CRITICAL]│  │ [MED]    │  │
│  │ Refactor   │──►| Fix bug  │  │ ✓ Tests  │  │
│  │ storage    │  │ in WAL    │  │ passing  │  │
│  │            │  │           │  │          │  │
│  │ [LOW]      │  │ [HIGH]    │  │ [HIGH]   │  │
│  │ Update     │  │ Add CAS   │  │ ✓ DSL    │  │
│  │ docs       │  │ layer     │  │ shipped  │  │
│  └────────────┘  └───────────┘  └──────────┘  │
│                                                 │
│  Dependencies: Refactor ──blocks──► Fix bug    │
└─────────────────────────────────────────────────┘
```

### Use Case 4: Patch Bay Signal Router

```
     Source Ports          Dest Ports
     ┌──────┐              ┌──────┐
  ◦─ │step1 │─────────────►│step3 │ ─◦
  ◦─ │output│              │input │ ─◦
     └──────┘              └──────┘
     ┌──────┐              ┌──────┐
  ◦─ │step2 │────┐   ┌────►│step4 │ ─◦
  ◦─ │output│    │   │     │input │ ─◦
     └──────┘    │   │     └──────┘
                 │   │
     ┌──────┐    │   │     ┌──────┐
  ◦─ │step3 │────┴───┴────►│step5 │ ─◦
  ◦─ │output│              │input │ ─◦
     └──────┘              └──────┘

  Cables: step1.output → step3.input   (SIGNAL_FLOW →)
          step2.output → step4.input   (SIGNAL_FLOW →)
          step3.output → step4.input   (SIGNAL_FLOW →)
          step3.output → step5.input   (SIGNAL_FLOW →)
```

---

## Layer 7: Data Transforms

### Cursor Transform Chain

```
Raw JSON ──► ConfixDoc ──► FacetedCursor ──► Reduction ──► Artifact
                │                │               │            │
                │                │               │            │
           parse JSON       project cols      group by     export
           build index      filter rows       aggregate    JSON/ZIP
           resolve paths    join cursors      rereduce
```

### Transform Examples

```kotlin
// 1. Parse JSON to Cursor
val cursor = confixFacetBridge.parse(jsonText)

// 2. Project columns
val projected = cursor.facet("schema")  // column metadata view

// 3. Filter rows
val filtered = cursor.where { row -> row["status"] == "active" }

// 4. Aggregate
val summary = cursor.aggregate("machine", "cpu_mhz") { values ->
    values.map { it as Double }.average()
}

// 5. Export as artifact
val artifact = workspace.artifact("Report", "description", files)
val exported = workspace.export(artifact.id, ExportFormat.JSON)
```

---

## NodeJS Demo (Bun Install)

A minimal NodeJS demo that renders the gallery in a browser:

```json
// package.json
{
  "name": "trikeshed-gallery",
  "version": "1.0.0",
  "scripts": {
    "dev": "bun run index.ts",
    "build": "bun build index.ts --outdir ./dist"
  },
  "dependencies": {}
}
```

```typescript
// index.ts — Bun-compatible HTTP server
const PORT = 3000

const html = `<!DOCTYPE html>
<html>
<head><title>TrikeShed Gallery</title></head>
<body>
<h1>TrikeShed Stack Gallery</h1>
<pre>
Layer 0: Kernel Algebra    Join<A,B>, Series<T>, Cursor
Layer 1: Use-Signals       Signal<T>, SignalSource, Toggle, Slider
Layer 2: Visual Templates  TemplateHole, SignalComponent, RenderBackend
Layer 3: LCNC              KeyAlg, ValueAlg, PhaseAlg, CarrierAlg
Layer 4: Forge             WorkflowStep, PatchBay, Cascade, Artifact
Layer 5: Forge UI          ForgeCard, ForgeButton, dark palette
Layer 6: Use Cases         Document pipeline, cascade analytics, kanban
Layer 7: Transforms        ConfixDoc → Cursor → Reduction → Artifact
</pre>
</body>
</html>`

const server = Bun.serve({
  port: PORT,
  fetch(req) {
    return new Response(html, {
      headers: { "Content-Type": "text/html" }
    })
  }
})

console.log(\`Gallery running at http://localhost:\${PORT}\`)
```

```bash
# Install and run
bun install
bun run dev
# → Gallery running at http://localhost:3000
```

---

## Appendix: Seam Routing

```
┌─────────────────────────────────────────────┐
│              Seam Factor                     │
│                                             │
│  Logic         Computation    Notification   │
│  (pure read)   (derived)      (fanout)       │
│     │             │               │          │
│  L_GET/L_SET   Signal.map     Signal.changes │
│     │             │               │          │
│  ─────────────────────────────────────────   │
│                                             │
│  Coupling (physical)                        │
│     │                                       │
│  P_GET/P_SET  → ioctl/kernel boundary       │
│  SignalSource.emit → write to hardware      │
└─────────────────────────────────────────────┘
```

---

*Generated from TrikeShed working tree — June 2026*