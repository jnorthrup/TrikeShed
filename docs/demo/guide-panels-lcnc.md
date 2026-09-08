# Panels / LCNC Guide

Open the concentric canvas and run an LCNC program. This guide is **deliberately thin** — no widget-library investment.

> **Status: PROBATIONARY.** The panels editor was rooted out on 2026-08-27 and
> revived on 2026-08-28 as the concentric canvas. The user's standing verdict
> (2026-08-29) is that the concentric-UI promise re-entered past the no-widget
> ruling — this surface's commerce status is probationary. This guide documents
> what is; it does not pitch this surface as part of the commerce story.

> **Launch prerequisite:** the daemon must be running. See [guide-daemon-launch.md](guide-daemon-launch.md).

## Opening the Canvas

Navigate to `http://localhost:8888/panels` in a browser. The panels page is served as a static asset from the daemon.

## What the Concentric Canvas Is

The canvas renders LCNC programs as concentric scopes. A program is an `LcncProgram` — an imperative program in single-assignment normal form. Scopes nest concentrically: a node holding children IS a ring, and ring entry uses `withContext(LcncScopeFrame)`. The model:

- **scope** — a named boundary (the ring)
- **scope.in** — formal parameters entering the scope
- **scope.out** — return values exiting the scope
- Inner nodes see outer bindings; nearest shadows; only `scope.out` crosses outward.

One paragraph is the entire scope model. The full spec lives in [docs/concentric-lcnc-ccek-spec.md](concentric-lcnc-ccek-spec.md).

> **Status:** verified-live — the concentric model is pinned in `LcncRunner.kt:45-60`.

## Server Persistence

Panel constructions are **server documents** (CAS-addressed, replicated), not browser `localStorage`. They are stored via the `/api/panels/*` family and survive daemon restarts.

> **Status:** verified-live — persistence model matches `PatchWire.kt:385`.

## Running a Program

```http
POST /api/lcnc/run
Content-Type: application/json

{
  "program": "greet"
}
```

Or with an inline document:
```json
{
  "name": "inline-greet",
  "document": {
    "nodes": [
      {"id": "n1", "type": "literal", "params": {"value": "hello"}},
      {"id": "n2", "type": "string.upper", "params": {}}
    ],
    "wires": [{"from": ["n1", "out"], "to": ["n2", "text"]}]
  }
}
```

Or a single-node run:
```json
{
  "type": "literal",
  "params": {"value": "hello"},
  "inputs": {}
}
```

Response (success):
```json
{
  "ok": true,
  "program": "greet",
  "returns": {...},
  "outputs": {...}
}
```

Response (failure):
```json
{
  "ok": false,
  "program": "greet",
  "error": "no_such_program"
}
```

> **Status:** verified-live — run route shape matches `KanbanModule.kt:264-345`.

## Stub Routes

The following routes exist but are stubs — they return empty or placeholder data:

| Route | Status |
|-------|--------|
| `GET /api/lcnc/mating-options` | **stub** |
| `GET /api/lcnc/fills` | **stub** |
| `GET /api/lcnc/autowire` | **stub** |

> **Status:** stub — these routes are explicitly not implemented.

## Additional Routes (read-only)

| Route | Method | Description |
|-------|--------|-------------|
| `/api/lcnc/kanban` | GET | Active LCNC sheets |
| `/api/lcnc/concentric` | GET | Modules + rings + wizard roster |
| `/api/lcnc/contracts` | GET | Full LCNC contract vocabulary |

These are thin read-only projections. The guide does not document them further — they are the LCNC subsystem's internal vocabulary, not user-facing surfaces.

> **Status:** verified-live — routes claimed in `KanbanModule.kt:210-254`.
