# TrikeShed Gallery: Signal → LCNC → Forge Pipeline

## Overview

This gallery demonstrates the complete signal processing pipeline from primitive signals through LCNC neural computation to Forge visual workflows.

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  user-signals   │────▶│      lcnc       │────▶│   forge-ui      │────▶│  Forge Apps     │
│  (0D/1D/ND)     │     │ (CRMS/Phase)    │     │ (Palettes)      │     │ (Workflows)     │
└─────────────────┘     └─────────────────┘     └─────────────────┘     └─────────────────┘
```

---

## Stage 1: user-signals (Primitive Signals)

**Location:** `libs/user-signals/src/commonMain/kotlin/borg/trikeshed/usersignals/`

### 0D Signals (Boolean/Discrete)
```kotlin
// Toggle - persistent on/off
val toggle = Toggle()
toggle.observe { println("Toggled: $it") }
toggle.toggle()

// IdiotLight - transient flash/pulse
val light = IdiotLight()
light.flash()  // auto-resets
light.pulse()  // brief activation

// MomentaryButton - press/release/tap
val btn = MomentaryButton()
btn.onPress { println("Pressed") }
btn.onRelease { println("Released") }
btn.press()

// RadioToggle<T> - exclusive selection
val radio = RadioToggle<String>(options = listOf("A", "B", "C"))
radio.select("B")
```

### 1D Signals (Continuous)
```kotlin
// Slider - linear range
val slider = Slider(range = 0.0..1.0, initial = 0.5)
slider.observe { println("Value: $it") }
slider.moveTo(0.8)

// Knob - circular with detents
val knob = Knob(range = 0..127, detents = 16)
knob.rotate(3)

// Dial<T> - parametric
val dial = Dial<Color> { hsv -> Color.HSV(hsv.x, hsv.y, hsv.z) }
dial.set(HSV(0.5, 1.0, 1.0))

// LevelMeter - readout with optional peak hold
val meter = LevelMeter(range = -60.0..0.0)
meter.update(-12.0)
```

### Widget System
```kotlin
// Widget binding to signals
interface Widget<T> {
    val signal: Signal<T>
    fun render(value: T, ctx: RenderContext): Rendered
}

// Composite layouts
sealed class Layout {
    data class Row(children: List<Widget<*>>) : Layout()
    data class Column(children: List<Widget<*>>) : Layout()
    data class Grid(children: List<Widget<*>>, cols: Int) : Layout()
    data class Flex(children: List<Widget<*>>, direction: Direction) : Layout()
}
```

### Algebra
```kotlin
// Signal composition
val combined = slider.

*2  // scale
val summed = slider + knob  // zip and add
val product = slider * knob // zip and multiply
```

---

## Stage 2: lcnc (Liquid Computing Neural Computation)

**Location:** `libs/lcnc/src/commonMain/kotlin/borg/trikeshed/lcnc/`

### CRMS (Conflict Resolution Multi-Space)
```kotlin
// Bare rules as candidates
data class BareRule(
    val id: String,
    val condition: (Vector) -> Boolean,
    val action: (Vector) -> Vector
)

// Fanout plan for parallel evaluation
data class FanoutPlan(
    val rules: List<BareRule>,
    val quorumThreshold: Double = 0.66
)

// Verdict kinds
sealed class VerdictKind {
    data class Accept(val rule: BareRule) : VerdictKind()
    data class Reject(val rule: BareRule) : VerdictKind()
    data class Abstain : VerdictKind()
}

// Eigenvalue-based quorum
fun crmsCycle(input: Vector, plan: FanoutPlan): VerdictKind {
    val votes = plan.rules.map { rule ->
        if (rule.condition(input)) VerdictKind.Accept(rule)
        else VerdictKind.Reject(rule)
    }
    return if (votes.count { it is VerdictKind.Accept } >= plan.quorumThreshold * plan.rules.size)
        VerdictKind.Accept(votes.filterIsInstance<VerdictKind.Accept>().maxBy { it.rule.priority }!!)
    else VerdictKind.Abtain()
}
```

### Phase Algebra
```kotlin
// Phase states carry both value and phase metadata
data class PhaseState<A>(
    val value: A,
    val phase: Phase,  // e.g., PRE, ACTIVE, POST
    val metadata: Map<String, Any>
)

// Transition across phases
infix fun <A> PhaseState<A>.through(f: (A) -> PhaseState<B>): PhaseState<B> {
    val next = f(value)
    return next.copy(phase = next.phase.next())
}
```

### K-Means Clustering (Story Grouping)
```kotlin
// Stories grouped in embedding space
data class Story(
    val id: String,
    val embedding: Vector,
    val metadata: Map<String, Any>
)

fun kmeansCluster(stories: List<Story>, k: Int): List<List<Story>> {
    // Lloyd's algorithm with cosine distance
    var centroids = stories.shuffled().take(k).map { it.embedding }
    var clusters = List(k) { mutableListOf<Story>() }
    
    repeat(10) {
        // Assign
        clusters.forEach { it.clear() }
        stories.forEach { story ->
            val nearest = centroids.indices.minByOrNull { 
                cosineDistance(it.embedding, centroids[it]) 
            }!!
            clusters[nearest].add(story)
        }
        // Update
        centroids = clusters.map { cluster ->
            cluster.map { it.embedding }.reduce { a, b -> a + b } / cluster.size
        }
    }
    return clusters
}
```

---

## Stage 3: forge-ui Palettes

**Location:** `libs/forge-ui/src/commonMain/kotlin/borg/trikeshed/forge/ui/`

### Graphical Palette Components
```kotlin
// Palette registry
interface Palette<T> {
    val id: String
    val name: String
    val icon: ImageVector
    val category: Category
    fun create(): T
    fun configure(initial: T?): T
}

// Drag-and-drop palette surface
@Composable
fun PaletteSurface(
    palettes: List<Palette<*>>,
    onDragStart: (Palette<*>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn {
        items(categories) { category ->
            CategoryHeader(category)
            LazyHorizontalGrid(
                cells = GridCells.FixedSize(64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(palettes.filter { it.category == category }) { palette ->
                    PaletteCard(palette, onDragStart)
                }
            }
        }
    }
}

// Node editor canvas
@Composable
fun NodeEditorCanvas(
    nodes: List<NodeView>,
    edges: List<EdgeView>,
    onNodeDrag: (NodeView, Offset) -> Unit,
    onEdgeConnect: (OutputPort, InputPort) -> Unit
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Render edges
        edges.forEach { edge ->
            drawBezier(edge.source, edge.target, strokeWidth = 2.dp)
        }
        // Render nodes
        nodes.forEach { node ->
            drawNode(node)
        }
    }
}
```

---

## Stage 4: Forge Starter Usecases

**Location:** `libs/forge/src/commonMain/kotlin/borg/trikeshed/forge/`

### Usecase 1: Data Pipeline Builder
```kotlin
// Workflow: CSV → Transform → Aggregate → Export
val pipeline = workflow {
    val source = csvSource("data/raw/*.csv")
    val cleaned = transform(source) { row ->
        row.mapValues { (k, v) -> clean(k, v) }
    }
    val aggregated = reduce(cleaned) { 
        groupBy { it["category"] }.mapValues { (_, rows) -> 
            rows.map { it["value"].toDouble() }.average() 
        }
    }
    export(aggregated, format = ExportFormat.JSON)
}

// Cascade execution
val cascade = cascade {
    source("raw-data", Format.CSV)
    map("clean", ::cleanRow)
    reduce("by-category", ::aggregateCategory)
    rereduce("totals", ::sumTotals)
    sink("output", Format.JSON)
}
```

### Usecase 2: Agent Swarm Orchestration
```kotlin
// Swarm topology
val swarm = swarm {
    root("analyze-logs", graph = Graph())
    workers = listOf(
        Worker("extract-errors", skills = listOf("regex", "log-parsing")),
        Worker("extract-metrics", skills = listOf("stats", "time-series")),
        Worker("extract-traces", skills = listOf("distributed-tracing"))
    )
    verifier = Verifier("cross-check", workers = workers)
    synthesizer = Synthesizer("report", dependsOn = verifier)
}

// Kanban board for swarm
val board = kanbanBoard {
    column("TODO") { cards = swarm.workers.map { it.toCard() } }
    column("DOING") { wipLimit = 3 }
    column("REVIEW") { dependsOn = "DOING" }
    column("DONE")
}
```

### Usecase 3: Notion ↔ Forge Sync
```kotlin
// Bidirectional sync
interface NotionSync {
    fun push(page: ForgePage): NotionPage
    fun pull(notionPage: NotionPage): ForgePage
    fun watchChanges(): Flow<SyncEvent>
}

// Cursor-driven Notion database
val db = cursorDrivenNotionDatabase {
    schema = Schema(
        fields = listOf(
            Field("title", Type.TITLE),
            Field("status", Type.SELECT, options = ["TODO", "DOING", "DONE"]),
            Field("priority", Type.NUMBER),
            Field("assignee", Type.PERSON)
        )
    )
    // Auto-sync from Forge kanban
    forgeBoard.cards.forEach { card ->
        upsert(card.toNotionRecord())
    }
}
```

---

## Complete Pipeline Example

```kotlin
// Full pipeline: Signal → LCNC → Forge → Result
fun main() = runBlocking {
    // 1. Define input signals
    val sensor = Slider(range = 0.0..100.0, initial = 50.0)
    val threshold = Knob(range = 0..100, detents = 20, initial = 75)
    
    // 2. LCNC processing
    val plan = FanoutPlan(
        rules = listOf(
            BareRule("high", { it > threshold.value }, { it * 2.0 }),
            BareRule("low", { it < threshold.value }, { it * 0.5 })
        )
    )
    val crmsResult = crmsCycle(sensor.value.toVector(), plan)
    
    // 3. Forge workflow execution
    val workflow = workflow {
        val input = constant(crmsResult.value)
        val processed = map(input) { transform(it) }
        val output = reduce(processed, ::aggregate)
    }
    
    // 4. Visualize in Forge UI
    val canvas = NodeEditorCanvas(
        nodes = workflow.toNodes(),
        edges = workflow.toEdges()
    )
    
    // 5. Deploy
    deploy(workflow)
}
```

---

## Gallery Widget App (JS Demonstration)

```kotlin
// gallery-app/src/main/kotlin/GalleryApp.kt
@Composable
@Preview
fun GalleryApp() {
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            // Stage 1: Signal Controls
            SignalPalette()
            
            // Stage 2: LCNC Visualization
            LCNCVisualizer()
            
            // Stage 3: Forge Palette
            ForgePaletteSurface()
            
            // Stage 4: Workflow Canvas
            NodeEditorCanvas()
        }
    }
}

// Run: ./gradlew :gallery-app:run
```

---

## Running the Gallery

```bash
# Build all commonMain modules
./gradlew :user-signals:compileKotlinJs :lcnc:compileKotlinJs :forge-ui:compileKotlinJs

# Run gallery app
./gradlew :gallery-app:run

# Or run Forge UI
./gradlew :forge-ui:run
```

---

## Source Sample Locations

| Component | Source Path |
|-----------|-------------|
| Signals (0D/1D/ND) | `libs/user-signals/src/commonMain/...` |
| Signal Algebra | `libs/user-signals/src/commonMain/.../Algebra.kt` |
| Widgets/Rendering | `libs/user-signals/src/commonMain/.../Rendering.kt` |
| LCNC Core | `libs/lcnc/src/commonMain/.../reduction/` |
| CRMS/Phase/Value | `libs/lcnc/src/commonMain/.../reduction/` |
| Forge Types | `libs/forge/src/commonMain/.../ForgeTypes.kt` |
| Forge Palettes | `libs/forge-ui/src/commonMain/.../ForgeComponents.kt` |
| Window Toolkit | `libs/window-toolkit/src/commonMain/...` |
| CCEK Elements | `libs/ccek-core`, `libs/ccek-dsl` |
| LCNC Grid | `libs/lcnc/src/commonMain/.../LcncGrid.kt` |
| Forge Kanban | `libs/forge/src/commonMain/.../kanban/` |
| ISAM/Confix | `src/commonMain/kotlin/borg/trikeshed/isam/` |
| CCEK Runtime | `libs/ccek-core`, `libs/ccek-dsl` |