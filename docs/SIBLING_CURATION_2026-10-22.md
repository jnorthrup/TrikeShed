# Dirty sibling curation — 2026-10-22

Source: `../old/triumverant/trikeshed`, an absorbed submodule at
`aa79fc1f21ccf4bf8c3de92c757ca0d9c0982a68`. Its source edits mostly date
from January 2025; the indicator placeholder dates from February 2026.
Age reduces the authority of those implementations, while their intended
behavior is evaluated against the current code.

## Preserved evidence

- Staged source: `3f7c84482d9e90de2a66ad96fe5140f49af0798b`.
- Working source, including the untracked indicator placeholder:
  `6df066c5da303013139f99e78980cb03e2fc21e8`.
- Raw merge tree with conflict markers:
  `45b5252109e8bbb74347606f7578dce910bb2e3c`.
  Its commit message records every unmerged index entry: mode, blob, stage
  and path. There were 44 conflicted paths. The incoming and current parents
  preserve both sides of deletion and relocation conflicts.

These commits remain ancestors of the curated merge. For example,
`git show 45b5252109:src/commonMain/kotlin/borg/trikeshed/indicator/FeatureExtractor.kt`
shows the original competing APIs.

The complete original checkout, including generated files, and its Git
directory are also archived locally at
`../.retired/trikeshed-siblings-20261022/triumverant-trikeshed.tar.gz`.
SHA-256: `04b217344027fda1937d0efbe9dd44970aa203dddde1c0203e63be3a007ee493`.
That archive preserves the original index and untracked files; staged and
unstaged binary patches accompany it. The 41 generated cache additions are
kept in this local archive rather than introduced into source history.

## Disposition

| Recovered intent | Curated result |
| --- | --- |
| Kotlin 2.1, JDK 21, ARM targets and wrapper refresh | Current Kotlin 2.4.21, JDK 25, ARM targets and Gradle 9.8.2 already supersede these changes. |
| Move six legacy Kademlia FSM files into common code | The relocation still depends on Java selectors, threads and executors, and removes required imports/types. Current `userspace.nio.channels.ChannelRunner` supplies common readiness, bounded dispatch, ownership and drain. Preserve the abandoned relocation as evidence. |
| Delete older storage and collection implementations | Many paths have already moved or been replaced. Retain current implementations and still-required platform bindings; the old deletion supplies no replacement for those bindings. |
| Add an empty `ByteSeries` companion | The current companion already implements `unbrace`, `unbracket` and `unquote`. |
| Simplify the old JSON quoted-string branch | Semantics are unchanged; the old parser has been replaced. |
| `FeatureExtractor.compute(Cursor): Cursor` | Complete the missing API by projecting named numeric columns into existing indicator calculations and exposing named feature columns. Do not preserve the placeholder's identity behavior. |
| Indicator family labels, including `percentile_rank` | Labels have no callers and supply no percentile-rank computation or specification. Preserve the original text without inventing runtime behavior. |

The raw merge also proposed moving current JVM Java files into
`commonMain` and removed current Couch, Cursor and pointcut files. Those
changes do not occur in the sibling's source delta and are rejected as merge
artifacts. The raw tree records them for inspection.

Only the recovered TrikeShed submodule is retired. Its independently dirty
multi-project parent, the separate benchmark project and the live NPO
runtime retain their existing roles.

## Build repair

The starting `master` (`febce3715`) did not compile: `HeadhunterWire` and
`HeadhunterCouncilHarness` called the `providerTag` extension without importing
it. Both imports were already present among the primary checkout's pending
edits. Include only those imports to repair compilation; leave its remaining
council behavior edits pending.

## Validation

With the overload absent, full JVM test compilation fails on the new Cursor
calls. With the implementation restored,
`./gradlew :jvmTest --tests 'borg.trikeshed.indicator.*' --console=plain`
passes all 30 tests (three new Cursor cases and 27 existing indicator cases),
with no failures or skips. JVM main/test sources and the browser assets
required by the normal task graph compiled successfully. Logs and JUnit XML
are retained alongside the local archive under `validation/`.
