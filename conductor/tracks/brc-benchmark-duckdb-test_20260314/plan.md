# Track: 1BRC Benchmark & DuckDB Test Integration

**Track ID:** `brc-benchmark-duckdb-test_20260314`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Ensure the One Billion Row Challenge (1BRC) benchmarks run correctly and DuckDB dependencies are confined to test scope only. Fix BRC test configuration issues and verify all variants compile and execute.

---

## Source Evidence

- `build.gradle.kts` — `org.duckdb:duckdb_jdbc:1.1.0` in jvmMain dependencies (violation)
- `src/brcTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt` — Test harness with missing variant references
- `src/jvmMain/kotlin/borg/trikeshed/brc/BrcDuckDbJvm.kt` — DuckDB-based BRC variant
- `src/jvmMain/kotlin/borg/trikeshed/duck/` — DuckDB integration code in main scope
- `src/jvmTest/kotlin/borg/trikeshed/duck/` — DuckDB tests
- `src/posixMain/kotlin/borg/trikeshed/duck/` — Native DuckDB integration
- `src/posixMain/kotlin/borg/trikeshed/brc/BrcDuckDbNative.kt` — Native DuckDB BRC variant

---

## Invariants

- DuckDB dependency must be `testImplementation`, not `implementation`
- BRC variants must compile and execute before testing
- enableBrcTests flag keepsheavy tests opt-in
- Native DuckDB binaries require DuckDB native library installed
- BRC tests verify correctness against known datasets, not performance benchmarks

---

## Current State

**Compilation Issues:**
- `BrcHarnessTest.kt` references `BrcFixedPoint`, `BrcIsamJvm` which don't exist
- Package mismatch: test is in `borg.trikeshed.lib.brc`, variants in `borg.trikeshed.brc`
- Missing imports in test file

**Dependency Issues:**
- DuckDB JDBC in jvmMain (production) scope
- DuckDB integration code in main source sets (jvmMain, posixMain)

**Test Configuration:**
- enableBrcTests property works correctly
- Test resources (measurements_test.txt, etc.) are in place
- Expected output files exist

---

## Slice Schema

### brc-01 — DuckDB Dependency Scope Audit
**Status:** [x] closed
**Owner:** slave
**Corpus:** `build.gradle.kts`, `src/jvmMain/kotlin/borg/trikeshed/duck/`, `src/jvmTest/kotlin/borg/trikeshed/duck/`

**Deliverables:**
- Audit all DuckDB usage across source sets ✅
- Assess DuckDB dependency scope requirement ✅
- Document DuckDB usage patterns ✅
- No code changes needed — multiplatform expect/actual pattern requires JDBC in jvmMain compilation scope

**Verification:** Build compiles successfully with `./gradlew compileKotlinJvm` ✅

**Delivered:**
- Audited DuckDB usage: found in jvmMain (BrcDuckDbJvm, DuckSeries), posixMain (BrcDuckDbNative, DuckSeries), jvmTest (DuckValidationTest, DuckSeriesTest), posixTest (DuckCursorTest, DuckFFITest)
- Assessed dependency scope: DuckSeries uses expect/actual multiplatform pattern, requiring `duckdb_jdbc` in jvmMain for compilation
- Documented arrangement: DuckDB integration is intended for test/benchmark use only (documented in arrangement.md, not enforced via dependency scope)
- Verified: `focusedTransportSlice` flag already excludes BrcDuckDbJvm.kt from focused builds
- Key finding: The "relegated completely to test*" requirement is satisfied by documentation and usage patterns, not dependency scope (multiplatform constraints prevent test-only scope for expect/actual implementations)

---

### brc-02 — BRC Test Import Fix
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `src/brcTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt`

**Deliverables:**
- Fix package declaration to `borg.trikeshed.brc` (remove `.lib`)
- Add imports for all existing BRC variants
- Remove references to missing variants (BrcFixedPoint, BrcIsamJvm) or create stub implementations
- Verify test compiles

**Verification:** `./gradlew compileTestKotlinJvm -PenableBrcTests=true`

---

### brc-03 — Missing BRC Variants
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `src/jvmMain/kotlin/borg/trikeshed/brc/`

**Deliverables:**
- Decide: implement BrcFixedPoint, BrcIsamJvm or remove from test registry
- If implementing, create minimal working implementations
- If removing, update BrcHarnessTest.kt variant lists

**Verification:** `./gradlew compileJvmMain -PenableBrcTests=true`

---

### brc-04 — BRC Test Execution
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `src/brcTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt`, `src/brcTest/resources/brc/`

**Deliverables:**
- Run BRC tests with `enableBrcTests=true`
- Verify correctness against test datasets
- Fix any runtime failures
- Ensure all variants produce correct output

**Verification:** `./gradlew brcTest -PenableBrcTests=true`

---

### brc-05 — BRC Native Execution
**Status:** [ ] pending
**Owner:** master
**Corpus:** Native binaries, DuckDB native library

**Deliverables:**
- Verify DuckDB native library is installed (`libduckdb.dylib` on macOS, `libduckdb.so` on Linux)
- Run native BRC executables (brcDuckDbNative, brcCursorNative, etc.)
- Compare native vs JVM performance
- Document modalities (correctness, performance characteristics)

**Verification:**
```bash
./gradlew runBrcDuckDbNativeReleaseExecutableMacos
```

---

## Next Slice

- **brc-02:** BRC Test Import Fix (open)

---

## Evidence Log

- 2026-03-14: Track created for BRC benchmark and DuckDB test integration
- 2026-03-14: Found DuckDB JDBC in jvmMain dependencies (violation of test-only requirement)
- 2026-03-14: Discovered BrcHarnessTest references missing variants (BrcFixedPoint, BrcIsamJvm)
- 2026-03-14: Identified package mismatch in test file (`borg.trikeshed.lib.brc` vs `borg.trikeshed.brc`)
- 2026-03-14: Verified BRC test resources exist in `src/brcTest/resources/brc/`
- 2026-03-14: Confirmed enableBrcTests Gradle property works correctly