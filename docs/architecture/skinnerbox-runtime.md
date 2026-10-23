# Skinnerbox namespace runtime decisions

`MeshNode` composes the enrolled peer transport, the namespace membership cell,
and the durable persistence owner. A configured cell names one executor and a
`Twin<MeshPeer>` of replicas in three different configured failure domains.
The three members retain the immutable outcome; the operation is executed once
by the configured owner. Failure-domain labels require deployment verification.

| Decision | Trigger, behavior and limit |
| --- | --- |
| Validate before ownership | Peer identities, membership enrollment and durable storage are checked before a node creates its supervisor. A failed exchange constructor cancels the newly created supervisor. Invalid configuration cannot leave a live owner attached to its parent. |
| Fixed durable membership epoch | Before application reads or writes, local lookup and every retained publication, the owner submits internal `cell.bind {epoch}` to persistence. A different durable epoch returns typed `CONFLICT`, including after restart. `cell.bind` is absent from the public operation allowlist. There is no automatic replacement, leader election or consensus claim. |
| Bounded public ingress | One node worker owns up to 16 queued requests. Cancellation of a waiting caller does not undo an admitted mutation. The peer transport has separately bounded execution and control lanes; local retained publications remain under the persistence owner. |
| Execute at the configured primary | A gateway sends a signed `mesh.execute` carrying membership epoch and the immutable application operation. Domain failures travel in the signed successful protocol response as a typed reason; a remote revision conflict remains `CONFLICT` at the gateway and HTTP 409 at the data API. |
| Three verified durable custodians | The primary retains the membership record and an immutable packet containing epoch, namespace, the durable event request digest and the durable event. Both replicas sync that same packet before signing a fresh custody proof. Three distinct cell members must authenticate the same record and epoch, and the primary must durably ACK its pending outbox before the gateway reports completion. The gateway recomputes the retained record from the returned event. |
| Stable retry across gateways | The packet uses the author and request digest recorded in the durable event, so a restarted primary reconstructs the identical record without the original transport task. A retry through another authorized ingress must not rewrite the original author or create different retained bytes. The application's persistent request identity and original result decide whether it is the same mutation. |
| Ordered durable outbox | Every mutation remains pending until the owner durably records a custody ACK for its event after all three proofs verify. A per-namespace mutex repairs the oldest pending events before running a new mutation, including after restart. At most three pending events are repaired per call; additional backlog refuses the new mutation and continues on its retry. Retain/find control handlers never acquire this mutation mutex. Later complete custody cannot conceal a missing predecessor. |
| Prior pending work is distinct | If older request A cannot be repaired, new request B receives `MeshPriorCustodyPending` through a signed `prior-custody-pending` response. B was not admitted. An exact retry of A instead returns its original durable result with current partial proofs. Row/object retries match the durable request digest; twin retries also bind the stored batch task and original author before application policy is reapplied. |
| Partial completion is explicit | If fewer than three proofs arrive or the durable outbox ACK fails, `MeshCustodyPending` carries the durable result and record identity. A retention-capacity or ACK failure after application commit never becomes a precommit refusal. Three proofs with an unsuccessful ACK still report pending completion. The mutation may already be readable. Retrying the same application request ID repairs custody; issuing a fresh request ID can create a different operation. No rollback is inferred from a lost connection. |
| Bounded replica retry | Only the idempotent immutable `mesh.retain` task is retried: at most three attempts per replica, deterministic full jitter keyed by peer/record/attempt, and a shared deadline of `min(4000ms, taskTimeout/2)`. An individual wait gets one third of that budget. The exchange owns admitted requests independently, so a timed-out waiter can rejoin the same in-flight task; there is no unbounded retry fanout. |
| Authenticated Kademlia lookup | `mesh.lookup {key}` performs signed `mesh.find {epoch,key}` requests. Each of at most three rounds queries at most three unvisited peers in unsigned SHA-256 XOR order. Responses bind epoch/key and contain at most 20 IDs. Only pre-enrolled identities and origins in the concentric namespace scope can be contacted; a remote result never enrolls a URL or key. The whole lookup shares the bounded protocol deadline. |
| Honest lookup result | Lookup returns `{epoch,key,peers}` containing responsive registered candidates ordered by unsigned XOR distance. Unreachable nodes and untrusted references do not become successful results. The default subnet embedding has zero network fanout at depth one; network lookups use dotted scopes such as `tenant.rows`. Static enrollment limits discovery to the configured trust set; this is not an open public DHT bootstrap protocol. |
| Ordered drain | Stop listener admission first, then drain the node and exchange, then drain application and persistence owners. Accepted effects are awaited before closing their dependencies. Parent cancellation is an exceptional stop, not a report of successful custody. |

The JVM integration test opens three real common-NIO HTTP listeners and uses
userspace HTX for every peer hop. Each peer owns a different durable Couch/CAS
directory. It exercises remote REST ingress, reads, retry through another
gateway, typed conflict propagation, signed lookup, an actually stopped peer,
two-of-three pending custody, a successor refused until its predecessor is repaired,
primary reopen with that durable pending outbox, repair with the same request, and a
primary restart that refuses a changed epoch. It also reads the retained packet
from all three stores and confirms identical content identity. A separate real-peer
regression caps primary records immediately after event commit and immediately
before custody ACK, verifies the original result survives both failures, blocks
successors, then reopens with capacity and repairs the same request. These checks do
not establish cross-host failure independence, hosting prices, native kernel
uring availability or physical cache locality.

Automatic membership handoff and promotion of a replica to a different writer
are unsupported. [Offline snapshot restoration](skinnerbox-recovery.md) rebuilds
the original owner's application indexes and retry receipts in a fresh store,
under the same membership epoch and through an explicit verified cutoff. A
different owner still needs a separately specified migration that verifies
retained history and atomically publishes the new membership.
