# Skinnerbox data ingress

This surface is part of the common-Kotlin successor in TrikeShed. Its source
requirements are Skinnerbox `main` at `bd82134f`, `twins-prod-20261022` at
`c0bc7602`, and `rental-twin-pickyourai` at `e96ab339`; their existing deployment,
customer billing, and GPU controls are not booted by the peer listener.

## Decisions

| Decision | Reason and implementation |
| --- | --- |
| API calls become ordinary `MeshTask`s | `MeshDataSurface` has only a suspend submission function. Reads and writes cross the same tenant grant, pinned owner, durable persistence, and N=3 custody path as peer calls. There is no direct database-write shortcut. |
| Tenant credentials are deployment inputs | `MeshApiKey` retains a SHA-256 digest, one namespace, and a write grant. A supplied `apikey` or Bearer token must match that grant. Supplying both requires the same token. Peer signatures do not become end-user identities. |
| Preserve exact JSON values | The Confix-backed `TwinJson` decoder checks UTF-8, duplicate keys, depth and value count while retaining exact integers and decimals. No binary64 round trip is used for a row. |
| Require explicit mutation identity | Row/object mutations need `Idempotency-Key`; replacements and deletions need `If-Match`. Twin observation admission uses `X-Request-ID` and its canonical immutable identity. Persistence commits its mutation and dedupe receipt atomically. A partial custody result returns 503 and instructs the caller to retry the same request id; it does not claim rollback. |
| Publish Twin observations through the same owner | API write grants allow submitting one canonical observation, while the deployed peer's `TwinGrant` still governs source and key admission. Source labels and measurement timestamps inside JSON are claims, not caller authentication. Reads, residuals, diagnostics and projections submit typed tasks through the pinned owner. |
| Reserve owner-maintained records | The generic row/object API rejects the node's `twin-batches` and `mesh-decisions` collections before submission; the node independently enforces this boundary for signed peers. Ordinary writes cannot inject Twin replay packets or overwrite placement decisions. |
| Preserve bounded Twin continuation | Residual export uses an empty known inventory and emits decoded observation objects within a 64 KiB source-byte budget. Reference pages expose their structured cursor. Both continuations require the returned savepoint; changed views return 409. The HTTP layer does not embed CBOR byte arrays or expose the internal custody event. |
| Keep the supported API small and exact | Existing PostgREST query planning needs a typed table catalog, while the mesh currently retains schemaless rows. The adapter provides the subset below and rejects unsupported filters, PATCH, upsert, profiles and range headers. It does not apply unacknowledged local writes through the wider SQL surface. |
| Do not recreate the old daemon | `MeshListener` is a common NIO/uring listener serving a handler. It starts no Kanban, model, rental, billing, multicast, or background provider services. |
| Bound ingress before allocation | One 8 KiB header buffer per worker; validate one Content-Length and its cap before allocating that body. Duplicate headers, transfer encoding, folded headers, control characters and ambiguous targets are rejected. One request per connection; no chunked bodies or pipelining. |
| Fixed work and queue ownership | Three framing workers consume a bounded six-socket queue, then hand complete requests to three application workers or three reserved control workers. Each execution lane has a six-request queue and an independent byte budget (default three maximum-sized envelopes, including active requests). Full admission refuses work without spawning a coroutine. Body I/O yields every 16 KiB. Nonblocking NIO readiness misses back off from 1 to 16 ms per existing worker; no per-arrival timer or task is created. This is bounded polling through the current adapter, not a claim of CPU affinity or L2 residency. |
| Replica replies must progress while applications wait | Only `mesh.retain` and `mesh.find` choose the reserved control lane; both handlers perform bounded local work and cannot await further peer RPCs. `mesh.execute` stays in the application lane. Envelope classification selects capacity, never authentication: the exchange independently verifies the original signed bytes and tenant membership before any effect. Malicious traffic still consumes bounded framing/control capacity; this is deadlock prevention, not denial-of-service immunity. |
| Restart a listener through the existing socket contract | The listener sets `SO_REUSEADDR` before bind. JVM emulation now executes the existing `URING_CMD` socket option through its POSIX syscall provider, including Darwin constant translation and errno reporting. Unsupported options remain explicit failures; the listener does not swallow socket setup errors. |
| Separate admission from completion | Drain stops accept, abandons incomplete frames, finishes admitted handlers and replies, and joins workers. Header reads and response writes have five-second budgets. Accepted task execution belongs to the mesh owner and its independent operation budgets. |
| TLS belongs to deployment | The listener binds loopback by default. An external plaintext bind requires explicit `allowExternalBind`; deploy it only behind the selected TLS endpoint. Signed envelopes authenticate peer packets but do not encrypt payloads, and API credentials must not cross a public plaintext link. |
| Cross-origin browser use is explicit | No wildcard CORS. Configured exact origins may preflight only the supported methods and headers. Origin rejection happens before task dispatch. Responses use `Cache-Control: no-store`. |

## Supported requests

All data routes authenticate a tenant API key. Row and object names are 1–128
ASCII letters, digits, dots, underscores or hyphens, with an alphanumeric first
character. A nested object path is not a valid single key. Row JSON is bounded
to 16 KiB; objects to 64 KiB. Pages request 1–64 items and may end earlier at the
persistence byte budget. Follow the returned cursor rather than assuming a full
page. A bounded scan may return an empty page with an advancing cursor and
`more=true` after tombstones or unrelated events. JSON responses remain bounded
to 128 KiB.

| Request | Behavior |
| --- | --- |
| `GET /rest/v1/<collection>?id=eq.<key>` | One row in a JSON array; optional `Accept: application/vnd.pgrst.object+json` returns the object. Revision is returned in `ETag`. |
| `GET /rest/v1/<collection>?limit=32&after=<key>` | Key-ordered page; `X-Mesh-After` and `X-Mesh-More` provide continuation. `select=*` is accepted. |
| `POST /rest/v1/<collection>` | Create one JSON object containing a string `id`; optional `If-None-Match: *`. `Prefer: return=representation` returns the created row; otherwise the reply is empty. |
| `PUT /rest/v1/<collection>?id=eq.<key>` | Replace the entire row, including matching string `id`; `If-Match` must name its current revision. This is not partial PATCH. |
| `DELETE /rest/v1/<collection>?id=eq.<key>` | Revision-checked deletion. |
| `GET /storage/v1/object/<bucket>/<key>` | Read opaque bytes; fixed `application/octet-stream`. |
| `POST`, `PUT`, `DELETE` on the object path | Create, revision-checked replacement, or revision-checked deletion. `x-upsert:true` is rejected. |
| `GET /v1/skinnerbox/changes?since=0&limit=32` | Sequence-resumable tenant change page. This is an explicit mesh polling API, not Supabase Realtime's websocket protocol. |
| `POST /v1/skinnerbox/twins` | One canonical `skinnerbox.twin/v1` JSON observation, at most 16 KiB, with `X-Request-ID`. Returns the durable batch receipt, added count and savepoint; byte-for-byte retries retain their request id. |
| `GET /v1/skinnerbox/twins/<kind>/<key>` | Winning canonical observation, content identity in `ETag`, current view in `X-Mesh-Savepoint`. Encode reserved key characters in the URL; hierarchy slashes are also accepted in the key suffix. |
| `GET /v1/skinnerbox/twin-residual?limit=32` | Bounded export with JSON `observations`, `after` CID or null, and `savepoint`. Continue with URL-encoded `after` and `savepoint`. |
| `GET /v1/skinnerbox/twin-references?limit=32` | Bounded derived reference diagnostics with `issues`, structured `after` or null, and `savepoint`. Continue by URL-encoding the returned cursor's JSON as `after`, and supplying `savepoint`. Empty pages with a cursor still require continuation. |
| `GET /v1/skinnerbox/twin-project?model=<key>&hardware=<key>&continent=<key>` | Project one explicit model/hardware/continent coordinate from retained observations and report provenance, unresolved requirements and savepoint. This query does not synthesize missing measurements. |
| `HEAD` on read routes | The same metadata and declared content length, without a response body. |

Successful mutations report `ETag`, `X-Mesh-Sequence`, and `X-Content-Id` when
the value is live. A missing precondition returns 428; stale revisions or a
reused request id with different content return 409. The retry request must
retain the original method, body, revision and idempotency key.

`custody_pending` means this mutation committed locally and still needs replica
receipts. `prior_custody_pending` means an earlier commit still needs custody, so
the new mutation was not admitted. Both return 503 with `Retry-After: 1`; retry
the same request identity after the pinned peers are reachable.

JWT verification, PostgreSQL transactions, RLS expressions, RPC execution,
arbitrary PostgREST filters, storage signing URLs and Realtime websocket parity
are not provided by this adapter. It exposes concrete row/object/change-feed
use cases over the peer persistence contract.

For example, with a deployment-provided `MESH_API_KEY` and a listener on port
8080, create a row and submit a planned Twin observation:

```sh
curl -H "apikey: $MESH_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: row-alice-1' -H 'Prefer: return=representation' \
  --data-binary '{"id":"alice","display_name":"Alice"}' \
  http://127.0.0.1:8080/rest/v1/users

curl -H "apikey: $MESH_API_KEY" -H 'Content-Type: application/json' \
  -H 'X-Request-ID: twin-alice-1' \
  --data-binary '{"at_ms":0,"fields":{"display_name":"Alice"},"key":"alice","kind":"user","source":"planned","twin":"skinnerbox.twin/v1","via":"manual"}' \
  http://127.0.0.1:8080/v1/skinnerbox/twins

curl -H "apikey: $MESH_API_KEY" \
  http://127.0.0.1:8080/v1/skinnerbox/twins/user/alice
curl -H "apikey: $MESH_API_KEY" \
  'http://127.0.0.1:8080/v1/skinnerbox/twin-residual?limit=16'
```

The observation body uses sorted JSON object keys, exact JSON numbers and no
formatting whitespace. Kotlin callers can create it with
`TwinObservation.create(...).bytes()`. Arbitrary JSON formatting is accepted for
rows; immutable Twin observations require canonical bytes. A read-only API key
can read both surfaces but cannot submit an observation.

## Listener composition

```kotlin
val route = exchange.route()
val api = MeshDataSurface(grants, node::submit)
val listener = MeshListener.open(scope, "127.0.0.1:0") { method, path, headers, body ->
    route(method, path, body, null) ?: api.handle(method, path, headers, body)
}
// listener.address is the actual bound address, including an ephemeral port.
// On shutdown: listener.drain(), then node and its owned dependencies.
```

The peer endpoint is `POST /v1/skinnerbox/exchange`, with canonical signed CBOR
and the independently enforced `MeshEnvelope.MAX_WIRE_BYTES` cap. API keys are
never accepted as peer signatures. A data credential cannot access that endpoint
without the enrolled peer's signing key.

Validation covers parser ambiguity and over-limit declarations, API tenant
isolation, exact large integers, revision/idempotency behavior, binary objects,
explicit CORS, Twin admission and continuation, real NIO binary exchange,
reserved-control progress while application workers wait, partial-frame
shutdown, admitted-reply drain, and immediate owner cancellation. Production TLS termination and live
hosting measurements remain deployment validation, not effects of these tests.
