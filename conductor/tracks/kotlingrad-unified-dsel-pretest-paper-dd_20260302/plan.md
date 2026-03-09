# Plan: Unified Kotlingrad DSEL for Pretesting + Paper Testing Drawdown

## Purpose
Build a stable, testable Kotlingrad DSEL layer for drawdown-related pretesting and paper-testing contracts consumed by adjacent repos.

## Bounded Corpus
- `src/jvmMain/kotlin/borg/trikeshed/grad/`
- `src/commonMain/kotlin/borg/trikeshed/grad/`
- `conductor/tracks/kotlingrad-unified-dsel-pretest-paper-dd_20260302/`

## Tasks
- [x] Restore TrikeShed as the Gradle source-of-truth.
- [x] Clear out duplicated library code from leafy sibling repos.
- [x] Document Gradle consumption logic for leafy projects.
- [x] Synchronize boundaries in product/tech-stack docs.
- [x] Add deterministic JVM test coverage for `SeriesGrad` drawdown and max-drawdown contract behavior.
- [ ] Validate the kotlingrad pretest thin-slice path with a focused JVM test run and capture evidence in this plan.

## Gradle Consumption Reference
In leafy sibling projects (`moneyfan`, `curly-succotash`), include TrikeShed as a composite build in `settings.gradle.kts`:
```kotlin
includeBuild("../TrikeShed")
```
Then declare dependency in `build.gradle.kts`:
```kotlin
dependencies {
    implementation("borg.trikeshed:trikeshed-core")
}
```

## Next Bounded Slice
- Slice ID: `kg-dd-test-contract-02`
- Owner: `master`
- Corpus: `src/jvmTest/kotlin/borg/trikeshed/grad/DselBenchmarkTest.kt`, `conductor/tracks/kotlingrad-unified-dsel-pretest-paper-dd_20260302/plan.md`
- Stop condition: focused drawdown contract tests executed with captured JVM evidence, or first concrete blocker.
- Verification command: `./gradlew :test --tests borg.trikeshed.grad.DselBenchmarkTest`

## Evidence Log
- 2026-03-08: Plan reconciled after markdown corruption and reopened with executable test-oriented slices.
- 2026-03-08: Added deterministic drawdown contract tests in `DselBenchmarkTest` for `(close - peak) / peak` and running worst-drawdown (`prevWorst minOf currentDrawdown`) behavior.
- 2026-03-08: Focused verification blocked by environment constraints:
  - `GRADLE_USER_HOME=/Users/jim/work/trikeshed/.gradle-home ./gradlew :test --tests borg.trikeshed.grad.DselBenchmarkTest`
  - failed with `java.net.UnknownHostException: services.gradle.org` (network-restricted runtime cannot download `gradle-8.13-bin.zip`).
