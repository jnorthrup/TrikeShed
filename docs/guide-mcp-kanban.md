# MCP Kanban Guide

> **Status:** verified-live — every route, tool, resource, and refusal below is
> covered by `LcncKanbanMcpTest` (19 cases against a live WAL-backed board),
> `McpKanbanOwnershipTest` (3 static gates on the ownership rule), and
> `KanbanModuleHttpTest.mcpIsMountedOnTheDaemonAndItsBoardSurvivesRestart`
> (mount, write, restart, read-back over the real HTTP route).

## Overview

Your coding agent can put work on the Oroboros board and move it, and what it
writes is still there tomorrow.

`/api/mcp` projects the LCNC Kanban asset as an [MCP](https://modelcontextprotocol.io)
server: two tools to change the board, four resources to read it. It is a
**lens, not a second board**. An MCP write is a call into the same
`LcncKanbanExperience` runner that `/api/lcnc/run` and the panels canvas call,
so it lowers through the same `BoardIntake` → `BoardStoreElement` and earns the
same guards, the same WAL record, and the same content-addressed receipt as a
click. A card an agent creates appears on `/api/board` and on the board page,
because there is only one board.

```text
MCP client (Claude Code, an agent, curl)
  -> POST /api/mcp                     one JSON-RPC document
  -> LcncKanbanMcp                     the lens; holds no store handle
  -> LcncKanbanExperience runner       LCNC owns the composition
  -> BoardIntake -> BoardStoreElement  one durable state owner
  -> WAL + CAS + guards + receipts
```

That the lens cannot reach the store directly is enforced by a constructor
signature, not by a convention: `LcncKanbanMcp` is handed a runner registry and
a read port, and never the store. `LcncKanbanMcpTest` proves both halves —
that a real write travels through the LCNC registry, and that withholding the
registry leaves no other way in — and `McpKanbanOwnershipTest` adds the static
gate, so the day someone adds a store handle "just for a quick read" the build
says no before anyone has to notice at runtime.

---

## Connect an MCP client

With the daemon running (see the [Daemon Launch Guide](guide-daemon-launch.md)),
point a client at the endpoint. For Claude Code:

```bash
claude mcp add --transport http oroboros-kanban http://localhost:8888/api/mcp
```

Use whatever port your daemon bound. Then ask it to work the board in plain
language — "what's on the board?", "add a card for the parser bug", "move the
parser card to running".

A quick check without a client at all — `GET` the endpoint for its server card:

```bash
curl -s http://localhost:8888/api/mcp
```

```json
{
  "server": "oroboros-lcnc-kanban",
  "protocolVersions": ["2026-07-28", "2025-06-18", "2025-03-26"],
  "transport": "POST this path with a JSON-RPC 2.0 document",
  "tools": ["kanban.submit", "kanban.move"],
  "resources": [
    "oroboros://lcnc/kanban/schema",
    "oroboros://lcnc/kanban/sheets",
    "oroboros://lcnc/kanban/cards/{jobId}",
    "oroboros://lcnc/kanban/receipts/{sequence}"
  ]
}
```

---

## Before you expose it

> **Status:** verified-live — and the reason this section is not buried.

The daemon binds `0.0.0.0` and `/api/*` has no authentication. Anything that can
reach the port can read and write your board. MCP does not change that exposure
class — `/api/invoke` and `/api/board/import` have always been unauthenticated
write surfaces on the same port — but it does add a surface that an agent will
use enthusiastically, so know the boundary:

- **Run it on a trusted network, or bind it where only you can reach it.**
- There is no per-caller identity, so a receipt records *what* changed, not *who*.
- A board write authorization policy is open work (KMFSM-007 in the
  [audit](marketability-kanban-mcp-audit.md)); it is not implemented.

---

## Tools

> **Status:** verified-live

Board refusals come back as MCP tool results with `isError: true` and a reason,
not as protocol errors — a stale revision is a normal outcome the client should
read and retry from, not a transport failure.

### `kanban.submit`

Puts a card on the board. Give `title` for a new card, or `jobId` to address one
that exists.

| Argument | Required | Notes |
|---|---|---|
| `title` | one of title/jobId | Card title |
| `jobId` | one of title/jobId | Stable id. Omitted, it is minted from the title's content hash — the same title always yields the same id, on any board |
| `priority` | no | Lower is more urgent; defaults to 2 |
| `tags` | no | String array |
| `dependencies` | no | jobIds this card waits on; a cycle is refused |
| `owner` | no | |
| `idempotencyKey` | no | Defaults to `submit#<jobId>`, so an accidental repeat is refused rather than doubling the card |

A submitted card lands in `todo`.

### `kanban.move`

Moves a card between columns under compare-and-set.

| Argument | Required | Notes |
|---|---|---|
| `jobId` | yes | |
| `toColumn` | yes | One of the seven columns |
| `expectedRevision` | yes | The revision you last read. Read it from the card resource first |
| `beforeJobId` | no | Land before this card instead of at the bottom of the column |
| `idempotencyKey` | no | Defaults to a value derived from the move itself, so a retried move dedupes |

### What a write hands back

Every accepted mutation returns the references needed for the next call:

```json
{
  "accepted": true,
  "jobId": "card-9f2a1c40b8e7",
  "revision": 1,
  "sequence": 1,
  "idempotencyKey": "submit#card-9f2a1c40b8e7",
  "cid": "…",
  "boardView": { "…": "the compact board projection" },
  "cardResource": "oroboros://lcnc/kanban/cards/card-9f2a1c40b8e7",
  "receiptResource": "oroboros://lcnc/kanban/receipts/1",
  "sheetsResource": "oroboros://lcnc/kanban/sheets"
}
```

The full sheet family is the `sheets` resource rather than an echo on every
write: a client pays for the whole board once, not per mutation.

---

## Resources

> **Status:** verified-live

| Resource | What it carries |
|---|---|
| `oroboros://lcnc/kanban/schema` | Columns with WIP limits, the transition policy, the guards a write must pass, and the card field schema |
| `oroboros://lcnc/kanban/sheets` | The `kanban.activeSheets` family — board, byStatus, byPriority, orchestration — plus the commit watermark |
| `oroboros://lcnc/kanban/cards/{jobId}` | One card by id — tags, dependencies, owner, the revision a move must quote, and its `receiptResource`. `/api/board` carries the same fields but has no single-card read, and no `lastSequence`/receipt link |
| `oroboros://lcnc/kanban/receipts/{sequence}` | What the store committed at that sequence: card, columns left and entered, command, and the CAS id of the raw command |

### The guards, as published

`schema` reports what the store actually enforces, not an aspirational
lifecycle. The transition policy is `open` — a move may target any recognized
column — and movement is constrained by guards instead:

| Guard | Applies to | Effect |
|---|---|---|
| `idempotency` | every write | A repeated key is refused as a duplicate, not applied twice |
| `expectedRevision` | `kanban.move` | Compare-and-set; a stale value is refused |
| `wipLimit` | `kanban.move` | A move into a full column is refused. Only `running` is limited (3) |
| `dependencyCycle` | `kanban.submit` | A submit that would close a cycle is refused at the door |

If a narrower transition policy is ever chosen, it changes inside the same LCNC
boundary and applies to HTTP, UI, and MCP callers at once.

### Receipts and retention

Receipts are indexed by sequence in a bounded in-memory ring (512 entries). Two
honest limits:

- A read past the window returns "no retained receipt", not a fabricated one.
- After a restart the ring is rebuilt from the replayed board — one entry per
  card, marked `source: "replay"`, with a **null `cid`**. The store keeps no
  sequence → cid index a reader could consult, and a synthesized content id
  would be a lie that reads exactly like the truth.

Live receipts (`source: "committed"`) carry the real CAS id, which is the
durable anchor that outlives the ring.

---

## Walkthrough

> **Status:** verified-live — this is the sequence
> `KanbanModuleHttpTest.mcpIsMountedOnTheDaemonAndItsBoardSurvivesRestart` runs.

```bash
P=http://localhost:8888/api/mcp

# 1. handshake
curl -s $P -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-06-18"}}'

# 2. what can I do?
curl -s $P -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3. put a card on the board
curl -s $P -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
      "name":"kanban.submit",
      "arguments":{"title":"Written by an agent","tags":["mcp"],"owner":"agent"}}}'

# 4. it is on the ordinary board too — one board, two lenses
curl -s http://localhost:8888/api/board

# 5. read the card, quote its revision, move it
curl -s $P -d '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{
      "uri":"oroboros://lcnc/kanban/cards/<jobId>"}}'
curl -s $P -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
      "name":"kanban.move",
      "arguments":{"jobId":"<jobId>","toColumn":"running","expectedRevision":1}}}'

# 6. restart the daemon, read the card again — still running.
```

Step 6 is the claim worth checking yourself. The WAL is the state.

### Or run the whole thing

```bash
scripts/demo-mcp-kanban.sh
```

Boots a daemon on a throwaway forge home, drives the board only through
`/api/mcp`, restarts it, reads the card back, and prints PASS/FAIL per claim —
exiting non-zero if any of them stops being true. It binds **8899, never 8888**,
and removes its scratch home on the way out, so it cannot disturb a running
operator daemon or touch production state. `--keep` leaves the home for
inspection; `PORT=nnnn` moves it.

Fifteen checks, including the three that are easy to claim and hard to prove:
the MCP-written card appearing on the ordinary `/api/board` route, a stale
revision being refused, and the card still sitting in `running` after a restart.

It also times itself, so the trial cost is a measurement rather than a guess:

| | Measured |
|---|---|
| Boot → first card on the board | **22s** |
| Full run, including restart and replay verification | **43s** |
| Build artifacts (`build/live/classes` + `build/staging/lib`) | 428M |
| Forge state the run leaves behind | 3.9M |

Those are **warm-cache numbers on one machine** (JDK 25.0.4.1, Gradle and
`build/` already populated). A cold clone with a first compile is a different and
much larger number, and is not measured here. Note the forge state: a short run
costs single-digit megabytes — the large CAS growth people see comes from a
daemon left running and ingesting, not from booting one.

### Capturing the evidence

```bash
scripts/demo-mcp-kanban.sh --evidence proof.json
```

Writes every claim, its expected and observed value, and the verdict, alongside
the git revision, whether the tree was dirty, the JDK, the platform, and the
timings — the claim-to-command-to-artifact record the audit asks for (MKT-004),
in a form you can archive or diff between runs rather than re-reading a
terminal.

The `gitDirty` flag is deliberate: evidence captured from an uncommitted tree
says so, so it can never be mistaken for a release artifact.

---

## Protocol notes

> **Status:** verified-live

- **Transport.** One JSON-RPC 2.0 document per `POST`, one response document
  back. A notification (no `id`) gets `202` and an empty body.
- **`GET` negotiates.** Streamable HTTP lets a client `GET` this path to open a
  server-initiated SSE stream. There isn't one — that is what
  `resources.subscribe: false` means — so a `GET` carrying
  `Accept: text/event-stream` is refused with **405**, the clean answer the
  transport requires. A plain `GET` (curl, a browser) still returns the
  human-readable server card. Verified live and pinned by
  `KanbanModuleHttpTest.theMcpGetRefusesAnEventStreamButStillGreetsCurl`.
- **Versions.** `initialize` echoes a recognized client version and otherwise
  answers with `2026-07-28`.
- **Batching is refused** with `-32600`. MCP removed JSON-RPC batching in the
  2025-06-18 revision; half-honouring it would be worse than saying no.
- **Capabilities.** `resources.subscribe` is `false` and means it: re-read the
  sheets resource after a write rather than expecting a push.
- **Errors.** `-32700` parse, `-32600` invalid request, `-32601` unknown method,
  `-32602` bad arguments, `-32002` unknown or unretained resource, `-32603` a
  server misconfiguration such as a missing runner.

Bad arguments are named before they reach the board: a move without a revision
is told which resource to read for one, and a bad column is shown the seven.

---

## What this is not, yet

The [marketability + MCP audit](marketability-kanban-mcp-audit.md) tracks the
rest. Open, and honestly so:

- **No write authorization** (KMFSM-007) — see the exposure note above.
- **No filtered queries or batch import** (KMFSM-008). Read the sheets and
  filter client-side; import still goes through `/api/board/import`, which
  keeps only the title.
- ~~No OpenAPI/MCP schema parity gate (KMFSM-009).~~ **Done.**
  `McpSurfaceParityTest` holds the tools, resources, templates, column
  vocabulary, WIP limit, and protocol versions to one description — including
  *this page*, so the guide cannot quote a URI the server does not serve.
  `ForgeHostSpecStatusParityTest` does the same for OpenAPI response codes.
  (One limit worth knowing: `docs/` is not a declared Gradle test input, so a
  doc-only edit is not re-checked until the next source change or a clean run.)
- ~~No concurrent-client race test (KMFSM-010).~~ **Done.**
  `McpKanbanRaceTest` races eight overlapping clients: one winner per revision,
  the WIP limit holding under a simultaneous rush, one card from eight identical
  submits, and a raced board replaying to the identical state after restart.
  Each case asserts peak in-flight > 1, so it cannot pass by accidentally
  running serially.
- **The task envelope is still the base card.** Acceptance criteria, evidence
  links, customer, and scoring fields do not exist yet (KMFSM-003).

## Related

- [Kanban Board Guide](guide-kanban-board.md) — the HTTP projections and the store
- [Panels / LCNC](guide-panels-lcnc.md) — the canvas that composes the same runners
- [Marketability + MCP Audit](marketability-kanban-mcp-audit.md) — why this exists and what remains
