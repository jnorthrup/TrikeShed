# Track: JSON Runtime Stack Overflow Repair

**Track ID:** `json-runtime-stack-overflow-repair_20260310`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Restore JVM JSON-path/parser runtime behavior after compile surfaces are green. The current
failure cluster is nine `jvmTest` failures, all collapsing to `StackOverflowError` at
`Series.kt:367`.

## Invariants

- Work one runtime root-cause slice at a time
- Keep each slice to one file unless verification proves a direct adjacent dependency
- Verify with targeted JVM test commands, not narration
- Do not reopen the compile-repair tracks unless verification proves a real compile regression
- Do not normalize `Series.map` as a canonical surface; preferred repair direction is through the arrow/iterable bridge

## Slice Schema

### jsonso-01 — `Series.kt` iterable-map recursion repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt`

**Goal:**
- Repair the recursion at `Series.kt:367` so `Series.map` dispatches to iterable/list mapping instead of re-entering itself through `IterableSeries`
- Preserve the surrounding `Series` helper behavior outside the bounded recursion fix
- Reduce the JSON runtime failure cluster in `JsonPathTest` and `JsonParserTest`

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'`
**Expected result:** the `StackOverflowError` at `Series.kt:367` must disappear from the targeted JSON test failure set, even if later assertions or smaller follow-on bugs surface

**Delivered:**
- local `Series.kt` no longer defines the ad hoc `Series.map` extension that previously re-entered through `IterableSeries`
- focused JSON verification no longer surfaces `StackOverflowError`; the next visible failures are `ArrayIndexOutOfBoundsException` in `Json.kt` segment pairing/reify paths

---

### jsonso-02 — `Json.kt` segment boundary pair repair
**Status:** [ ] open
**Owner:** slave

**Corpus:**
- `src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt`

**Goal:**
- repair `JsContext.segments` and the related `JsonParser.reify()` delimiter pairing so single-element and empty-element arrays/objects do not overrun the combined boundary series
- preserve the accepted `Series.kt` recursion repair from `jsonso-01`
- reduce the current `ArrayIndexOutOfBoundsException` failure cluster in `JsonPathTest` and `JsonParserTest`

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'`
**Expected result:** the `ArrayIndexOutOfBoundsException` frames at `Json.kt:55` and `Json.kt:149` must disappear from the targeted JSON test failure set, even if later assertion bugs surface

## Evidence Log

- 2026-03-10: `./gradlew compileKotlinJvm` is green on `master`.
- 2026-03-10: `./gradlew compileTestKotlinJvm` is green on `master`.
- 2026-03-10: `./gradlew jvmTest` now executes and fails 9 tests across `JsonPathTest` and `JsonParserTest`, all surfacing `StackOverflowError` at `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt:367`.
- 2026-03-10: `Series.map` currently calls `this.\`▶\`.map(transform)`, but `IterableSeries` implements both `Iterable` and `Series`, making recursive dispatch the leading local root-cause candidate.
- 2026-03-10: User acceptance constraint: `Series.map` is not an admissible repo surface. Future repair on this track must preserve the arrow/iterable mapping arrangement instead of legitimizing `Series.map` as the owner API.
- 2026-03-11: Accepted the live `Series.kt` repair in the dirty worktree after raw inspection showed the ad hoc `Series.map` extension was removed rather than normalized.
- 2026-03-11: Focused verification command `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'` still fails, but `StackOverflowError` is gone. The new visible blocker cluster is `ArrayIndexOutOfBoundsException` entering `Json.kt:getSegments` at line 55 and `JsonParser.reify` at line 149 through `Combine.kt:52`.
- 2026-03-11: Reopened the track around `jsonso-02` because runtime behavior is not yet restored; the recursion root cause is fixed, but JSON segment boundary pairing still overruns on empty and single-segment cases.
