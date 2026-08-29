# NARS Beliefs Guide

> **Status:** verified-live

A user guide for the NALS curation loop — the belief bag, its routes, and how to teach and query beliefs through the HTTP API.

---

## Conceptual Model

### Belief Bag

A belief bag is an **attention economy over NAL signals**. Each signal carries:

- An **angular encoding** (topic orientation in NAL space)
- **Truth values** — expectation and evidence counts (positive / negative)
- A **budget** — priority, durability, quality — that governs how long the signal survives curation

Beliefs are not permanent records. They are living hypotheses whose budgets decay over time. The bag enforces a finite capacity; beliefs that fall below the eviction floor are spilled to make room for fresher signals.

### Attention Economy

Budgets decay on each tick. Two hardcoded thresholds govern survival:

| Threshold | Behavior |
|-----------|----------|
| **90** | Budget ceiling — signals at or above this retain full salience |
| **30** | Eviction floor — signals below this are spilled from the bag |

Beliefs that decay below 30 are evicted. The bag keeps the top-k (default 64) signals that pass the floor.

### Truth Revision

When a review arrives (via `POST /api/beliefs/review`), evidence counts update:

```
expectation = positive / (positive + negative)
```

Positive evidence raises expectation; negative evidence lowers it. The revision is incremental — each review adds facts, it does not replace them.

### Teaching (W5.3)

Teaching takes **impulses** and **replay scenarios** in, runs curator hindsight, and emits landed glosses. This is the mechanism by which new domain knowledge is absorbed into the belief bag.

> **Status:** dual-state — `POST /api/beliefs/teach` returns **200** when the daemon boots with `--belief-bag` (the curator element is wired), and **503** without it. See the walkthrough for the working path.

---

## Routes

All routes are served from `BeliefWire.kt` (jvmMain, lines 44–293).

### GET /api/beliefs

Returns the top-k beliefs (default 64) with budgets and curation states.

**Response:**

```json
{
  "size": 42,
  "capacity": 64,
  "beliefs": [
    {
      "angular": "...",
      "relation": "...",
      "expectation": 0.87,
      "evidence": { "positive": 12, "negative": 3 },
      "priority": 0.91,
      "durability": 0.74,
      "quality": 0.65,
      "state": "verified-live",
      "subjectCid": "...",
      "provenanceCid": "..."
    }
  ]
}
```

> **Status:** verified-live

---

### GET /api/beliefs/render

Bounded MEMORY render — produces frozen-snapshot text of the belief field.

Requires `memoryFiles` wired in the daemon.

> **Status:** verified-live

---

### POST /api/beliefs/review

Induction pass. Accepts facts and a turn success flag.

**Request:**

```json
{
  "facts": [
    { "verb": "observes", "ok": true, "context": "...', "object": "..." }
  ],
  "turnSucceeded": true
}
```

**Response:**

```json
{
  "landed": 3,
  "bagSize": 45,
  "factsParsed": 3,
  "reviewState": "..."
}
```

> **Status:** verified-live

---

### POST /api/beliefs/tick

One decay tick — a curation pulse. Budgets decrement; beliefs below floor are evicted.

**Response:**

```json
{
  "ok": true,
  "bagSize": 40
}
```

> **Status:** verified-live

---

### POST /api/beliefs/teach

Curator hindsight (W5.3). Accepts impulses and replay scenarios.

**Request:**

```json
{
  "impulses": [
    { "kind": "learn", "subject": "...", "rationale": "..." }
  ],
  "scenarios": [ "..." ]
}
```

> **Status:** dual-state — returns **200** with `--belief-bag` (curator element wired), **503** without it.

---

### POST /api/beliefs/query

Bank solver query (W5.3).

**Request:**

```json
{
  "pattern": "kif"
}
```

> **Status:** dual-state — returns **200** with `--belief-bag`, **503** without it.

---

### POST /api/beliefs/resonate

Synonym and antonym peak detection over the belief field.

**Request:**

```json
{
  "goal": "...",
  "k": 10,
  "taxonomy": "...",
  "mode": "..."
}
```

**Response:**

```json
{
  "synonymPeaks": [ "..." ],
  "antonymPeaks": [ "..." ]
}
```

> **Status:** verified-live

---

### GET /api/beliefs/introspect

NAL-9 introspection of the belief field. Returns principal concepts, crux axis top bits, and pen cohort T2.

**Response:**

```json
{
  "beliefs": [ "..." ],
  "cruxAxisTopBits": [ "..." ],
  "principalConcepts": [ "..." ],
  "penCohortT2": 17
}
```

> **Status:** verified-live

---

### POST /api/beliefs/kg

Ingest Turtle / RDF / KIF and mint NAL beliefs.

**Request:**

```turtle
@prefix ex: <http://example.org/> .
ex:bird ex:has_feathers true .
```

**Response:**

```json
{
  "format": "turtle",
  "statements": 1,
  "minted": 1,
  "copulas": [ "..." ]
}
```

> **Status:** verified-live

---

## Boot Prerequisite

All belief routes require the `--belief-bag` flag at daemon startup. Without it, every belief endpoint returns **503**.

```bash
# Correct — belief routes available
bin/oroboros-daemon --belief-bag

# Incorrect — belief routes return 503
bin/oroboros-daemon
```

---

## Known Limitations

| Route | Status | Detail |
|-------|--------|--------|
| `POST /api/beliefs/teach` | **dual-state** | 200 with `--belief-bag`, 503 without |
| `POST /api/beliefs/query` | **dual-state** | 200 with `--belief-bag`, 503 without |
| All belief routes | **dual-state** | 200 with `--belief-bag`, 503 without `--belief-bag` |

> This is a NALS curation loop, not a database query interface. Beliefs are living hypotheses — they decay, get evicted, and are revised by incoming evidence. Do not treat them as persistent storage.

---

## Worked Walkthrough

### 1. Boot with belief bag

```bash
bin/oroboros-daemon --belief-bag
```

### 2. Send a decay tick

```bash
curl -X POST http://localhost:8888/api/beliefs/tick
```

Response:

```json
{ "ok": true, "bagSize": 0 }
```

The bag is empty — no beliefs have been taught yet.

### 3. Read top-k beliefs

```bash
curl http://localhost:8888/api/beliefs
```

Response:

```json
{
  "size": 0,
  "capacity": 64,
  "beliefs": []
}
```

### 4. Teach a belief (working path with --belief-bag)

```bash
curl -X POST http://localhost:8888/api/beliefs/teach \
  -H "Content-Type: application/json" \
  -d '{
    "impulses": [
      { "kind": "learn", "subject": "sparrow", "rationale": "bird with feathers" }
    ],
    "scenarios": []
  }'
```

Response (200 when daemon booted with `--belief-bag`):

```json
{ "landed": 1, "bagSize": 1, "factsParsed": 1 }
```

> If the daemon was booted without `--belief-bag`, this returns **503** — the
> curator element is not wired in that mode. The `teach` and `query` routes
> are dual-state: they work when the belief bag flag is set.

### 5. Ingest via KG

```bash
curl -X POST http://localhost:8888/api/beliefs/kg \
  -H "Content-Type: text/turtle" \
  -d '@prefix ex: <http://example.org/> . ex:sparrow ex:has_feathers true .'
```

Response:

```json
{
  "format": "turtle",
  "statements": 1,
  "minted": 1,
  "copulas": ["..."]
}
```

The KG route ingests domain statements and mints NAL beliefs directly — this is the working path for getting knowledge into the bag.

### 6. Read beliefs after ingestion

```bash
curl http://localhost:8888/api/beliefs
```

The bag now contains the minted belief with updated budgets.

---

## Status Convention

This guide uses the marker convention:

| Marker | Meaning |
|--------|---------|
| `verified-live` | Route confirmed working in current build |
| `degraded` | Route exists but returns 503 or partial results |
| `stub` | Route defined but not yet implemented |
| `known-bug` | Route implemented but has a known defect |
| `unverified` | Route defined but not yet tested |

Use `> **Status:** <class>` at the top of each section or route to indicate its current state.
