# `doc/concepts.md` Gap Analysis — refresh 2026-07-19

Re-audit of the prior pass (which compared `cebea1da` → `638fb71b`) against
current master (`90541f94`, post 2026-07-19 upstream merge). Each prior finding
is re-scored against live disk; new findings from the litebike/NUID/LCNC
session and the 2026-07-19 upstream merge are appended.

## 2026-07-19 merge summary

Two remote branches merged into master after pre-flight verification
(no libs/, no merge artifacts, root-shaped):

- `origin/jules-18017460688326899188-3d405ebb` (1 commit, 121 lines)
  — adds `ProcessReactorEndpoint` (commonMain) + JVM test. NUID-authorized
  exec dispatcher over `ProcessOperations` SPI. Fulfills T12.
- `origin/fix-forge-assets-11945900129262057005` (1 commit, ~7.5k lines)
  — moves the Forge HTML/CSS/JS shell out of inline Kotlin strings into
  `src/commonMain/resources/web/` and adds the `generateForgeAssets` Gradle
  task that bakes them into `borg.trikeshed.forge.generated.ForgeAssets`
  (ByteArray chunk objects). `ForgeApp.kt`/`ForgePersistenceScript.kt`
  now reference the generated object. `commit_changes.py` (Jules extraction
  scaffolding) was dropped before commit. 3 conflicts in
  `ForgeApp.kt`/`ForgePersistenceScript.kt`/`index.html` resolved by taking
  THEIRS on all three (consistent consolidated-asset form).

Skipped branches:
- `origin/gh-pages` — no merge base with master (Pages deployment root);
  every unique commit subject is superseded into master via other merges.
  Deployment target, not source-of-truth work.

`./gradlew compileKotlinJvm` → **BUILD SUCCESSFUL** after both merges
(pre-existing warnings only, zero new errors).

## 2026-07-19 doc curation

The N1–N7 findings from the prior refresh have been **applied to
`concepts.md`** (not just recorded here):

- N1 (Forge DTO removal) → §2 spine row updated; §8.1 prose describes
  BlackboardSurface as the seed source.
- N2 (`elastic/` shadow removed) — implicit in the canonical-types rule;
  not called out separately (decision: covered by the kernel-algebra note).
- N3/N4 (compiled-out slab + CircularQueue loud-hollow) → §0 orientation
  has a "Compiled-out layers" line.
- N5 (litebike/NUID spot-check) → no action; remains a verification record.
- N6 (LCNC package absent from spine) — deferred (decision, not prose):
  the package is still self-enclosed with zero external consumers.
- N7 (task-ledger pointer) → §0 orientation has a "Task ledger" line.

New doc edits for the 2026-07-19 merges:
- §2 spine: added a row noting the `resources/web/` consolidation and
  `generateForgeAssets` symbol-based reference.
- §0 orientation: added a "Static assets" line pointing at `resources/web/`
  as the single source of truth.
- §8.1c: new section documenting `ProcessReactorEndpoint`.
- §9 build tasks: added `generateForgeAssets` with a one-line contract.
- §10 reading paths: added a "Process reactor" row and added
  `resources/web/` + `generateForgeAssets` to the Gallery / Pages row.

## Re-scored prior findings

| ID | Prior claim | Live-tree status | Verdict |
|----|---|---|---|
| G1 | Oroboros is a substantial undocumented subsystem; components tested but uncomposed | `rg 'borg.trikeshed.util.oroboros' src -g '!src/commonMain/.../oroboros/**' -g '!*Test.kt'` → **1 hit**, and that hit is `src/commonTest/.../FakeFileOperations.kt`. Zero non-test external consumers. `OroborosNetwork.kt:53` still carries `// ... mocked for testing tests` with a `frame.toString() == "MOCK_PAYLOAD"` extractor. | **OPEN, unchanged.** Components exist, no production composition root, mock remains. Code gap, not a doc gap. |
| G2 | Couch CQRS docs claim Job/CID semantics the impl does not provide | `CouchStore.inMemory()` and `withPersistence()` now both build a **`ProductionCouchIngress`** (`CouchStore.kt:257,264`); `SyncTestIngress` (`CouchStore.kt:215`) still exists as a nested class but is no longer the default factory path. `CouchHeadProjection` still stores the raw revision string (`CouchHeadProjection.kt:24-56`) — no CID-derived `_id`/`_rev`. | **PARTIALLY CLOSED, unchanged.** `concepts.md` Couch prose updated to say "revision string stored raw; CID-derived revisions not yet implemented". |
| G3 | Checkpoint recovery incomplete — clears preceding snapshots, never hydrates from tree | `JobRepository.recover()` now has `verifyAndHydrateTree(cid)` (`JobRepository.kt:79-98`) which walks `BTreeNode.Internal`/`Leaf`, fetches each snapshot CID from CAS, decodes via `CanonicalCbor.decodeJobSnapshot`, and inserts into `recoveredSnapshots`. | **CLOSED, unchanged.** `concepts.md` recovery prose is accurate. |
| G4 | Stringpool documented but file-backed backing is simulated; WAL logger durability overstated | `FileBackedStringpool` (`Stringpool.kt:18-49`) now has a real `init` block: `fileOps.exists(location)` → `readAllBytes` → frame-walk with `isCorrupted` flag. `ReactorLogger.kt:60` now calls `durableAppendLog?.flush()`. | **PARTIALLY CLOSED, unchanged.** Recovery-on-open is real; append path and mmap/WAL block are still aspirational. |
| G5 | View-server runtime forks into two incompatible APIs (`addFunction` typed vs `addTool` raw-JSON) | `src/viewServerCommonMain`, `src/viewServerJsMain`, `src/viewServerJvmMain` **do not exist on disk**. `rg addTool src` → 0 hits. Only the common `CommonViewServer` + `CouchDbCascadeTool` path remains. `build.gradle.kts:27` still sets `viewServerNodeSlice = false` as a dead flag. | **CLOSED, unchanged.** The duplicate raw-JSON fork was deleted from the tree. |
| G6 | Build section commands not executable; serializer contract violated (commonMain has `kotlinx-serialization-json` directly) | `./gradlew compileKotlinJvm compileKotlinMacos compileKotlinJs compileKotlinWasmJs` → **BUILD SUCCESSFUL**. `kotlinx-serialization-json` still a direct `commonMain` dependency (`build.gradle.kts:146`). | **PARTIALLY CLOSED, unchanged.** All four targets compile; serializer contract violation is the actual enforced state. |
| N1 | HIGH: `ForgeAppState` DTO family deleted, `BlackboardSurface` projection is the seed source | `concepts.md` §2 spine row + §8.1 prose now describe BlackboardSurface as the seed source and call out the DTO removal. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |
| N2 | MED: `elastic/` shadow removed; canonical-types rule enforced by deletion | Decision: covered implicitly by the kernel-algebra note. Not called out separately — the rule is now structural (no shadow exists to mislead a reader). | **DECISION 2026-07-19.** No separate doc line; the absence is the enforcement. |
| N3 | MED: `classfile/slab/**` excluded from commonMain compile; spine had no mention | `concepts.md` §0 orientation now has a "Compiled-out layers" line listing the slab tree + `CircularQueue` loud-hollow. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |
| N4 | LOW: `CircularQueue` poll/peek/iterator converted to `error(...)` | Folded into the N3 note. | **APPLIED 2026-07-18, verified 2026-07-19.** Same line as N3. |
| N5 | HIGH: litebike/NUID doc spot-check | `concepts.md` §8.1a/§8.1b verified against `Taxonomy.kt` and `JvmKanbanServer.kt`; IDs 1–7 match litebike `taxonomy.rs`. | **VERIFIED 2026-07-18, re-verified 2026-07-19.** No action; remains a verification record. |
| N6 | MED: `LcncIngestPipeline` has zero production callers; LCNC package absent from spine | Decision: deferred. The package is still self-enclosed with zero external consumers. Adding a spine row would promote an aspirational surface. | **DEFERRED 2026-07-19.** Re-evaluate when the package gains an external consumer. |
| N7 | LOW: `concepts.md` does not reference `doc/todo.md` as the task ledger | `concepts.md` §0 orientation now has a "Task ledger" line. | **APPLIED 2026-07-18, verified 2026-07-19.** Doc updated. |

## Historical findings (applied or closed)

The detailed N1–N7 findings from the 2026-07-18 refresh are preserved in git
history at commit `603b0859`. They have all been applied to `concepts.md`,
deferred by decision (N2, N6), or verified (N5) — see the re-scored table above
for the current verdict on each.

### Coverage disposition (current)

| Concept-map area | Disposition |
|---|---|
| Oroboros | G1 OPEN (code gap) — doc correctly says "uncomposed"; do not rewrite until a composition root lands |
| Couch | G2 PARTIALLY CLOSED — prose says "revision string stored raw; CID-derived revisions not yet implemented" |
| B+tree/recovery | G3 CLOSED — recovery hydration is real; prose is accurate |
| Collections | G4 PARTIALLY CLOSED — stringpool recovery-on-open real; append/mmap aspirational |
| Observability | G4 PARTIALLY CLOSED — ReactorLogger flushes via `durableAppendLog?.flush()` |
| View server | G5 CLOSED — fork deleted from disk; `viewServerNodeSlice = false` is a dead flag |
| Build | G6 PARTIALLY CLOSED — all four targets compile; serializer boundary aspirational |
| Forge | N1 APPLIED — BlackboardSurface projection is the seed source; DTO family removed from prose |
| Litebike/NUID | N5 VERIFIED — §8.1a/§8.1b spot-checked against code |
| LCNC | N6 DEFERRED — self-enclosed package; re-evaluate when it gains an external consumer |
| Process reactor | **NEW 2026-07-19** — §8.1c documents `ProcessReactorEndpoint` (merged from `origin/jules-1801...`) |
| Static assets | **NEW 2026-07-19** — §0 + §2 + §9 document `resources/web/` consolidation + `generateForgeAssets` (merged from `origin/fix-forge-assets-...`) |

## Stale-evidence note

The 2026-07-18 version of this file compared against `638fb71b` and claimed the
build could not configure. That is no longer true; the 2026-07-19 refresh
supersedes those sections. Structure preserved (re-scored table first, applied
findings summary, current disposition) so the next refresh can diff row-by-row.
