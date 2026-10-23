# Skinnerbox mesh transport decisions

The Kotlin successor uses the existing common userspace NIO HTTP transport and
Ed25519 implementation. Skinnerbox's three source branches supply requirements;
this protocol does not import their Rust runtime or claim wire compatibility.

| Decision | Reason and implemented boundary |
| --- | --- |
| Identity is SHA-256 of an enrolled Ed25519 public key | No supplied peer name can override its signing identity. Enrollment supplies an immutable origin, failure domain and concentric scope. Failure-domain labels are configuration, not physical placement proof. |
| Fixed canonical CBOR signed domains | `skinnerbox-request/v1` and `skinnerbox-response/v1` bind source, target, namespace, verb, request content ID, time, nonce and payload. The response echoes the request nonce and identity. Decoding caps bytes, nesting and value count, consumes the entire frame and rejects noncanonical encoding. |
| Namespace authority precedes handler invocation | Both enrolled source and destination scopes must contain the task's dotted namespace. The handler receives the verified source peer and applies its own tenant/verb grants. A routing identity alone does not grant data access. |
| One content ID per immutable task | Namespace, verb and bytes determine identity. Caller-owned arrays are copied at admission boundaries. Identical concurrent tasks from the same authority share execution; different authorities never share a result. |
| Independent application and control ingress/egress lanes | `mesh.retain` and `mesh.find` reserve their own queues, flight registries, workers, replay entries and byte budget. Application execution waiting for custody cannot occupy the custody workers; a blocked ordinary RPC cannot occupy control egress. Defaults allow three workers per lane (twelve workers across four lanes), all under one parented supervisor. Control egress uses three workers so one stalled peer leaves capacity for healthy peers. |
| Bounded queues, waiters and replay retention | Overload refuses immediately instead of spawning waiting work. Compact nonce records retain the request digest through its freshness window; a changed request cannot reuse that nonce. Full responses use a separate bounded cache: completed entries with no waiters can be evicted to admit new work. An evicted duplicate receives `MeshReplayUnavailable` (HTTP 409), without handler re-execution. In-flight entries and attached waiters are never evicted. |
| Short work strokes | Before an operation, each worker reserves its request frame plus the maximum reply frame against `MeshStroke`. If that exceeds the configured cache stroke, an explicit indivisible-frame path yields before and after that one operation; it cannot promise the smaller cache budget for that frame. Otherwise the full reservation fits before the effect starts. Mutable admission state is confined behind one short mutex; handler and network awaits do not hold it. This does not guarantee OS thread affinity, cache residency or absence of L2 misses. |
| HTTP request and response byte ceilings | `MeshListener` checks ingress framing before body allocation. `HtxRequest.maxResponseBytes` bounds headers and framed response bytes before the HTTP/TLS accumulator grows. Mesh payloads are at most 128 KiB and envelopes at most 132 KiB. |
| One request attempt, no redirects | A transport failure can follow remote execution. The exchange does not replay it automatically or infer that nothing happened. The domain's persisted request/result identity controls safe recovery. |
| Accepted work survives caller disconnect | The owned workers and replay-completion tasks hold accepted work. `drain()` stops external admission and keeps every lane open for admitted handlers and their nested effects, identified by a private owner context. It closes queues only once those operations settle, then joins children. Parent cancellation explicitly fails outstanding work; it is not reported as graceful drain. |
| Domain-specific durability and N=3 | A signed generic reply establishes signer and request binding, not durable custody. The persistence handler must sync retained bytes before replying; orchestration selects and verifies three distinct identities/failure domains separately. EXECUTE is not blindly broadcast to three replicas. |

`MeshExchangeElement.dispatch` returns verified response bytes;
`dispatchReply` also exposes the signed response for domain-specific receipt
verification. `route()` exposes the binary-safe common HTTP endpoint at
`POST /v1/skinnerbox/exchange`. `MeshListener` mounts that route; `HtxKey`
provides its outbound network path. HTTPS origins are required except loopback
HTTP for local operation. External listeners require explicit TLS termination.

Default application admission is 16 queued operations per direction, three workers
per direction, 32 waiters per shared flight, and 32 full-response slots. Control
admission has three workers and eight queued operations per direction, with
sixteen full-response slots. Completed slots can be reused immediately; their
count no longer limits the number of successful operations in a freshness window.

Each class separately retains up to 4,096 compact nonce records by default
(`replayTombstones` and `controlReplayTombstones`). They cannot be evicted early:
reaching this anti-replay ceiling refuses fresh nonces until records expire.
Each admission inspects at most 64 records in that class for expiry, rotating
unexpired records; a refusal can therefore leave further expiry work for a later
request. Timestamp freshness is 30 seconds in either direction, so a valid
future-dated request can retain its nonce for up to 60 seconds after admission.
These explicit bounds are not an unlimited-throughput claim. Request execution
and RPC deadlines are separately bounded.

A duplicate whose full reply was evicted cannot execute again under that nonce.
A caller can sign a fresh nonce while preserving the domain's persisted request
ID to recover a mutation receipt; the exchange performs no automatic retry and
makes no durable exactly-once claim of its own.

Application `maxAdmittedBytes` defaults to 16 MiB; the separate
`maxControlAdmittedBytes` defaults to 4 MiB. Their total protocol reservation is
therefore bounded by 20 MiB by default, with neither class borrowing the other’s
reserve. Each unique queued/running flight reserves its request frame (egress
conservatively reserves the maximum frame) plus three maximum wire frames for
protocol and result copies. Each compact nonce record reserves 256 protocol bytes;
each cached or in-flight response reserves one maximum reply frame. Completed
responses are evictable both for slot demand and aggregate byte pressure. Admission
refuses before queueing when those reservations would exceed the budget. A
completed flight keeps its reservation until its worker finishes and its last
waiter detaches. Response eviction also requires the completion task to finish
and all waiters to detach; drain retains the reservation for detached cache
entries until their final waiter leaves. Compact records store no response bytes
or deferred result after cache eviction. This is
protocol-byte accounting, not a measurement of JVM heap, cryptographic scratch,
provider buffers or caller-owned returned copies. Listener workers bound concurrent
untrusted verification; the exchange does not spawn a task for each invalid frame.

Protocol tests exercise tampering, stale requests, scope rejection, canonical
framing, mutable input ownership, replay and nonce collisions. Lifecycle tests
exercise shared completion, caller cancellation, overload, execution deadlines
and parent cancellation, control ingress/egress under ordinary saturation,
nonce binding across admission classes, repeated reuse of completed cache slots
without time advancing, refusal to re-execute evicted nonces, compact-record
capacity and expiry, nested custody work during drain and
frames larger than the configured cache stroke. The JVM loopback test uses the common socket listener
and actual userspace HTX path. No deployed host, provider price, kernel-native
uring path or physical cache topology is inferred from those tests.
