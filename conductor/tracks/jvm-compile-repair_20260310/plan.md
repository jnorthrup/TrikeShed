# Track: JVM Compile Repair

**Track ID:** `jvm-compile-repair_20260310`
**Branch:** `master`
**Status:** ✅ Completed

---

## Purpose

Restore default JVM compilation in bounded slices. Current `./gradlew compileKotlinJvm`
fails on multiple unrelated files, so this track repairs one compile blocker at a time
instead of pretending the whole JVM surface is one atomic fix.

## Invariants

- Work one compile blocker slice at a time
- Keep each slice to one file unless verification proves a direct adjacent dependency
- Verify with compile-oriented commands, not narration
- Do not reopen focused transport files unless a compile blocker directly depends on them

## Slice Schema

### jvmfix-01 — `HttpMethod.kt` syntax repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/one/xio/HttpMethod.kt`

**Goal:**
- Repair the syntax error reported near line 148 in `HttpMethod.kt`
- Preserve surrounding `AsioVisitor` origin-debug behavior
- Reduce the default JVM compile blocker set by one concrete file

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** compile may still fail on other files, but `HttpMethod.kt` syntax must no longer appear in the error set
**Actual result:** `HttpMethod.kt` removed from the compiler error set; remaining failures are in other files

### jvmfix-02 — `BrcDuckDbJvm.kt` Series access repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/borg/trikeshed/brc/BrcDuckDbJvm.kt`

**Goal:**
- Replace invalid array-style access on `Series<Any?>` with the actual `Series` access pattern returned by `DuckSeries.columns()`
- Preserve existing output formatting semantics
- Reduce the default JVM compile blocker set by one concrete file

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** compile may still fail on other files, but `BrcDuckDbJvm.kt` must no longer appear in the error set
**Actual result:** `BrcDuckDbJvm.kt` removed from the compiler error set; remaining failures are in other files

### jvmfix-03 — `HttpHeaders.kt` typed map initializer repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/one/xio/HttpHeaders.kt`

**Goal:**
- Repair the generic initializer in `getHeaders()` so the local map type matches `MutableMap<String?, IntArray?>`
- Preserve the surrounding header-scan behavior
- Reduce the default JVM compile blocker set by one concrete file

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** compile may still fail on other files, but `HttpHeaders.kt` must no longer appear in the error set
**Actual result:** `HttpHeaders.kt` removed from the compiler error set; remaining failures are in other files

### jvmfix-04 — `CookieRfc6265Util.kt` ByteSeries compile repair
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/rxf/server/CookieRfc6265Util.kt`

**Goal:**
- Reduce the `CookieRfc6265Util.kt` compile error cluster without widening scope beyond that file
- Reconcile the file’s ByteBuffer-style assumptions against the actual TrikeShed `ByteSeries` surface in use
- Reduce the default JVM compile blocker set by one concrete file

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** compile may still fail on other files, but `CookieRfc6265Util.kt` must either disappear from the error set or shrink in a demonstrable bounded way if a sharper sub-slice is required
**Actual result:** closed via `jvmfix-04a`; `CookieRfc6265Util.kt` no longer appears in the compiler error set on `master`

#### jvmfix-04a — `CookieRfc6265Util.kt` JVM ByteBuffer realignment
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/rxf/server/CookieRfc6265Util.kt`

**Goal:**
- Stop treating TrikeShed `BFrag`/`ByteSeries` like a Java `ByteBuffer` in this JVM-only file
- Realign the file to a coherent JVM buffer/parsing surface inside the same file
- Eliminate the top compile blocker cluster in `Name`, `Value`, `Expires`, `Max-Age`, `parseSetCookie()`, and `parseCookie()`

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** `CookieRfc6265Util.kt` no longer appears in the compiler error set, even if later JVM blockers surface elsewhere
**Actual result:** `CookieRfc6265Util.kt` removed from the compiler error set on `master`; the next blocker surfaced in `HttpMethod.kt`

### jvmfix-05 — `HttpMethod.kt` killswitch JVM signature clash
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/jvmMain/kotlin/one/xio/HttpMethod.kt`

**Goal:**
- Remove the JVM signature clash between the generated `killswitch` property setter and the explicit `setKillswitch(Boolean)` method in `HttpMethod.Companion`
- Preserve the existing killswitch control surface semantics for callers
- Reduce the default JVM compile blocker set by one concrete file

**Verification:** `./gradlew compileKotlinJvm`
**Expected result:** compile may still fail on later files, but `HttpMethod.kt` must no longer appear in the error set for the killswitch clash
**Actual result:** `./gradlew compileKotlinJvm` is green on `master`

---

## Evidence Log

- 2026-03-10: Track created from repo-local evidence after stream transport closed. `./gradlew jvmTest --tests 'borg.trikeshed.common.FilesTest'` is blocked before test execution by unrelated JVM compile failures.
- 2026-03-10: Default JVM compile currently fails in multiple files including `BrcDuckDbJvm.kt`, `HttpHeaders.kt`, `HttpMethod.kt`, and `CookieRfc6265Util.kt`.
- 2026-03-10: `jvmfix-01` chosen as the first bounded slice because `HttpMethod.kt` reports a direct syntax error and can be repaired without widening scope first.
- 2026-03-10: `jvmfix-01` closed — the malformed merged statement in `HttpMethod.kt` was split into a valid assignment followed by the origin-debug `if` block. `./gradlew compileKotlinJvm` still fails, but `HttpMethod.kt` no longer appears in the error set.
- 2026-03-10: Local delegated branch disposition checked for retirement. `codex/jvmfix-01-kilo` matches the accepted `HttpMethod.kt` repair already present in the master worktree. `codex/stream-02-kilo` and `codex/stream-03-kilo` are superseded by reconciled master-worktree versions of `QuicChannelService.kt` and `NgSctpService.kt`; those slave branches are no longer canonical truth. Retire all three only after the corresponding master-side changes are committed.
- 2026-03-10: `jvmfix-02` first attempted on `kilo`; the worker stalled inside the bounded corpus and never produced a usable file edit before hitting a runtime/API failure. The worker was explicitly terminated and the slice was rerouted.
- 2026-03-10: `jvmfix-02` closed via delegated `codex exec` worktree. The accepted repair imports `borg.trikeshed.lib.get` so `Series<Any?>` uses the TrikeShed indexed access operator expected by `DuckSeries.columns()`. `./gradlew compileKotlinJvm` still fails, but `BrcDuckDbJvm.kt` no longer appears in the error set.
- 2026-03-10: `jvmfix-03` closed via delegated `codex exec` worktree. `HttpHeaders.getHeaders()` now constructs `LinkedHashMap<String?, IntArray?>()` instead of an `Any?/Any?` map, and `HttpHeaders.kt` no longer appears in the compiler error set.
- 2026-03-10: After `jvmfix-03`, the remaining visible compile blocker cluster is concentrated in `src/jvmMain/kotlin/rxf/server/CookieRfc6265Util.kt`, so `jvmfix-04` is the next bounded slice.
- 2026-03-10: `jvmfix-04` sharpened to `jvmfix-04a` after raw compiler inspection showed this is a single-file JVM `ByteBuffer` realignment problem, not a cross-file `ByteSeries` utility gap. The acceptance gate is that `CookieRfc6265Util.kt` disappears from the `compileKotlinJvm` error set.
- 2026-03-10: `jvmfix-04a` was first attempted on `kilo`; the worker delivered the main file rewrite but stalled on follow-through. A second bounded `codex exec` continuation repaired the remaining `Join` accessor mismatch in that delegated worktree.
- 2026-03-10: `jvmfix-04a` closed after reconciling the accepted delegated `CookieRfc6265Util.kt` rewrite into `master`. `./gradlew compileKotlinJvm` on `master` no longer reports `CookieRfc6265Util.kt`; the next visible blocker is a `killswitch` JVM signature clash in `src/jvmMain/kotlin/one/xio/HttpMethod.kt`.
- 2026-03-10: `jvmfix-05` closed via delegated `codex exec` on the live `master` worktree. The generated JVM setter for `killswitch` was renamed with `@set:JvmName("setKillswitchValue")`, preserving the explicit `setKillswitch(Boolean)` control surface. `./gradlew compileKotlinJvm` is now green on `master`.
