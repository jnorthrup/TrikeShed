# Absorbing git: git IS a CAS, and Oroboros mounts it

> **Status:** design proposal. Every component named below exists in the tree.
> The proposal is a recognition, not a migration.

## IS, not WAS

The wrong framing — the one this page carried in its first draft — is that
Oroboros *replaces* git, that authority *moves*, that objects get *imported*.
All future tense, all migration, all work.

Git **is** a content-addressed store. Not "is like one":

| git | what it already is |
|---|---|
| `.git/objects/xx/…` | a sha-sharded CAS. `FileCasStore` shards `sha256/xx/…`. Same layout. |
| a blob | a CAS block |
| a commit | a patch: a tree delta, plus parents — which are `Patch.dependencies` |
| a ref | a mutable name over immutable content — which is what IPNS is |
| a packfile | block storage with delta compression |

So there is nothing to convert. `.git/objects` is **mounted**, not imported, and
`escape-velocity.md:108` already names this as the wave-2 deliverable: "git
objects decoded from the absorbed `.git/**` bytes and served as CAS citizens —
no external git process."

This is the same move as `CharSeries : CharSequence` earlier on this branch.
That conformance cost nothing — `length = size`, `get(i) = b(i)` — because
`CharSequence` **is** `Series<Char>`, a bound joined to an accessor, wearing a
JDK name. Nothing was adapted; the shape was recognised. Git is the same
situation one layer down: a CAS wearing a porcelain.

What Oroboros adds is not storage. Git has storage. It is that git's *merge* is a
three-way text algorithm over that storage, and the channel's is set union.

## The one thing that actually changes

Authority over the **worktree**.

```
git:       objects (CAS) ──checkout──▶ worktree ──▶ you edit ──▶ objects
oroboros:  objects (CAS) ──patches──▶ channel ──render──▶ worktree
```

The CAS is the same CAS in both lines. The difference is that the worktree stops
being an editable original and becomes a projection — `PijulCrdt.render()` per
path, reconstructible, disposable.

## Why this is available now, not later

`PijulCrdt`'s delete **is** vertex-anchored as of this branch — it reads authored
spans, and both insert and delete are idempotent. Before that, applying two
concurrent patches to one line silently ate the following line, so "merge by
applying patches" was not a thing that worked and no gateway could have made it
one.

Measured on forty real abandoned branches: `git merge` gives up on the third
("Should not be doing an octopus"), then degrades to thirty-nine two-way merges
each re-conflicting on a line eleven other branches already touched. Through the
channel, the twelve branches editing one line collapse to **three** distinct
edits — ten identical spellings are one patch by content address, and the two
that added a comment stay separate because they genuinely differ.

Git's merge cost grows with the number of *branches*. The channel's grows with
the number of *distinct edits*.

## Four costumes over one store

Not four systems. One content-addressed store, addressed four ways — the same
pattern as `.view`, `.toList()` and `CharSequence` over a single `Join`.

### Patches — how change is named

`PijulChannel` + `PijulCrdt` + `PatchStorage`.

A change is a `Patch(id: Blake3Hash, changes, dependencies)`. **The id is a hash
of the changes, not of the author or the branch.** Two contributors who make the
byte-identical edit produce the same patch, and it applies once. Merge is
`apply()`; there is no merge algorithm to fail.

`PatchStorage.store/get/getAll` is the patch log. It is a DAG, not a line.

### CAS — the store git already keeps

`FileCasStore` (`Sha2CasBus.kt`), sharded `sha256/xx/…`.

Blobs are content-addressed; patches reference blob CIDs. **The worktree is a
render, never authority** — `PijulCrdt.render()` per path materialises it, and
deleting it loses nothing.

### M2M — how two instances agree

`CasReplicationElement.replicate(cid, payload)` with `registerHook`, over the
relaxfactory transport (`RelaxTransport.http`, `RequestFactoryProxy`).

Two Oroboros instances converge by **exchanging patches, not negotiating refs**.
There is no fetch/merge/push cycle because there is nothing to reconcile: apply
is commutative and idempotent, so a peer that receives patches in any order,
twice, or out of sequence lands on the same render. `sameOrderReplicasRender-
Identically` and `applyOrderDoesNotChangeTheResult` are the existing gates.

This is the part git cannot do. `git pull` is a negotiation; patch exchange is a
set union.

### IPFS — the same blocks, off the machine

`IpfsBridge.putBlock/getBlock/resolveIpns`.

CAS blocks, git objects and IPFS blocks are all sha-addressed immutable bytes.
The bridge is an identity map, not a translation — which is why this plane is a
costume and not a port. IPNS names the channel head. The loop closes with no server
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

**P6 — the worktree becomes a render.** `git` becomes an export command. Note
what does NOT happen here: the object store is not migrated, because it was
never anything but a CAS. The repo does not stop being a git repo; git stops
being the thing that decides what the worktree says.

## What this does not claim

It does not claim to be faster. ISAM decode already sits behind page faults and
the same is true here — the win is that forty branches merge at all, not that
they merge quickly.

It does not remove GitHub. GitHub is where the collaborators are, and P6 leaves
push intact.

It does not make the CRDT a database. `BoardStoreElement` still owns durable card
state; this owns file content. Two state owners, complementary, as
`docs/marketability-kanban-mcp-audit.md` already establishes for the board.
