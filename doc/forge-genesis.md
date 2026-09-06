# Forge genesis — the post, the scorecard, the walk

Forge began as TrikeShed's answer to a post by Patrick Collison on 2026-06-06
(x.com/patrickc/status/2063337800209179029). Paraphrased, it asked for an LLM workflow tool with:

1. a managed set of input files, Markdown or similar, plus other general-purpose context;
2. real-time collaboration, and maybe snapshots or version-control integration;
3. the ability to create and manage inference workflows and a stored set of prompts;
4. access to general-purpose coding agents, not only chat models;
5. some notion of compiled outputs and inference results, ideally shareable outside the tool.

Its framing: many projects feel like "all this stuff I want to process over in an iterated way, with
some build artifacts worth saving" — GNU Autotools crossed with Notion.

Git agrees on the genesis. The first Kanban and reactor commits land 2026-06-20/21 (Stage 1-3,
`KanbanFSM`, the KanbanBoard Compose UI) and the `forge/` tree appears 2026-07-04/05. This document
scores the daemon against the post, names what was missing, and fixes the walk that proves the
answer. It is a doc of record in the style of `doc/landscape-consolidation.md`: every claim names a
path or a test, and the verification snapshot says what passed and what is not claimed.

## Scorecard, 2026-09-06, HEAD 0f99c47b1

| Bullet | What exists | Gap on that date | Cut |
|---|---|---|---|
| 1 input files + context | worktree, git and build-plane absorption into CAS and Couch (`daemon/OroborosDaemon.kt` watchers); dropped folders become project databases (`forge/server/ProjectDbWire.kt`, `/api/projects`); Tika mining (`ProjectMiner.kt`); ZIP import into TreeDoc (`kanban/module/ArchiveService.kt`, `/api/archives/*`); raw drop (`GraalWire.kt` ingest route) | no lego let a program iterate a managed file set; markdown ingest gated to the plan grammar (`ForgeKanbanIngest.requirePlanShape`); `utils/ingest` outside the build | F |
| 2 collaboration + snapshots/VCS | the blackboard SSE feed (`BlackboardWire.kt`, `GET /blackboard/facts`) with bounded replay; the WAL board with compare-and-set moves; Couch replication between nodes; mux session forks; git absorbed as data | program publish (`PatchWire.kt`, `POST /api/panels/{name}`) was last-writer-wins; btrfs `snapshot()` reached only from the VM world path; the pijul gateway is test-plus-CLI | C |
| 3 workflows + stored prompts | 137 typed LCNC node types, presets, the harness and panels canvases, `POST /api/lcnc/run` with immutable program versions and receipts, the claim loop, fan-out, the plane judge | no prompt entity: every prompt was a string literal inside `lcnc/LcncPresets.kt` | P |
| 4 coding agents | Forge is an MCP target for agents (`POST /api/mcp`, `mcp/LcncKanbanMcp.kt`); KeyMux borrows the key files of codex, opencode and Hermes | the daemon never launched an agent; a card's executor was one `prompt.chat` completion with no files, tools or diff (`kanban/BoardClaimWorker.kt`) | A |
| 5 shareable outputs | CID-verified run output (`GET /api/lcnc/content?cid=`), durable receipts with `programCid`, `receiptCid`, `previousReceiptCid`, `programVersions`; archive download with CID verification; the gh-pages bake | nothing bundled a run; "external" was a hand-pushed bake of `ForgeApp`, a different app from the daemon's surfaces | X |
| 6 iterated compute | absorption → Couch `_changes` → Rete facts is real; `CausalGraphNode.inputFingerprint` is recorded | nothing compared fingerprints, so nothing was ever stale and nothing rebuilt; `Configure` is a 0-byte file | S |

The concept inventory behind this table is [moat-inventory.md](moat-inventory.md).

## The walk

Every step is driven through a rendered surface in a browser, never a script, on a daemon booted
from a fresh scratch home on a scratch port:

```sh
bin/oroboros-up --fresh --port 8899
```

1. **Files and context.** Open `http://127.0.0.1:8899/documents`: the document surface lists the
   mounted projects and renders their notes. Open `http://127.0.0.1:8899/harness`. In Archives, import a small ZIP of
   Markdown notes (make one with `node bin/archive-demo.cjs create /tmp/notes.zip`). Drop the same
   notes folder onto the landscape so it mounts as a project. Expect: the project appears in the
   terrain and `project.docs` lists its files when asked (step 2 does the asking).
2. **Prompts and a workflow.** Open the preset "Save a prompt", set the name to `summarize`, type a
   new question, press Run. Expect: `lcnc/prompt/summarize` appears in the activity pane with a new
   cid whose `previousCid` is the seed. Open the preset "Corpus digest". The harness records the
   daemon's pinned model into the opened draft (its live-picklist rule), so publish it as `corpus`,
   set the project in the invocation editor, press Run. Expect: a completed receipt whose
   `promptVersions.summarize` is the new cid and whose `consumed` list names every document read;
   the program lamp reads Completed.
3. **A coding agent.** Submit a card whose spec says `AGENT: codex` and carries a MUST naming a
   file change (the board page has no submit form yet; the reactor ingress `POST /api/invoke` or
   the MCP tool is the way in); move it to Ready. Expect: Running with owner `claim:agent:codex`,
   then a claim row with `agent codex`, a patch and a transcript, then Review or Done signed by
   `judge:plane`. Open the patch; download it; `git apply --check` passes in a clean checkout.
4. **A snapshot, and two editors.** Open the same program in a second tab and edit in both (an
   unedited tab simply takes the board's new version when it lands); publish from both. Expect: the
   second publish is refused as stale, the draft is kept, and the inspector offers reload and
   overwrite. Press the toolbar's snapshot action. Expect: `lcnc/snapshot/head` opens with the
   counts, and its bytes name the program cid, the prompt cids, the project's sequence and the
   latest receipt cid.
5. **A shareable output.** From the receipt inspector press Export run; download the ZIP from the
   Archives dialog. On a second fresh daemon (`--port 8898`) import that ZIP. Expect: the receipt
   entry opens CID-verified and its cids match the first daemon's.
6. **Iterated compute.** Change one Markdown file in the mounted project (through the project's
   upload lane, `POST /_project/<name>/put?path=`, or the folder watcher where one exists). Expect
   within a few seconds: the program lamp turns Stale naming the changed document and the legend
   counts it; open the evidence and press Rebuild; a new receipt appears whose `rebuildOf` is the
   stale receipt, its consumed row for that document carries the new cid, and the lamp returns to
   Completed.

The walk counts only when a second run from another fresh home reaches the same end state, and only
after every observable above was seen on the page, not inferred from a status code.

## What landed

| Cut | Files | Tests | Rendered check |
|---|---|---|---|
| P prompts | `lcnc/PromptDocument.kt`, `lcnc/PromptNodes.kt`, `lcnc/LcncPromptSeeds.kt` (commonMain); `lcnc/PromptStore.kt` (jvmMain); contracts `prompt.get`, `prompt.render`, `prompt.list`, `prompt.save` and `mux.chat.system?` in `LcncContracts.kt`; `prompt.chat` honours a cabled `prompt?`; `lcnc/prompt/<name>` keys in `LcncBlackboard`, `LcncPublisher.publishPrompt`, a `BlackboardNamespaces` row; `preset-brain-mux` reads `hello` through `prompt.get`; new preset `preset-prompt` "Save a prompt"; `GET/POST /api/prompts[/{name}]` in `PatchWire.kt` with `RouteManifest` rows; `promptVersions` on run receipts in `LcncRunService.kt`; daemon wiring and the seed thaw in `OroborosDaemon.kt` | `PromptTemplateTest` (6), `PromptNodesTest` (5), `PromptStoreTest` (5), `PatchWirePromptsTest` (3), `PromptProvenanceReceiptTest` (1); gates `LcncContractParityTest`, `LcncPresetCatalogTest`, `LcncPresetsGateTest`, `PresetAssemblyTest`, `PresetRequiredInputsTest`, `RouteManifestParityTest`, `LcncShakeDemoTest`, `ArchiveServiceTest`, `KanbanModuleHttpTest`, `McpSurfaceParityTest` all green; `compileKotlinJs` 0 errors | 2026-09-06 on `bin/oroboros-up --fresh --port 8899`: "Save a prompt" opened from `?load=prompt`, the text changed, Publish (as `prompt`), Run; the activity pane gained `lcnc/prompt/hello` and the inspector showed its new cid with `previousCid` = the seed cid, text and actor `prompt.save`; `/api/prompts`, `/api/prompts/hello?history=1`, `/api/lcnc/content?cid=` and the ledger on disk agreed; a restart on the same home logged "1 head(s) restored from the ledger" and served the same head |
| D document surface | `docs/DocumentMarkdown.kt` and `docs/DocumentSurface.kt` (commonMain: the Markdown renderer and the three-pane page model, pure JSON-in/HTML-out); `jsMain/.../docs/DocumentSurfacePage.kt` (fetch, mount, clicks: the only browser-shaped code) reached through the bundle's one entry point (`ForgeNodeMain` dispatches on `#document-surface`); `web/documents.html` shell; `GET /documents` and `GET /kotlin/TrikeShed.js` in the static table; `GET /api/projects/{name}/docs` and `/docs/{id}` in `PatchWire.kt` over the same `ProjectCorpus` the legos read (one `JvmProjectCorpus` in the daemon serves both); `RouteManifest` rows; Gradle `stageKotlinJs` (webpack, then the bundle staged under the JVM resources); a JS actual for the commonTest `runBlocking` helper, its line deleted from `gradle/js-target-debt.excludes`, and eleven commonTest imports pointed at it, so the JS test target compiles at all | `DocumentMarkdownTest` (5), `DocumentSurfaceTest` (3) in commonTest, run on the JVM and on the JS and macOS native targets; `ProjectDocsRoutesTest` (jvmTest); `RouteManifestParityTest`, `PatchWirePromptsTest`, `ProjectCorpusJvmTest`, `KanbanModuleHttpTest`, `ForgeHostSpecParityTest` green; `jvmMainClasses` and `compileKotlinJs` green | 2026-09-06 on the scratch daemon in Chrome: `/documents` loaded the 926 KB bundle, `window.forgeKotlin.documentSurface` came up true, the projects pane listed `genesis-notes`; clicking it listed `a.md`, `b.md`, `c.txt` with type, size and sequence; clicking `a.md` rendered its Markdown (`Alpha`, one paragraph) under a header naming project, cid, rev, seq and type, set the title and the `#genesis-notes/a.md` deep link. Every string on the page came from commonMain. |
| A coding-agent lane | `lcnc/AgentNodes.kt` (commonMain: `AgentCliInfo`, `AgentRunRequest`, `AgentRunResult` with a strict `ok` and the `agent/run/<runId>` receipt shape, the `AgentRuns` seam, `InMemoryAgentRuns`, legos `agent.run` and `agent.list`); contracts with exact kinds; `PlaneBrief` parses `AGENT:` and `AGENT-BUDGET:` (colon required) and renders an AGENT block naming the reader, the scratch clone and the evidence id; `BoardRules.CLAIM_AGENT_PREFIX` = `claim:agent:` and `ClaimProduction` binds the owner from the card fact's new `agent` field (`BoardFactElement`); `BoardClaimWorker` (commonMain) gains the lane through an injected `AgentRuns` lookup: an unknown or disabled agent leaves the card READY like a missing brain, a recorded diff is citable evidence, a budget kill parks in REVIEW without a strike, the claim receipt names `agent, runId, patchCid, transcriptCid, exit, killed, truncated, patchBytes`; fan-out strips `AGENT:` lines; `agent/AgentCli.kt` (jvmMain: the roster probed once at boot from real directories, never `which`, with per-CLI argv for codex `exec --json -C -s workspace-write --ephemeral --skip-git-repo-check -o -`, opencode `run --format json --dir --auto`, claude `-p --output-format json --permission-mode acceptEdits`), `agent/AgentEnvironment.kt` (a whitelist: no key crosses, PATH built from the CLI and tool dirs, `GIT_LFS_SKIP_SMUDGE=1`, `GIT_TERMINAL_PROMPT=0`), `agent/JvmAgentRunner.kt` (shared clone of the repo at HEAD under `<forgeHome>/agents/<runId>/repo`, the brief on stdin or as the argument, a wall-clock budget, bounded capture that keeps draining past the cap, process-tree kill, `git add -A` + `git diff --cached --binary` as the patch, transcript and patch to CAS and to `agents/<runId>/{brief.md,transcript.md,patch.diff}` attachment docs, the receipt on the blackboard, scratch removed unless retained); `forge/server/AgentWire.kt` (`GET /api/agents`, `GET /api/agents/runs`), `ForgeHostRoutes.ALL`, OpenAPI, `RouteManifest`, the parity source list; `ModuleContext.agentRuns` filled by the daemon, `KanbanModule` passes the lookup to the worker; daemon `--agents a,b` / `TRIKESHED_AGENTS` (default codex,opencode; claude installed but off), the roster probe, the legos, the wire; `BlackboardNamespaces` row `agent/run/`; the MCP spec description; the board page's claim row shows the agent, a `patch` link (raw bytes at `/trikeshed/_cas/<cid>`, downloads as `<jobId>.diff`), a `transcript` link and budget/truncated badges; RFC 0001 gains section 8 | `AgentNodesTest` (4), `PlaneBriefAgentTest` (3) in commonTest; `AgentEnvironmentTest`, `AgentWireTest`, `KanbanAgentClaimLoopTest` (4: worked in a scratch clone and closed by the judge with the source repo byte-identical and no worktree, a budget kill to REVIEW without a strike, a 1 MiB output kept whole under the 4 MiB cap, an unknown agent leaves READY) over a committed `sh` stub fixture that reads the evidence id from the brief; `KanbanClaimLoopTest`, `KanbanFanOutLoopTest`, `KanbanReaperLoopTest`, `PlaneBriefTest`, `PlaneJudgeTest`, the route, OpenAPI, MCP and contract parity gates, `LcncShakeDemoTest` green | 2026-09-06 on the scratch daemon (port 8899): `GET /api/agents` listed codex 0.145.0 and opencode 1.18.15 enabled and claude 2.1.263 installed but off. A card `GOAL: add hello.txt at the repo root containing the single word hello / MUST: hello.txt is in the patch / AGENT: codex / AGENT-BUDGET: 300` went in through the board's reactor ingress and READY; within a second it was RUNNING under `claim:agent:codex`, about a minute later DONE, signed by the judge. The run receipt named base `1afc1945e` (the committed HEAD, none of the shared tree's uncommitted work), exit 0, a 134-byte one-file patch, and a REPLY citing `blackboard/agent/run/<runId>`; the board page's claim row read `claim:agent:codex · codex · agent codex · ✓ · MET · DONE · patch · transcript`, the patch link served the diff (`+hello`) through the raw byte route. The first attempt failed honestly: the repo tracks a movie in git-lfs and the whitelisted PATH had no git-lfs, so the clone's checkout failed and three instant strikes parked the card in BLOCKED; the lane now skips LFS smudging and puts the tool dirs on the git steps' PATH. |
| C stale publish, snapshot | `POST /api/panels/{name}?baseCid=` compares the editor's loaded version with the board's `programCid` and refuses `409 stale_base` with nothing written; the 200 body gains `previousCid`; every outcome lands as the board-only receipt `lcnc/publish/<name>` (`LcncPublisher.publishOutcome`, `LcncBlackboard.publishKey`, a `BlackboardNamespaces` row with `admitted = false`); no `baseCid` keeps last-writer-wins. `lcnc/WorkspaceSnapshot.kt` (commonMain: composed from the board's program and run entries plus the prompt heads and the project databases, canonical form with integral numbers normalised so a parsed copy re-mints the same cid, `previousCid` lineage) and `forge/server/WorkspaceSnapshotService.kt` (jvmMain: CAS put, attachment `snapshots/<cid>`, ledger `<forgeHome>/snapshots/ledger.jsonl`, head `lcnc/snapshot/head` through `LcncPublisher.publishSnapshot`, `restore()` at boot, the `workspace.snapshot` lego); routes `POST /api/snapshots`, `GET /api/snapshots`, `GET /api/snapshots/{cid}` (cid-verified bytes); contract with exact kinds; daemon wiring. Harness: `loadedCids` records the version each mount loaded, `publish()` sends it as `baseCid` when publishing over the selected program, a 409 keeps the draft, says so in the status and opens the `lcnc/publish/<name>` receipt with two exits, Reload board version (discard draft) and Overwrite (publish again naming the current cid); a toolbar snapshot button and `Harness.snapshot()`; the inspector offers a Snapshot button on `lcnc/snapshot/head` that opens the bytes by cid | `WorkspaceSnapshotTest` (2, commonTest); `PatchWirePublishTest` (2: stale base refused, matching base succeeds with `previousCid`, no base keeps last-writer-wins, the refusal receipt on the board; snapshots chain, list newest first, serve verified bytes, restore over the ledger); Node `harness-publish.test.cjs` (3, slicing `publish()` like the arguments test); `PatchWirePromptsTest`, the route and contract parity gates, `LcncShakeDemoTest`, `ProjectDocsRoutesTest` green; `compileKotlinJs` green | 2026-09-06 on the scratch daemon in Chrome, two tabs on the published program `corpus` at the same version, both with a node nudged so neither auto-remounts. Tab A published: the board moved to a new cid and its `lcnc/publish/corpus` said ok with `previousCid` = the old one; tab B read `Board updated while corpus has unpublished changes`, published, and got `Publish refused: corpus moved on the board (now sha256:9d44a873...)` with its draft kept and the inspector open on the receipt (`refused`, `stale_base`, both cids) and the two buttons; Reload board version took B to A's version, clean. The toolbar snapshot answered `Snapshot sha256:14b7ccc7...` and opened the head (23 programs, 2 prompts, 1 project database, 2 receipts); its Snapshot button opened the bytes by cid: `kind lcnc.snapshot/1`, `programs.corpus.programCid` = A's version, both prompts, `genesis-notes`. |
| S staleness, rebuild | `lcnc/LcncRunFacts.kt` (commonMain: every project document and listing a completed run consumed becomes one `lcnc-consumed` fact in that project's partition under `lcnc/consumed/<runId>/`, beside the tendon's document facts; assert once, modify after; retract per run); `lcnc/rules/RunStaleProduction.kt` (`run-stale`, salience 30: a consumed document whose recorded cid is not the document fact's `contentId` fires once per new cid with both facts as support, a deletion fires `deleted`, a listing whose sorted file ids moved fires the index row; an edited file is its own row, not a listing change, so `LcncConsumedLedger.indexFingerprint` is now over file ids); `lcnc/LcncStaleMarker.kt` (the one marker per run, merged on document under `lcnc/stale/<runId>`, a rule output kept off the fact plane by a namespace row); `KanbanModule` registers the production, folds firings in its production sink, passes the facts to the run service, and claims `POST /api/lcnc/run/rebuild` ({runId} or {receiptCid}: the exact program version from CAS, the recorded inputs, budgets and pinned subprogram versions; 404 unknown, 409 not terminal or version drift); `LcncRunService` asserts a completed receipt's facts, re-asserts them in `recover()`, writes `rebuildOf`/`rebuildOfRunId` on a rebuild and retires the old run's facts and marker on completion. Harness: a Stale state in the legend, `latest()` attaches the marker to its run, `program()` reads Stale when the version-matched completed receipt has one and nothing is active, `node()` turns only the node whose recorded output carries a moved cid, the evidence dialog names the stale inputs and offers Rebuild, `Harness.rebuild()` posts the run id; the inspector offers Rebuild on `lcnc/stale/*` keys | `RunStaleProductionTest` (2) on the in-memory changes rig, `LcncStaleMarkerTest` (2) in commonTest; `CorpusStaleRebuildRouteTest` on the module rig over a mounted project (run, three facts in the project's partition, an edit marks the run stale within the drain, a second edit raises the count under one key, rebuild names the old receipt, reads the new cid, retires the marker and the old facts); `landscape-activity.test.cjs` gains the Stale cases; `BlackboardChangesFactElementTest` now lists five rule-output exclusions; the loop, route, contract and MCP gates green | 2026-09-06 on the scratch daemon in Chrome: `corpus` published and run over `genesis-notes`, lamp Completed; `a.md` edited through the project store's upload lane; within six seconds the lamp read `Stale: Completed against inputs that have since changed: a.md`, the legend counted it, and the evidence dialog showed the marker (`a.md`, old cid `59204cb0...`, new cid `b079d86f...`, sequence 4, count 1) with a Rebuild button; Rebuild answered `Rebuilt corpus: completed (rebuild of sha256:787b5d22...)`, the new receipt named `rebuildOf` and `rebuildOfRunId`, kept the program cid, read the new `a.md` cid, the old marker was gone and the lamp read Completed again. Two receipts recorded before this cut also read Stale on their listing rows at boot, because their listing fingerprints were minted by the earlier id-and-cid formula: a one-time migration effect, not a moved file. |
| N canary binary | `build.gradle.kts` (`macosArm64("macos")` gains `binaries.executable("canary")` with a named entry point); `nativeMain/.../canary/TrikeShedCanary.kt` | the binary is the proof: `./gradlew linkCanaryDebugExecutableMacos` then `build/bin/macos/canaryDebugExecutable/canary.kexe` | 2026-09-06: a Mach-O 64-bit arm64 executable (8.8 MB) ran Confix parse + canonical CBOR identity, a CAS round trip, a stored prompt's identity and render, and an LCNC program walked by the one executor with the prompt legos (`Greet the canary.`), 138 contracts, receipt cid printed, exit 0. No JVM. |
| F project documents | `lcnc/ProjectNodes.kt` (commonMain: `ProjectCorpus`, `ProjectDoc`/`ProjectRef`/`ProjectText`, `InMemoryProjectCorpus`, `ProjectGlob`, runners `project.list`/`project.docs`/`project.read`/`project.extract`); `forge/server/JvmProjectCorpus.kt` (jvmMain, over `ProjectDbRegistry` and `ProjectScopes`); the ring gains `each?` with `item` and `limit` (`LcncRunner.runRing`, `LcncTypeCheck` derives `List<K>` from the item's declared kind and lists the per-name yields); contracts with exact kinds `ProjectDoc`, `List<ProjectDoc>`, `List<ProjectRef>`; `CouchHeadProjection.sequenceOf`; `SurfaceNodes` no longer serves `project.list`; new preset `preset-corpus` "Corpus digest"; `lcnc/LcncConsumedLedger.kt` (the consumed-input ledger a run carries: what it read and one fingerprint over the cids; `prompt.get`, `project.docs`, `project.read` and `project.extract` record into it; receipts gain `consumed`, `consumedTruncated`, `inputFingerprint`, and `promptVersions` now names prompts read inside rings); the ring's `each?` is held open by the shake specimen and never proposed by the matcher (`LcncTypeCheck.RING_EACH`, `LcncShakeDemo`, `LcncTreeShake`, `doc/shake-demo.md`); `preset-brain-mux` and `preset-corpus` re-spaced to Chrome's measured node sizes (columns 320/720/1000) and their `prompt.chat` budgets raised from 256 to 1024 tokens; daemon wiring | `ProjectNodesTest`, `LcncEachRingTest`, `LcncEachTypeTest`, `CorpusPresetExecutionTest` (commonTest), `ProjectCorpusJvmTest` (jvmTest); a 25-suite batch of the LCNC, prompt, preset, route-manifest, run-route and claim-loop gates green; `compileKotlinJs` 0 errors | 2026-09-06 on the scratch daemon (port 8899, restarted on its home), in Chrome: the notes folder (`a.md`, `b.md`, `c.txt`) mounted through `POST /api/projects` and listed as project `genesis-notes` with 3 docs; "Corpus digest" opened from `?load=preset-corpus`, the daemon recorded its model into the draft, Publish as `corpus`, `project` set to `genesis-notes` in the invocation dialog, Run. The dialog's Resolved Bindings listed `n-proj project` from the invocation and two `r-in doc` rows sourced `each` carrying the documents' cids and sequences 0 and 1; the receipt (`7aa8b867`, cid `sha256:910e84`) carried `promptVersions.summarize` = the seed cid, `consumed` = the listing (glob `*.md`), `a.md`, the prompt, `b.md` (the `.txt` excluded by the glob), `consumedTruncated` false, and an `inputFingerprint` byte-identical to an earlier run over the same inputs; the display node read `a.md: The document is a brief note titled "Alpha," ...` and `b.md: ...`; `/api/lcnc/content?cid=` served the same receipt |
| A coding-agent lane | pending | pending | pending |
| X run export | pending | pending | pending |
| C stale publish, snapshot | pending | pending | pending |
| S staleness, rebuild | pending | pending | pending |

## Verification snapshot

### 2026-09-06, Cut P

- `./gradlew jvmMainClasses` green; `./gradlew compileKotlinJs --continue` 0 errors (343 warnings,
  the 21-line ratchet unchanged).
- 20 new JVM test cases pass; the ten gate suites named in the table pass. Two of those gates were
  red at HEAD before this cut and were repaired here, not worked around: the preset set gate did
  not list the Shake Demo specimen and asked it for a viewport and fed sockets it does not have by
  design (`PresetAssemblyTest`, `PresetRequiredInputsTest` now skip inspection-only specimens and
  name every offered preset), and the route manifest could not see the mux session routes or the
  interpolated archive claims (`RouteManifestParityTest` now scans `MuxSessionService.kt`; the
  three archive claims are literals with manifest rows).
- Rendered on a fresh scratch daemon (port 8899), in Chrome: the walk's step 2 first half. The
  seeds `hello` and `summarize` were on the board at boot; "Save a prompt" ran after a publish as
  `prompt` and the fact, the inspector, the wire, the content route and the ledger agreed on the
  new version and its lineage; the restart re-read the ledger.
- Not claimed: the harness palette group listing stored prompts and the inspector's "Prompt
  version" button are deferred to the Kotlin/JS gateway cut (the owner's 09-06 ruling: UI logic
  from commonMain, not more hand-written JS); no model call was made, so `preset-brain-mux`
  answering through its stored prompt is not claimed here.

### 2026-09-06, Cut F

- `./gradlew jvmMainClasses` green; a 25-suite batch (the Cut F suites, the prompt suites, the
  preset gates, `LcncShakeDemoTest`, the tree-shake suites, `RouteManifestParityTest`,
  `LcncRunProgramRouteTest`, `KanbanClaimLoopTest`) green; `compileKotlinJs` 0 errors; the macOS
  canary re-linked and ran on the same commonMain.
- Rendered on the scratch daemon in Chrome, as the table says. Two facts about the harness came out
  of it and are recorded rather than worked around. First, a preset with a blank live-list param
  (`prompt.chat` model and prefill, `project.docs` project) becomes an unpublished draft a second or
  two after it opens: the harness's live picklist records its first entry so that "what the select
  shows is what the daemon runs" (the owner's rule, commit 298de829b). The claim in 04b1e2474 that
  `preset-brain-mux` opens on the blackboard was read from the status line before the picklists
  landed; it opens clean geometrically now (its columns were also being pushed by the resolver, since
  a note is 330 wide) and then becomes a draft by that rule, so the walk publishes and runs. Second,
  the first paint of a large board takes about fifteen seconds in Chrome: the overlap resolver reads
  element widths inside an all-pairs loop over every top-level node on the landscape, and each read
  forces a reflow. That code is in another session's in-flight file and under the no-panels-widget
  rule, so it is noted for the owner, not touched.
- The first rendered run returned two empty summaries with the presets' 256-token budget: the
  scratch daemon's failover answered as a reasoning model, which spends that budget on hidden
  thinking. With 1024 tokens the same run summarised both notes. Both presets now say 1024; the
  runner's own default of 256 is the owner's call and is unchanged.
- Not claimed: the drop-to-mount gesture of walk step 1 (the folder was mounted through the
  projects route); the stale and rebuild half of the corpus story (Cut S); a model answer from
  `preset-brain-mux`, which was opened but not run here.

### 2026-09-06, Cut D (the document surface)

- The owner's ruling (4) asked where the muggle Notion clone is. This is its first page, and the
  first page whose logic is commonMain Kotlin reaching the browser through the bundle (ruling 1):
  the renderer and the page model have no browser in them and are tested on three targets.
- The JS test target could not compile commonTest at HEAD: 214 errors in 20 files. The cause was
  the JS-target ratchet (`gradle/js-target-debt.excludes`) cutting `**/RunBlockingHelper.kt` from
  the web compiles, which hid the repo's `runBlocking` expect from every commonTest that uses it,
  plus eleven files importing the JVM-only `kotlinx.coroutines.runBlocking` directly. A JS actual
  that throws (the wasm precedent), the ratchet line deleted per its own rule, and the eleven
  imports pointed at the helper: the target compiles, the two docs suites pass on it (5 and 3
  cases) and on macOS native. Coroutine suites on JS fail loudly at the helper if selected; that
  is a compile repair, not a claim that they pass there.
- The bundle is staged by `./gradlew stageKotlinJs`, deliberately outside `hotswapFeed` (webpack
  is its own step); `/documents` says so on the page when the bundle is missing.
- Not claimed: editing; ingest of formats beyond the miner's `.extract.md` twin (shown when it
  exists; the notes project has none); the trikeshed self-mount, which lists zero documents
  because it is served by the main store rather than a project database.

### 2026-09-06, Cut A (the coding-agent lane)

- Bullet 4 was the inverted one: Forge was only a target for agents. It now launches one, on a
  card's say-so, in a scratch clone at the committed HEAD, with a budget, and keeps the diff and
  the transcript as citizens the judge and a person can cite.
- The rendered run is the table's: codex added `hello.txt` and the judge closed the card. The
  first attempt taught something real: the clone's checkout invoked git-lfs for a tracked movie,
  the whitelisted PATH had none, and three instant strikes blocked the card. Skipping smudge in a
  scratch clone is the right answer (pointers, not blobs) and is now pinned by a test.
- Not claimed: opencode and claude were not run here (opencode is enabled and probed; claude
  stays off by default); the "patch" link downloads raw bytes rather than opening a viewer, since
  `/api/lcnc/content` still refuses non-JSON bytes (the viewer fix is deferred); the board page
  has no submit form, so the card went in through the reactor ingress; `git apply --check` in a
  clean checkout was not part of this run.

### 2026-09-06, Cut C (stale publish, snapshot)

- Bullet 2 was the weakest. Two editors on one program now meet a refusal instead of a silent
  overwrite, the refusal is a receipt a person can read, and the whole workspace can be named as one
  cid with a lineage. Restore from a snapshot stays deferred, as planned.
- A finding, recorded rather than fixed here: a published program does not survive this daemon's
  restart. The CAS-backed store rebuilds its document index per boot, so the attachment
  `panels/<name>` is gone with it; the prompt store and the snapshot service survive because each
  keeps a ledger under the forge home and thaws it at boot. The same ledger is what programs need;
  it is the owner's call whether that lands here or with the VCS gateway.
- Not claimed: overwrite was offered and tested in Node, not pressed in Chrome; restore of a
  snapshot; a snapshot taken by the lego from a program (the lego is registered and covered by the
  route test's service, not run from a canvas here).

### 2026-09-06, Cut S (staleness, rebuild)

- Bullet 6, the post's own sentence: "all this stuff which I want to process over in this iterated
  way, with some build artifacts being important and worth saving". A run now knows what it read,
  the plane knows when that moved, the page says so, and one gesture refreshes exactly that run.
- Design points that matter: the consumed facts live in the PROJECT'S partition because a production
  only sees the partition it is evaluated for; their local ids live under `lcnc/consumed/` so the
  tendon never touches them; refraction keys on support cids, so one firing per new document
  version comes for free and the marker merges on document rather than counting firings; the
  marker's namespace is not admitted to the fact plane, so a rule output never re-enters Rete.
- The listing fingerprint changed meaning here (file ids only), so receipts minted before this cut
  read as stale listings once at boot. That is honest and one-time; a rebuild clears it.
- The rendered check hit the shared tree twice: another session's in-flight library refactor was
  re-absorbed into the running scratch daemon and threw on a method that no longer existed, and a
  later clean deleted the live classes and staged libraries under the JVM. The check finished on a
  daemon launched over a private copy of the classes, resources and libraries, with the launcher's
  own argument shape and without the hot-swap agent. That recipe is in memory for the next session.
- Not claimed: the folder watcher path (the edit went through the upload lane); `controls.rebuildOnStale`
  is not parsed (rebuild stays a gesture, as ruled); a stale receipt of a program that was republished
  since is Unknown, not Stale, by design.

### 2026-09-06, the walk, twice from fresh homes

Both passes ran on a daemon launched over a private copy of the built classes, resources and
libraries (the checkout's build directories were being rewritten by another session all afternoon),
each on an empty home under the temp dir, with the notes folder mounted through the projects route.
Pass one's home was `oroboros-walk1.7Wq8Ay`, pass two's `oroboros-walk2.GVzTi9`. Each pass started
from the two seed prompts, no snapshots, no user programs, the roster codex and opencode.

| step | pass one | pass two |
|---|---|---|
| 1 files and context | `/documents` painted `genesis-notes` with `a.md`, `b.md`, `c.txt` from the bundle; the Archives dialog's demo import stored six entries under one manifest cid | the same, the self-mounted `trikeshed` project beside it |
| 2 prompts and a workflow | "Save a prompt" saved `summarize` as `sha256:6cdc6921...` with `previousCid` = the seed `sha256:f621f848...`; "Corpus digest" published as `corpus` and run over the project: `promptVersions.summarize` = the new cid, `consumed` = the listing, `a.md`, the prompt, `b.md`; the digest read `**Title:** Alpha ... **Summary:** ...`; lamp Completed | the same prompt cid, the same receipt shape, lamp Completed |
| 3 a coding agent | `walk1-codex` READY to RUNNING under `claim:agent:codex` to DONE in about a minute; base = the committed HEAD, a 134-byte one-file patch, a REPLY citing its own evidence id; the board's DONE column shows the card under its claim owner | `walk2-codex`, the same, closed in about a minute |
| 4 a snapshot and two editors | tab A published a second version; tab B, holding the first, was refused with `stale_base`, both cids and both exits, and its Reload took the board's version; the snapshot named the second program version, the new prompt cid, `genesis-notes seq 3 docs 3`, and the latest receipt | the same, with its own cids |
| 5 a shareable output | not walked: Cut X is another session's card, still in Running, and the export route is not on the tree | the same |
| 6 iterated compute | the second version run once more, then `a.md` edited through the upload lane; the marker and the Stale lamp within about ten seconds; Rebuild produced a receipt naming the stale one, reading the new cid, marker gone, lamp Completed | the same, cids of its own |

What the walk did not do, said plainly: the folder was mounted through the projects route rather
than dropped on the landscape; the agent card went in through the reactor ingress because the
board page has no submit form; step 5 was not walked; and twice the evidence dialog opened one
second before the activity refresh had seen the marker, so Rebuild appeared on the second opening.
Both passes ended in the same state, which is the stop condition the plan set. Port 8888 was not
restarted by this session: it already answered the snapshot and rebuild routes, its classes having
been refreshed by the hot-swap agent and a restart another session made in the afternoon.
