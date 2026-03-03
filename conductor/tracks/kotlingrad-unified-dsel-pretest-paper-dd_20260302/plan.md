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

## Gradle Consumption Reference
In leafy sibling projects (`moneyfan`, `curly-succotash`), include TrikeShed as a composite build in `settings.gradle.kts`:
\`\`\`kotlin
includeBuild("../TrikeShed")
\`\`\`
Then declare dependency in `build.gradle.kts`:
\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\}
\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\fie\`\`\eS\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\`\fie\`\`\eS\`\`\`\`\`\`\`\`\`\`\`\`inclusion.
