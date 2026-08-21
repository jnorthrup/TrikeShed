# TrikeShed Quality Gates

This document describes the automated quality gates run to verify the integrity of the codebase.

## Gate Set

### 1. jvmMainClasses — JVM compilation gate (always)
```bash
./gradlew jvmMainClasses --console=plain
```

**Purpose**: Verifies that the primary JVM target compiles successfully.

**Status**: Must succeed. This is the main gate for the drain-contract build.

---

### 2. compileKotlinJs — JavaScript/WASM compilation gate (per-file)
```bash
./gradlew compileKotlinJs --console=plain --continue 2>&1 | grep '^e: ' | grep -c '<owned path fragment>'
```

**Purpose**: Verifies JavaScript and WebAssembly target compilation. The JS/WASM tree is red until tasks T01–T05 land, so violations are counted per-file rather than globally.

**Status**: Expected to FAIL until T01–T05 are complete. Per-file error count must be 0 only for owned files.

---

### 3. commonMainPurity — commonMain purity check
```bash
./gradlew commonMainPurity --console=plain
```

**Purpose**: Detects JVM-specific patterns and imports in `src/commonMain/**/*.kt` to enforce platform-neutral code.

**Checked patterns**:
- `^import java\.` — JVM stdlib imports
- `\bSystem\.` — JVM System class
- `Dispatchers\.IO` — JVM-only coroutine dispatcher
- `Charsets\.` — JVM-specific charset utilities
- `\.format\(` — JVM String.format
- `java\.nio` — JVM NIO packages
- `\bSelector\b` — NIO Selector
- `SocketChannel` — NIO SocketChannel

**Whitelist**: Add `// purity:allow <reason>` to the line to exempt it from checks.

**Status**: Allowed to FAIL until T01–T05 land (violations are expected). Must list only real violations when enabled.

---

## Running the full gate set

To run all three gates:

```bash
./gradlew jvmMainClasses compileKotlinJs commonMainPurity --console=plain
```

Or individually via the `check` task (includes commonMainPurity):

```bash
./gradlew check --console=plain
```

## Design Notes

- **Drain-contract principle**: The JVM gate is always required and must pass.
- **JS gate**: Per-file error counting allows incremental healing across multiple tasks without blocking the drain.
- **Purity gate**: Catches regressions in commonMain hygiene to support multiplatform code sharing and future polishing.
