# Classfile Pointcut Trajectory — Source Root Contract Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Put JVM/Graal/polyglot pointcut TDD under `libs/classfile` while using the **source root contract** — `PRELOAD.md` plus the existing root algebra in `src/commonMain/kotlin` — as the controlling API. Do **not** create a new dependency in root.

**Architecture:** Root source remains the kernel contract (`Join`, `Series`, `Cursor`, metadata, CCEK lifecycle/fanout). `libs/classfile` builds peripheral classfile/JEP-484/Graal pointcut surfaces against that contract and must not introduce root → classfile coupling. Direction is one-way: classfile consumes root algebra; root does not depend on classfile.

**Tech Stack:** Source-root TrikeShed kernel algebra from `PRELOAD.md`, `Series`/`Cursor`/metadata projections, peripheral `libs/classfile` SPI, JEP 484 `java.lang.classfile`, JVM launch harnesses, GraalVM polyglot mapping scaffolds, TDD RED tests.

---

## Source root is the main contract

Use `/Users/jim/work/TrikeShed/PRELOAD.md` and existing root source as normative:

- `Join<A,B>` is the base composition shape.
- `Series<T> = Join<Int, (Int) -> T>` is the indexed abstraction.
- `Cursor = Series<RowVec>` is the dataframe-shaped specialization.
- Metadata is part of the algebra, not an afterthought.
- `List<T>.toSeries()` and `Array<T>.toSeries()` are first-class gateway priorities for peripheral scans that start from JVM/stdlib collections.
- Use `α` lazy projections and `.view` only at stdlib/materialization boundaries.
- Use `/` (`div`) for value-side cursor/coordinate reduction and `%` (`rem`) for index-side reduction so reduced pointcut cursors collapse back to `Series` before `α` transforms and `.view` materialization.
- Side effects stay at explicit userspace/CCEK boundaries with lifecycle and fanout.

`libs/classfile` must **not** define a parallel algebra. Its pointcut rows, blackboards, symbol maps, and activation cursors collapse back to source-root `Join`/`Series`/`Cursor` shapes.

---

## Hard dependency rule

**DO NOT CREATE A NEW DEPENDENCY IN ROOT.**

Allowed dependency direction:

```text
root source contract  ──consumed by──>  libs/classfile peripheral implementation
```

Forbidden direction:

```text
root build/source  ──depends on──>  libs/classfile
```

Implementation implications:

1. No new root `implementation(project(":libs:classfile"))`.
2. No new root source imports from `borg.trikeshed.classfile.*`.
3. No root `src/` pointcut scaffolding.
4. No root Gradle expansion as the integration mechanism.
5. `libs/classfile` may reuse existing root algebra contracts exactly as already modeled by the project build, but root must remain ignorant of classfile internals.

---

## Peripheral package hierarchy in `libs/classfile`

Use source-root algebra names in every model. Do not create opaque wrappers where a `Join`, `Series`, cursor row, or metadata projection is the natural contract.

```text
libs/classfile/src/commonMain/kotlin/borg/trikeshed/classfile/
  spi/
    ClassfileScanSpi.kt              # scan(bytes, request): Series<PointcutCoordinate>
    PointcutDelegateSpi.kt           # activation delegate, explicit lifecycle
    PointcutSink.kt                  # sink boundary over MutableSeries/Series
  model/
    ClassfilePattern.kt              # glob/pattern scan request as algebraic data
    BytecodePointcutKind.kt          # field/local/array/const/invoke/operator/conversion
    SourceCoordinate.kt              # source file/line/column/language
    SymbolCoordinate.kt              # symbol name/fqn/owner/descriptor/table key
    PointcutCoordinate.kt            # bytecode + source + symbol + activation key
  cursor/
    ClassfileFacets.kt               # metadata facets for endpoint/lambda/sink/activation
    ClassfileBlackboard.kt           # Series/ Cursor projection of scans and activations
    PointcutActivationCursor.kt      # spreadsheet-like late-bound function rows
  sink/
    MutableSeriesPointcutSink.kt     # observable/lossless sink delegate

libs/classfile/src/jvmMain/java/borg/trikeshed/classfile/jep484/
  Jep484ClassfileScanner.java        # JEP 484 java.lang.classfile scanner
  Jep484PointcutCommand.java         # JVM command/harness contract

libs/classfile/src/jvmMain/kotlin/borg/trikeshed/classfile/
  jvm/
    JvmPointcutHarness.kt            # actual JVM launch facade
    AotJitDumpHarness.kt             # AOT/JIT dump scanning harness
  graal/
    GraalPointcutMappingBridge.kt    # polyglot source/symbol mapping bridge
    GraalEcmaPointcutLaunch.kt       # ECMA-specific fixture launch
```

If the current Gradle plugin has no `javaMain`, use `src/jvmMain/java` for Java files. The architectural contract remains SPI/common model first, Java/JVM implementation second, no `expect/actual`.

---

## TDD RED tranche 1 — source-root algebra contract

### Task 1: RED test for algebra-shaped classfile rows

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileSourceRootContractRedTest.kt`

**The test should assert:**

- `ClassfileScanSpi.scan(...)` returns `Series<PointcutCoordinate>`.
- `PointcutCoordinate` is decomposable into source-root-like joined parts, not a sealed command object maze.
- Row projections can be expressed as `Series.α { ... }`.
- Materialized inspection uses `.view`, not eager lists in the core API.
- Cursor projection preserves metadata/facets.

**Expected RED:** unresolved `ClassfileScanSpi`, `PointcutCoordinate`, `SourceCoordinate`, `SymbolCoordinate`, `ClassfileFacets`.

### Task 2: RED test for no root dependency direction

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/RootDependencyDirectionRedTest.kt`

**The test should assert by convention/static inspection:**

- No root source file imports `borg.trikeshed.classfile`.
- No root build file declares a dependency on `:libs:classfile`.
- Classfile package names are peripheral-only.

This can be a test utility that scans files under the repo root and fails if root gains a classfile dependency. It protects Jim’s “DO NOT CREATE A NEW DEPENDENCY IN ROOT” rule.

---

## TDD RED tranche 2 — JVM/JEP-484 pointcut command

### Task 3: RED full JVM pointcut command test

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/Jep484JvmPointcutCommandRedTest.kt`

**The test should assert:**

- A command can describe classpath, target main/test class, glob activation patterns, sink key, and pointcut categories.
- The command is data-first and can be projected into a cursor row.
- A fixture scan asks for value-related bytecode categories:
  - field get/set: `GETFIELD`, `PUTFIELD`, `GETSTATIC`, `PUTSTATIC`
  - local load/store: `ILOAD`/`ISTORE` and representative L/F/D/A variants
  - array load/store: representative typed load/store
  - constants: intrinsic constants and `LDC`
  - invokes: virtual/static/special/interface where available
  - numeric operators/conversions/compare/branch
- Every emitted `PointcutCoordinate` has bytecode, source coordinate, and symbol coordinate fields.

### Task 4: RED JEP 484 static scan test

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/Jep484BytecodeScanRedTest.kt`

**The test should assert:**

- Scanner backend marker is `java.lang.classfile/JEP-484`.
- It reads `SourceFile`, line numbers, local variable table if present, field refs, method refs, and loadable constants.
- Results are a `Series<PointcutCoordinate>` sorted by bytecode offset.
- Missing debug info becomes an unresolved `SourceCoordinate`, not a dropped event.

---

## TDD RED tranche 3 — Graal/polyglot source mapping scaffold

### Task 5: RED polyglot symbol map contract

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/PolyglotSymbolMappingRedTest.kt`

**The test should assert:**

- A polyglot mapping table maps JVM/Graal callsites to `SourceCoordinate` and `SymbolCoordinate`.
- Language id (`js`, `python`, etc.) is part of the coordinate.
- Unknown source sections preserve JVM fallback coordinates.
- The mapping table projects as `Series<PointcutCoordinate>` and as a cursor for inspection.

### Task 6: RED Graal ECMAScript launch scaffold

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/GraalEcmaPointcutLaunchRedTest.kt`

**The test should assert:**

- A Graal JS fixture can be launched through the classfile SPI/harness.
- Object field get/set and function invocation are mapped to JS-facing symbol names where source sections allow it.
- JVM fallback symbols are retained when JS source mapping is incomplete.
- The sink records are the same `PointcutCoordinate` rows as the JEP-484 scanner uses.

---

## TDD RED tranche 4 — faceted cursor blackboard and delegate activation

### Task 7: RED ColumnMeta facet contract

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileFacetedCursorRedTest.kt`

**The test should assert facets for:**

- `SourceMappedSymbol`
- `PointcutDelegateEndpoint`
- `LambdaMedium`
- `ObservableSink`
- `GlobActivation`
- `LateBoundFunction`

Only rows backed by in-VM lambda-capable media expose `LambdaMedium` / `LateBoundFunction`. Confix-only rows remain data rows.

### Task 8: RED Confix classfile blackboard test

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileBlackboardRedTest.kt`

**The test should assert:**

- Whole-pattern scans register on a blackboard.
- Glob activation rules project into cursor rows.
- Facet filters select delegate-capable rows.
- Source/symbol/bytecode coordinates survive projection.

### Task 9: RED MutableSeries observable sink delegate test

**Create:** `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/MutableSeriesObservableSinkRedTest.kt`

**The test should assert:**

- Activated delegates write every event into a lossless sink.
- Attach/detach is represented as algebraic lifecycle state, not hidden callbacks.
- Sink content can be viewed as `Series<PointcutCoordinate>` and cursor rows.

---

## TDD RED tranche 5 — actual launch and dump paths

### Task 10: RED AOT/JIT dump harness test

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/AotJitDumpHarnessRedTest.kt`

**The test should assert:**

- Harness accepts a dump directory/jar/class glob.
- Every matched classfile gets scanned through the same SPI.
- Missing debug tables yield unresolved source coordinates.

### Task 11: RED actual JVM pointcut launch test

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/JvmActualPointcutLaunchRedTest.kt`

**The test should assert:**

- A tiny JVM fixture launches.
- The sink drains observed pointcut rows.
- Runtime rows can be matched back to static JEP-484 scan rows by kind and symbol coordinate.

### Task 12: RED actual GraalVM polyglot pointcut launch test

**Create:** `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/GraalPolyglotPointcutLaunchRedTest.kt`

**The test should assert:**

- Graal JS launches.
- Emitted/mapped events enter the same sink.
- Rows include language id, source coordinate, symbol name, and JVM fallback coordinate.

---

## Implementation sequence after RED

1. Add minimal common model/SPI records so RED tests compile one layer at a time.
2. Keep every model algebra-shaped: `Series`, `Join`, cursor projections, metadata facets.
3. Implement blackboard + sink as in-memory peripheral structures under `libs/classfile`.
4. Implement JEP 484 scanner using `java.lang.classfile`.
5. Implement JVM command/harness over the scanner.
6. Implement Graal/ECMA mapping bridge into the same `PointcutCoordinate` rows.
7. Implement AOT/JIT dump harness.
8. Only then consider build integration — without adding a root dependency.

---

## Verification commands

Use the existing project path if `libs/classfile` is already included:

```bash
./gradlew :libs:classfile:compileTestKotlinJvm --rerun-tasks
./gradlew :libs:classfile:jvmTest --rerun-tasks --tests "borg.trikeshed.classfile.red.*"
```

If Gradle currently cannot see `libs/classfile`, do **not** solve that by adding a root dependency. Either use the existing root source contract as-is or create a temporary/peripheral-only verification route that does not make root depend on classfile.

---

## Final invariant

`PRELOAD.md` + root source algebra is the contract. `libs/classfile` is a peripheral consumer/implementation. Root does not depend on it.
