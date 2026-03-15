# Arrangement: 1BRC Benchmark & DuckDB Test Integration

## Intent

Run the One Billion Row Challenge (1BRC) benchmarks and ensure DuckDB dependencies are documented as test-scoped only, with proper test configuration for all BRC variants.

## Owner Matrix

- **BRC Test Configuration**
  - **Current state:** `BrcHarnessTest.kt` references missing variants (BrcFixedPoint, BrcIsamJvm) and has package mismatch
  - **Required fix:** Import existing variants properly, implement missing variants or remove from test registry
  - **Owner:** test configuration in `src/brcTest/kotlin/`

- **DuckDB Dependency**
  - **Current state:** `org.duckdb:duckdb_jdbc:1.1.0` declared in `jvmMain` dependencies (required for expect/actual DuckSeries)
  - **Assessment:** DuckSeries uses expect/actual multiplatform pattern, requiring JDBC in jvmMain for compilation
  - **Resolution:** Document that DuckDB integration (DuckSeries, BrcDuckDbJvm) is intended for test/benchmark use only
  - **No violation:** Multiplatform expect/actual requires JDBC in compilation scope; usage is test-only
  - **Owner:** `build.gradle.kts` dependency declaration with arrangement documentation

- **BRC Variants**
  - **Existing:** BrcBaseline, BrcCursor, BrcMmap, BrcParallel, BrcDuckDbJvm, BrcDuckDbNative
  - **Missing:** BrcFixedPoint, BrcIsamJvm (referenced in test but not implemented)
  - **Required fix:** Either implement missing variants or remove them from test registry

- **enableBrcTests Flag**
  - **Current usage:** `enableBrcTests` property adds `src/brcTest/kotlin` to jvmTest
  - **Owner:** Gradle build configuration
  - **Status:** Correct approach for optional/heavy tests

## Negative Decisions

- Do **not** move DuckDB dependency from jvmMain during expect/actual compilation (breaks multiplatform)
- Do **not** run heavy 1BRC regression tests in default `jvmTest` target
- Do **not** include experimental BRC code variants in default build (keep exclusions in build.gradle.kts)
- Do **not** break existing DuckDB tests in `src/jvmTest/kotlin/borg/trikeshed/duck/`

## TDD Stance

- Fix package imports in `BrcHarnessTest.kt` to reference existing variants
- Document DuckDB as test-only usage in arrangement (no code change needed for dependency scope)
- Implement missing BRC variants or remove from test registry
- Verify BRC tests pass with `enableBrcTests=true`
- Ensure DuckDB usage remains focused on tests/benchmarks

## Immediate Follow-On

1. Fix BRC test package imports
2. Implement or remove missing BRC variants from test registry
3. Run BRC tests and verify correctness
4. Run native BRC executables and inspect modalities