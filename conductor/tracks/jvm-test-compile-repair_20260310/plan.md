# Track: JVM Test Compile Repair

**Track ID:** `jvm-test-compile-repair_20260310`
**Branch:** `master`
**Status:** ✅ Completed

---

## Purpose

Restore JVM test compilation in bounded slices now that `./gradlew compileKotlinJvm`
is green. The first visible blocker is a test-only MsgPack dependency surface in
`SmMsgPackTest.kt`.

## Invariants

- Work one JVM test compile blocker slice at a time
- Keep each slice to one file unless verification proves a direct adjacent dependency
- Verify with targeted test-compilation commands, not narration
- Do not reopen the production JVM compile track unless test compilation proves a real regression there

## Slice Schema

### jvmtest-01 — `SmMsgPackTest.kt` MsgPack dependency repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmTest/kotlin/gk/kademlia/codec/SmMsgPackTest.kt`

**Goal:**
- Remove the unresolved `com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack` test dependency failure in `SmMsgPackTest.kt`
- Keep the slice bounded to the test file unless verification proves a direct adjacent dependency must change
- Reduce the `compileTestKotlinJvm` blocker set by one concrete file

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.common.FilesTest'`
**Expected result:** test execution may still fail later, but `SmMsgPackTest.kt` must no longer appear in the `compileTestKotlinJvm` error set
**Actual result:** `SmMsgPackTest.kt` no longer appears in the `compileTestKotlinJvm` error set, and `compileTestKotlinJvm` is green on `master`

## Evidence Log

- 2026-03-10: `./gradlew compileKotlinJvm` is green on `master`, so the JVM compile-repair track is complete.
- 2026-03-10: `./gradlew jvmTest --tests 'borg.trikeshed.common.FilesTest'` now reaches `compileTestKotlinJvm` and fails in `src/jvmTest/kotlin/gk/kademlia/codec/SmMsgPackTest.kt` with unresolved `MsgPack` imports and symbols.
- 2026-03-10: `jvmtest-01` chosen as the first bounded slice because the failure is isolated to one test file and can likely be repaired without widening scope first.
- 2026-03-10: `jvmtest-01` closed via delegated `codex exec` on `master`. The test now uses JVM-native object serialization instead of an unavailable MsgPack dependency, and both `./gradlew jvmTest --tests 'borg.trikeshed.common.FilesTest'` and `./gradlew compileTestKotlinJvm` are green.
