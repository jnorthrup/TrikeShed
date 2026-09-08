# IntelliJ ⇄ Blackboard: Truffle / Bytecode View — spec

Status: draft 2026-08-21, grounded in a from-disk review (file:line below). Companion to
`docs/forge-substrate-plan.md` (Phases 2–3 there are prerequisites here).

## 0. One sentence

A view — in IDEA and on the Forge JS surface — of the **join** between four fact rings that share a
coordinate: static bytecode (classfile), dynamic JVM trace (typedef production system), guest-VM execution
(Graal/Truffle), and the IDE's own semantic model (PSI, diagnostics, caret). The blackboard is the bus;
Rete is where the join happens; IDEA and Forge are two projections of the same facts.

## 1. Review — what exists, exactly

### 1.1 Coordinates (three of them, none joined)

| type | fields | where | has source loc? |
|---|---|---|---|
| `PointcutCoordinate` (static) | `kind: BytecodePointcutKind, jvmOpcode, bytecodeOffset, source: SourceCoordinate(sourceFile,line,column,language,bytecodeOffset), symbol: SymbolCoordinate(owner,name,descriptor,methodName,methodDescriptor)` | `src/commonMain/kotlin/borg/trikeshed/classfile/model/PointcutCoordinate.kt:32-48` | **yes** |
| `DagCoordinate` (runtime) | `className, methodName, bytecodeOffset, timestamp, threadId` | `dag/BlackboardDagFabric.kt:30` | no |
| `PointcutLanding.key` (string) | `"pointcut/<typedef>/<method>/<siteIdx>"` | `src/jvmMain/kotlin/borg/trikeshed/pointcut/PointcutBlackboardAdapter.kt:345` | no |

`BytecodePointcutKind` already has 18 kinds (`PointcutCoordinate.kt:11`) — richer than anything the
runtime emits. `FieldSynapse` (`cursor/FieldSynapse.kt:16`) carries only `OP_L_GET/L_SET/P_GET/P_SET`
(0xA5–0xA8) and `TPL_BEFORE/AFTER_{GET,SET}`; `TypedefProductionSystem` adds `OP_CALL/ALLOC/RETURN/
PROPERTY/PARAMETER/CAST` (0x10–0x15). Nothing maps these to `BytecodePointcutKind`.

**Finding A:** there is no join key. An IDEA gutter marker needs `(file, line, column)`; only the static
ring has it. Every dynamic landing must be enriched by joining to the static ring on
`(owner, methodName, methodDescriptor, bytecodeOffset)`.

### 1.2 Bytecode ring (static)

- Reader: `src/jvmMain/java/borg/trikeshed/cursor/ClassfileTaxonomy.java:38` — `java.lang.classfile`
  (JDK 25 public API, not `jdk.internal`). Emits `Row(kind ∈ {CLASS, FIELD, METHOD, INSTRUCTION,
  CONSTANT}, cols)`, `instructionHistogram()`, `invokeSummary()`, `methods()`, `instructions()`.
  **It does not emit `PointcutCoordinate`.**
- A second reader, ASM-based: `ClassfileBlackboardAdapter.java:17` (`attachClass`, `parseAndRegister`,
  `flush` into `ConfixBlackboard`). **Finding B:** two classfile readers, different libraries, neither
  produces the common coordinate type.
- Rewriter: `JavaAotClassfileTransformer.java:14 transform(bytes)` — `ClassTransform`/`CodeBuilder`. This
  is the injection point for pointcuts.
- `runtime/ConfixClassfileDir.kt:17 pathOf(pc)`, `:21 nodeVal(pc)` — a content-addressed path scheme for
  `PointcutCoordinate` already exists in commonMain. Use it as the join key's string form.

### 1.3 JVM trace ring (dynamic)

- `TypedefProductionSystem` (`src/jvmMain/kotlin/borg/trikeshed/cursor/TypedefProductionSystem.kt:33`):
  ring (`RING_CAP=2048`) → slab → `fold(slab): List<ConflictCell>`; `SlabSubscriber.onSlab(slab, count,
  epoch, nanoStart, nanoEnd)`; `TraceEvent(opcode, phase, typedefIdx, methodIdx, siteIdx, seq, nano, depth,
  callsiteHash, templateIdx)`; `InternPool` (64k strings).
- `PointcutBlackboardAdapter` (`:71`) is the existing slab→blackboard bridge: `PointcutLanding(key,
  coordinate: DagCoordinate, mark: PointcutMark, facet: VmFacet, propertyName, value)`, exposed as
  `flow: SharedFlow<PointcutLanding>` (`:130`) and `landings: Series<PointcutLanding>` (`:133`). It writes
  into `ConfixBlackboard` and serializes writes itself (`:314`).
- **Finding C:** this ring never reaches Rete. `ReteFact` has exactly six variants (`BoardFact, CardFact,
  DependencyFact, OverlayFact, DagFact, NodeFact` — `BlackboardDagFabric.kt:204-259`), and
  `ReteRule.predicate` is typed `(CausalGraphNode) -> Boolean` (`dag/ReteAgent.kt:35`) — rules cannot
  currently match a trace fact at all. `BlackboardEvent` does have the right runtime vocabulary
  (`MethodEnter/MethodExit/FieldAccess/ProductionActivation/…`, `:43-125`) and `ReteFact.DagFact(coordinate,
  event)` wraps it, but nothing produces `DagFact` from landings.

### 1.4 Guest-VM ring (Graal)

- `SubgraalPointcutRunner` (`src/jvmMain/kotlin/borg/trikeshed/pointcut/SubgraalPointcutRunner.kt:14`):
  `Context("python","js")`, `HostAccess.NONE`, `ResourceLimits.statementLimit`, polyglot
  `ExecutionListener` with `.expressions(true)`. **Every event is recorded as `OP_L_GET`** (`:63-67`, the
  comment says so) — no read/write/call distinction, and `event.location` (a `SourceSection` with file,
  line, column) is **not** consumed, so guest events have no source coordinate.
- `VmFacet` enum (`pointcut/VmFacet.kt:3`): `JVM, GRAAL_JS, GRAAL_PYTHON, GRAAL_RUBY, GRAAL_CLOJURE`.
- `PointcutEvent` (`pointcut/PointcutEvent.kt:6`) is the guest-side event; `toFieldSynapse()` hard-codes
  `GRAAL_PYTHON` and `OP_L_GET`.
- **Finding D:** zero use of `com.oracle.truffle.*` anywhere in `src/`, although `truffle-api` is on the
  jvmMain classpath (`build.gradle.kts:224`). All instrumentation is through the polyglot SDK.

### 1.5 Blackboard + surface

- `ConfixBlackboard` (`graal/ConfixBlackboard.kt:44`): `put(key, value, language)`, `get`, `remove`,
  `merge(ConfixDoc, language)`, `keys`, `has`, `changes: SharedFlow<ConfixDoc>`, `ProvenanceEntry(language,
  timestamp, sourceLocation?)`. Unsynchronized, single-writer by convention; `state` is a one-key
  projection (documented caveat, `:23-32`).
- Forge surface contract (`forge/blackboard/ForgeSurfaceProjection.kt`): `ForgeSurfaceProjection<D,P>`
  (`:184`) with `sectionIdOf(item, index)` / `payloadOf(item, index)`; `project(domain, base, now)` (`:230`)
  gives sticky one-tile-per-section placement inside an anchor section. `ReteFireSurfaceProjection`
  (`ReteFireSurfaceProjection.kt:20`) is the worked example; `ReteFireBoardTap` (`:120`) is the bounded
  tap. Sections today: `"page", "board", "gallery", "graph"` (`ForgeBlackboardCamera.kt:103`); modes
  `FLAT_2D, PARALLAX_25D, WORLD_3D` (`:12`).

### 1.6 IDE side

Nothing. No `plugin.xml`, no IntelliJ Platform Gradle plugin, no module. `nexus/TODO.summary.md:26` lists
"IntelliJ PSI Integration" as an aspiration. The only IDE coupling in use is the Claude Code JetBrains
plugin's per-open-file diagnostics (see `forge-substrate-plan.md §5`).

## 2. Design

### 2.1 The join key (commonMain)

```kotlin
package borg.trikeshed.pointcut.coord

/** Static identity of a site. Equal across class loads, JVM runs, and IDE sessions. */
data class SiteKey(
    val owner: String,            // binary class name, or guest source path for VmFacet != JVM
    val methodName: String,       // rootName for guest code
    val methodDescriptor: String, // "" for guest code
    val bytecodeOffset: Int,      // -1 for guest code; (line,column) carries identity instead
    val line: Int, val column: Int,
)
fun PointcutCoordinate.siteKey(): SiteKey
fun DagCoordinate.siteKey(line: Int = -1, column: Int = -1): SiteKey   // static join fills line/col
val SiteKey.confixPath: String   // == ConfixClassfileDir.pathOf for JVM sites; "guest/<lang>/<path>#L:C" otherwise
```

Every fact below carries a `SiteKey`. IDEA places markers by `(owner-or-path, line, column)`; Forge tiles
are keyed by `confixPath` so `project()`'s sticky placement holds across refreshes.

### 2.2 Facts (commonMain, extend `ReteFact`)

```kotlin
/** Static: one per instruction site, produced once per classfile. */
data class SiteFact(val site: SiteKey, val pointcut: PointcutCoordinate) : ReteFact()          // factId = "site:"+confixPath
/** Dynamic JVM: one per landing, joined to SiteFact by SiteKey. */
data class TraceFact(val site: SiteKey, val facet: VmFacet, val kind: BytecodePointcutKind,
                     val phase: Phase, val value: Any?, val nano: Long, val threadId: Long) : ReteFact()
/** Guest VM: same shape, facet != JVM, kind derived from Truffle tags (§2.4). */
typealias GuestFact = TraceFact
/** Aggregate maintained by a Rete production: heat per site. */
data class SiteHeat(val site: SiteKey, val count: Long, val lastNano: Long, val lastValue: Any?) : ReteFact()
/** IDE → blackboard. */
sealed class IdeFact : ReteFact() {
    data class Focus(val site: SiteKey, val editorId: String) : IdeFact()
    data class Diagnostic(val site: SiteKey, val severity: String, val message: String) : IdeFact()
    data class PointcutRequest(val site: SiteKey, val kinds: Set<BytecodePointcutKind>, val armed: Boolean) : IdeFact()
}
```

`VmFacet` and `BytecodePointcutKind` move to (or get mirrored in) commonMain so facts are common; `VmFacet`
is currently jvmMain-only.

Opcode → kind mapping (new, commonMain `PointcutKinds.kt`): `OP_L_GET/P_GET → LOCAL_READ/INSTANCE_FIELD_READ`,
`OP_L_SET/P_SET → LOCAL_WRITE/INSTANCE_FIELD_WRITE`, `OP_CALL → INVOKE`, `OP_RETURN → RETURN`,
`OP_ALLOC → NEW_VALUE`, `OP_CAST → CONVERSION`, `OP_PARAMETER → LOCAL_READ`, `OP_PROPERTY → INSTANCE_FIELD_READ`.

### 2.3 Rete: make rules match facts, not only causal nodes

`ReteRule.predicate: (CausalGraphNode) -> Boolean` (`ReteAgent.kt:35`) is too narrow. Add a parallel
`ReteFactRule(name, predicate: (ReteFact) -> Boolean, transform: (ReteFact) -> Fire)` and an overload
`ReteAgent.run(factRules, …)` fed from `ReteWorkingMemory` assertions. Productions for this view:

1. `site-heat`: on `TraceFact` → upsert `SiteHeat(site)` (count+1, last*). This is the only aggregate the
   IDE needs for gutter rendering; it keeps the IDE off the raw trace stream.
2. `armed-pointcut`: on `IdeFact.PointcutRequest(armed=true)` → emit `Fire("arm", …)` consumed by the
   JVM host, which (a) adds a `TypedefProductionSystem.AdjacentRule` for the site and (b) for guest sites
   narrows the `ExecutionListener.sourceFilter`. Disarm reverses. This is the breakpoint-shaped loop.
3. `focus-follow`: on `IdeFact.Focus(site)` → `Fire("focus", nodeId = site.confixPath)` → Forge
   `ForgeBlackboardInteraction.focusSection(sectionId)`. The IDE caret drives the 2.5D camera.
4. `diagnostic-overlay`: on `IdeFact.Diagnostic` → `OverlayFact(principal = "ide")` so IDE errors show up
   as overlays on the same tiles as runtime heat.

### 2.4 Guest-VM fidelity (jvmMain, two steps)

Step 1 — polyglot SDK only, no new deps:
```kotlin
ExecutionListener.newBuilder()
    .statements(true).roots(true)                // not .expressions(true): far fewer events, stable locations
    .sourceFilter { src -> armed.containsPath(src.path) }
    .collectInputValues(true).collectReturnValue(true)
    .onEnter { e -> emit(e, Phase.BEFORE) }.onReturn { e -> emit(e, Phase.AFTER) }
```
`emit` reads `e.location` (`SourceSection`: `source.path`, `startLine`, `startColumn`) → `SiteKey`, and
`e.rootName` → `methodName`. `kind` is still coarse (`STACK` for statements, `INVOKE` for roots).

Step 2 — Truffle instrument, for real kinds: a `@TruffleInstrument.Registration(id="trikeshed-pointcut")`
instrument using `SourceSectionFilter.tagIs(StandardTags.ReadVariableTag, WriteVariableTag, CallTag,
StatementTag)` → `LOCAL_READ / LOCAL_WRITE / INVOKE / STACK`. Requires the instrument on the engine's
module path and `Context.option("trikeshed-pointcut", "true")`; gated behind a `PlatformHost.subVm`
capability flag so the absence of the instrument degrades to Step 1, not to failure.

Both steps publish `TraceFact(facet = VmFacet.GRAAL_*)` into the same working memory as JVM landings and
retire the `OP_L_GET`-for-everything placeholder at `SubgraalPointcutRunner.kt:63-67`.

### 2.5 Bytecode ring → `SiteFact` (jvmMain)

Extend `ClassfileTaxonomy` (keep `java.lang.classfile`; retire the ASM `ClassfileBlackboardAdapter` or
make it delegate) with `List<PointcutCoordinate> pointcuts()`: for each `CodeElement` that is a
`FieldInstruction`, `InvokeInstruction`, `LoadInstruction`/`StoreInstruction`, `ReturnInstruction`,
`NewObjectInstruction`, `TypeCheckInstruction`, `BranchInstruction`, emit the matching
`BytecodePointcutKind`, with `SourceCoordinate` from `LineNumber` attributes (column = 0 when absent).
Assert one `SiteFact` per coordinate at class attach time. This also gives `JavaAotClassfileTransformer`
a selection set: transform only armed sites (from rule 2), instead of all.

### 2.6 Wire (jvmMain server; consumed by IDEA plugin *and* Forge JS)

Extend `KanbanHttpServerJvm` (exists; see `forge-substrate-plan.md` Phase 4 item 5):

- `GET /blackboard/facts?since=<seq>&kinds=site,heat,trace,ide` — SSE, one `ConfixDoc` per event. Confix
  is already the blackboard's document format, so no second serializer.
- `POST /blackboard/assert` — body: one `IdeFact` as ConfixDoc. Single-writer preserved by funnelling into
  the adapter's existing serialized writer (`PointcutBlackboardAdapter.kt:314`), not by writing to
  `ConfixBlackboard` directly.
- `GET /blackboard/sites?owner=<class>` — snapshot of `SiteFact`s for one class (IDEA calls this when a
  file opens).

Zero NPM on the JS side: the browser Forge surface uses `EventSource` from the DOM API. Same stream, same
tiles, same section ids as the IDE — that is the GWT-gateway point.

Optionally expose the same three operations as MCP tools (`blackboard_facts`, `blackboard_assert`,
`blackboard_sites`) so any agent runtime reads/writes the same facts the IDE does.

### 2.7 Forge surface (commonMain + jsMain)

- Two new sections in `forgeBlackboardDefault3DLayout`: `"bytecode"` and `"vm"` (guest). Both sit at the
  elevation of `"graph"` so the 2.5D parallax reads them as the same stratum as the causal graph.
- `SiteHeatSurfaceProjection : ForgeSurfaceProjection<SiteHeat, SiteHeat>` anchored at `"bytecode"` for
  `facet == JVM`, at `"vm"` otherwise; `sectionIdOf = "site-" + site.confixPath.forgeSectionToken()`.
  Payload drives tile colour (heat) and label (`shortMethod`).
- Tile click → `IdeFact.Focus` *outbound* (surface → blackboard → IDE navigates). Symmetric with rule 3.

### 2.8 IDEA plugin (new module `idea-plugin/`, IntelliJ Platform Gradle Plugin 2.x, Kotlin/JVM)

Dependency rule: the plugin depends on a new tiny `blackboard-wire` KMP module (ConfixDoc codec + the fact
DTOs in §2.2 + `SiteKey`) — **not** on the TrikeShed jvm jar. IDEA's plugin classloader plus TrikeShed's
kotlinx versions plus GraalVM on one classpath is not a fight worth having.

Components:
- **Tool window "Blackboard"** with tabs *Bytecode*, *VM*, *Fires*. Each is a table bound to the SSE
  stream filtered by `facet`; double-click → `OpenFileDescriptor(project, vfile, line, column)`.
- **Line markers** (`LineMarkerProvider`) at every `SiteHeat.site` for the open file: colour by
  `VmFacet`, intensity by `count`; tooltip = last value + phase + nano age. Resolution: for JVM sites,
  `SiteKey.owner` → `JavaPsiFacade.findClass` → method by `(name, descriptor)` → `PsiElement` at line;
  for guest sites, `owner` is a path → `LocalFileSystem.findFileByPath`.
- **Inlay hints** (optional, off by default): last observed value after an armed site.
- **Action "Arm pointcut here"** (editor context menu, also a gutter toggle): builds
  `IdeFact.PointcutRequest(site, kinds, armed)` from the PSI element under the caret and POSTs it.
- **Caret listener** → debounced `IdeFact.Focus` POST (rule 3). Togglable; off by default so the camera
  doesn't chase every keystroke.
- **Diagnostics bridge** → `IdeFact.Diagnostic` from `DaemonCodeAnalyzer` highlights of the open file
  (rule 4). Severity-filtered (errors + warnings only).
- Connection settings: host/port of the Forge JVM; reconnect with `since=<seq>`.

What the plugin does **not** do: decompile, show raw bytecode, or replace IDEA's *Show Kotlin Bytecode* /
Java bytecode viewer. It overlays *runtime and guest-VM facts* on source; IDEA already owns the static view.

## 3. Phases and gates

| # | deliverable | gate |
|---|---|---|
| 0 | `SiteKey`, `ReteFact.{SiteFact,TraceFact,SiteHeat,IdeFact}`, opcode→kind map, `VmFacet` in common; `ReteFactRule` + `site-heat` production | `commonTest` on jvm **and** js: landings → `SiteHeat` with correct counts; `jvmMainClasses`, `compileKotlinJs` green |
| 1 | `ClassfileTaxonomy.pointcuts()` → `SiteFact`; landings enriched with `SourceCoordinate` by static join | `jvmTest`: for a compiled fixture class, every `PointcutLanding` resolves to a `(file,line)`; ASM reader retired or delegating |
| 2 | Guest Step 1 (`ExecutionListener` with location + sourceFilter); Step 2 behind capability flag | `jvmTest`: a Python snippet with a read and a write yields two `TraceFact`s with distinct `(line,column)` and, with the instrument present, distinct kinds |
| 3 | Wire: SSE + assert + sites on `KanbanHttpServerJvm`; Forge sections `bytecode`/`vm` + `SiteHeatSurfaceProjection` on JS | `jsTest` consumes a recorded stream and produces sticky tiles; `curl` smoke on the three endpoints |
| 4 | IDEA plugin MVP: tool window, line markers, navigate, arm-pointcut | `runIde` against a Forge JVM: arming a line in IDEA produces landings for that site and nowhere else |
| 5 | Caret focus, diagnostics bridge, MCP exposure | focus in IDEA moves the Forge camera; an IDEA error appears as an overlay tile |

## 4. Decisions needed

1. **Plugin dependency boundary** — `blackboard-wire` module (recommended) vs. depending on the full jvm
   artifact.
2. **Truffle instrument in scope now** (Step 2, needs instrument registration on the module path) or
   polyglot-only for the first release (Step 1; kinds stay coarse for guest code).
3. **Retire the ASM reader** (`ClassfileBlackboardAdapter.java`) in favour of `java.lang.classfile`, or
   keep both.
4. **Camera-follows-caret default**: off (recommended) or on.
5. **Where the two new sections live** — separate `bytecode`/`vm`, or one `vm` section with facet-coloured
   tiles.
