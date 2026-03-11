# Track: JSON Scan Autovec Shaping

**Track ID:** `json-scan-autovec-shaping_20260311`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Reshape TrikeShed's JSON structural scan so compiler autovec has a fair shot instead of seeing pretty abstractions and backing away.

The accepted direction for this track is:

- contiguous access in the hot scan kernel
- explicit `Int` induction variables in structural loops
- no hidden aliasing or polymorphic iterator overhead in the kernel
- no accidental allocation in the hot loop
- branch structure simple enough to lower toward masks
- `▶` kept for traversal/report surfaces, not hot structural loops
- `α` used only when deferred indexed projection is the real goal
- scan separated from reify so structural discovery is not fused with object materialization

This track removes code-shape blockers. It does not claim that autovec is guaranteed once the shaping lands.

## Gate

- `json-runtime-stack-overflow-repair_20260310` remains the active runtime repair track; the recursion root cause is accepted, but JSON boundary pairing still fails in `Json.kt`.
- `autovec-00` is closed in this turn because it is conductor truth only.
- `autovec-02` through `autovec-04` are acceptance-blocked on `jsonso-02` unless a focused scan-only verification surface proves they can be validated without the still-red JSON runtime path.

## Invariants

- Keep JSON hot loops on direct indexed access over `Series<Char>` or packed byte arrays; do not reintroduce generic collection helpers into the kernel.
- Prefer one structural concern per loop: byte event scan, depth bookkeeping, segment slicing, and materialization should not collapse into one abstraction-heavy pass.
- Do not use `▶`, `zipWithNext()`, `map()`, or `associate()` as the owner form inside the structural scan kernel.
- Do not add helper layers that obscure stride, aliasing, or write destination.
- Preserve current JSON semantics and path/depth behavior while reshaping the implementation.
- Prove behavior with focused JSON tests before widening the pattern to CSV parity.

## Source Evidence

- User direction for this track: TrikeShed autovec work succeeds only after the code is manually shaped toward contiguous access, explicit induction, low allocation, simple branches, and a scan/reify split.
- `src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt`
  - `JsonParser.index()` is already an explicit indexed scan, but it is coupled tightly to downstream recursive consumers.
  - `JsonParser.reify()` still mixes structural segmentation with materialization and uses traversal helpers like `(combine.\`▶\` as Iterable<Int>).zipWithNext().map { ... }`.
- `src/commonMain/kotlin/borg/trikeshed/parse/json/JsonBitmap.kt`
  - the file already declares autovectorization as the goal, but the current nested `do/while` kernel still needs hand-shaped induction and contiguous write logic.
- `src/commonMain/kotlin/borg/trikeshed/parse/csv/CsvBitmap.kt`
  - mirrors the JSON bitmap kernel closely enough to serve as a later parity slice once the JSON pattern is proven.
- `src/commonMain/kotlin/borg/trikeshed/parse/json/JsonIndex.html`
  - records the repo's existing byte-encoding/autovectorization intent for JSON state tracking.
- `conductor/tracks/json-runtime-stack-overflow-repair_20260310/plan.md`
  - adjacent prerequisite track; this autovec track must not pretend the current recursion failure is already solved.

## Slice Schema

### autovec-00 — Truth and Gate Capture
**Status:** [x] closed
**Owner:** master
**Corpus:** `conductor/tracks.md`, `conductor/tracks/json-scan-autovec-shaping_20260311/`

**Deliverables:**
- create the JSON autovec-shaping track in repo-local conductor truth
- record the accepted loop-shaping doctrine
- distinguish this work from the active JSON runtime recursion repair
- lock JSON as the first bounded corpus; CSV parity is follow-on only

**Verification:** this plan exists and is indexed in `conductor/tracks.md`

---

### autovec-01 — JSON Scan/Reify Red Contracts
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonTest/kotlin/borg/trikeshed/parse/json/`, `src/jvmTest/kotlin/borg/trikeshed/parse/`, `src/jvmTest/kotlin/borg/trikeshed/parse/json/` (create if needed), `src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt` (read-only)

**Goal:**
- add focused contracts that make the scan/reify split executable instead of rhetorical
- capture behavior for structural segment discovery independent from full object materialization where practical
- keep the slice test-first and bounded to JSON parser coverage

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'`

**Exit gate:**
- the next implementation slices have a test surface that can detect regressions in scan shape and materialization behavior separately

---

### autovec-02 — `JsonBitmap.kt` Induction-Shape Repair
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/parse/json/JsonBitmap.kt`, adjacent JSON bitmap tests if created
**Blocked by:** `jsonso-01` unless validation stays fully scan-local

**Goal:**
- rewrite `encode()` and `decode()` around explicit `Int` induction variables and direct contiguous writes
- avoid helper abstractions that hide stride, mask position, or array ownership
- preserve existing packed-bit behavior exactly

**Verification:** focused JSON bitmap tests once introduced; otherwise targeted JSON parser/path tests plus raw file inspection

---

### autovec-03 — `Json.kt` Structural Scan Extraction
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt`
**Blocked by:** `jsonso-01`

**Goal:**
- separate structural scan/index discovery from object/array reification
- keep structural loops on indexed access with explicit `Int` vars
- avoid `▶`-driven traversal inside the hot structural path

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'`

---

### autovec-04 — `Json.kt` Reify Consumption Tightening
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt`
**Blocked by:** `autovec-03`

**Goal:**
- make reify consume precomputed structure instead of rediscovering it through iterator-heavy segment traversal
- confine allocations to materialization boundaries instead of the structural scan kernel
- preserve object/array/scalar semantics for current JSON tests

**Verification:** `./gradlew jvmTest --tests 'borg.trikeshed.parse.JsonPathTest' --tests 'borg.trikeshed.parse.json.JsonParserTest'`

---

### autovec-05 — `CsvBitmap.kt` Parity Follow-On
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/parse/csv/CsvBitmap.kt`, focused CSV bitmap tests if created
**Blocked by:** `autovec-02`

**Goal:**
- port the proven JSON bitmap loop shape into the CSV bitmap kernel without widening the abstraction surface
- keep CSV parity as a follow-on, not a co-owned first slice

**Verification:** focused CSV bitmap tests once introduced

## Evidence Log

- 2026-03-11: Track created from explicit user direction that TrikeShed autovec work must be manually shaped around contiguous access, explicit `Int` loops, low allocation, simple branches, and a scan/reify split.
- 2026-03-11: Local repo evidence gathered from `Json.kt`, `JsonBitmap.kt`, `CsvBitmap.kt`, and `JsonIndex.html`; JSON was selected as the first bounded corpus because the repo already states an autovectorization goal there.
- 2026-03-11: Track gated behind `json-runtime-stack-overflow-repair_20260310/jsonso-01` for runtime acceptance so conductor truth does not conflate the `Series` recursion bug with autovec shaping work.
- 2026-03-11: Gate retargeted to `jsonso-02` after focused verification showed the `Series.kt` recursion failure is gone and the next blocker is `Json.kt` boundary pairing.
