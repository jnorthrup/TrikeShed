# Classfile Peripheral Pointcut Trajectory Integration Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Move JVM/Graal/polyglot pointcut work into a peripheral `libs/classfile` package hierarchy with TDD RED contracts first, keeping the root TrikeShed build nearly unchanged.

**Architecture:** `libs/classfile` becomes the boundary library. `commonMain` defines pure SPI/data contracts for classfile scans, source mappings, faceted cursor endpoints, pointcut delegate activation, and observable sinks. A Java/JVM implementation layer uses JEP 484 `java.lang.classfile` to scan bytecode and launch JVM pointcut harnesses. Polyglot/Graal mappings consume the same SPI through explicit symbol/source mapping tables rather than Kotlin `expect/actual`.

**Tech Stack:** Kotlin common SPI, JVM/Java source for JEP 484 Class-File API, JUnit/TDD RED tests, TrikeShed `Series`/`MutableSeries`/`Cursor`/`ColumnMeta`, Confix blackboard projections, GraalVM Polyglot launch fixtures.

---

## Non-negotiable shape

1. **Root barely changes.** Do not expand root `settings.gradle.kts` or root `build.gradle.kts` as the main integration mechanism. Keep root changes to zero during RED/scaffold work; if a later green build needs root visibility, make it a one-line include/composite decision after the peripheral lib is green standalone.
2. **`libs/classfile` owns the trajectory.** New contracts, RED tests, harnesses, and scaffolds live under `libs/classfile`, not `src/` root and not `libs/polyglot` first.
3. **SPI, not `expect/actual`.** `commonMain` exposes interfaces and data records. JVM/JEP 484 and Graal/polyglot implementations register through explicit constructors, `ServiceLoader`, or a registry object injected into tests.
4. **Tests first.** Add new RED test files; do not mutate existing tests unless Jim explicitly overrides the standing rule.
5. **No fake integration claims.** Synthetic delegate/blackboard tests are labeled unit/scaffold tests until JVM/Graal launch tests actually run a classfile/polyglot program and observe emitted records.

---

## Proposed package hierarchy under `libs/classfile`

```text
libs/classfile/
  build.gradle.kts                         # peripheral build only
  settings.gradle.kts                      # optional standalone build, not root integration
  src/commonMain/kotlin/borg/trikeshed/classfile/
    spi/ClassfileScanSpi.kt                # SPI entry point, no expect/actual
    spi/PointcutDelegateSpi.kt             # delegate registration and activation
    spi/PointcutSink.kt                    # Observable/MutableSeries sink abstraction
    model/ClassfilePattern.kt              # glob/pattern activation model
    model/BytecodePointcutKind.kt          # field/local/array/const/invoke/value op kinds
    model/SourceCoordinate.kt              # file, line, column?, language, symbol
    model/SymbolCoordinate.kt              # symbol table key/name/fqn/owner/descriptor
    model/PointcutCoordinate.kt            # bytecode + source + symbol mapped row
    cursor/ClassfileFacets.kt              # ColumnMeta facets for endpoint/lambda/sink/etc.
    cursor/ClassfileBlackboard.kt          # Confix projection and cursor rows
    cursor/PointcutActivationCursor.kt     # spreadsheet-like late-bound activation rows
    sink/MutableSeriesPointcutSink.kt      # lossless observable sink contract
  src/javaMain/java/borg/trikeshed/classfile/jep484/
    Jep484ClassfileScanner.java            # `java.lang.classfile` scan implementation
    Jep484PointcutCommand.java             # JVM command/harness launch description
  src/jvmMain/kotlin/borg/trikeshed/classfile/jvm/
    JvmPointcutHarness.kt                  # JVM launch facade over Java scanner
    AotJitDumpHarness.kt                   # dump scanner/harness fixture
  src/jvmMain/kotlin/borg/trikeshed/classfile/graal/
    GraalPointcutMappingBridge.kt          # maps polyglot source sections to SPI records
    GraalEcmaPointcutLaunch.kt             # ECMA-specific launch fixture
  src/commonTest/kotlin/borg/trikeshed/classfile/red/
    ClassfileSpiRedTest.kt
    ClassfileFacetedCursorRedTest.kt
    ClassfileBlackboardRedTest.kt
    PointcutDelegateActivationRedTest.kt
    MutableSeriesObservableSinkRedTest.kt
  src/jvmTest/kotlin/borg/trikeshed/classfile/red/
    Jep484JvmPointcutCommandRedTest.kt
    AotJitDumpHarnessRedTest.kt
    GraalPolyglotPointcutLaunchRedTest.kt
    GraalEcmaSymbolMappingRedTest.kt
```

If Gradle cannot support `javaMain` directly with the current classfile plugin, use `src/jvmMain/java` initially but keep package names `borg.trikeshed.classfile.jep484`. The contract remains: Java/JVM implementation below the SPI, no `expect/actual`.

---

## TDD tranche 1: JVM pointcut command and value-bytecode scan RED

### Task 1: Add RED SPI inventory test

**Objective:** Define the common SPI surface before implementation.

**Files:**
- Create: `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileSpiRedTest.kt`
- Later create: `libs/classfile/src/commonMain/kotlin/borg/trikeshed/classfile/spi/ClassfileScanSpi.kt`

**RED assertions:**
- `ClassfileScanSpi.scan(bytes, request)` returns a `Series<PointcutCoordinate>`.
- `PointcutCoordinate` includes:
  - `bytecodeOpcode`: JVM opcode mnemonic or byte value.
  - `pointcutKind`: field/local/array/constant/invoke/value category.
  - `source`: `SourceCoordinate`.
  - `symbol`: `SymbolCoordinate`.
  - `delegateKey`: nullable activation key.
- The RED compile inventory should show missing types only from `borg.trikeshed.classfile.*`.

**Run:**
```bash
./gradlew :libs:classfile:compileTestKotlinJvm --rerun-tasks
```

**Expected RED:** compilation fails with missing classfile SPI/model types.

### Task 2: Add RED JVM command test for full pointcut command surface

**Objective:** Show the complete command/harness contract for JVM pointcuts before implementation.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/Jep484JvmPointcutCommandRedTest.kt`

**RED assertions:**
- A `JvmPointcutCommand` can be built with:
  - classpath entries,
  - main class or test class target,
  - glob activation patterns,
  - output sink key,
  - bytecode categories: field, local load/store, array load/store, constants, invokes, numeric ops, conversions, compare/branch.
- Running the command against a tiny fixture class yields pointcut coordinates for at least:
  - `GETFIELD`, `PUTFIELD`, `GETSTATIC`, `PUTSTATIC`,
  - `ILOAD`/`ISTORE` plus long/float/double/object variants where fixture allows,
  - `IALOAD`/`IASTORE` plus representative array variants,
  - `LDC`, intrinsic constants,
  - `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKESPECIAL`,
  - numeric operator/conversion instructions.
- Every coordinate has non-empty source and symbol names.

**Expected RED:** missing `JvmPointcutCommand`, `Jep484ClassfileScanner`, and fixture mapping types.

### Task 3: Add RED JEP 484 scanner test

**Objective:** Force the implementation to use JEP 484 as the classfile scanner, not ASM as the primary scanner.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/Jep484BytecodeScanRedTest.kt`

**RED assertions:**
- The scanner exposes a `scannerBackend == "java.lang.classfile/JEP-484"` marker.
- It reads `SourceFile`, `LineNumberTable`, local variables, field refs, method refs, and constants from a compiled fixture.
- It emits deterministic `PointcutCoordinate` rows sorted by bytecode offset.

**Expected RED:** missing scanner implementation.

---

## TDD tranche 2: Polyglot/Graal source and symbol mapping scaffold RED

### Task 4: Add RED polyglot mapping table test

**Objective:** Define how Graal/polyglot language mappings reroute pointcuts to source objects/symbols.

**Files:**
- Create: `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/PolyglotSymbolMappingRedTest.kt`
- Later create: `libs/classfile/src/commonMain/kotlin/borg/trikeshed/classfile/model/PolyglotSourceMapping.kt`

**RED assertions:**
- `PolyglotSourceMapping` maps from JVM/Graal callsite to:
  - language id (`js`, `python`, etc.),
  - source URI/name,
  - line/column if known,
  - symbol name,
  - source object key or symbol table key.
- Unknown mappings preserve JVM coordinate and mark source as unresolved instead of dropping the event.

### Task 5: Add RED Graal ECMA launch test

**Objective:** Make ECMAScript the first real specialized language mapping.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/GraalEcmaSymbolMappingRedTest.kt`

**RED assertions:**
- Launch a Graal JS snippet through the classfile SPI harness.
- Capture pointcut coordinates whose symbols are JS-facing names where possible, not only JVM host names.
- Assert mappings for object field get/set and function invocation.
- Assert fallback to JVM coordinate when Graal source section is unavailable.

**Expected RED:** missing Graal mapping bridge and launch fixture.

---

## Faceted cursor and Confix blackboard tranche

### Task 6: Add RED ColumnMeta facet contract

**Objective:** Define delegation endpoint facets without making every Confix mapping executable.

**Files:**
- Create: `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileFacetedCursorRedTest.kt`
- Later create: `libs/classfile/src/commonMain/kotlin/borg/trikeshed/classfile/cursor/ClassfileFacets.kt`

**RED assertions:**
- Cursor columns can carry facets:
  - `SourceMappedSymbol`,
  - `PointcutDelegateEndpoint`,
  - `LambdaMedium`,
  - `ObservableSink`,
  - `GlobActivation`,
  - `LateBoundFunction`.
- Only rows whose medium supports in-VM lambda passing expose `LambdaMedium` and `LateBoundFunction`.
- Confix-only rows remain data-only and are filtered out of actuation cursors.

### Task 7: Add RED Confix classfile blackboard test

**Objective:** Strengthen the classfile blackboard into a pattern-based scan and activation surface.

**Files:**
- Create: `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/ClassfileBlackboardRedTest.kt`

**RED assertions:**
- A blackboard registers whole-pattern scans and glob activation rules.
- It can project records as cursor rows for spreadsheet-like inspection.
- It can filter rows by facet and activation state.
- It preserves source/symbol/bytecode coordinates losslessly.

### Task 8: Add RED MutableSeries observable sink delegate test

**Objective:** Keep firehose/sink semantics explicit and lossless.

**Files:**
- Create: `libs/classfile/src/commonTest/kotlin/borg/trikeshed/classfile/red/MutableSeriesObservableSinkRedTest.kt`

**RED assertions:**
- `MutableSeriesPointcutSink` receives every activated pointcut delegate event.
- Delegates can be attached/detached by activation row.
- Sink records include activation pattern, delegate key, source, symbol, and bytecode pointcut kind.
- The unit test is explicitly synthetic until JVM/Graal launch tests feed the same sink.

---

## AOT/JIT dump and actual launch tranche

### Task 9: Add RED AOT/JIT dump harness test

**Objective:** Ensure dump artifacts scan through the same classfile SPI.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/AotJitDumpHarnessRedTest.kt`

**RED assertions:**
- Harness accepts an AOT/JIT dump directory or jar/classfile glob.
- Scanner records each matched classfile with source/symbol coordinates where present.
- Missing debug tables are represented as unresolved source coordinates, not failures.

### Task 10: Add RED real JVM launch test

**Objective:** Prove the JVM harness can execute a tiny fixture and drain pointcuts into the same sink.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/JvmActualPointcutLaunchRedTest.kt`

**RED assertions:**
- Launch a fixture with the command built in Task 2.
- Drain `MutableSeriesPointcutSink`.
- Assert observed events match the JEP 484 static scan at least by pointcut kind and symbol names.

### Task 11: Add RED real GraalVM launch test

**Objective:** Prove Graal polyglot actual launches use the same SPI and sink.

**Files:**
- Create: `libs/classfile/src/jvmTest/kotlin/borg/trikeshed/classfile/red/GraalPolyglotPointcutLaunchRedTest.kt`

**RED assertions:**
- Launch JS via GraalVM.
- Feed emitted/mapped events into `MutableSeriesPointcutSink`.
- Assert language id, source coordinate, symbol name, and bytecode fallback coordinate are all present.

---

## Minimal implementation order after RED inventory

1. Create only the common SPI/model records needed for compilation.
2. Add the faceted cursor constants and filtering helpers.
3. Add blackboard registration/projection as an in-memory `MutableSeries` model.
4. Add synthetic observable sink/delegate activation.
5. Implement the JEP 484 scanner over `java.lang.classfile` in Java/JVM source.
6. Wire JVM command/harness to scanner output first, runtime launch second.
7. Wire Graal ECMA mapping bridge using explicit `PolyglotSourceMapping` tables.
8. Add AOT/JIT dump scanner harness.

Each step must run a targeted RED→GREEN cycle before the next step.

---

## Verification commands

Prefer peripheral verification first:

```bash
# If libs/classfile remains wired as a root project:
./gradlew :libs:classfile:compileTestKotlinJvm --rerun-tasks
./gradlew :libs:classfile:jvmTest --rerun-tasks --tests "borg.trikeshed.classfile.red.*"

# If classfile is isolated as a peripheral standalone build:
/Users/jim/work/TrikeShed/gradlew -p libs/classfile compileTestKotlinJvm --rerun-tasks
/Users/jim/work/TrikeShed/gradlew -p libs/classfile jvmTest --rerun-tasks
```

If standalone Gradle complains the lib is not part of root settings, do **not** broaden root immediately. First add a local `libs/classfile/settings.gradle.kts` for peripheral verification. Root inclusion is a final integration step only after classfile is green standalone.

---

## Root-change budget

Allowed before the peripheral lib is green:
- Zero root code changes.
- Zero root source changes.
- Optional `.hermes/plans/*` only.

Allowed after peripheral lib is green, if needed:
- One small root `settings.gradle.kts` inclusion or composite-build line.
- No root `src/` dependencies on classfile internals; root consumes SPI-facing artifact only.

---

## Risks and guardrails

- **Existing `libs/classfile` build may be stale.** Do not fix by pulling root into it. Fix the peripheral build locally or isolate with a standalone settings file.
- **JEP 484 requires JDK 24+.** Current environment reports JDK 25, so scanner implementation can target `java.lang.classfile` directly.
- **Graal source sections are not always available.** Mapping must preserve unresolved fallback records.
- **MutableSeries firehose can overflow if backed by recursive series at high depth.** Use a flat sink journal for launch tests; keep cursor projection lazy.
- **Confix actuation must be opt-in.** Only facets declaring lambda-capable in-VM media can expose late-bound function actuation.
