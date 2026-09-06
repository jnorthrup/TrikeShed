# Couch Surface Guide

> **Status:** verified-live

## Overview

The Couch surface provides a CouchDB 1.6-compatible document store with CRUD operations, change tracking, and replication. Built on CouchWire (Kotlin Multiplatform), it exposes routes through CouchWireRouter (commonMain) and CouchWire (jvmMain).

## Routes

### Document CRUD

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `GET` | `/{db}/{id}` | verified-live | — | `{ "_id": "...", "_rev": "...", ... }` |
| `PUT` | `/{db}/{id}` | verified-live | `{ "_id": "...", "_rev": "...", ... }` (JSON body) | `{ "ok": true, "id": "...", "rev": "..." }` |
| `DELETE` | `/{db}/{id}` | verified-live | — | `{ "ok": true, "id": "...", "rev": "..." }` |

### All Documents

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `GET` | `/{db}/_all_docs` | verified-live | — | `{ "rows": [...] }` |

### Views

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `GET` | `/{db}/_design/{ddoc}/_view/{v}` | verified-live | Query params for view options | `{ "rows": [...] }` |

### Changes Feed

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `GET` | `/{db}/_changes` | verified-live | Query params: `?since=...&limit=...` | `{ "results": [...] }` |
| `GET` | `/{db}/_changes?feed=continuous` | degraded | Query params | Stream of JSON objects |
| `GET` | `/{db}/_changes?feed=longpoll` | verified-live | Query params | Blocks until changes arrive |

**Note:** Continuous mode is a polling loop with `interval_ms`, not true push. Longpoll mode waits for changes and returns when new data arrives or a heartbeat fires.

### Replication

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `POST` | `/{db}/_replicate` | verified-live (two daemons, 2026-09-05; see note) | `{ "source": "...", "target": "...", "continuous": false, "interval_ms": 1000, "cancel": false }` | `{ "ok": true, "history": [...] }` |
| `GET` | `/{db}/_replicate` | verified-live | — | `{ "jobs": [...] }` |

**Note:** Replication is a 1.x replicator. Interrupted replication has no automatic recovery procedure—you must restart manually.

### CAS Block Access

| Method | Path | Status | Request | Response |
|--------|------|--------|---------|----------|
| `GET` | `/_cas/{cid}` | verified-live | — | Binary content |
| `GET` | `/api/v0/block/*` | verified-live | — | Binary content (IPFS-compatible alias) |

### Known Gaps

| Issue | Status | Details |
|-------|--------|---------|
| Attachment GET + rewrite wiring | **known-bug** | Attachment handling is incomplete. Requests may succeed but attachments aren't correctly served. |
| Interrupted replication recovery | **known-bug** | No automatic recovery. If replication stops, you must restart manually. |
| Continuous replication polling | **degraded** | Uses `interval_ms` polling, not true CouchDB-style push replication. |

## Worked Walkthrough

There is **no second server and no port 5984**: CouchWire mounts ONE
`CouchDatabase` on the daemon's own HTTP tier, so the Couch surface answers on
the same port as `/api/…` (default `8888`; use your scratch port from the
launch guide). The mounted database's name is the daemon's configured db —
`trikeshed` in a default boot — and `_changes`/`_replicate` are wired for
THAT name only.

> **Status:** fixed 2026-08-29 — the wire now refuses any `/{db}/` prefix that
> is not the mounted db: `PUT /testdb/doc1` and `GET /testdb/_changes` both
> return 404, while the mounted name serves docs and `_changes` symmetrically.
> (Fell out of evicting the ddoc vhost from the app port: CouchWire answers
> only `/{db}` paths.)

### PUT a Document

```bash
curl -X PUT http://localhost:8888/trikeshed/doc1 \
  -H "Content-Type: application/json" \
  -d '{"_id": "doc1", "name": "Hello", "value": 42}'
```

Response:
```json
{"ok": true, "id": "doc1", "rev": "1-abc123"}
```

### GET It Back

```bash
curl http://localhost:8888/trikeshed/doc1
```

Response:
```json
{"_id": "doc1", "_rev": "1-abc123", "name": "Hello", "value": 42}
```

### See It in _changes

```bash
curl http://localhost:8888/trikeshed/_changes
```

Response:
```json
{"results": [{"seq": 1, "id": "doc1", "changes": [{"rev": "1-abc123"}]}]}
```

### Replicate to a Peer

Replication requires one side to be the mounted database's name and the other
to be an HTTP peer URL (`http://host:port/db`). Both db-name-only
sources/targets return 400 with `one side must be 'trikeshed', the other a
peer URL`. Pull is the preferred m2m direction (the laptop pulls the install).

```bash
# Push the local mounted DB to a remote TrikeShed/Couch-compatible peer:
curl -X POST http://localhost:8888/trikeshed/_replicate \
  -H "Content-Type: application/json" \
  -d '{"source": "trikeshed", "target": "http://remote-host:8888/trikeshed", "continuous": false, "interval_ms": 1000}'
```

Or pull from a peer:

```bash
curl -X POST http://localhost:8888/trikeshed/_replicate \
  -H "Content-Type: application/json" \
  -d '{"source": "http://remote-host:8888/trikeshed", "target": "trikeshed", "continuous": false, "interval_ms": 1000}'
```

Response:
```json
{ "ok": true, "history": [...] }
```

> **Note:** One side must be the local database name (`trikeshed`), the other
> must be an HTTP URL. Passing two local db names returns
> `{ "error": "bad_request", "reason": "one side must be 'trikeshed', the other a peer URL" }`.

## Caveats

- **Attachment handling** is incomplete. Use document metadata for now.
- **Replication** is stateless—if interrupted, you must restart from scratch.
- **Longpoll mode** is blocking. Use with timeout handling.
- **No attachment rewrite** wiring. Attachments are not reliably served.

## Replication, verified between two daemons (2026-09-05)

The rows above were written from in-process tests. On 2026-08-29 a real two-daemon run (8891/8892)
502'd on push and hung on pull; on 2026-09-05 the same experiment on two scratch daemons (8930/8934,
each with its own home, both absorbing this worktree) reproduced a deterministic 502 in both
directions: `Replication pull incomplete: 252 revision(s) not delivered; checkpoint retained`.

Root cause, not HTX: both absorbers had minted the same 252 `.git/**` attachment documents under
different revisions — identical bytes, but the attachment body carries the absorber's wall-clock
`sequence` (`CouchAttachmentGateway.putAttachment`), so content-equal documents diverge by
timestamp — and `CouchReplicator` counted a revision the 1.x winner rule declined as "not
delivered", failed the page, and never advanced the checkpoint past it. The replicator now keeps
**undelivered** (a blob that never arrived: the checkpoint holds) apart from **conflicts** (a
declined loser: reported, and the pass completes). `CouchWireSocketTest` is the checked-in form:
two `JvmKanbanServer`s on real loopback sockets, replicating through the same HTX client element
the daemon uses, asserting a document and its blob land by pull and by push, a divergent head
resolves by winner rule and is reported, and the checkpoint advances past it.

Note also that the changes feed carries `credential:*` documents (provider, base_url, and the key
itself); replication moves them to whatever peer pulls. That is a policy decision the code does
not make today.

### Push bound and long first syncs (2026-09-05)

A listener reassembles one request in memory and answers `413` then closes above its cap
(`JvmKanbanServer.maxRequestBatch`, 4 MiB on the daemon). Push sends each blob as one `POST _cas`, so a
blob above the cap broke the pipe under the HTX write — "HTX reactor write failed for fd=N", the
2026-08-29 push failure; on 2026-09-05 the first such blob was a 30.7 MiB `.git/lost-found` object at
sequence 344 of a fresh absorber. `CouchReplicator` now refuses a blob over `maxPushBlobBytes`
(4 MiB less headroom) before sending it and fails the pass naming the document, the cid and the size;
blobs that size cross by **pull**, whose `_cas` reads are bounded by the server. A chunked upload lane
is the open design item.

A first pull between two absorbers of one worktree walks ~9,000 revisions; run it with `"async": true`
and read the outcome from `GET /{db}/_replicate`, or the HTTP client times out while the daemon is still
working (the 2026-08-29 "hung pull"). The pull no longer fetches bodies for revisions the winner rule
will decline, which was most of that walk.

