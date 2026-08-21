# Forge Substrate Plan — commonMain mold, polyglot pour

Status: landed 2026-08-21. Grounded in a full-repo survey (file:line refs below). Phases are ordered
by what unblocks what; each has a hard gate.

## Thesis

TrikeShed is the **mold**. commonMain defines the contracts, the fact vocabulary, and the inference
substrate. Implementations — by humans or by any generative agent (Jules, opencode, kilo, OpenAI-compat,
Claude; admission by capability, not vendor) — are **poured** into that mold; being rule sets, and renderers without
stipulating and in fact averse to platform actuals.

Three load-bearing decisions:

1. **commonMain primacy.** `expect`/`actual` stays available as a tool but stops being the *structure*.
   Today ~30 expect symbols are mirrored across up to 7 platform source sets (§2). Every new common-side
   need costs N files, and every missed mirror silently drops a target. That is the agility tax.
2. **Kotlin/JS + wasmJs is the GWT gateway.** Systems-level Kotlin compiles to node *and* browser with
   zero NPM dependencies. `jsMain`/`wasmJsMain` are the delivery vehicle for Forge, not debt. Therefore
   `compileKotlinJs` green is a release gate, equal to `jvmMainClasses`.
3. **JVM hosts the hard-pointcutting polyglot sub-VM** (GraalVM CE 25.0.2, `build.gradle.kts:29-31,221-225`).
   Guest code (Python/JS) runs under `HostAccess.NONE` with statement limits; execution events are
   pointcuts that become facts on the blackboard, so the production system reasons about guest execution
   the same way it reasons about kanban cards and classfile events.

## 1. Ground truth (survey 2026-08-21)

| Fact | Where |
|---|---|
| commonMain 900 .kt / jvmMain 143 / jsMain 45 / wasmJsMain 31 / posixMain 32 / linuxMain 20 / nativeMain 15 / macosMain 7 | `src/` |
| Targets: jvm (JDK 25), js (node+browser), wasmJs (node+browser), androidNativeArm64, linuxX64, mingwX64, mac-only apple family | `build.gradle.kts:70-177` |
| `-Xexpect-actual-classes` enabled — expect *classes* are in use (`FileBuffer`, `IntAccumulator`, `OroborosDaemon`, `LiburingVolume`…) | `build.gradle.kts:63` |
| GraalVM deps are jvmMain-only; no native-image plugin | `build.gradle.kts:221-225` |
| `classfile/slab/**` (GraalJS-eval / DuckDB stubs) excluded from compile | `build.gradle.kts:195-199` |
| `compileKotlinJs` is red: JVM leakage in commonMain (`java.*`, `System.*`, `@JvmInline`) | inventory: Phase 0 |
| **Three blackboards**: `cursor.BlackboardContext`/`BlackboardOverlay` (epistemic roles), `graal.ConfixBlackboard` (content-addressed, polyglot-facing), `blackboard.BlackboardSurface` (causal graph + kanban projection); plus a JVM `cursor/ConfixBlackboard.kt` | `src/commonMain/kotlin/borg/trikeshed/{cursor,graal,blackboard}/`, `src/jvmMain/kotlin/borg/trikeshed/cursor/ConfixBlackboard.kt` |
| **Two production systems**: `dag.ReteNetwork` (common; alpha/beta/agenda) and `cursor.TypedefProductionSystem` (JVM; CRMS ring→slab fold for typedef call sites) | `src/commonMain/kotlin/borg/trikeshed/dag/Rete*.kt`, `src/jvmMain/kotlin/borg/trikeshed/cursor/TypedefProductionSystem.kt` |
| Fact vocabulary exists: `sealed class ReteFact { BoardFact, CardFact, DependencyFact, OverlayFact, DagFact, NodeFact }` | `src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagFabric.kt:204` |
| The self-declared "overarching pointcutting fabric" | `BlackboardDagFabric.kt:8-21` |
| Graal sub-VM harness exists, JVM-only, publishes only to `TypedefProductionSystem`, not to Rete | `src/jvmMain/kotlin/borg/trikeshed/pointcut/SubgraalPointcutRunner.kt` |
| Single-seam SPI pattern already in house style: interface + `Key` + `register()` + one `expect` loader | `src/commonMain/kotlin/borg/trikeshed/userspace/nio/platform/spi/SystemOperations.kt`, `isam/IsamOperations.kt` |
| Duplicate `ForgeWindowManager` **already merged** (gap-analysis item 1 is stale) | `src/commonMain/kotlin/borg/trikeshed/forge/window/ForgeWindowManager.kt:5-14` |
| Package-root drift: `forge.doc.WorkDrain`, `org.trikeshed.oroboros.*` vs `borg.trikeshed.*` | `src/commonMain/kotlin/forge/doc/WorkDrain.kt`, `src/commonMain/kotlin/org/trikeshed/oroboros/` |
| `OroborosDaemon` is an expect class with a JVM actual only | `src/commonMain/kotlin/org/trikeshed/oroboros/OroborosDaemon.kt:4` |

## 2. expect/actual inventory → disposition

Leaf primitives (fold into `PlatformHost`, Phase 1):

| expect | declared | actuals | disposition |
|---|---|---|---|
| `currentTimeMillis`, `monotonicNanoTime`, `availableProcessors` | `cursor/BlackboardOverlay.kt:330-342` | js, jvm, posix (partial), wasmJs | `PlatformHost.clock`, `.processors` |
| `monotonicNowMillis` | `lib/MonotonicClock.kt:3` | js, jvm, native, wasmJs | merge into `.clock` |
| `homedirGet`, `rm`, `mkdir`, `mktemp` | `common/HomeDir.kt:5-12` | js, jvm, posix, wasmJs | `PlatformHost.fs` |
| `readLinesSeq`, `readLines` | `common/ReadLines.kt:3-4` | js, jvm, posix, wasmJs | `PlatformHost.fs` |
| `Files` (object) | `common/Files.kt:7` | js, jvm, posix, wasmJs | `PlatformHost.fs` |
| `sha256` | `job/ContentId.kt:40` | js, jvm, posix, wasmJs | `PlatformHost.digest` |
| `defaultSecureIdGenerator` | `modelmux/SecureIdGenerator.kt:7` | js, jvm, native, wasmJs | `PlatformHost.random` |
| `assert` ×2 | `lib/debug.kt:11-14` | js, jvm, posix, wasmJs | plain common fn over `PlatformHost.debug` |
| `synchronizedLock` | `isam/Locks.kt:3` | js, jvm, native, wasmJs | `PlatformHost.lock` (kotlinx-atomicfu-free) |
| `IntAccumulator` (class) | `lib/IntAccumulator.kt:5` | js, jvm, posix, wasmJs | interface + `PlatformHost.accumulator()` factory — removes one expect-class |
| `platformNativeByteOrder` | `userspace/nio/platform/spi/PlatformEndianness.kt:5` | js, jvm, posix, wasmJs | `PlatformHost.endianness` |
| `platformCacheTopology` | `userspace/nio/platform/spi/platformCacheTopology.kt:3` | js, jvm, posix, wasmJs | `PlatformHost.cacheTopology` |
| `loadDefaultSystemOperations` | `userspace/nio/platform/spi/SystemOperations.kt:30` | js, jvm, linux, macos, wasmJs | **becomes** the one remaining loader, renamed `loadPlatformHost` |
| `loadConfixSchemaBytes` | `job/schema/SchemaResourceAdapter.kt:3` | js, jvm, posix, wasmJs | `PlatformHost.resources` |
| `HtmlShell` (object) | `forge/shell/HtmlShell.kt:3` | js, jvm, wasmJs | interface `ShellHost` on `PlatformHost.shell` |

Genuinely platform-native (stay as capabilities, nullable on `PlatformHost`, Phase 1b):
`LiburingImpl` (`userspace/Liburing.kt:52`), `FileImpl/FilesImpl/ChannelsImpl/openUserspaceChannelBackend`
(`userspace/UserspaceIO.kt:25-40`), eBPF `runNative`/`bpfProbeAttach`/`BEBPF_ORDER`
(`userspace/nio/ebpf/`), `ProcessWorkerFactory`, `currentNioCapabilityReport`, `platformNioProviders`,
`createFusePathCanonicalizer`, `defaultIsamOperations`, `FileBuffer`, `LiburingVolume`/`VolumeBackends`
(nativeMain-scoped expects). These become `val liburing: LiburingCapability?` etc. — a missing platform
is a **null capability fact**, not a compile failure.

Test helper `runBlocking` (`commonTest/RunBlockingHelper.kt:5`) — keep; note the jsTest actual is
misnamed `runBlockingTest`.

## 3. Cyc mapping (what "Cyc-inspired" means here, concretely)

| Cyc concept | TrikeShed substrate | file |
|---|---|---|
| Ontology / constants | `sealed class ReteFact` vocabulary | `dag/BlackboardDagFabric.kt:204` |
| Epistemic status | `OverlayRole` (OBSERVATION / DERIVED / AGGREGATE / HYPOTHESIS …) | `cursor/BlackboardOverlay.kt` |
| Microtheory (Mt) | `BlackboardContext` — a scoped assertion context | `cursor/BlackboardOverlay.kt` |
| Assertion + justification | `ReteAssertionResult` + `Provenance`/`Evidence` | `dag/ReteWorkingMemory.kt`, `cursor/BlackboardOverlay.kt:300-325` |
| Truth maintenance | `ReteBetaMemory` retraction paths + `ConfixBlackboard` provenance | `dag/ReteBetaMemory.kt`, `graal/ConfixBlackboard.kt` |
| Inference engine | `ReteNetwork` + `ReteAgenda` + `ReteAgent` | `dag/ReteNetwork.kt`, `dag/ReteAgent.kt` |
| Everything is in the KB | **Phase 1/3**: platform capabilities and guest-VM execution events are asserted as facts | new `ReteFact.CapabilityFact`, `ReteFact.PointcutFact` |

The substrate is the KB; Forge is the semi-graphical surface over it (`forge/blackboard/ForgeSurfaceProjection.kt`:
"one surface contract for every ring"). Kanban = work metaphor, causal graph = graph metaphor, camera/force-layout
= visual metaphor; all three are projections of the same fact store.

## 4. Phases

### Phase 0 — JS gateway green (gate change)

Goal: `./gradlew compileKotlinJs` green; add it to the drain-contract gate beside `jvmMainClasses`.

Method: the Kotlin compiler is the semantic oracle — its cross-target errors *are* the commonMain
contamination map. Cluster errors by (file, symbol) and fix by class:
- `System.currentTimeMillis()` → `borg.trikeshed.cursor.currentTimeMillis()` (done for
  `forge/window/ForgeWindowManager.kt:38` in this landing).
- `java.*` imports → common equivalents or move the file to jvmMain.
- `@JvmInline value class` → `value class` (annotation is redundant on non-JVM; `@JvmInline` is JVM-only).
- Anything that cannot be made common moves to jvmMain behind a `PlatformHost` capability.

Gate: `compileKotlinJs` exit 0; `jvmMainClasses` still green; no new expects introduced.

### Phase 1 — `PlatformHost`: one seam

Generalize `SystemOperations` (already interface + `Key` + `register()` + single expect loader) into
`borg.trikeshed.platform.PlatformHost : CoroutineContext.Element` with grouped capabilities:
`clock`, `fs`, `digest`, `random`, `lock`, `endianness`, `cacheTopology`, `resources`, `shell`, `env`,
`processors`, plus nullable natives (`liburing`, `ebpf`, `userspaceIo`, `processWorkers`, `nio`, `fuse`, `isam`).

Exactly **one** `expect fun loadPlatformHost(): PlatformHost` in commonMain; one actual per platform
source set. Every existing leaf `expect fun` in §2 becomes a plain common function delegating to
`PlatformHost.default.<cap>` — call sites don't change, so this is non-destructive and incremental
(one expect folded per commit, gate-verified each time). Old expect declarations are deleted only after
their delegating shim compiles on all targets.

Because `PlatformHost` is a CCEK element it is injectable through coroutine context — tests supply a
fake clock/fs without touching globals, which `SystemOperations.register()` already half-does.

Then: `PlatformHost.default.capabilities()` emits `ReteFact.CapabilityFact` into the blackboard at boot —
the production system can rule on "no liburing here, use NIO provider" instead of code doing `if (isLinux)`.

Gate: `compileKotlinJs`, `jvmMainClasses`, `jvmTest --tests '*PlatformHost*'` green; expect count in
commonMain ≤ 10 (from ~30).

### Phase 2 — Blackboard unifier

Define `borg.trikeshed.blackboard.Blackboard` in commonMain as the single contract:
- store: `ConfixBlackboard` (content-addressed, provenance) — fix its documented `doc` single-key caveat
  (`graal/ConfixBlackboard.kt:23-32`) so `state` is a faithful multi-key projection.
- context: `BlackboardContext` as Mt scoping; every assertion carries its Mt.
- facts: `ReteWorkingMemory` as the typed fact memory over the store.
- surface: `BlackboardSurface` and `ForgeSurfaceProjection` consume only `Blackboard`, never the parts.

Bridge `TypedefProductionSystem` into Rete: its CRMS fold emits `ReteFact.PointcutFact(coordinate, opcode,
phase, callsiteHash)` the way `ReteCausalBridge` already emits `NodeFact` from `NodePlanning`. Result: one
inference substrate, `TypedefProductionSystem` demoted to a high-rate fact source.

Move `graal/ConfixBlackboard.kt` out of the `graal` package (it has no Graal dependency) into
`blackboard/`; delete the JVM `cursor/ConfixBlackboard.kt` once call sites use the common one.

Gate: `commonTest` `Rete*Test`, `BlackboardDagCausalGraph*Test`, `PointcutPolyglotBlackboardTaxonomyTest`
green on jvm and js.

### Phase 3 — Polyglot sub-VM harness contract

commonMain:
```kotlin
interface PolyglotSubVm : AutoCloseable, CoroutineContext.Element {
    val languages: Set<String>
    fun eval(language: String, source: String, name: String = "eval"): PolyglotValue
    val pointcuts: Flow<PointcutEvent>          // enter/return at expression granularity
    fun bind(name: String, fn: (List<PolyglotValue>) -> PolyglotValue?)
}
```
with `ResourceBudget(statementLimit, wallMillis, heapBytes)` and `HostIsolation.NONE` as the only
allowed default. Every `PointcutEvent` is projected to `ReteFact.PointcutFact` by a `PointcutReteBridge`
(common), so guest execution is reasoned about by the same rules as everything else.

Actuals, as `PlatformHost.subVm: PolyglotSubVm?`:
- **jvm**: `SubgraalPointcutRunner` refactored to implement the interface; keeps `HostAccess.NONE`,
  `allowHostClassLookup { false }`, `ResourceLimits`, `ExecutionListener`; publishes to the Rete bridge
  *and* (during migration) to `TypedefProductionSystem`. Add Truffle tag filtering (truffle-api is already
  on the classpath, `build.gradle.kts:224`) to replace the "everything is L_GET" placeholder at
  `SubgraalPointcutRunner.kt:63-67`.
- **js / wasmJs (browser)**: `Function`-based sandbox in a Worker with `postMessage` pointcut stream —
  no NPM; **node**: `vm` module via `js("require('vm')")`, still no NPM.
- **native / android / wasi**: `null` capability; the blackboard records `CapabilityFact(subVm=false)`.

"Irrespective of models and AI": the harness never knows which agent wrote the guest code. Agents are
admitted by key lease + capability (see `docs/` modelmux); their output is just source handed to `eval`.

Gate: `jvmTest --tests '*SubgraalPointcutRunner*'` and a new `PolyglotSubVmContractTest` in commonTest
running on jvm and js (node `vm`).

### Phase 4 — Forge JS rendering pipeline (the pour)

From `docs/forge-ui-gap-analysis.md`, with corrections: item 1 is **done**; item 11 — `forge/ForgeDoc.kt`
exists, verify against the stated gap. Remaining, in ROI order: 2 (conduit → HTML seed live binding),
3 (widget preview renderer, DOM), 4 (SVG renderer over `ForceLayout`), 5 (`KanbanHttpServerJvm` serves
`ForgeApp.renderHtml`), 6 (drag/zoom on canvas), 7 (`ForgePersistenceJs` writeback), 8
(`GalleryRenderer` ← `ForgeGalleryCatalog`), 9, 10, 12, 14. All of these are jsMain/wasmJsMain work
against commonMain contracts — exactly the mold/pour split, and exactly why Phase 0 comes first.

Each item is a self-contained task: contract in commonMain (exists), test in commonTest or jsTest,
implementation in jsMain. Dispatchable to any agent runtime; acceptance = the test, not the vendor.

### Phase 5 — Hygiene

- Move `forge.doc.WorkDrain` → `borg.trikeshed.forge.doc`; `org.trikeshed.oroboros.*` →
  `borg.trikeshed.oroboros.*`.
- `OroborosDaemon`: either supply actuals or convert to interface + `PlatformHost.daemon` capability.
- Decide `classfile/slab/**`: delete or un-exclude with tests. Zero non-test consumers today
  (`build.gradle.kts:195`).
- Rename jsTest `runBlockingTest` → `runBlocking` to match the expect.

## 5. IntelliJ as the semantic instrument

The refactor is driven by semantic facts (type hierarchy, usages, cross-target diagnostics), not text
search. Channels, in order of reliability on this machine:
1. **Kotlin compiler per target** — `compileKotlinJs`, `compileKotlinWasmJs`, `jvmMainClasses`. Ground truth.
2. **IntelliJ diagnostics via the Claude Code plugin** (`getDiagnostics(uri)`) — IDEA 2026.1.3 is
   running on this project; per-file calls work intermittently (one timeout observed 2026-08-21). Use
   per touched file, not as a sweep.
3. **Headless `inspect.sh`** (`~/Applications/IntelliJ IDEA 2026.1.3.app/Contents/bin/inspect.sh`) —
   full inspection engine from the CLI, but it refuses a project currently open in the IDE. Run it
   from a checkout not open in IDEA, with the *Kotlin → Multiplatform* and *Unused declaration*
   inspections, to get the usages-based dead-code and redundant-expect lists for Phase 1/5.

## 6. Agent pour protocol

Any task from §4 is handed to an agent runtime as: (contract file, test file, target source set, gate
command). No vendor-specific prompting, no privileged runtime. The agent's output is accepted when the
gate is green and nothing new appears in `commonMain` that references `java.*`, `System.*`, NIO, or
`@JvmInline`. That rule is checkable by the Phase 0 compile and should become a CI check.
