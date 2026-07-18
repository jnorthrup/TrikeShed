# `doc/concepts.md` Gap Analysis — refresh 2026-07-18

Re-audit of the prior pass (which compared `cebea1da` → `638fb71b`) against
current master (`603b0859`). Each prior finding is re-scored against live disk;
new findings from the litebike/NUID/LCNC session are appended. No code changed
in this pass — documentation state only.

## Re-scored prior findings

| ID | Prior claim | Live-tree status | Verdict |
|----|---|---|---|
| G1 | Oroboros is a substantial undocumented subsystem; components tested but uncomposed | `rg 'borg.trikeshed.util.oroboros' src -g '!src/commonMain/.../oroboros/**' -g '!*Test.kt'` → **1 hit**, and that hit is `src/commonTest/.../FakeFileOperations.kt`. Zero non-test external consumers. `OroborosNetwork.kt:53` still carries `// ... mocked for testing tests` with a `frame.toString() == "MOCK_PAYLOAD"` extractor. | **OPEN, unchanged.** Components exist, no production composition root, mock remains. |
| G2 | Couch CQRS docs claim Job/CID semantics the impl does not provide | `CouchStore.inMemory()` and `withPersistence()` now both build a **`ProductionCouchIngress`** (`CouchStore.kt:257,264`); `SyncTestIngress` (`CouchStore.kt:215`) still exists as a nested class but is no longer the default factory path. `CouchHeadProjection` still stores the raw revision string (`CouchHeadProjection.kt:24-56`) — no CID-derived `_id`/`_rev`. | **PARTIALLY CLOSED.** Default ingress is production-shaped now; revision-string semantics still not CID-derived. `concepts.md` Couch prose is still ahead of the code on `_id = head CID, _rev = gen-CID-prefix`. |
| G3 | Checkpoint recovery incomplete — clears preceding snapshots, never hydrates from tree | `JobRepository.recover()` now has `verifyAndHydrateTree(cid)` (`JobRepository.kt:79-98`) which walks `BTreeNode.Internal`/`Leaf`, fetches each snapshot CID from CAS, decodes via `CanonicalCbor.decodeJobSnapshot`, and inserts into `recoveredSnapshots`. | **CLOSED.** Recovery hydrates checkpoint tree values before tail application. `concepts.md` recovery prose is now accurate. |
| G4 | Stringpool documented but file-backed backing is simulated; WAL logger durability overstated | `FileBackedStringpool` (`Stringpool.kt:18-49`) now has a real `init` block: `fileOps.exists(location)` → `readAllBytes` → frame-walk with `isCorrupted` flag. The header comment still says "In a full implementation, this uses functional uring or NIO byte channels to append to a memory-mapped file or WAL block." `ReactorLogger.kt:60` now calls `durableAppendLog?.flush()`. | **PARTIALLY CLOSED.** Recovery-on-open is real; append path and mmap/WAL block are still aspirational per the in-code comment. Logger now flushes. |
| G5 | View-server runtime forks into two incompatible APIs (`addFunction` typed vs `addTool` raw-JSON) | `src/viewServerCommonMain`, `src/viewServerJsMain`, `src/viewServerJvmMain` **do not exist on disk**. `rg addTool src` → 0 hits. Only the common `CommonViewServer` + `CouchDbCascadeTool` path remains. `build.gradle.kts:27` still sets `viewServerNodeSlice = false` as a dead flag. | **CLOSED.** The duplicate raw-JSON fork was deleted from the tree. Only the stale `viewServerNodeSlice` flag remains in the build script. |
| G6 | Build section commands not executable; serializer contract violated (commonMain has `kotlinx-serialization-json` directly) | `./gradlew compileKotlinJvm compileKotlinMacos compileKotlinJs compileKotlinWasmJs` → **BUILD SUCCESSFUL**. `kotlinx-serialization-json` still a direct `commonMain` dependency (`build.gradle.kts:146`). | **PARTIALLY CLOSED.** Build configures and all four targets compile. The serializer contract violation is still real but is now the actual enforced state — `concepts.md:24` ("commonMain allows only `kotlinx-serialization-core`") is a *desired* boundary, not the current one. |

## New findings from the litebike / NUID / LCNC session (post-`638fb71b`)

These are gaps between `concepts.md` (last edited before this session) and what
actually landed in commits `c3370100` … `603b0859`.

| ID | Sev | Area | Description | Evidence (file:line) | Suggested fix path |
|----|-----|------|-------------|----------------------|--------------------|
| N1 | HIGH | forge | `concepts.md` never mentions that `ForgeAppState` / `ForgeAppReactorState` / `ForgeSpatialState` / `LcncEntityDTO` / `CascadeRollupRow` / `ForgeAppUseCase` / `ForgeAppItem` / `ForgeAppChecklistItem` / `ForgeAppColumn` were **deleted** and replaced by `BlackboardSurface.project()` + `confixDoc()` parsing of persisted JSON (PR #160, commit `1e8fd692`). The architecture spine at `concepts.md:65-70` still describes "Blackboard-as-Confix-cursor" as an aspiration; the DTO-removal is a landed structural change. | `ForgeApp.kt` HEAD: `defaultForgeAppState(): Map<String, Any?>`; commit `1e8fd692` message | Update §2 spine row + §3.4 projections table to state the DTO is gone and the seed JSON is built from `BlackboardSurface` rows |
| N2 | MED | commonMain | `elastic/` directory was deleted this session — it redeclared `interface Join` / `typealias Series` (CRIT shadow, zero importers). `concepts.md` does not record that this ever existed or that the rule "canonical types live only in `borg.trikeshed.lib`" is now enforced by deletion, not just convention. | (deleted; was `elastic/ElasticHashingInTrikeShed.kt:36,44`) | One line in §1 kernel algebra: "canonical `Join`/`Series` live only in `borg.trikeshed.lib`; a prior `elastic/` shadow was removed Jul 2026" |
| N3 | MED | build | `classfile/slab/**` is **excluded from `commonMain` compile** in `build.gradle.kts` (entire layer of ~20 `TODO()` stubs: GraalJS eval, DuckDB c-interop, `FacetedCursorContract`, `MiniDuckContract`, `CircularQueue`). Files remain on disk. `concepts.md` architecture spine has no mention of the slab layer at all — neither its existence nor its exclusion. | `build.gradle.kts` slab exclude block; `FacetedCursorContract.kt:177,178,301,306,311`; `MiniDuckContract.kt:98-167` | Add a "compiled-out layers" note to §0 or §2 listing the excluded tree + rationale |
| N4 | LOW | collections | `CircularQueue.poll/peek/iterator.remove` converted from `TODO("Not yet implemented")` to `error("CirQlar.poll is write-only…")` — the hollow is now loud at the call site. Not worth a concepts.md entry on its own, but belongs in the same "compiled-out / loud-hollow" note as N3. | `CircularQueue.kt:50,51,69` | Same note as N3 |
| N5 | HIGH | litebike/nuid | `concepts.md` **does** cover the NUID/CCEK fanout (§8.1a) and Litebike listener (§8.1b) — but the additions were written alongside the code in the same session, so the spine at §2 and the prose at §8.1a/§8.1b have not been cross-checked for drift. Specifics to verify: the doc claims "IDs 1-7 match litebike taxonomy.rs conceptually" — verified true (`Taxonomy.kt` vs `taxonomy.rs:362-370`, Http=1…WebSocket=7). It also says `JvmKanbanServer` registers `Http/Json/Socks5/Tls/Bonjour/Upnp` — live code matches (`JvmKanbanServer.kt`). | `doc/concepts.md:344-363`; `Taxonomy.kt`; `JvmKanbanServer.kt:run` | No action — spot-check passed. Keep as a verification record. |
| N6 | MED | lcnc | `LcncIngestPipeline` (new file, `lcnc/reactor/LcncIngestPipeline.kt`) landed but has **zero production callers** — same S5 signature the LCNC package had before. It also contains a `RegexOption.DOT_MATCHES_ALL` JVM-only flag that was converted to inline `(?si)` this session (commit `af4b0894`). `concepts.md` LCNC section does not exist at all — the package (`lcnc/`) is absent from the architecture spine despite 17 files on disk. | `rg 'LcncIngestPipeline' src -g '!LcncIngestPipeline.kt'` → 0; `LcncIngestPipeline.kt:460` | Either add an LCNC row to the §2 spine marked "self-enclosed, no external consumers" or state the exclusion explicitly in §0 orientation |
| N7 | LOW | todo | `doc/todo.md` now carries T22–T29 (LCNC no-code gap follow-up) plus T-KANBAN-HTTP-1 … T-KANBAN-CROSS-11 (running-Kanban-live task list). `concepts.md` does not reference `doc/todo.md` as the task ledger. A maintainer reading only `concepts.md` would not know these queues exist. | `doc/todo.md` (513+ lines) | One pointer line in §0: "Task ledger: `doc/todo.md` (LCNC + Kanban-live queues)" |

## Coverage disposition (updated)

| Concept-map area | Disposition |
|---|---|
| Oroboros | Add a new section; mark components tested but uncomposed; note the `MOCK_PAYLOAD` extractor explicitly (unchanged from prior pass) |
| Couch | Prose now closer to truth (ProductionCouchIngress default) — rewrite the `_id`/`_rev` sentences to say "revision string stored raw; CID-derived revisions not yet implemented" |
| B+tree/recovery | Expand mechanics — recovery hydration is real now; prior "incomplete recovery" warning can be deleted |
| Collections | Keep Funnel coverage; stringpool recovery-on-open is real, append/mmap still aspirational — downgrade the durability sentence, don't delete it |
| Observability | ReactorLogger now flushes via `durableAppendLog?.flush()` — the "no flush" claim in the prior pass is stale |
| View server | Fork is gone from disk; only the stale `viewServerNodeSlice = false` flag in `build.gradle.kts:27` remains — update to say the duplicate was deleted, flag is dead |
| Build | All four targets compile; keep the serializer note as "desired boundary, currently violated by direct `commonMain` dep on `kotlinx-serialization-json`" |
| Forge | **New entry needed** — `ForgeAppState` DTO family deleted, `BlackboardSurface` projection is the seed source (N1) |
| Litebike/NUID | Doc sections exist and were spot-checked against code (N5) — no action |
| LCNC | Package absent from spine; either add a row marked self-enclosed or state the omission (N6) |

## Best debt-reduction cut (documentation-only)

One focused edit to `doc/concepts.md`, 1 file, ~15 lines:

- **§2 spine**: change the Forge row from "Blackboard-as-Confix-cursor: single JSON file → Cursor slices" to "BlackboardSurface projection: `confixDoc(persistedJson)` → `BlackboardSurface.project(...)` → seed rows; the `ForgeAppState` DTO family was removed (commit `1e8fd692`)".
- **§3.4 projections table**: replace the two Couch rows' revision semantics with "revision string stored raw".
- **§3.3 / recovery paragraph**: delete the "incomplete checkpoint recovery" caveat (G3 closed).
- **§0 orientation**: add one pointer line to `doc/todo.md` as the task ledger, and one "compiled-out layers" note for the slab exclusion + `CircularQueue` loud-hollow (N3/N4).
- **§6 build**: keep serializer note, drop the "commands do not configure" sentence (G6 build is green).

Verification: `rg -n 'ForgeAppState|SyncTestIngress|incomplete|addTool' doc/concepts.md` returns only the
corrected/removed references after the edit.

Non-goals: do not rewrite the Oroboros section (G1 is a code gap, not a doc
gap — the doc should keep saying "uncomposed"); do not add an LCNC spine row
until the package gains an external consumer or a decision to exclude is made
(N6 is a decision, not prose).

## Stale-evidence note

The prior version of this file compared against `638fb71b` and claimed the
build could not configure. That is no longer true; this refresh supersedes
those sections. Keep this file's structure (re-scored table first, new
findings second) so the next refresh can diff row-by-row.
