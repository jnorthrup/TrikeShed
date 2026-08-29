# Kanban Board Guide

> **Status:** verified-live

## Overview

The kanban board is a WAL-backed card store exposed as a set of HTTP routes by the Kanban module. It is the primary surface for tracking jobs through column transitions, running LCNC programs, and importing plan documents.

The WAL lives at `.kanban` in the forge home directory. Board JSON is memoized by commit watermark — repeated reads return the same payload until a write advances the sequence.

---

## Routes

> **Status:** verified-live

### GET /api/board

Returns the live board as JSON. Response fields:

- `title` — board title (default: `"Oroboros board"`).
- `items` — array of card objects, each containing:
  - `id`, `title`, `column`, `owner`
  - `attention` (optional — only when belief bag is wired)
  - `contested` (optional — only when belief bag is wired)

Board JSON is memoized keyed by the current commit watermark (`lastSequence`). The memo is invalidated on every write that advances the sequence. When a belief bag is present, `BoardAttentionOrder.garnish()` computes attention scores and contested flags from the bag's resonance sweep; these fields are absent when `bag == null`.

> **Status:** unverified

### POST /api/invoke

Batched command ingestion. The body can be a single command object or a JSON array of commands. Each command is processed through `InvokeLowering.commandsOf(parsed)` into a list of canonical maps, then each is sent through the single-writer `BoardIntake` channel with a `CompletableDeferred<BoardApply>`.

Response (HTTP 202):

```json
{
  "ok": true,
  "accepted": 1,
  "rejected": 0,
  "sequence": 42,
  "results": [
    {
      "verdict": "committed",
      "idempotencyKey": "...",
      "jobId": "...",
      "revision": 1,
      "sequence": 42
    }
  ]
}
```

Each result carries a `verdict` of `"committed"` or `"rejected"`, plus the `idempotencyKey`, `jobId`, `revision`, and `sequence` on success, or `reason` on rejection.

### POST /api/board/import

Tolerant plan-doc import. The body is markdown text. Bullet lines (`-`, `*`, or numbered) are extracted, trimmed, and filtered to 3–200 characters (max 100 bullets). Each parsed bullet becomes a `submit` command with an idempotency key derived from the content hash (`import#<hex16>`), so re-importing the same doc is a no-op (dedupe).

Response (HTTP 200):

```json
{
  "ok": true,
  "parsed": 5,
  "imported": 3,
  "duplicates": 2,
  "sequence": 42
}
```

### GET /api/lcnc/kanban

Returns LCNC active sheets. No request body.

### POST /api/lcnc/kanban/move

Move a card between columns with optimistic concurrency. Body:

```json
{
  "itemId": "job-123",
  "to": "running",
  "expectedRevision": 3
}
```

Field aliases: `itemId` or `jobId`; `to` or `toColumn`. The `expectedRevision` is required and normalized to a long (JSON numbers like `3.0` are handled). On success, returns HTTP 202 with `{"accepted": true}`. On conflict (stale revision), returns HTTP 409 with `{"accepted": false}`.

### GET /api/lcnc/concentric

Returns the concentric composition surface: modules, rings, and wizard roster projected from contracts, stored programs, and the sub-VM substrate. No request body.

### GET /api/lcnc/contracts

Returns the full LCNC contract vocabulary. Each contract includes: `type`, `title`, `inputs`, `outputs`, `inputKinds`, `outputKinds`, `cardinality`, `functions`, `params` (with `v`, `opts`, `ta`, `ph`, `cols`), `source`, `sink`, `wide`.

### POST /api/lcnc/run

Generic runner dispatch. Accepts three request shapes:

1. **Named program:** `{"program": "name", "inputs": {...}}` — runs a stored program via the program loader.
2. **Inline document:** `{"name": "label", "document": {...}, "inputs": {...}}` — parses the document through `LcncProgramConfix` and runs it.
3. **Single node:** `{"type": "runner.type", "params": {...}, "inputs": {...}}` — dispatches to a registered runner.

Response (HTTP 200):

```json
{
  "ok": true,
  "program": "name",
  "returns": "...",
  "outputs": {}
}
```

---

## Invoke Command Taxonomy

> **Status:** verified-live

Commands flow through `InvokeLowering.lower()` → `BoardIntake` channel → single-writer reducer. The three command types in the WAL store:

### submit

Add a new card.

```json
{
  "type": "submit",
  "jobId": "card-abc123",
  "title": "Implement feature X",
  "idempotencyKey": "k1"
}
```

Fields: `jobId`, `title`, `idempotencyKey`. Optional: `dependencies` (list of jobIds), `tags` (list of strings), `owner`.

### move

Move a card between columns.

```json
{
  "type": "move",
  "jobId": "card-abc123",
  "toColumn": "running",
  "expectedRevision": 1,
  "idempotencyKey": "k2"
}
```

Fields: `jobId`, `toColumn`, `expectedRevision`, `idempotencyKey`. The board guard checks WIP limits on the target column before the reducer runs.

### archive (= move to `archived`)

There is no `archive` command type — `InvokeLowering` rejects it with
`unknown command type 'archive'`. Archiving is a `move` to the `archived`
column:

```json
{
  "type": "move",
  "jobId": "card-abc123",
  "toColumn": "archived",
  "expectedRevision": 1,
  "idempotencyKey": "k3"
}
```

The full verb set the lowering accepts: `submit`/`create`/`new`, `move`,
`start`, `complete`/`done`, `fail`, `retry`, `progress`, `block`, `cancel`,
`acknowledge`/`ack`, `retract`/`delete`.

---

## Board Guards

> **Status:** verified-live

Before the reducer runs, the board enforces:

- **WIP limits** — move/start commands are rejected if the target column is at capacity.
- **Dependency cycles** — submit commands with dependencies are rejected if they form a cycle (via `KanbanBoard.hasCycle()`).

---

## Belief Bag Fields (Optional)

> **Status:** stub

When a belief bag is wired (`bag != null`), each card in the board response includes:

- `attention` — float score from `BoardAttentionOrder.garnish()`, a resonance sweep over the bag's vector plane.
- `contested` — boolean, true when a card's score has near-antonyms within an angular distance of 4 bits from the centroid.

These fields are absent when no belief bag is configured. This is the Phase-5 gate — the fields only appear when the Rete production engine and the board attention order are both active.

---

## Worked Walkthrough

> **Status:** unverified

### 1. Read the board

```bash
curl http://localhost:<port>/api/board
```

Returns the current board state. Items are grouped by column with owner fields populated.

### 2. Submit a card via /api/invoke

```bash
curl -X POST http://localhost:<port>/api/invoke \
  -H "Content-Type: application/json" \
  -d '{"type":"submit","jobId":"card-demo1","title":"Write the kanban guide","idempotencyKey":"demo-001"}'
```

Response:

```json
{
  "ok": true,
  "accepted": 1,
  "rejected": 0,
  "sequence": 43,
  "results": [
    {
      "verdict": "committed",
      "idempotencyKey": "demo-001",
      "jobId": "card-demo1",
      "revision": 1,
      "sequence": 43
    }
  ]
}
```

### 3. Import a board from a plan document

```bash
curl -X POST http://localhost:<port>/api/board/import \
  -H "Content-Type: text/plain" \
  -d "- Write the kanban guide
- Wire belief bag fields
- Add WIP limit enforcement
- Archive stale cards"
```

Response:

```json
{
  "ok": true,
  "parsed": 4,
  "imported": 4,
  "duplicates": 0,
  "sequence": 47
}
```

Re-importing the same document returns `duplicates: 4` and `imported: 0` — idempotent by content hash.

### 4. Move the card

```bash
curl -X POST http://localhost:<port>/api/lcnc/kanban/move \
  -H "Content-Type: application/json" \
  -d '{"itemId":"card-demo1","to":"running","expectedRevision":1}'
```

Returns HTTP 202 with `{"accepted": true}` on success, or HTTP 409 if the revision is stale.

---

## See Also

- The `/api/submit` endpoint is documented canonically in the corpus guide — see that file for the full submit surface. This guide links to it; it does not duplicate it.
