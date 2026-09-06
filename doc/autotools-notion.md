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
| `Makefile`, the concrete rules | a published program `lcnc/program/<name>` with `programCid`; `POST /api/panels/{name}?baseCid=` refuses `409 stale_base` | published programs do not survive a restart (the Cut C finding; prompts and snapshots do, by ledger) |
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
| The program ledger now, or with the VCS gateway (deferred in Cut C) | now: run blocks bind pages to programs by name |
| The block's form | a fenced `lcnc-run` block whose body is the run request: no new grammar, survives every editor, diffs as text |
| A page holding a block inside the glob its program reads | pages of kind `pages` are excluded from `project.docs` unless the glob names them; the alternative is a self-consuming target |
| Does a re-pinned model make runs stale? | no: record `configureCid` on receipts and never fire on it; a toolchain change is a rebuild the owner chooses, and model calls cost |
| `Configure` at the repo root | the checked-in description of what the citizen probes, or deleted by the owner's word |
| Rebuild by itself | stays a gesture (the Cut S ruling); Cut M's build is a gesture over a project |

## What this is not

Not a Notion clone with a database backend (`doc/rewire.md` §8). Not a rich block editor: Cut E
is a textarea over Markdown, blocks are Markdown, and the run block is a fence. Not comments or
presence. Not a scheduler: nothing builds unbidden. Not more hand JS. Not a new type system:
cables stay exact.
