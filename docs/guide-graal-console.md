# Graal Console Guide

The Graal console is the web console and API surface for a running daemon.
It combines a PWA dashboard (`/graal`), a Couch-CRUD companion (`/futon`), and
a set of JSON/streaming API routes for JVM vitals, terrain mapping, CAS linking,
AOT metadata, and the flourish event feed.

> **Status:** verified-live — the console page, vitals, map, pointcuts, SSE feed,
> and AOT routes are exercised on standard daemon boots.

## Launch Prerequisite

The daemon must be running. Follow the
[Daemon Launch Guide](guide-daemon-launch.md) before proceeding.

```bash
# Quick check that the daemon is alive:
curl -s http://localhost:8888/api/board | head -c 200
```

## Route Reference

All routes are served on the same port as the rest of the daemon (default 8888).

### Console & Assets

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/graal` | verified-live | Console page (HTML PWA). The primary dashboard. |
| GET | `/futon` | verified-live | Couch-CRUD companion page — plain document editing. Separate page, not a sub-route of `/graal`. |
| GET | `/graal.webmanifest` | verified-live | PWA install manifest (reuses the Forge icons). |

### JVM Vitals

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/vitals` | verified-live | JVM vitals snapshot (heap, threads, GC, uptime) plus pointcut route summary. Returns JSON: `JvmVitals.snapshot()` merged with `{"pointcuts": {"routes": N, "byFacet": {...}}}`. |
| GET | `/api/graal/heap` | verified-live | Class histogram via JMX. Returns JSON array of `{className, instances, bytes}`. Dispatches to `Dispatchers.IO` internally. |

### Terrain Map

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/map` | verified-live | The whole store as compact `[id, bytes, seq, gen, code]` rows — the RTS terrain. The console builds a prefix-tree treemap client-side. Returns `{"rows": [...], "dbs": [...], "at": <millis>}`. |

### CAS Zoom & Density

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/zoom?code=<hex\|dec>` | verified-live | Representative byte-chunk fragments per code-ring8 group. Returns `{"ring8": N, "docs": N, "representatives": [...]}`. Each representative carries a `chunkCid` (Claim Check — fetch bytes via `_cas` route). |
| GET | `/api/graal/strength?a=<cid>&b=<cid>` | verified-live | Match strength between two CIDs. Both fragments are re-ingested through `LineCas` and graded with `MatchGrade`/`LinkConfidence`/`rampScore`. Returns `{a, b, linked, partial, contentOnly, proximity, confidence, ramp, aBytes, bBytes}`. |
| GET | `/api/graal/density?path=<id>&aperture=L0..L3` | degraded | Per-region residual density at a zoom band. Reads the live `MemoryStore` index. **Returns 503 when `memoryStore` is absent** (not mounted on scratch daemon or CLI/wire-only hosts). Accepts `path` or `id` query param; `aperture` defaults to `L2`. Returns `{"regions": [...], "totals": {...}, "path": "...", "aperture": "L2"}`. |

### DAG Cross-Links

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/dag` | verified-live | High-degree hubs: blobs named by more than one document (dedup hubs). Returns `{"hubs": [{"cid": "...", "degree": N, "ids": [...]}]}`. |
| GET | `/api/graal/dag?id=<docId>` | verified-live | Cross-links for one node: every other doc sharing its blob, plus pointcut→class edges. Returns `{"node": "...", "cid": "...", "edges": [...]}`. Edge kinds: `shared-blob`, `pointcut-target`, `pointcut-source`. |

### Wiring Sheets

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/sheet?id=<docId>` | verified-live | Live wiring sheet for a Couch document (parsed as Confix, projected into nested `SheetRef` cells). `id=vms` returns the sub-VM sheet instead. Returns JSON array of sheet maps. Dispatches to `Dispatchers.IO`. |

### Pointcuts

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/pointcuts` | verified-live | All `pointcut/…` documents as route rows. Returns `{"routes": [{route, facet, property, value, mark, className, methodName, bci}, ...]}`. |

### Decompilation

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/decompile?source=<fqcn>` | verified-live | Source + byte-identical classpath mates, parsed by JDK 25. Requires a CAS database (returns 503 if absent). Returns projection map with `error` field on failure (404). |

### AOT Cache

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/aot` | verified-live | Process AOT flags and HotSpot cache metadata. Returns snapshot JSON. Dispatches to `Dispatchers.IO`. |
| GET | `/api/graal/aot/blob` | verified-live | Opaque HotSpot AOT archive bytes (binary). Content-Type: `application/x-java-aot-cache`. Returns 404 if no blob is available. |
| POST | `/api/graal/aot/capture` | verified-live | Land the current AOT archive into the Couch/CAS attachment plane. Returns 201 on success, 409 if already captured. Requires CAS database. |

### Corpus Ingest

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| POST | `/api/graal/ingest?name=…` | verified-live | Raw bytes → store citizen. Binary goes through Tika/Tesseract to markdown; text extraction lands as `dropzone/<name>.extract.md`; plan-shaped drops are persisted into the board. See [guide-drop-a-corpus.md](guide-drop-a-corpus.md) for the canonical reference. |

### SSE Event Feed

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/events` | verified-live | Server-Sent Events stream. Opens with `text/event-stream` headers. Events: `compile`, `deopt`, `gc`, `cpu`, `score`, `commit`, `vm`. Each event is `data: <json>\n\n`. The stream also carries scoring-session completions (`kind: "score"`) and Couch commit events (`kind: "commit"`). |

> **Note:** SSE is long-lived. The stream opens immediately and delivers the first
> event on connection. Consumers should handle reconnection with backoff.

### Capsule Routes (experimental)

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| POST | `/api/graal/capsule/spawn` | stub | Spawn a Hermes sleeve: GraalPy guest VM with its own btrfs subvolume. Body: `{"id": "optional-name"}`. Returns `{ok, id, terminal}`. |
| POST | `/api/graal/capsule/{id}/stdin` | stub | Type a line at the captured shell. Body: `{"text": "..."}`. |
| GET | `/api/graal/capsule/{id}/output` | stub | VT scrollback so far (poll, not stream). Returns `{id, alive, text}`. |
| POST | `/api/graal/capsule/{id}/kill` | stub | Interrupt + close the guest. |

### Occupy Routes (experimental)

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/graal/occupy` | stub | List occupied repos: `{"repos": [{id, path, live path count, ...}]}`. |
| POST | `/api/graal/occupy` | stub | Absorb a git repo's worktree under `repos/<id>/` and watch it live. Body: `{"path": "/path/to/repo"}`. Returns `{ok, id, prefix}`. |
| POST | `/api/graal/occupy/{id}/release` | stub | Stop watching. Already-absorbed content stays. |

## Worked Walkthrough

### 1. Open the Console

```bash
open http://localhost:8888/graal
```

The console page loads. It is a PWA — you can "Add to Home Screen" on supported
browsers via the `graal.webmanifest`.

### 2. Watch Vitals

Hit the vitals endpoint to see the JVM's instrument cluster:

```bash
curl -s http://localhost:8888/api/graal/vitals | python3 -m json.tool
```

You will see heap usage, thread count, GC stats, uptime, and a pointcut
route summary showing how many instrumented routes are live and which facets
they cover.

### 3. Read the Terrain Map

The map endpoint returns every live document as a compact row — the terrain
the console renders as a zoomable treemap:

```bash
curl -s http://localhost:8888/api/graal/map | python3 -m json.tool | head -40
```

Each row is `[id, bytes, seq, gen, code]`. The client resolves the `id`
path into prefix-tree territories and sizes each cell by `bytes`. The `code`
field carries the taxonomy signature — the same ring used by zoom and density.

### 4. Inspect DAG Cross-Links

Find the high-degree hubs (documents sharing CAS blobs):

```bash
curl -s http://localhost:8888/api/graal/dag | python3 -m json.tool | head -30
```

Pick a node from the terrain and drill into its edges:

```bash
curl -s "http://localhost:8888/api/graal/dag?id=pointcut/your.route.here" | python3 -m json.tool
```

### 5. Open the SSE Feed

```bash
curl -N http://localhost:8888/api/graal/events
```

Events arrive as they happen: `compile` and `deopt` when the JIT cycles,
`gc` on collection pauses, `cpu` on load spikes, `commit` when documents
land in the store, and `vm` for sub-VM lifecycle events. The stream stays
open until you Ctrl-C.

> **Status:** verified-live — the SSE feed is tested by the console's own
> live-wiring page and by CLI consumers.
