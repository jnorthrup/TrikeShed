# Skinnerbox mesh persistence decisions

These decisions implement tasks T06–T10. The baseline is TrikeShed's common
Kotlin Couch/CAS commit boundary, with Skinnerbox's append-only observations
and content-addressed residual exchange informing the network contract.

| Decision | Reason and consequence |
| --- | --- |
| T06: one bounded channel owns one Couch writer and its row projections | Nonblocking admission refuses CAPACITY when full and CLOSED after drain; producers never form an unbounded suspended queue. Once admitted, work settles even if its caller cancels. Normal drain settles queued work, joins the owner, and releases the writer lease. File operations run on the worker dispatcher; no claim that fsync is a kernel io_uring operation. |
| T07: immutable mutation documents are the authoritative rows | Couch atomically publishes one document revision. Each event contains the tenant, key, expected predecessor, value bytes and content identity. Current rows are replayed projections; a second mutable row document would introduce a two-commit ambiguity. |
| T08: request identity and its mutation share that document | Retrying the same tenant/request ID returns its original receipt. Reusing that identity for different bytes refuses. An ambiguous HEAD publication poisons the writer until reopen. This is local durable deduplication, not distributed consensus or an exactly-once external side effect. |
| T09: sequence pages and content-addressed replica packets | Changes use a bounded page and committed sequence. Replica retention publishes the blob before its tenant-bound receipt, and acknowledges only after durable publication. The transport receives identifiers and self-contained immutable packets, never a mutable database copy. |
| T10: persistence owns authorization, admission and replay checks | The transport supplies a verified peer ID; explicit peer-to-tenant grants are checked before payload decoding. Restart validates event chains, object digests and receipt bindings before serving requests. |
| Cell epoch binding | The node binds its namespace to one content-addressed cell epoch before serving operations. One dedicated immutable Couch record preserves that binding across restart. Repeating it is idempotent; changing it refuses, preventing a configuration change from silently selecting a new writer. |
| Custody prefix | Every local event enters a durable pending outbox as part of the event commit. The node repairs the oldest pending event before admitting a later mutation in that namespace. Only three verified custody proofs permit a durable acknowledgment record. This prevents a recovered replica from missing an earlier mutation beneath a later successful write. |
| Explicit offline recovery | A trusted local snapshot manifest binds the original cell, packet inventory, event count and cutoff. A fresh original-owner store remains unavailable until that exact count and cutoff are imported and the completion marker is durable. Restoration does not elect another owner or infer the latest global state from one replica. |

The internal `custody.pending {}` operation returns at most one oldest event and
an indication that more remain. `custody.ack {epoch, revision}` accepts only that
oldest event, with idempotent retries after completion. These verbs are not in
the public mesh operation allowlist. The node serializes namespace mutations
while repairing this prefix; other namespaces and replica/control traffic use
their existing owners. A failed copy therefore cannot become an invisible hole
under a subsequent acknowledged mutation.

Offline inventory uses `replica.list {since?, limit?}` and `replica.read`;
inventory pages inspect at most 64 committed records. `recovery.begin` requires
a fresh destination and stores the declared manifest CID, event count, cutoff
and pinned epoch. `recovery.advance` writes at most eight explicit gap records
per call. Those records preserve original receipt sequence numbers without
pretending to reconstruct omitted global metadata or other namespaces.
`recovery.event` validates and imports one original immutable event, retaining
its content ID, request identity, sequence and predecessor. `recovery.end`
requires the declared manifest count and cutoff to be complete. Interruption
leaves a durable incomplete marker; reopening cannot silently serve the partial
state. Imported events remain pending until the original owner verifies all
three custodians before a later mutation.

Gap positions are bounded by 65,536 and consume the retained-byte budget; they
do not consume the semantic mutation/replica record quota. The explicit
recovery protocol is offline and is also absent from the public mesh allowlist.
Clients reset their global change-feed cursor after restoring a snapshot,
because a namespace-only replica cannot prove the old global cursor's metadata
tail. A snapshot with a missing predecessor is rejected; a replica cannot prove
that no later event exists elsewhere.

Complete Couch history is required on reopen. The baseline's optional recovery
from a truncated historical chain cannot preserve permanent request deduplication,
so this owner opts out of that recovery policy; missing old events refuse service.

List and change pages inspect at most 64 candidates, including tombstones or
other tenants' events. An empty page can therefore carry an advancing cursor and
`more=true`; consumers must follow the cursor, not equate an empty page with EOF.
Per-collection ordered key indexes keep list reads from sorting the global row
map on every request. A cancelled opening caller drains a constructed owner even
when its supplied parent scope remains alive; projection failures after commit
poison the owner just like an uncertain HEAD publication.

The durable constructor rejects volatile CAS/filesystem providers. A separately
named volatile constructor exists for tests and transient local use; its replies
say `durable=false`. No JWT verifier, PostgreSQL transaction, RLS engine or full
Supabase protocol compatibility is implied. The root API adapter exposes only
the supported row, object and sequence-feed use cases.

All task payloads and replies are canonical CBOR maps. Row values are bounded
strict JSON object bytes; object values are opaque bytes. The immutable event
retains the bounded value bytes so replication is self-contained. This spends
some local CAS space in exchange for a single verifiable commit packet. It does
not promise zero-copy transfers or hardware L2 residency. Default admission,
retained-byte, row, history and page limits bound the working set; historical
events and idempotency receipts are retained, with no unsafe automatic pruning.

Mutation responses report local durability. Three peer receipts establish three
retained copies only after the caller verifies distinct enrolled peers and their
failure domains. They do not establish a majority-write consensus protocol.
Cell handoff and epoch replacement are not implemented. A changed membership or
placement must not delete the binding to manufacture a successful restart; an
explicit state-transfer and fencing protocol is required. The first binding
records the operator-supplied epoch rather than inferring historical ownership.

## Task contract

`MeshTask.namespace` is the tenant. The transport passes the verified sender
separately to `execute`; a payload cannot supply its own authorization identity.
All names are 1–128 ASCII letters, digits, dots, underscores or hyphens, with an
alphanumeric first character. The mesh namespace grammar applies independently.

| Verb | Canonical CBOR request fields |
| --- | --- |
| `cell.bind` | `epoch` content ID; returns `epoch` and local `durable` flag |
| `row.put`, `object.put` | `requestId`, `collection`, `key`, `expectedRevision` (explicit null for a new key), `value` bytes |
| `row.delete`, `object.delete` | Same fields without `value`; exact current revision required |
| `row.get`, `object.get` | `collection`, `key` |
| `row.list`, `object.list` | `collection`, optional `after` key and `limit` (1–64) |
| `changes` | Optional `since` committed sequence and `limit` (1–64) |
| `event.get` | `revision` |
| `replica.retain` | `contentId`, `value` bytes whose digest must match |
| `replica.read` | `contentId`, scoped to the tenant's previously retained receipt |

Row JSON is at most 16 KiB, an object at most 64 KiB, and a retained packet at
most 96 KiB. Replies are capped at 112 KiB. Mutation replies carry `requestId`,
`revision`, `sequence`, `contentId`, `deleted`, `durable`, and self-contained
`event` bytes. The revision is the digest of those event bytes. Retain replies
carry the packet `contentId`, byte count, and local `durable` flag.

List replies carry `rows`, `after`, and `more`; each live row carries its key,
collection, revision, sequence, value content ID and bytes. Change replies carry
`changes`, `since`, and `more`; changes contain row/object identifiers and
revisions without copying value bytes. A deleted key keeps its tombstone and
predecessor revision; recreating it requires that revision, so an old create
cannot silently resurrect it. Retry the original request ID after an uncertain
publication and reopen: if it committed, the same receipt is recovered; if it
did not, the mutation is admitted against the still-current predecessor.
