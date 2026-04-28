---
name: trikeshed-tdd-process
description: TrikeShed KMM/KMP TDD discipline and cloud delegation test conventions — RED test rules, session boundaries, git discipline, and forbidden patterns distilled from all sessions.
---

# TrikeShed TDD + Cloud Delegation Process

## Role

Distilled from all sessions (April 2026) as the canonical reference for TDD discipline and cloud delegation requirements capture in the TrikeShed KMM/KMP monorepo.

---

## 1. What a RED Test IS

A RED test is an **irrefutable inline specification** — not documentation, not a comment, not a placeholder. It is the canonical, executable articulation of a requirement.

For cloud delegation offload, these tests ARE the contract. The cloud agent receives the test file and implements to make it green. No prose spec. No comment. The test is the spec.

A RED test:
- **Fails** intentionally (pins a missing behavior)
- **Named by the thing under test**, never by its status
- Lives in `*Test.kt` under `commonTest` or `jvmTest` source set
- Compiles cleanly against production types
- Has zero imports from stubs — imports production code and asserts on real shapes
- **Self-contained**: no cross-file imports from broken production code
- **Lossless**: every semantic edge case as an assertion, not a comment
- **Runnable**: `gradlew jvmTest` executes it (it fails, but it runs)


---

## 2. Filename Convention

| Style | Meaning |
|---|---|
| `ComponentTest.kt` | RED — fails until feature is built |


**Forbidden file naming**: NO ADJECTIVES IN FILENAMES AND NO BYPASSES IN EXTENSION.  .kt for kotlin is a concrete minimum.  `Actual`, `Legit`, `Real`, `Working`, and similar in filenames is evidence of a flailing llm experience

---

## 3. assertFails Is NOT a RED Test

`assertFails` / `assertFailsWith<NotImplementedError>` wrapping an unimplemented feature **is a fake HRM**. Forbidden as a substitute for real implementation.

assertFails is valid only for:
- Genuine error-handling tests: "given malformed input, `parse()` throws `MalformedFrameException`"
- Contract verification on known-bad inputs with real implementations

For missing features: write the test that asserts correct behavior, let it fail on `throw NotImplementedError` stub, then **build the feature**.

---

## 4. Session Boundary: Main Code Offlimits

**Test-only scope**:
- test code and main code should occupy the same project it started in while DRY applies to 360 degrees of the superproject 
- concurrency testsd should co-occupy jvmMain and NativeMain/PosixMain (we have no android or mobile Natives as of this writing)

---

## 5. Git Discipline

- **Never**: `git checkout`, `restore`, `reset`, `revert`, `stash pop` ; **/museum/ is reserved for low confidence code replaced by higher confidence, to be cleared by User manually and checked into the code never ignored   
- **Serialize writes**: `flock ~/.hermes/git-lock`
- **Commit body**: include agent session ID/url 
- **Prove runtime before claiming done**: show actual test output, don't describe what would happen
- **No requirements liberties**: while it is possible that the TDD is better served by an alternate methodology, these belong in optimzation recco/passes outside of solving either of TDD red or green.    
---

## 6. Concurrent Editor Rule

If the user is actively editing files during your session:
- Files regenerating = **user's editor output**, not build artifacts
- **Stop deleting them**
- If a file is gone, ask rather than recreate.  estimate the fix with work in museum/ and cease after minimal surface area and tokens expended.

If the user reverts something you've written: **Stop and report.**

---

## 7. Cloud Delegation Test Shape

Tests serving as inline requirements for  offload must be:

```
src/commonTest/kotlin/.../XXXXXTest.kt except where concurrency needs jvm/llvm linktime
```

The delegation agent receives the test file content and implements to make it green. The test is the complete, lossless specification.

---

## 8. TDD Session Phases

| Phase | Action                                                                                                                                                                          |
|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **RED** | Write smallest failing test pinning one missing behavior. Do not implement. Do not compose declarative Types in test as project code unless the specification is type-oriented  |
| **GREEN** | Implement minimum to pass. No refactoring.                                                                                                                                      |


If user says "TDD only" or "requirements first": stay in RED. Do not implement.

---

## 9. Forbidden Patterns

1. Wrapping unimplemented features in `assertFails` to fake progress
1. Naming tests with adjectives (Actual, Legit, Red, Real, Working)
1. Using `git checkout/restore/reset/revert/stash pop` in live (tdd red) sessions.   
1. Deleting files regenerating from the user's editor
1. Claiming done without showing runtime test output


## 10. Common Bug Patterns (TrikeShed KMP)

### RowAccessor / MiniRowVec type cast trap

`exec.Cursor.row` returns `RowAccessor`, NOT the underlying `MiniRowVec`. Attempting `cursor.row as? MiniRowVec` always returns `null` silently, producing 0 rows with no exception.

**Symptom:** `adaptCursorToMiniCursor` collects 0 rows — test passes 0 assertions, `assertEquals(expectedCount, actualRows.size)` fails with "expected 10 but was 0".

**Fix:** Wrap the underlying `MiniRowVec` in a `RowAccessor` implementation, then unwrap via the `row` property:
```kotlin
// Production: Cursor.row is RowAccessor, not MiniRowVec
internal class MiniRowVecRowAccessor(override val row: MiniRowVec) : RowAccessor

// In the adapter:
val rows = mutableListOf<DocRowVec>()
cursor.forEach { accessor ->
    (accessor as? MiniRowVecRowAccessor)?.row?.let { rows.add(it) }
}
MiniCursor(rows.size j { rows[it] })
```

**`internal` visibility requirement:** If a test helper needs to instantiate a `RowAccessor` wrapper to give the adapter a type to cast against, that wrapper class must be `internal` (not `private`), so the test in a different file can access it. Mark wrapper classes `internal` when they need to be shared between production and test source sets in the same module.

**`final` class trap:** `MiniRowVecRowAccessor` is a `final` class. It cannot be subclassed with `object : MiniRowVecRowAccessor(...) {}` in tests. Directly instantiate: `MiniRowVecRowAccessor(miniCursor.at(idx))`.

### Duck-type fallback that always falls through

When an adapter has multiple fallback cast attempts (`as? Type1`, `as? Type2`, `as? Type3`), each returns `null` and the loop continues. If ALL fallbacks return `null`, the result is 0 rows with no error. Silent failure.

**Fix:** Use exactly ONE cast path. Multiple fallback paths are only safe when each has a distinct, verifiable output (e.g., a log line or counter). For cursor adapters, prefer a single named cast with a test helper that provides the exact expected type.

### Zero-length snapshot cursor from snapshot-based MiniCursor

`MiniCursor` built via `rows.size j { rows[it] }` — if `rows` is populated from a snapshot list, all rows are collected eagerly. If `rows` is empty, `size` is 0, and the cursor has no elements. This is correct behavior but can surprise callers expecting at least one element.

**Fix:** Empty cursors are valid. Test the empty case explicitly:
```kotlin
@Test
fun empty cursor returns empty MiniCursor() {
    val emptyDocRows = emptyList<DocRowVec>()
    val result = adaptCursorToMiniCursor(emptyDocRows.cursor())
    assertEquals(0, result.size)
}
```


## 11. Known Sharp Edges

- **`MiniRowVecRowAccessor` is `final`** — cannot be extended
- **`MiniCursor` snapshot builds eagerly** — `rows.size j { rows[it] }` materializes all rows at construction time; large cursors should use lazy evaluation (streaming `Series<MiniRowVec>` from an iterator)
- **`rowCount` parameter in `adaptCursorToMiniCursor`** — may be unused; check if it is a leftover from a prior design before relying on it


This skill is the distillation of all corrections and instructions issued across sessions from April 25–28, 2026. It replaces any conflicting verbal instructions given mid-session.
This skill is the distillation of all corrections and instructions issued across sessions from April 25–28, 2026. It replaces any conflicting verbal instructions given mid-session.
