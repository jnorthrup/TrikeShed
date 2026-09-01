# Absorbing git: Oroboros as the repository, GitHub as an export

> **Status:** design proposal. Every component named below exists in the tree; the
> proposal is about which one holds authority, not about new machinery.

## The inversion

Today git holds authority and Oroboros watches it. `GitStateCache` shells out to
`git rev-parse`/`git diff`, and `WorktreeCouchGateway.reconcile(repoRoot, prefix)`
walks the worktree and files what changed into attachments. Oroboros is a
downstream observer of a store it does not own.

The proposal inverts exactly that and nothing else:

```
NOW      git (authority) ──watch──▶ Oroboros (observer) ──▶ CAS
PROPOSED Oroboros (authority) ──render──▶ worktree ──▶ git ──push──▶ GitHub
```

Git stops being the repository and becomes a **publication format** — the thing
you emit at the boundary, the way you emit a tarball. GitHub stays exactly where
it is, because that is where other people are.

## Why this is possible now

`PijulCrdt` was positional until this branch: `contentLen()` read live content, so
tombstoning a vertex collapsed the span its neighbours were addressed by, and two
concurrent deletes of one line ate the following line. Vertices now keep their
authored span and both operations are idempotent. That was the load-bearing
repair — without it, "merge by applying patches" silently corrupts, and no amount
of gateway wrapping fixes it.

Measured on forty real abandoned branches: `git merge` gives up on the third
("Should not be doing an octopus"), then degrades to thirty-nine two-way merges
each re-conflicting on a line eleven other branches already touched. Through the
channel, twelve branches editing one line collapse to **three** distinct edits —
ten identical spellings become one patch by content address, two that added their
own comment stay separate because they genuinely differ.

That ratio is the whole argument. Git's merge cost grows with the number of
*branches*; the channel's grows with the number of *distinct edits*.

## Four planes

### 1. Patch plane — authority

`PijulChannel` + `PijulCrdt` + `PatchStorage`.

A change is a `Patch(id: Blake3Hash, changes, dependencies)`. **The id is a hash
of the changes, not of the author or the branch.** Two contributors who make the
byte-identical edit produce the same patch, and it applies once. Merge is
`apply()`; there is no merge algorithm to fail.

`PatchStorage.store/get/getAll` is the patch log. It is a DAG, not a line.

### 2. CAS plane — storage

`FileCasStore` (`Sha2CasBus.kt`), sharded `sha256/xx/…`.

Blobs are content-addressed; patches reference blob CIDs. **The worktree is a
render, never authority** — `PijulCrdt.render()` per path materialises it, and
deleting it loses nothing.

### 3. M2M plane — replication

`CasReplicationElement.replicate(cid, payload)` with `registerHook`, over the
relaxfactory transport (`RelaxTransport.http`, `RequestFactoryProxy`).

Two Oroboros instances converge by **exchanging patches, not negotiating refs**.
There is no fetch/merge/push cycle because there is nothing to reconcile: apply
is commutative and idempotent, so a peer that receives patches in any order,
twice, or out of sequence lands on the same render. `sameOrderReplicasRender-
Identically` and `applyOrderDoesNotChangeTheResult` are the existing gates.

This is the part git cannot do. `git pull` is a negotiation; patch exchange is a
set union.

### 4. IPFS plane — the closed loop

`IpfsBridge.putBlock/getBlock/resolveIpns`.

CAS blocks and IPFS blocks are the same idea, so the bridge is an identity map,
not a translation. IPNS names the channel head. The loop closes with no server
in it:

```
patch ─▶ CAS blob ─▶ IPFS block ─▶ IPNS head
                                      │
peer:  IPNS resolve ─▶ fetch blocks ─▶ CAS ─▶ apply ─▶ render
```

`CasReplicationElement` is inert today per the gap analysis; this is what it is
for.

## The git gate

Asymmetric on purpose.

**Ingest** — a git branch becomes patches:

```
branch ─▶ diff vs merge-base ─▶ PijulDiffParser ─▶ FileChange
       ─▶ [GATE] ─▶ PijulChannel.applyPatch(workId, patchCid, title, appliedAt)
```

`PijulDiffParser` and `applyPatch` already exist. The gateway is the diff-to-
FileChange step plus content-addressing the id.

**The gate is not optional.** `docs/counter-threat-gap-analysis.md` records that
`FlywheelDriver.drainExactArtifacts:904` applies *zero* validation before Pijul
merge — no AST lint (Layer 4), no xattr rejection (Layer 2). Ingesting forty
unreviewed bot branches through an ungated merge is the exact scenario that gap
describes. Ingest runs the lint; patches that fail never enter the channel.

**Egress** — the channel becomes a git commit:

```
render() per path ─▶ worktree ─▶ git commit ─▶ push origin
```

One commit per channel checkpoint, not per patch. GitHub sees a normal repo with
a linear history; it never learns it is a projection.

## Internalising the tooling

Each git verb has a cheaper answer on the patch plane, and one is strictly
better:

| git | on the channel |
|---|---|
| `log` | walk `PatchStorage.getAll()` — a DAG, with `appliedAt` and `sessionId` per patch |
| `diff` | render two channel states, diff the strings |
| `merge` | `apply()` — commutative, idempotent, cannot conflict |
| `branch` | a channel; branching is free because patches carry their own identity |
| `cherry-pick` | `apply()` the one patch — no rebase, no id rewrite |
| **`blame`** | **free** — `VertexId(patch, offset)` means every character already knows which patch created it |

`blame` is the one to notice. Git reconstructs authorship by re-diffing history on
demand; the CRDT stores it in the vertex identity. Blame is a map lookup.

## Phasing, with falsifiable gates

**P1 — gateway in, read-only.** Ingest branches to a scratch channel; render to a
temp tree; report what converges. Nothing written to the worktree, no branch
deleted.
*Gate:* the forty branches ingest and render; the twelve-into-three collapse is
reproduced outside the test fixture.

**P2 — egress and round-trip.** Render → worktree → commit → push to a throwaway
GitHub repo, then re-ingest and assert the channel is unchanged.
*Gate:* `ingest(export(channel)) == channel` on patch ids, not on bytes.

**P3 — the counter-threat gate.** AST lint at ingest; a patch failing Layer 4 is
refused with a receipt.
*Gate:* a known-bad patch is refused and the refusal is durable and readable.

**P4 — M2M.** Two daemons, patch exchange over HTX, no shared filesystem.
*Gate:* both render identically after exchanging in opposite orders.

**P5 — IPFS.** CAS blocks to `IpfsBridge`, head under IPNS; a third daemon
bootstraps from IPNS alone.
*Gate:* a daemon with an empty CAS reconstructs the tree from the IPNS name and
nothing else.

**P6 — authority flips.** The worktree is generated; `git` becomes an export
command. Only here does the repo stop being a git repo.

## What this does not claim

It does not claim to be faster. ISAM decode already sits behind page faults and
the same is true here — the win is that forty branches merge at all, not that
they merge quickly.

It does not remove GitHub. GitHub is where the collaborators are, and P6 leaves
push intact.

It does not make the CRDT a database. `BoardStoreElement` still owns durable card
state; this owns file content. Two state owners, complementary, as
`docs/marketability-kanban-mcp-audit.md` already establishes for the board.
