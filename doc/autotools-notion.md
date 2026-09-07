# The AutoTools Notion — one workspace, read two ways

The post that started Forge ([forge-genesis.md](forge-genesis.md)) ends in a framing, not a
feature list: many projects are "all this stuff I want to process over in an iterated way, with
some build artifacts worth saving" — GNU Autotools crossed with Notion. The genesis cuts answered
the five bullets one at a time. This document says what the framing itself is, so that the next
cuts build one thing rather than two.

It is a plan and a doc of record in the style of forge-genesis.md: every "today" claim names a
path or a test in the tree at HEAD 8570cf3fe (2026-09-06); every cut names its files, its tests
and the rendered observation that would falsify it; names that do not exist yet are marked
proposal. Nothing here dispatches by itself: the work is cards on the board under the parent
`autotools-notion`, human-review, never READY.

## The notion

Autotools is the discipline of a source tree. Inputs live in a tree. Rules say how outputs derive
from inputs. `configure` binds the rules to this machine's toolchain. `make` rebuilds only what
is stale, by dependency. `make check` verifies. `make dist` packs a bundle that builds the same
elsewhere. `make install` puts the outputs where other people read them.

Notion is the discipline of a page. Pages are made of blocks. Databases have typed properties and
views. A page embeds a view or a computed value. Several people edit at once. A template is a
page that starts other pages. A page is shared by link.

The cross is one object read both ways: **a page whose blocks are build targets.** A document in
a project can hold a block that names a program and its inputs. What the block shows is the
artifact of the latest completed run of that program over those inputs. When an input moves, the
run is stale and the page says so. A gesture on the page rebuilds exactly that run, and the new
artifact replaces the old with lineage. A person reads the page (Notion). The machine keeps the
derivation (Autotools). There is no second store, no second grammar, and no pin in the page's
bytes.

Nearly everything the cross needs already exists as a citizen (the two tables below). What is
missing is the block itself, and the few seams that let a page be edited on the surface, chained
to other targets, built as a whole, and carried out of the tool with its artifacts.

## Invariants

1. A page is bytes in a project database. Its identity is its cid, its order its sequence, its
   history the revision chain (`forge/server/ProjectDbWire.kt`, `lcnc/ProjectNodes.kt`).
2. A run block is a run request written in the page. It pins nothing. The artifact it shows is
   the head of the run history for its program version and inputs, the way `LandscapeActivity.latest` in
   `web/landscape.js` already decides the program lamp. A target's artifact is its last build.
3. Stale is a fact, never a timestamp: a run is stale when a cid it consumed moved
   (`lcnc/rules/RunStaleProduction.kt`, `lcnc/LcncStaleMarker.kt`). Where make compares mtimes,
   Forge compares cids.
4. Rebuild is a gesture on the surface that shows the block. Nothing rebuilds by itself unless
   the owner rules it so (`controls.rebuildOnStale` stays unparsed, the Cut S ruling).
5. Everything a block needs to render is content-addressed: program cid, prompt cids, consumed
   cids, receipt cid, and the toolchain it ran under. A bundle that carries them renders the page
   elsewhere.
6. UI logic is commonMain Kotlin reaching the browser through the bundle, tested on jvm and js;
   hand JS is DOM glue (the 2026-09-06 GWT ruling; `docs/DocumentSurface.kt` is the precedent).
7. Cables stay exactly typed. A run's output enters another program only through a port that
   names its exact kind; `json` never stands in for a type.
8. The database is a Confix document, its queries are cursor projections, its indexes are
   facets (`doc/rewire.md` §8). No SQL appears because a page got a table.

## The build half, mapped

| Autotools | Forge today | Gap |
|---|---|---|
| the source tree | a mounted project database: `POST /api/projects`, `ProjectDbWire.kt`; documents with cid, rev and seq; `project.list/docs/read/extract` (`lcnc/ProjectNodes.kt`) | no page is written from the surface; drop-to-mount not walked |
| `Makefile.am`, the rule template | a preset (`lcnc/LcncPresets.kt`) with blanks for model, prefill and project | — |
| `./configure` | the harness's live picklist records the daemon's pinned model and project into the opened draft; the roster probes `GET /api/agents`, `GET /api/mux/models` | no configure citizen: a receipt names program, prompt and input versions, not the toolchain it ran under; `Configure` at the repo root is 0 bytes |
| `Makefile`, the concrete rules | a published program `lcnc/program/<name>` with `programCid`; `POST /api/panels/{name}?baseCid=` refuses `409 stale_base`; `<forgeHome>/programs/ledger.jsonl` names each published head and the boot thaw re-files and republishes it (Cut 0) | — |
| `make target` | `POST /api/lcnc/run {program, inputs}`; the receipt carries `outputs` per node, `returns`, `consumed`, `inputFingerprint`, `promptVersions`, `programVersions` (`kanban/module/LcncRunService.kt`) | — |
| the `.d` files and mtimes | `lcnc/LcncConsumedLedger.kt`; `lcnc-consumed` facts in the project's partition; the `run-stale` production; `lcnc/stale/<runId>` | — |
| `make` again | `POST /api/lcnc/run/rebuild {runId}`; the Rebuild gesture on the harness | one run per gesture; no build of a whole project |
| `foo: foo.o`, targets that depend on targets | subprograms pinned per run (`LcncNode.subprogram`, `LcncRunner.subprogramLoader`, `programVersions`) | a run's output is not an input another program can consume by cid, so staleness never chains |
| `make check` | the plane judge over cards and their `MUST:` lines (`kanban/PlaneJudge.kt`) | nothing judges a run |
| `make dist` | Cut X, another session's card `genesis-cut-x`, in Running | pending there |
| `make install` | `/documents` renders a project's documents (`docs/DocumentSurface.kt`); the gh-pages bake bakes `ForgeApp`, not the daemon's pages | no page shows an artifact; no bake of a page |
| `config.status`, a tagged release | `lcnc/WorkspaceSnapshot.kt`, `lcnc/snapshot/head`, `/api/snapshots` | restore |

## The page half, mapped

| Notion | Forge today | Gap |
|---|---|---|
| workspace | the forge home with its mounted projects, one snapshot cid over all of it | — |
| page | a project document rendered by `docs/DocumentMarkdown.kt` under a header naming project, cid, rev and seq | no editor; no new page from the surface |
| block | Markdown paragraphs; `forge/ForgeDoc.kt` names 16 block kinds that no editor writes; `LcncBlock` in `lcnc/isam/LcncTaxonomy.kt` | no computed block |
| database | the project database; the board's sheets with typed columns (`kanban/BoardCursor.kt`, `ColumnMeta` over `IOMemento`); `LcncAssociative.PropertyType` (10 kinds since the 2026-07-21 de-stub) | `lcnc/editor/DatabaseView.kt` and `PropertyEditor.kt` have zero callers; no sheet over a project's documents on the surface |
| view | the `board-by-status` and `board-by-priority` sheets; the board; the gallery | none on the document surface |
| formula, rollup, relation | `lcnc/formula/LcncFormula.kt` (partial); `LcncReductions`; struck from the enum on 2026-07-21 | the run block is the honest replacement: a relation is a cable, a rollup is a ring's `each?` with a reduction, a formula is a lego, a computed column is a run per row |
| template | a preset | — |
| real-time editing | Couch `_changes` → facts → SSE; stale-base refusal for programs | the upload lane `POST /_project/{name}/put` takes no expected revision, so two editors on one page silently overwrite |
| history | `previousCid` on prompts, programs, snapshots and receipts; Couch revisions on documents | a page's history is not on the page |
| share by link | `GET /api/lcnc/content?cid=` (JSON only); the archive export (Cut X) | a page with its artifacts as one bundle; a static bake of a page |
| comments, presence | — | out of this plan |

## The cuts

Order is by what the cross needs to exist at all, then by what closes the loop on rendered pages,
then by what widens it. Each cut lands as the genesis cuts did: gate → targeted tests →
hotswapFeed → rendered check on `bin/oroboros-up --fresh --port 8899` → commit own files only.

### 0. The program ledger

Prerequisite. A run block names a program by name; if published programs die with the daemon
(the Cut C finding: the CAS-backed store rebuilds its index per boot and the `panels/<name>`
attachment goes with it), every block on every page breaks at the first restart. Same shape as
`lcnc/PromptStore.kt`: `<forgeHome>/programs/ledger.jsonl`, thawed at boot, the head
`lcnc/program/<name>` republished. The owner deferred this as "here or with the VCS gateway";
this plan recommends here, first, for the reason above.

- Files: jvmMain `lcnc/ProgramLedger.kt` (proposal); the publish path in `PatchWire.kt`; the
  daemon's thaw beside the prompt seeds.
- Tests: `PatchWirePublishTest` gains the case that a restart on the same home serves the same
  `programCid`; the ledger replays in order.
- Rendered: publish `corpus`, restart on the same home, the harness lists `corpus` at the same
  cid and a run block still resolves it.

### 1. Cut B — the run block

The synthesis. A Markdown page carries a fenced block whose info string is `lcnc-run` and whose
body is the request `POST /api/lcnc/run` already takes, plus which node's output to show:

````
```lcnc-run
{"program": "corpus", "inputs": {"project": "genesis-notes"}, "show": "display"}
```
````

The renderer recognises the fence and emits the block's frame. The page asks the daemon for the
head of the run history for that program version and those inputs and paints the shown output,
a lamp (Never built, Running, Completed, Stale naming the moved documents, Failed), the receipt
cid, and one button: Build or Rebuild. Build posts the block's body to `/api/lcnc/run`; Rebuild
posts the run id to `/api/lcnc/run/rebuild`. The page refreshes the block from the run and stale
facts the way the harness refreshes its lamps.

- Files (commonMain first): `docs/DocumentMarkdown.kt` (the fence); `docs/RunBlock.kt`
  (proposal: parse the body, decide the lamp from a receipt and a marker, render; pure);
  `docs/DocumentSurface.kt`; jsMain `docs/DocumentSurfacePage.kt` (fetch, clicks); jvmMain a
  read route for the run head by program and inputs (proposal `GET /api/lcnc/runs?program=&inputs=`,
  the `LandscapeActivity.latest` decision of `web/landscape.js` moved server-side) with a `RouteManifest` row.
- Tests: commonTest `RunBlockTest` on jvm and js (fence parse, lamp decision over receipt and
  marker fixtures, HTML); jvmTest the route over the module rig, with
  `CorpusStaleRebuildRouteTest` as the model.
- Rendered: a page `digest.md` in `genesis-notes` holding the block; Never built → Build → the
  two summaries render inside the page with the receipt cid; `a.md` edited through the upload
  lane; the block reads Stale naming `a.md` within seconds; Rebuild; the new digest with
  `rebuildOf` shown.
- Not in it: writing the page from the surface (Cut E); a page inside the glob it digests (see
  the rulings).

### 2. Cut E — the page editor with a revision base

Notion's editing, and the loop closed on rendered surfaces. `/documents` gains New page and Edit
for text documents. Save sends the bytes with the revision the editor loaded; the daemon refuses
`409 stale_rev` when the document moved, with nothing written, and the page offers the two exits
the program editor has (reload the board's version, overwrite). A save is a new document version,
so the tendon's document fact moves and every run block that consumed the page turns Stale on
its own, with no upload-lane call.

- Files: `docs/DocumentSurface.kt` and `docs/DocumentEditor.kt` (proposal: loaded revision,
  dirty, refused, reloaded; pure); jsMain glue (a textarea, not a rich editor); jvmMain
  `PUT /api/projects/{name}/docs/{id}?baseRev=` (proposal) beside the read routes in
  `PatchWire.kt`, writing through the same `ProjectScopes` the upload lane uses; a
  `RouteManifest` row.
- Tests: commonTest the editor states on jvm and js; jvmTest `ProjectDocsRoutesTest` gains the
  stale-revision refusal and a matching-revision save with the new cid and sequence.
- Rendered: two tabs on `a.md`; A saves; B saves and is refused with both revisions; B reloads;
  `digest.md`'s block reads Stale naming `a.md`; Rebuild.

### 3. Cut G — the configure citizen

`./configure` as a citizen. `lcnc/configure/head` (proposal) is a small canonical document naming
the pinned model (the Hermes session row), the brain roster ids, the agent roster (id, version,
enabled), the mounted projects (name, kind, sequence), the JS bundle's cid and the daemon's
build-plane id, content-addressed with `previousCid`, re-minted at boot and when a roster
changes. Receipts gain `configureCid`. A bundle that names it says what toolchain built the page.

- Files: commonMain `lcnc/ConfigureDocument.kt` (proposal); jvmMain a service in the
  prompt-store shape; `LcncRunService.kt` adds the field; a `BlackboardNamespaces` row;
  `GET /api/configure` (proposal).
- Tests: the canonical form re-mints the same cid from a parsed copy (`WorkspaceSnapshotTest`
  as the model); a receipt names the head at run time.
- Rendered: the inspector opens `lcnc/configure/head`; a run's receipt shows the same cid.
- The 0-byte `Configure` file at the repo root is the owner's. The plan proposes it becomes the
  checked-in description of what the citizen probes, or is deleted by his word. Not touched here.

### 4. Cut K — a run's output as another program's input

Targets that depend on targets. A lego `run.output` (proposal) takes a program name, inputs and a
node id and yields that node's output from the head receipt, typed by the node's declared port
kind, and records the receipt cid in the consumed ledger. A run that read another run's output
is stale when that receipt is superseded, so staleness chains: edit a note, the digest is stale,
the digest of digests is stale.

- Files: commonMain `lcnc/RunNodes.kt` (proposal); `LcncContracts.kt` rows with exact kinds;
  `LcncConsumedLedger.kt` (a receipt row kind); `RunStaleProduction.kt` (a consumed receipt whose
  key has a newer completed run fires); a preset "Digest of digests" for the walk.
- Tests: commonTest the lego over an in-memory run history, the chain in
  `RunStaleProductionTest`; jvmTest a two-program chain over the module rig.
- Rendered: two pages, `digest.md` and `overview.md`; one edit; both Stale in order; two
  Rebuilds.

### 5. Cut M — build the project

`make all`. One gesture on a project (the projects pane; the harness territory) and one route
(`POST /api/lcnc/build {project}`, proposal) rebuild every stale run whose inputs name the
project, in dependency order from the consumed ledger, each rebuild a receipt naming the stale
one, and one build receipt `lcnc/build/<project>/<id>` (proposal) naming them all. Bounded like
the board's Running column.

- Files: jvmMain `LcncBuildService.kt` (proposal) over `LcncRunService`; commonMain the order
  (a topological sort over consumed receipt cids, pure); the pages' Build project button.
- Tests: commonTest the order; jvmTest three chained runs rebuilt in order, one failure stopping
  the chain honestly.
- Rendered: one edit, one press, both pages Completed, the build receipt in the inspector.

### 6. Cut V — the project sheet, and columns that are runs

Notion's database. The documents pane becomes a sheet: rows are documents, columns are typed (id,
type, size, sequence, cid, and the miner's properties from the extract twin), sortable and
filterable, from commonMain in the `BoardCursor` shape; the orphaned `DatabaseView` promise made
good on the one surface that has data. Then a column that is a run: a program per row
(summarize, classify, extract entities), each cell a run block with its own lamp, so Notion's
formula and rollup come back as build targets rather than as enum cases. The per-document
windows the owner ruled for CoreNLP, RDF and SUMO (bytes, text, entities, triples, classes) are
cells of this sheet.

- Files: commonMain `docs/ProjectSheet.kt` (proposal) over the docs route; the run block reused
  per cell; no new route beyond Cut B's.
- Tests: commonTest sort, filter and typed columns on jvm and js; the per-row run key.
- Rendered: the sheet over `genesis-notes`; a summarize column built for two rows; one edit; that
  cell alone reads Stale.

### 7. After Cut X — the page bundle, and a page's bake

`make dist` for a page. Once `genesis-cut-x` lands, export a page rather than a run: the page's
bytes, the receipts its blocks show, the consumed cids, the program and prompt versions, the
configure cid; an import on a second daemon renders the same block from the bundle.
`make install`: a static bake of a project's pages with their blocks rendered from receipts at
bake time, cid-named, beside `forge/ForgeBakePages.kt`, so "shareable outside the tool" holds
for a page as a link and not only as a ZIP.

### 8. Restore

The snapshot's next cut in [moat-inventory.md](moat-inventory.md): pin program and prompt
versions from a snapshot through the draft machinery. Here it is `config.status` re-applied. It
stays last because nothing above needs it.

## What landed

| Cut | Files | Tests | Rendered check |
|---|---|---|---|
| 0 program ledger | `lcnc/ProgramLedger.kt` (jvmMain: `{name, cid, previousCid, atMs, actor}` per published version in `<forgeHome>/programs/ledger.jsonl`, `PromptStore`'s shape; `record` appends and is a no-op on a byte-identical re-publish; `thaw()` replays the last line per name, re-reads the bytes from CAS by cid, re-files the `panels/<name>` attachment with the recorded actor, instant and cid — skipping one already at that cid — and then calls `LcncPublisher.publishAll()` ONCE, so a composite is filed before any cable is typed against it; a preset's name is skipped, and NOTHING in the thaw throws: each head is restored under a total guard, a blob that is missing OR unreadable is skipped and logged (the CAS answers `null` only for an absent blob — a present one that no longer hashes to its own id makes `CasStore.get`/`FileCasStore.get` raise `digest mismatch`), an unreadable ledger file restores no head instead of dying, and the closing `publishAll()` is guarded too, so a corpus fault leaves the heads re-filed and `LcncPublisher.load` still resolving them; only cancellation propagates); `PatchWire.kt` (the ctor gains `programs`, defaulted null; the one publish path records the head between the attachment put and the board publish, with `previousCid` the same value the 200 body reports; `GET /api/panels/{name}?history=1` lists the recorded versions, 503 when the ledger is not wired); `RouteManifest.kt` (that row's description; no new path); `OroborosDaemon.kt` (construction beside the prompt store, `programs = programLedger` on the wire, and `programLedger.thaw()` sequential in the boot AFTER the last `lcncRunners.putAll(...)` and immediately before `moduleSupervisor.attach(KanbanModule())` — the republish is a `lateBound()`, so resolving it beside the prompt thaw would have answered the vocabulary with two thirds of the runner registry missing — and that call is `runCatching`-guarded like the module attach beside it: the thaw is boot code before `kanbanServer.run(...)` binds and `main` catches only `CancellationException`, so nothing here may cost the daemon its HTTP surface); `lcnc/LcncPublisher.kt` (jvmMain: `storedCorpus()`'s per-panel `getAttachment` and `storedPanel`'s are guarded like the `listAttachments` and `fromJson` around them — the read goes through the CAS, so one rotted `panels/*` blob used to throw the WHOLE corpus, presets included, out of every `publishAll()`; an unreadable panel now costs that panel); `lcnc/PromptStore.kt` and `forge/server/WorkspaceSnapshotService.kt` (jvmMain, the same unguarded CAS read on the same boot path, pre-existing from Cuts P and C: `thaw()`'s and `open()`'s `cas.get` now read a rotted blob as absent — `open`'s own `takeIf { ContentId.of(it) == id }` already said that was the intent); `lcnc/LcncFacts.kt` (commonMain: `learn(bindings)` REPLACES a type's binding row instead of asserting a second one — a binding is the one non-monotone family here, since the registry grows all through a boot, and the shared KIF bank retracts nothing on its own while `bindingOf` reads the first row in telling order) | `ProgramLedgerTest` (9, jvmTest: the chain replays last-per-name and the lineage continues across the boot; the restored `programCid` is the cid the round trip mints; a head whose blob is gone is skipped and the rest restore; a head whose blob is ROTTED (`cas.corrupt`, which makes `cas.get` throw rather than answer null) is skipped the same way and the boot keeps its other programs; a rotted `panels/*` panel the ledger never named does not cost the thaw its republish — the restored head still reaches the board and so do the presets that shared its corpus; a name that has since become a preset is not resurrected; a second thaw moves neither the board revision nor the attachment; an unwired ledger and a garbage line are both survivable; a thaw published while the registry is still empty leaves exactly one binding row per type, and the row is the later, true one); `LcncFactsOneBankTest` gains `aBindingIsReplacedNotStackedWhenTheRegistryGrows`; `PatchWirePublishTest` gains `aRestartOnTheSameHomeServesTheSameProgramCid` (404 and a null board cid BEFORE the thaw — the bug — then the same `programCid` in `/api/panels`, the v2 document text, and a further publish reporting v2 as its predecessor) and `aWireWithNoLedgerPublishesAsBeforeAndRefusesTheHistoryRead`; gates `RouteManifestParityTest`, `PatchWirePromptsTest`, `PatchWireTest`, `ProjectDocsRoutesTest`, `LcncPublisherTest`, `LcncPublisherFactsTest`, `PromptStoreTest`, `WorkspaceSnapshotTest`, `LcncFactsTest`, `LcncFactsOneBankTest`, `CorpusStaleRebuildRouteTest`, `LcncContractParityTest`, `LcncShakeDemoTest`, `BlackboardChangesFactElementTest` green. NOT covered by a rig: the daemon's own `runCatching` at the thaw call site and the unreadable-ledger-file branch — there is no test that runs `mainImpl`, and no portable way to make `File.readLines()` fail; both are guards, and the behaviour they protect is the two-boot rendered check. `ForgeHostSpecParityTest > specMatchesTheRouteTableBothWays` is RED at HEAD and untouched by this cut — `GET /blackboard/sheet` is in `BlackboardWire.ROUTES` (BlackboardWire.kt:16) with no row in `forge-host.openapi.yaml`, both files clean at HEAD, and no input to that test is a file this cut changes. No lego, no route path, no blackboard key family, no preset, no JS; the one commonMain file is `LcncFacts.kt` above | 2026-09-06 on a private scratch daemon (port 8897, fresh home `oroboros-cut0.uQhkws`, the staged 956,065-byte `TrikeShed.js`), driven in headless Chromium. Boot 1 came up on the empty home logging `programs: 0 head(s) restored from the ledger`, with the notes folder mounted as `genesis-notes` (3 paths, 3 docs). `/harness?load=preset-corpus` connected Live, `#panelName` pre-filled `corpus`, the live picklists recorded the daemon's model into the draft (`prompt.chat` model `glm-5.3-flash` of 34 options, prefill `(none - env/harness keys)`) and the status turned `Unpublished changes in preset-corpus`; the harness's own `↑ Publish` answered `Published corpus`, the program select grew to 23 options, `GET /api/panels` read `corpus` at `sha256:d5324cb10d66…` (4507 bytes), and `<forgeHome>/programs/ledger.jsonl` held exactly one line, `{name corpus, cid sha256:d5324cb10d66…, previousCid null, atMs 1788741810160, actor panels-editor}`. The cid was read off the page, not off curl: the `corpus` territory header (`corpus | lcnc/program/corpus | ◇ | ▶ Run`) and its version diamond, whose title and whose inspector `#factKey` both read `sha256:d5324cb10d6609d77cc37b48946660d5755bc04cfa2cbc2f5ce511e54d94af0c` under the actor `Immutable program version`. The daemon was then killed (pid gone, port free, `/api/health` HTTP 000) and relaunched on the SAME home: `programs: 1 head(s) restored from the ledger`, no skip line. The reopened harness listed `corpus` among its 23 options; selecting it read `corpus on the blackboard` — not a draft — with `▶ Run` enabled, and the same diamond gesture gave back the same cid character for character. `GET /api/panels` agreed (same cid, same 4507 bytes), `?entry=1` gave the same `programCid` and `sourceCid` with 13 cables and no violations, `/blackboard/board` carried `lcnc/program/corpus` at that cid, `/api/panels/presets` still listed 22, and `⊞ Fit the blackboard` drew 23 program territories with `preset-bughunter`, `preset-ccek`, `preset-council`, `preset-kanban`, `preset-legal-tribunal`, `preset-shake` and `preset-turbohaul` painted by name. Zoomed into the restored `corpus`, its `prompt.chat` node painted MODEL `glm-5.3-flash`, MAXTOKENS 1024, TEMPERATURE 0.2 and the version bytes under the cid (4496 chars) carried `"model": "glm-5.3-flash"` — the model the live picklists recorded before the publish, so what came back is the published document and not just a name. Not run: `corpus` itself. No `/api/lcnc/run` was posted for it, so no receipt, lamp word or digest is claimed; the harness Activity reads `corpus: Unknown`, which is the expected lamp for a program with no version-matched receipt |

Cut 0 makes the program name → version binding durable and nothing else. Pages, run heads and
`lcnc/configure/head` belong to Cuts B, E and G; the walk's step 7 is not claimed here. No
`pages` document kind lands in this run either, so a page sitting inside the glob its own
program digests remains the author's problem — that ruling row above stays a recommendation.

## Verification snapshot

### 2026-09-06, Cut 0

- The Cut C finding was that a published program does not survive this daemon's restart: the
  CAS-backed store rebuilds its document index per boot and the `panels/<name>` attachment goes with
  it. Cut 0 closes it in the shape `lcnc/PromptStore.kt` already uses — a ledger under the forge home,
  thawed at boot — so the `Makefile` row of the build table has no gap left, and a run block (Cut B)
  can name a program and still find it tomorrow.
- Compile gates: `./gradlew jvmMainClasses` green; `./gradlew compileKotlinJvm compileJvmMainJava
  --rerun` green (the compiled `ProgramLedger` carries `restoreHead`, so the classes on disk are these
  sources); `./gradlew compileKotlinJs` green with no line added to `gradle/js-target-debt.excludes`.
  `utils/lcnc-depth` `scan_repo --fail-on-ccek-gap` reports 0 gaps: the cut adds no public capability
  to the CCEK plane, no lego, no route path, no blackboard key family and no preset.
- Test gates: each changed suite alone first — `ProgramLedgerTest` 9/9, `LcncFactsOneBankTest` 5/5,
  `PatchWirePublishTest` 4/4 — then one batch, 29 suites and 152 tests with 0 failures and no
  contamination: `ProgramLedgerTest`, `PromptStoreTest`, `LcncPublisherTest`, `LcncPublisherFactsTest`,
  `LcncFactsOneBankTest`, `LcncFactsTest`, `LcncFactsOrderingTest`, `WorkspaceSnapshotTest`,
  `PatchWirePublishTest`, `PatchWirePromptsTest`, `PatchWireTest`, `MediaPatchWireTest`,
  `ProjectDocsRoutesTest`, `RouteManifestParityTest`, `LcncContractParityTest`, `LcncShakeDemoTest`,
  `BlackboardChangesFactElementTest`, `CorpusStaleRebuildRouteTest`, `LcncRunProgramRouteTest`,
  `LcncPresetCatalogTest`, `LcncPresetsGateTest`, `PresetAssemblyTest`, `PresetRequiredInputsTest`,
  `KanbanModuleHttpTest`, `LcncRdfWireTest`, `ReteWireTest`, `KifTeeTest`,
  `KifKnowledgeBaseRetractTest`, `KifKnowledgeBaseReplaceTest`. `BoardProductionsTest`,
  `KernelParityTest`, `RunStaleProductionTest` and `McpSurfaceParityTest` green in a second batch. On
  the JS target, `./gradlew jsNodeTest` over the commonTest gates: 10 suites, 47 tests, 0 failures
  (`BlackboardChangesFactElementTest`, `KifKnowledgeBaseReplaceTest`, `KifKnowledgeBaseRetractTest`,
  `LcncContractParityTest`, `LcncFactsOrderingTest`, `LcncPresetCatalogTest`, `LcncPresetsGateTest`,
  `LcncShakeDemoTest`, `PresetAssemblyTest`, `WorkspaceSnapshotTest`).
- `ForgeHostSpecParityTest > specMatchesTheRouteTableBothWays` is red and is not this cut's:
  `(GET, /blackboard/sheet)` is in `BlackboardWire.ROUTES` (`BlackboardWire.kt:16`) with no row in
  `src/commonMain/resources/openapi/forge-host.openapi.yaml`; all four of the test's inputs print
  nothing under `git status --porcelain`, and `git log -S'/blackboard/sheet'` dates the route to
  `dd34eae1f` (2026-09-05, the day before). The suite's other case passes.
- The premise the fix rests on, since it is easy to get backwards: the CAS does not answer `null` for a
  corrupt blob. `CasStore.get` and the daemon's `FileCasStore.get` re-hash what they read and throw
  `digest mismatch`; only an absent blob is `null`. Every skip-and-log branch on the boot path had been
  written for the `null` case alone.
- Falsifiability, run three ways before the fix was accepted. With `thaw()`'s CAS read and
  `LcncPublisher.storedCorpus`'s per-panel read put back in their unguarded form, the two new cases fail
  with the predicted traces (`CasStore.get(CasStore.kt:27)` → `ProgramLedger.thaw`, and
  `getAttachment` → `storedCorpus` → `lateBound` → `publishAll` → `thaw`). With only the thaw guarded,
  the panel case still fails, on the board assertion rather than an exception, so the test cannot be
  satisfied by swallowing the throw. And on the real `FileCasStore`, a boot over a home whose head blob
  had one byte flipped, run on pre-fix classes, died before binding the port: `Exception in thread
  "main" java.lang.IllegalStateException: digest mismatch … at FileCasStore.get(Sha2CasBus.kt:55) at
  ProgramLedger.thaw`, `/api/health` unreachable. All three needed the sources edited, so neither the
  gate re-run nor the rendered check reproduced them; they are the implementer's observation.
- Rendered: the two-boot check in the table above, on port 8897 from one fresh home, with the daemon
  killed between the two boots. Screenshots under the session scratchpad's `rendered-cut0/`:
  `01-harness-preset-corpus-loaded.png`, `02-picklists-recorded.png`, `03-published-corpus.png`,
  `10-before-restart-harness-on-load.png`, `11-before-restart-program-select-corpus.png`,
  `12-before-restart-program-version-inspector.png`, `10-after-restart-harness-on-load.png`,
  `11-after-restart-program-select-corpus.png`, `12-after-restart-program-version-inspector.png`,
  `13-after-restart-corpus-fitted.png`, `14-after-restart-version-bytes-raw.png`,
  `15-after-restart-corpus-zoomed.png`, `16-after-restart-prompt-chat-node.png`,
  `17-after-restart-model-on-the-restored-corpus.png`, `20-harness-idle-no-run.png`,
  `21-panels-idle-after-load.png`, `23-after-restart-board-fit-corpus-among-presets.png`,
  `24-after-restart-toolbar-select-corpus.png`.
- Findings recorded rather than fixed, all of them the owner's call:
  - **A page runs something nobody asked for, on `/panels`.** Opening `/panels?load=corpus` and pressing
    nothing posts `POST /api/lcnc/run` within about 25 seconds: an inline ring run labelled
    `corpus:n-ring` (`programKey` null, empty inputs) that fails with `project.read: no document wired
    and no project named` and lands a failed receipt at `lcnc/run/<id>`. Reproduced twice on the scratch
    daemon, against zero new runs from an idle `/harness` holding the same program for 20 seconds. The
    mechanism is `runAll()` on load in `web/patch.js` (:2536 and :2620) and the scope runner posting the
    ring as an inline document (:239-244). It is pre-existing canvas behaviour on a surface outside this
    cut and it touched no Cut 0 observable, but it contradicts invariant 4: a reader who merely opens a
    program in a panel window gets a failed receipt with their program's name on the board.
  - **The publish path is three writes with no shared critical section.** `att.putAttachment`,
    `programs?.record` and `publisher.publishProgram` in `PatchWire.kt` are separate, and
    `ProgramLedger`'s mutex covers only the append. Two concurrent publishes of one name can leave the
    ledger's last line at v1 while the board and the 200 body say v2 — and now that a restart replays the
    ledger, the restart serves the loser. `PromptStore.save` does not have this hole: it holds its mutex
    across validation, head read, install and append. The fix is to serialise the whole publish body per
    name.
  - **`ProgramLedger.record` and `thaw` do not validate the name** that `POST /api/panels/{name}`
    validates (`^[a-z0-9][a-z0-9._-]*$`). A hand-edited or copied ledger line carrying `/` re-files at a
    couch id outside the one-segment `panels/<name>` shape and reaches the board as
    `lcnc/program/sub/name`, a key family with no `BlackboardNamespaces` row. One regex, hoisted so the
    route and the ledger spell it once.
  - **`LcncFacts.learn` replaces across two lock acquisitions.** `kb.asserts()` takes and releases the
    bank's gate, then `kb.replace(gone, told)` takes it again, so two `lateBound()` passes over the one
    shared bank can each compute `gone` from the same snapshot and both `tell`, leaving the doubled row
    the change exists to prevent. The window is the boot: `POST /api/panels/{name}` is served from
    `kanbanServer.run` while `KanbanModule.attach`'s own `publishAll()` is still in flight. Not a
    regression — before this cut the stacking was unconditional — but the invariant is not actually held
    until `KifKnowledgeBase` grows one locked filter-forget-tell primitive.
  - **The one commonMain behaviour change is pinned only on the JVM.** `LcncFacts.learn` ships to js and
    to the native targets, but both regressions for it (`LcncFactsOneBankTest`'s
    `aBindingIsReplacedNotStackedWhenTheRegistryGrows` and `ProgramLedgerTest`'s thaw-before-the-runners
    case) live in jvmTest. The first has no jvm-only dependency and belongs in commonTest beside
    `LcncFactsOrderingTest`.
  - Smaller: `GET /api/panels/{name}?history=1` sits below the `attachments` 503 guard, so a wire with a
    ledger and no attachment store answers `store not wired` instead of the history it could serve;
    `ProgramLedger.ACTOR` is documented as the publish route's actor but the route passes the literal, so
    the constant's only caller is a test; `ProgramLedger.head(name)` has no caller at all; and
    `aSecondThawMovesNeitherTheBoardNorTheAttachment` asserts sequence equality without pinning the
    value, so it would pass if `atMs` round-tripped as 0.
- Not claimed:
  - The `corpus` program was never run on the scratch daemon, so no receipt, no lamp word, no digest and
    no consumed facts were seen. Cut 0's claim — the name → version binding survives a restart — is what
    was checked; the second half of the plan's Rendered line for this cut, a run block resolving the
    program, belongs to Cut B and is not claimed.
  - One kill-and-relaunch cycle, on an intact home, with one published version: `previousCid` is null and
    the ledger has a single line, so the chained-version path was exercised only in `ProgramLedgerTest`,
    not rendered. The rotted-blob boots were the implementer's, on a different home, and were not
    repeated by the rendered check.
  - Two branches of the fix have no rig: the daemon's `runCatching` around the thaw call site (nothing
    runs `mainImpl` in a test) and `readLedger()`'s unreadable-file guard (no portable way to make
    `File.readLines()` fail as root). Both are covered only by the live boots.
  - `storedCorpus()` skips an unreadable panel silently, matching the two guards beside it: it runs on
    every `publishAll`, so a log line would repeat per publish. A rotted panel is therefore diagnosable
    from the thaw's own line or from a 404, not from the corpus read.
  - Other boot-path calls stay unguarded at their call sites: `snapshotService.restore()` and
    `promptStore.thaw(...)` are bare in the daemon. The CAS reads inside both were fixed here; a
    different throw inside either still costs the boot. Those are Cut P and Cut C surfaces.
  - `/documents` was not opened during the rendered check, and the staged bundle was not exercised by it:
    `/harness` is hand JS (`harness.html`, `patch.js`, `harness.js`). The presets drawer could not be
    opened either — `#presetsBtn` computes to `display:none` on that surface at every width tried — so
    "the presets are back" rests on the 22 preset options read off the program select and the named
    territories on the fitted board.
  - No native target was linked or tested by anyone this session, so `LcncFacts.kt` ships to macos, linux
    and mingw unverified here. `jsNodeTest`'s pre-existing whole-suite failures (the `runBlocking has no
    JS actual` family, plus `tessera.core.RlncTest`) were not swept in and are unchanged.
  - Two exposures were left open on purpose and are unchanged: `appendText` on a ledger under a home two
    daemons share interleaves lines, so two boots of a check must not run concurrently on one home; and a
    program edited straight onto the board through `/blackboard/assert` never touches the ledger, so after
    a restart the last published version wins over that board edit.
  - Practical notes for the next rendered check: `/harness` holds an SSE stream open, so Playwright's
    `wait_until='networkidle'` never settles — use `domcontentloaded` and then wait for `#connection.live`.

## The walk

Every step on a rendered page, on a daemon from a fresh scratch home
(`bin/oroboros-up --fresh --port 8899`), with the notes folder mounted as `genesis-notes`:

1. **A page with a target.** Open `/documents`; New page `digest.md` in `genesis-notes` holding
   one `lcnc-run` block over `corpus`. Expect: the block reads Never built, with Build.
2. **Build.** Press Build. Expect: the summaries render inside the page, the lamp Completed, the
   receipt cid under it, `consumed` naming `a.md`, `b.md`, the listing and the prompt.
3. **Stale by editing a page.** Edit `a.md` on the surface and Save. Expect within seconds:
   `digest.md`'s block reads Stale naming `a.md`; Rebuild; the new digest with `rebuildOf` the old
   receipt.
4. **Two editors.** A second tab on `a.md`; both save. Expect: the second is refused with both
   revisions; reload takes the first's version.
5. **A chain, built whole.** `overview.md` with a block over "Digest of digests" reading
   `corpus`'s output; edit `b.md`. Expect: both blocks Stale in order; Build project; both
   Completed and one build receipt naming both rebuilds.
6. **Carried out.** Export `digest.md`; import on a second fresh daemon. Expect: the page renders
   the same block from the bundle's receipt with the same cids.
7. **Survives a restart.** Restart on the same home. Expect: programs, prompts, pages and run
   heads all served; every block renders as before; `lcnc/configure/head` re-minted with
   `previousCid` the old one.

The walk counts only when a second run from another fresh home reaches the same end state, and
only after every observable above was seen on the page, not inferred from a status code.

## Rulings this plan needs

| Question | Recommendation |
|---|---|
| The program ledger now, or with the VCS gateway (deferred in Cut C) | **taken, now** — landed as Cut 0 below; run blocks bind pages to programs by name |
| The block's form | a fenced `lcnc-run` block whose body is the run request: no new grammar, survives every editor, diffs as text |
| A page holding a block inside the glob its program reads | pages of kind `pages` are excluded from `project.docs` unless the glob names them; the alternative is a self-consuming target — **still a recommendation**: no `pages` kind lands in this run, so a page inside its own program's glob stays the author's problem |
| Does a re-pinned model make runs stale? | no: record `configureCid` on receipts and never fire on it; a toolchain change is a rebuild the owner chooses, and model calls cost |
| `Configure` at the repo root | the checked-in description of what the citizen probes, or deleted by the owner's word |
| Rebuild by itself | stays a gesture (the Cut S ruling); Cut M's build is a gesture over a project |

## What this is not

Not a Notion clone with a database backend (`doc/rewire.md` §8). Not a rich block editor: Cut E
is a textarea over Markdown, blocks are Markdown, and the run block is a fence. Not comments or
presence. Not a scheduler: nothing builds unbidden. Not more hand JS. Not a new type system:
cables stay exact.
