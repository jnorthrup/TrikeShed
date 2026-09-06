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

1. **Files and context.** Open `http://127.0.0.1:8899/harness`. In Archives, import a small ZIP of
   Markdown notes (make one with `node bin/archive-demo.cjs create /tmp/notes.zip`). Drop the same
   notes folder onto the landscape so it mounts as a project. Expect: the project appears in the
   terrain and `project.docs` lists its files when asked (step 2 does the asking).
2. **Prompts and a workflow.** Open the preset "Save a prompt", set the name to `summarize`, type a
   new question, press Run. Expect: `lcnc/prompt/summarize` appears in the activity pane with a new
   cid whose `previousCid` is the seed. Open the preset "Corpus digest", set the project in the
   invocation editor, press Run. Expect: a completed receipt whose `promptVersions.summarize` is the
   new cid and whose `consumed` list names every document read; the program lamp reads Completed.
3. **A coding agent.** On the board page submit a card whose spec says `AGENT: codex` and carries a
   MUST naming a file change; move it to Ready. Expect: Running with owner `claim:agent:codex`, then
   a claim row with `agent codex`, a patch and a transcript, then Review or Done signed by
   `judge:plane`. Open the patch; download it; `git apply --check` passes in a clean checkout.
4. **A snapshot, and two editors.** Press the toolbar's snapshot action. Open the same program in a
   second tab; publish from both. Expect: the second publish is refused as stale, the draft is kept,
   and the inspector offers reload and overwrite. The snapshot lists the program cid, the prompt cid,
   the project's sequence and the latest receipt cid.
5. **A shareable output.** From the receipt inspector press Export run; download the ZIP from the
   Archives dialog. On a second fresh daemon (`--port 8898`) import that ZIP. Expect: the receipt
   entry opens CID-verified and its cids match the first daemon's.
6. **Iterated compute.** Change one Markdown file in the mounted project. Expect within a second: the
   program lamp turns Stale naming the changed document; press Rebuild; a new receipt appears whose
   `rebuildOf` is the stale receipt and the lamp returns to Completed.

The walk counts only when a second run from another fresh home reaches the same end state, and only
after every observable above was seen on the page, not inferred from a status code.

## What landed

| Cut | Files | Tests | Rendered check |
|---|---|---|---|
| P prompts | pending | pending | pending |
| F project documents | pending | pending | pending |
| A coding-agent lane | pending | pending | pending |
| X run export | pending | pending | pending |
| C stale publish, snapshot | pending | pending | pending |
| S staleness, rebuild | pending | pending | pending |

## Verification snapshot

Not yet run. This section will list, in the style of `doc/shake-demo.md`, exactly which gates,
tests and rendered steps passed, on which date, and what is not claimed.
