# Skinnerbox successor

This branch distills the peer task mesh into TrikeShed common Kotlin. The old
Skinnerbox branches are requirements sources, not release dependencies. Billing,
GPU provisioning, the customer console and their deployment history stay in the
old repository; this successor handles peer tasks, digital twins and persistent
application data.

## Source decisions

| Source | Pinned revision | Requirements retained |
| --- | --- | --- |
| Skinnerbox `main` | `bd82134f6fcae377ec1d2ec569bdec855245bce1` | One authoritative twin per thing; bounded parsing; observation provenance; three distinct custody identities and failure domains; signed requests and exact signed responses; durable publication before acknowledgment |
| Skinnerbox `twins-prod-20261022` | `c0bc7602470326fb14b6b0076c7f05dc55d6573f` | Grow-only observation union; deterministic measured/curated/planned fold; first hardware-revision measurement; LineCas savepoints and residual exchange; lazy resolution |
| Skinnerbox `rental-twin-pickyourai` | `e96ab339c7a9a5ba2f74e32369f4bb4228dea2ed` | Provider/hardware cost observations, transfer and retention cost, explicit units and provenance; derived parameters rather than drifting copies |
| TrikeShed `master` | `31e5890eb1f028a61750526cb5243c6055896abd` | Join/Twin/Series algebra, Confix, LineCas/CAS, Couch durable commits, NUID concentric scopes, common NIO/uring, PostgREST query foundations |

The user explicitly selected all three Skinnerbox branches as requirements for
the successor **in TrikeShed**. This supersedes the initial assumption that the
implementation belonged in the old Rust repository.

## Decision record

These decisions are recorded before their implementations. Component records
expand the reasoning and link the concrete public contracts.

| ID | Decision | Reason and consequence |
| --- | --- | --- |
| D01 | Implement in common Kotlin on `codex/skinnerbox-mesh`, from the pinned TrikeShed baseline. | Reuse existing I/O, storage and algebra; no second Rust runtime or production deployment. |
| D02 | Three custody members means three identities and three failure domains. Execute each mutation at one owner. | N=3 is a durability minimum, not three executions of the same side effect and not a consensus claim. |
| D03 | Pin the executor and two replica members for a namespace within an explicit membership epoch. | Cost changes cannot independently move concurrent writes to different owners. Membership migration needs an explicit new placement and data handoff. |
| D04 | Require explicit, fresh integer tariffs with source twin identities. Enumerate the bounded eligible candidate set. | Report the least modeled cost within that set, not an unmeasured global cloud optimum. Missing prices are not zero. |
| D05 | Separate authorization scope, XOR distance, placement cost and failure-domain diversity. | A nearby identifier does not establish geographic locality, permission or independent durability. |
| D06 | Send an operation through one ingress owner; retain immutable evidence on its two selected peers. | Prevent all-peer payload broadcasts. Failed custody remains incomplete and retriable; local mutation alone is not N3 completion. |
| D07 | Retain observations; fold views without deleting losers. Preflight union bounds before publication. | Preserve convergence, provenance and atomic admission. A winner-only cache is not the authoritative observation set. |
| D08 | Bind observation authority to the authenticated writer's configured grants. | A peer cannot gain measured authority merely by putting `measured` in a JSON field. |
| D09 | Publish durable mutation identity and its result in one storage commit. | Lost replies and retries must not repeat effects after restart. An enqueued write is not a durable acknowledgment. |
| D10 | Use bounded channel owners and separate bounded receive/send lanes. | An outbound call waiting for an inbound response must not exhaust the receiving workers and deadlock all peers. |
| D11 | Coalesce identical in-flight work within the same authority scope; cap queues, bytes, waiters and work per stroke. | Prevent thundering herds without turning the waiter registry into an unbounded queue. |
| D12 | Use measured cache topology to size a working-set budget; yield between short strokes. | Ownership and bounded bytes reduce contention. They do not prove CPU affinity, cache residency or a measured L2 miss rate. |
| D13 | Expose a documented tenant data subset: rows, objects and resumable changes. | Supabase use cases do not imply full PostgreSQL, JWT/RLS, Realtime or Storage protocol parity. All writes use the same durable owner. |
| D14 | Preserve runtime decisions as content-addressed records with source identities and cost inputs. | A host choice and its estimated charges must remain reviewable after quotes and twins change. |
| D15 | Serialize mutations per namespace and persist a pending-custody outbox. Repair the preceding prefix before admitting another mutation. | A later successful write must not hide a missing predecessor on a recovered replica. The caller can distinguish its own partial commit from a new request that was not admitted. |
| D16 | Restore an explicit offline snapshot to a fresh original-owner store under the same epoch. | Custody must support operational recovery, while a snapshot cutoff cannot prove the absence of a later unacknowledged write. Missing predecessors fail closed; restoration does not elect a different owner. |
| D17 | Derive bounded model/hardware/continent projections from retained twins. | Memory fit and rental prices retain their source identities and planned/measured distinction. Queries do not manufacture measured observations or qualify a host for execution. |

## Work ownership

The PRELOAD implementation contract calls for 25 concrete tasks. This is the
integration map; it does not prescribe 25 modules.

| Tasks | Owner | Deliverables |
| --- | --- | --- |
| T01–T05 | Twin | Immutable canonical observations; deterministic folds; atomic residual union; source grants and references; adversarial regressions |
| T06–T10 | Persistence | Durable channel owner; tenant rows/objects; atomic request deduplication; bounded changes/retention; recovery regressions |
| T11–T15 | Transport | Key-bound peers/tasks; signed protocol; bounded full-duplex lanes; common NIO routes/ingress limits; transport regressions |
| T16–T20 | Placement | Fresh explicit tariffs; bounded exact N3 selection; concentric XOR routing; charged transfer gates; stroke/jitter bounds |
| T21–T25 | Integration | Three-source provenance; pinned-epoch runtime/custody; tenant API/listener; real network/recovery checks; runnable entry and GitHub handoff |

Component records: [twins](skinnerbox-twins.md),
[persistence](skinnerbox-persistence.md),
[transport](skinnerbox-transport.md),
[placement](skinnerbox-placement.md),
[custody](skinnerbox-custody.md),
[data API](skinnerbox-data-api.md), and
[offline recovery](skinnerbox-recovery.md).

The [runtime contract](skinnerbox-runtime.md) connects those owners. The
[configuration guide](skinnerbox-configuration.md) includes a complete local
three-process example with explicit demo tariffs and freshly generated keys.
The service uses common userspace NIO/uring and durable file CAS/Couch storage;
the JVM launcher supplies only the process and shutdown boundary.

```bash
./gradlew -PkotlinJsApps= skinnerboxCheck --console=plain --max-workers=4
./gradlew -PkotlinJsApps= skinnerbox --args='help' --console=plain
```

The empty `kotlinJsApps` property is the repository's supported choice to omit
browser bundles for this service. It does not exclude Kotlin source or tests.
`skinnerboxCheck` compiles all JVM main/test sources and runs the feature and
affected storage, hashing, routing and HTTP regressions. It is also an ordinary
part of the full JVM test source set.

The cloud activation script sets `TRIKESHED_AIRGAP=1`. For this selected test
suite, use `TRIKESHED_AIRGAP=0 ./gradlew ...`: an existing HTX timeout fixture
uses a mocked model channel and must pass that process-wide admission check.
The test does not contact a model provider. Serving the mesh needs no model
credentials or model calls.

One namespace has one pinned executor and two durable custodians. The service
does not implement automatic leader election, membership migration, PostgreSQL
transactions, JWT/RLS, or a Supabase Realtime websocket server. HTTPS peers
require a separately configured TLS endpoint; the built-in listener permits
external plain HTTP only with an explicit reverse-proxy deployment option.
No host is provisioned and no cloud tariff is assumed by starting the service.

Validated on 2026-10-23 with Linux x86_64 and GraalVM JDK 25.0.4: all JVM
main/test sources compiled, and `skinnerboxCheck` passed **193 tests** across
29 classes (100 Skinnerbox tests and 93 shared-contract tests), with no failures,
errors or skips. The Gradle launcher help command also passed.

A separate three-process CLI check exercised the documented fixture plan,
remote create/read, retry through another ingress with an unchanged revision,
conflicting retry, an actually stopped replica, refusal of a successor while
custody was pending, restart and repair, and graceful process shutdown. It then
exported four retained events from a replica, restored them into a fresh store
for the original owner, served the restored data, and verified that bounded
history repair deferred a new write until its next retry could safely admit it.
All validation servers were stopped afterward.

These checks use synthetic tariffs and three loopback processes on one machine.
They do not establish real provider prices, independent physical failure domains,
macOS execution, native kernel uring availability, or measured cache residency.
