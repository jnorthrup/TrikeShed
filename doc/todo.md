# TrikeShed Local-First Reactor / litebike Taxonomy Integration

This is the architectural worklog and task queue for dividing the TrikeShed
KMP targets into inheritance-based domains around a shared, addressable reactor
blackboard. It preserves the `Join`/`Series`/`Cursor` algebra in `commonMain`
and adapts the `../litebike/` taxonomy into the TrikeShed source tree.

## Gating substrate and trust actions (must land before feature expansion)

> **Read this before opening or merging any Jules session.**
> The positioning paper (`/tmp/forge_positioning_paper.agent.final/forge_positioning_paper.agent.final.md` §7.3) names three trust actions that gate every market pair in §5. Two substrate gates precede them: one Confix serialization/CBOR path and the upstream ngSCTP transport. LLM sessions that keep cranking out feature-local codecs, transports, or windows while these five sit open are procrastinating on the substrate and trust surfaces the early adopters inspect first — that is the diagnosed behavior this section exists to foreclose.
>
> **Priority rule:** a Jules session that closes an unchecked gate below outranks every T1–T29 / T-KANBAN-* / T-RESUME-* / T-CAS-* feature task. Gates are not optional and not "after the next merge." No new serializer, CBOR implementation, or SCTP implementation may be invented beside the named canonical paths.

The first two gates establish runtime truth. The final three are **market actions, not engineering chores**. All five are owned by the sole maintainer plus the Jules sessions already in the loop — no hiring, no procurement, no roadmap.

- [ ] **GATE-CONFIX-CBOR (DECOMPOSED → CBOR-1..6). One portable serializer and one canonical CBOR path**
  - Contract: `KSerializer<T> ↔ Confix Encoder/Decoder ↔ ConfixDoc/RowVec ↔ JSON/YAML/CBOR bytes`. Confix is the `SerialFormat`; Kotlin serialization supplies generated serializers and the base `kotlinx-serialization-core` library only.
  - Classpath invariant: beside the Kotlin serialization base/core library, Confix is the only serialization format. No `kotlinx-serialization-json`, `kotlinx-serialization-cbor`, protobuf, or properties runtime may remain on a product runtime classpath or act as an intermediate DOM.
  - Current verified gap: `jvmRuntimeClasspath` contains `kotlinx-serialization-json:1.11.0`; `commonMain` contains forbidden `JsonElement`/`JsonObject`/`JsonPrimitive` references; `parse/confix/ConfixSerialization.kt` is in `jvmMain` and routes through the kotlinx JSON DOM. The existing `ConfixSerializationBoundaryTest` states the intended boundary but the tree currently violates it.
  - Canonical CBOR must be one Confix-owned RFC 8949 implementation, not `CanonicalCbor` plus an unrelated Confix scanner. Pin deterministic map ordering, definite lengths, minimal integer widths, nested arrays/maps, byte/text strings, tags, floats, null/bool, malformed/truncated rejection, and Confix `(value,key)` kid order. Live processing, CID computation, WAL replay, and cross-target decode use the same bytes and the same lowering path.
  - Evidence: boundary test scans all product source sets and resolved runtime classpaths; dependency reports show only `kotlinx-serialization-core`; RFC 8949 vectors and malformed-input tests pass; JVM/JS/Wasm/Native encode identical fixtures byte-for-byte; every encoded `ConfixDoc` decodes to the same facets and canonical re-encoding is idempotent.

- [ ] **GATE-NGSCTP (DECOMPOSED → SCTP-1..5). Finish TrikeShed ngSCTP from the KMPngSCTP README contract**
  - Donor evidence: `jnorthrup/KMPngSCTP` README and source. The README is the feature contract; the donor is not a nested project, composite build, submodule, or runtime dependency.
  - Canonical implementation lives in TrikeShed's existing `borg.trikeshed.sctp` / reactor spine. Import useful behavior instead of importing the donor build or growing a second SCTP implementation.
  - Required behavior: TLV chunks with unknown-skip, bounded/cancellable channel streams, association-owned structured concurrency, multihoming/failover, partial reliability, migration, observable control plane, and the existing liburing facade seam.
  - Constraints: current TrikeShed Kotlin 2.4.x; no Ktor, Netty, Spirit parser, duplicate protocol stack, or UDP placeholder presented as completion.
  - Evidence: two peers exchange a NUID-authorized Confix-CBOR action over loopback; failover, partial reliability, cancellation/close, and dependency-boundary tests pass.

- [ ] **GATE-LICENSE. Resolve the license contradiction** (POSITIONING PAPER §7.1.1, §7.3.1)
  - `LICENSE` is a custom "ThisIsSuperior" zlib-variant; `doc/concepts.md:25` declares "AGPLv3, do not change"; the GitHub API reports "Other." Three texts, one project — no company, NGO, or OSS distributor can adopt Forge until one OSI-approved text governs.
  - Action: choose one OSI-approved text (AGPLv3 per `concepts.md` is the project's own declaration), delete the contradiction, let the API settle.
  - Owner: maintainer (sole decision-maker). No agent session can ratify this — it is a sign-off, not a patch.
  - Unblocks: **all pairs**; P3, P5, P6 first (institutional adopters bounce off a contradictory license on page one).
  - Evidence: `LICENSE`, `doc/concepts.md`, and the GitHub API report one consistent license string.

- [x] **GATE-CLEAN-MASTER. Clean master of integrity debt and branch drift** (POSITIONING PAPER §7.1.1, §7.3.1) — DRAINED 10559702188945957626
  - The positioning-paper snapshot found nine conflict blocks in `HtmlShell.kt` and `ActionDecoder.kt`; live verification on 2026-07-20 finds no markers in either file. Keep this closed sub-finding from regressing while the remaining branch/PWA/build integrity work is completed.
  - 129 open branches, ~70 `jules-*` agent sessions, fifteen `wip` commits, one named `dirty-push-to-master`. The deployed PWA (`docs/index.html`) has drifted from master HEAD; the GitHub Pages API returns 404.
  - Actions: strip the conflict markers, triage the 129 branches, realign the deployed PWA with master HEAD.
  - Owner: maintainer with the Jules sessions that produced the debt.
  - Unblocks: P4, P7 first (infra audiences clone before they read — master fails inspection on page one, and the PWA is the first touch); then P1, P2, P6.
  - Evidence: `./gradlew build` passes on master HEAD; `git branch --list | wc -l` shows triaged count; the gh-pages HTML matches the committed shell.

- [ ] **GATE-MATURITY-MAP. Publish the one-page honest maturity map** (POSITIONING PAPER §7.3.1, §1.3.3)
  - Distinguish shipped subsystems from adapters-without-production-legs, codecs-without-sockets, and aspirational specs. PWA/litebike/Kanban-daemon/CAS are shipped. TrikeShed SCTP is an adapter awaiting GATE-NGSCTP-UPSTREAM, not the canonical implementation; HTTP3/LCNC remain codec/contract surfaces; tunnels/Creeper-Node/UX-metrics are aspirational-spec. The performance creed (`doc/taste.md`) is aspiration, not telemetry — zero UX-level numbers are published.
  - Action: land that table as a repo-resident page (e.g. `doc/maturity-map.md`) so the P1–P4 audiences who detect oversold infrastructure on sight find the disclosure *before* they find the claims.
  - Owner: maintainer.
  - Unblocks: P1–P4 (the trust-verifiers). Pre-empts the oversold-infrastructure verdict, which for these audiences is the same thing as arriving credible.
  - Evidence: `doc/maturity-map.md` exists, is linked from the README, and names each shipped/codec/aspirational row with its repo path.

### Reading order for the gates

Read by dependency, the sequence is: **GATE-CONFIX-CBOR → GATE-NGSCTP-UPSTREAM**, because the transport carries the canonical document/action bytes; then **GATE-LICENSE** before institutional conversations, **GATE-CLEAN-MASTER** before clone-first audiences, and **GATE-MATURITY-MAP** before trust-verifiers. Feature work fans out only after the substrate gate it depends on is closed.

---

## Core hierarchy (non-designated NUID -> subnets -> workgroups -> capabilities)

```
NUID = capability + nonce + subnet
    |
    +-- subnet  (e.g., local, lan.localhost, mesh.worker.<id>, global.relay)
    |       |
    |       +-- workgroup  (a set of workers registered on a reactor slot)
    |               |
    |               +-- capability  (process, CAS, wireproto, sctp, mesh, modelmux, ...)
```

Capabilities are traits. Workgroups advertise a `TraitSpace`. Subnets scope
where a NUID is valid. The undesignated random nonce makes each NUID a bearer
token, not an identity.

## DRY chokepoints (must remain thin and stable)

1. `Reactor` — `WamBlock`, `SessionState`, `TransformCode`, `Protocol` from litebike.
2. `Nuid` — `Join<Capability, Join<Nonce, Subnet>>` authorization context.
3. `Volume` — `BlockArray` + `BootBlock` block storage surface.
4. `ReactorEndpoint` — `ReactorAction`/`ReactorResult` request/response algebra.

All higher layers (CAS, wireproto, mesh, modelmux, litebike gates) must use
these interfaces. No platform IO leaks into `commonMain`.

## Platform targets

- `commonMain` — algebra and shared interfaces only.
- `jvmMain`/`nativeMain` — real Btrfs/JBOD userspace, io_uring, posix sockets.
- `jsMain` — Node localhost proxy and browser PWA runtime.
- `wasmJsMain` — browser PWA with localStorage/IndexedDB/OPFS backends.

## Landed work (pins — do not re-dispatch)

- [x] **T1. Reactor algebra in commonMain** (DRAINED 2026-07-20, commit 114f5314)
- [x] **T2. NUID / authorization algebra in commonMain** (DRAINED 2026-07-21, commit ed8d5a79)
- [x] **T3. Volume / BlockArray / BootBlock interface in commonMain**
- [x] **T4. ReactorEndpoint / confix wire transport in commonMain** (DRAINED 2026-07-20, commit faa2619d)
- [x] **T6. Btrfs userspace JBOD backend** (DRAINED 2026-07-21, commit da20abcd)
- [x] **T7. Browser storage backend** (DRAINED 2026-07-20, commit 9f2ab178)
- [x] **T8. Node localhost proxy** (DRAINED 2026-07-21, commit ed8d5a79)
- [x] **T9a. Mesh / SCTP reactor adapter** (DRAINED 2026-07-20, commit 19a84b2d)
- [x] **T10. litebike gate / tunnel adaptation** (DRAINED 2026-07-21, PR #241, commit c7cd42059)
- [x] **T11. CAS worker** (DRAINED 2026-07-20, commit 42f3b209)
- [x] **T12. Process worker** (DRAINED 2026-07-20, commit f1ee66394)
- [x] **T14. ModelMux worker** (DISPATCHED 2026-07-20, session 18443322164395743742, IN_PROGRESS)
- [x] **T24. LCNC ROLLUP reducer** (DRAINED 2026-07-20, PR #229, commit 98c2386db via a8dfb9ad2)
- [x] **T16. Define `ForgeWindowManager` SPI in commonMain** (DRAINED 2026-07-20, PR #231, commit 0ddf1ecfa via 059612622)
- [x] **T17. Move HTML shell assets into `src/commonMain/resources`** (DRAINED 2026-07-20, PR #232, commit f260bb825 via 34fb5ffc8)
- [x] **T18. Implement per-target window managers** (DRAINED 2026-07-20, session 717567726403101346)
- [x] **T19. Reposition `manimwm-tk` as a native render/composit layer**
- [x] **T20. Add missing targets to Gradle build**
- [x] **T-KANBAN-HTTP-1. `KanbanHttpServerJvm` in jvmMain — closes G01+G02+G06** (DRAINED 2026-07-21, commit da20abcd)
- [x] **T-KANBAN-WAL-7. WAL for causal chain recovery (closes G12)** (DRAINED 2026-07-21, commit 7c7ebd32d)
- [x] **T-KANBAN-PERSIST-9. Pick a persistence surface (closes G09)**

# todo — 39 landable TDD tasks for flywheel dispatch
# 80% project work (32 items) + 20% kanban evolution (7 items)
# Each task scoped for ONE Jules pass: one test file + one implementation file.
# MAX_LIVE=15 → 12 project workers : 3 kanban-evolution workers

## GATE-CONFIX-CBOR (decomposed into 6 small tasks)

- [x] **CBOR-1. Confix Cbor encoder: uint/int test vectors** — DRAINED 2199106204565555300
  Write failing tests for canonical CBOR encoding of unsigned integers (0, 1, 23, 24, 255, 65535, 4294967295) and negative integers. Test file: src/commonTest/kotlin/borg/trikeshed/parse/confix/ConfixCborIntTest.kt. Assert exact byte output per RFC 8949. Implement: ConfixCbor.encodeUInt in commonMain.

- [ ] **CBOR-2. Confix Cbor encoder: text strings and byte strings**
  Write failing tests for CBOR encoding of text strings (empty, short, long) and byte strings. Assert exact bytes including length prefixes. Test file: src/commonTest/kotlin/borg/trikeshed/parse/confix/ConfixCborStringTest.kt.

- [ ] **CBOR-3. Confix Cbor encoder: definite-length maps and arrays**
  Write failing tests for definite-length CBOR maps and arrays. Verify key ordering is canonical (length then lexicographic). Test file: src/commonTest/kotlin/borg/trikeshed/parse/confix/ConfixCborMapTest.kt.

- [ ] **CBOR-4. Confix Cbor encoder: bool, null, float**
  Write failing tests for CBOR encoding of true, false, null, and 64-bit floats including NaN. Test file: src/commonTest/kotlin/borg/trikeshed/parse/confix/ConfixCborPrimitiveTest.kt.

- [ ] **CBOR-5. Confix Cbor decoder: round-trip all types**
  Write failing tests that decode every encoded type from CBOR-1 through CBOR-4 and assert round-trip equality. Test file: src/commonTest/kotlin/borg/trikeshed/parse/confix/ConfixCborDecodeTest.kt.

- [ ] **CBOR-6. Confix Cbor: classpath boundary enforcement test**
  Write a test that scans build.gradle.kts and asserts no kotlinx-serialization-cbor or kotlinx-serialization-json dependency exists. Extend ConfixSerializationBoundaryTest with a grep for JsonElement/JsonObject in commonMain source.

## GATE-NGSCTP (decomposed into 5 small tasks)

- [ ] **SCTP-1. TLV chunk parser: unknown-skip behavior**
  Write failing tests that parse a byte stream of TLV chunks and verify unknown chunk types are skipped without error. Test file: src/commonTest/kotlin/borg/trikeshed/sctp/TlvChunkParseTest.kt.

- [ ] **SCTP-2. Bounded channel stream: enqueue/dequeue**
  Write failing tests for a bounded channel stream that enqueues and dequeues data chunks with capacity limits. Verify overflow behavior. Test file: src/commonTest/kotlin/borg/trikeshed/sctp/BoundedChannelStreamTest.kt.

- [ ] **SCTP-3. Association-owned structured concurrency scope**
  Write failing tests that an SCTP association creates and owns a CoroutineScope, and that closing the association cancels all child jobs. Test file: src/commonTest/kotlin/borg/trikeshed/sctp/AssociationScopeTest.kt.

- [ ] **SCTP-4. Partial reliability: drop oldest on capacity**
  Write failing tests that verify partial-reliability mode drops the oldest unacked chunk when the send buffer is full. Test file: src/commonTest/kotlin/borg/trikeshed/sctp/PartialReliabilityTest.kt.

- [ ] **SCTP-5. Liburing facade seam: interface contract**
  Write failing tests for the liburing facade interface (submitBatch, completeBatch) as a pure interface in commonMain. Verify the interface compiles against the existing Volume abstraction. Test file: src/commonTest/kotlin/borg/trikeshed/sctp/LiburingFacadeTest.kt.

## Zero-consumer packages (wire one real consumer each)

- [ ] **WIRE-DHT. Wire DHT routing table to the reactor mesh**
  Write a failing test that the reactor mesh queries the DHT routing table for peer discovery. Test file: src/jvmTest/kotlin/borg/trikeshed/dht/DhtRoutingIntegrationTest.kt. Implement: a real call from borg.trikeshed.reactor.mesh to borg.trikeshed.dht.routing.

- [ ] **WIRE-CRDT. Wire CRDT Pijul patch to the CAS store**
  Write a failing test that a CRDT operation produces a CAS-stored patch. Test file: src/commonTest/kotlin/borg/trikeshed/crdt/CrdtCasIntegrationTest.kt.

- [ ] **WIRE-BTRFS. Wire TinyBtrfsContract cloneRange to the CAS dedup path**
  Write a failing test that CAS dedup calls the btrfs cloneRange interface when available. Test file: src/jvmTest/kotlin/borg/trikeshed/classfile/slab/btrfs/BtrfsCasDedupTest.kt.

- [ ] **WIRE-DUCKDB. Wire MiniDuckContract query to the ISAM analytics path**
  Write a failing test that an ISAM analytics query routes through MiniDuckContract.query. Test file: src/jvmTest/kotlin/borg/trikeshed/classfile/slab/duckdb/DuckDbIsamQueryTest.kt.

- [ ] **WIRE-ANIM. Wire animation frame to the rendering window**
  Write a failing test that the window manager receives animation frame ticks. Test file: src/commonTest/kotlin/borg/trikeshed/animation/AnimationFrameIntegrationTest.kt.

- [ ] **WIRE-FLAGS. Wire flag system to the reactor configuration**
  Write a failing test that reactor reads feature flags from the flag system at startup. Test file: src/commonTest/kotlin/borg/trikeshed/flags/FlagReactorIntegrationTest.kt.

## TODO() stubs to fill (top contributors, on the path to gates)

- [ ] **STUB-CCEK-1. Fill convertRequestToCursor in CcekChoreography**
  Write a failing test that convertRequestToCursor produces a valid RequestCursor from a simple request object. Test file: src/commonTest/kotlin/borg/trikeshed/cursor/CcekChoreographyTest.kt.

- [ ] **STUB-CCEK-2. Fill convertCursorToResponse in CcekChoreography**
  Write a failing test that convertCursorToResponse produces a valid response from a ResponseCursor. Same test file as STUB-CCEK-1.

- [ ] **STUB-ISAM-1. Fill ConfixIsamFactory RowVec composition**
  Write a failing test that the ISAM factory composes a RowVec from a ConfixIndexK. Test file: src/commonTest/kotlin/borg/trikeshed/couch/isam/ConfixIsamFactoryTest.kt.

- [ ] **STUB-ISAM-2. Fill inferIsamSchemaFromConfixIndex**
  Write a failing test that inferIsamSchemaFromConfixIndex reads ConfixIndexK tags/spans and emits RecordMeta. Same test file as STUB-ISAM-1.

- [ ] **STUB-BLACKBOARD-1. Fill BlackboardDagFabric.create()**
  Write a failing test that BlackboardFabric.create() returns a non-null fabric with a valid boardId. Test file: src/commonTest/kotlin/borg/trikeshed/dag/BlackboardDagFabricCreateTest.kt.

- [ ] **STUB-BLACKBOARD-2. Fill TODO_subscribe and TODO_getEvents**
  Write failing tests that subscribe registers a handler and getEvents returns events in coordinate range. Same test file.

## LCNC remaining reducers

- [ ] **LCNC-RELATION. LCNC RELATION traversal reducer**
  Write failing tests that a RELATION property resolves a foreign key to a linked entity. Test file: src/commonTest/kotlin/borg/trikeshed/context/lcnc/RelationReducerTest.kt. Implement RelationReducer in commonMain.

- [ ] **LCNC-PEOPLE. LCNC PEOPLE typed property**
  Write failing tests that a PEOPLE property stores and validates a list of user references. Test file: src/commonTest/kotlin/borg/trikeshed/context/lcnc/PeoplePropertyTest.kt.

- [ ] **LCNC-FILES. LCNC FILES typed property**
  Write failing tests that a FILES property stores and validates file attachment references. Test file: src/commonTest/kotlin/borg/trikeshed/context/lcnc/FilesPropertyTest.kt.

- [ ] **T28. Split lcnc/reduction/* into its own package**
  Move reduction classes from borg.trikeshed.context.lcnc to borg.trikeshed.lcnc.reduction. Write a test that verifies the package boundary (no reduction imports from context). Test file: src/commonTest/kotlin/borg/trikeshed/lcnc/PackageBoundaryTest.kt.

## Build / platform

- [ ] **BUILD-LINUX-POSIX. Add PosixProcessOperations to linuxMain**
  Copy the existing macosMain PosixProcessOperations implementation to linuxMain. Write a test that verifies the file exists in the linux source set. Test file: src/jvmTest/kotlin/borg/trikeshed/platform/LinuxPosixProcessTest.kt.

- [ ] **BUILD-KMP-CURSOR. Fix Series index/size import shadow**
  Write a failing test that cursor[i] and cursor.size resolve to borg.trikeshed.lib operators, not stdlib. Test file: src/commonTest/kotlin/borg/trikeshed/lib/SeriesImportShadowTest.kt.

## Kanban evolution (20% — 7 items)

- [ ] **KAN-EVOLVE-1. Snapshot script reads todo/dont-redo, not doc/todo.md**
  Modify snapshot.sh to read the flat todo file (39 items) and dont-redo file instead of the 787-line doc/todo.md. Verify the snapshot output is under 4KB.

- [ ] **KAN-EVOLVE-2. Flywheel marks todo items done on land**
  When a patch lands, move the matching todo item to dont-redo. Write a test that verifies the item moves. Test file: agent-flywheel/test_flywheel.py.

- [ ] **KAN-EVOLVE-3. Task dedup uses dont-redo fingerprints**
  Cross-check proposed tasks against dont-redo file before dispatch. Reject any task whose title matches a dont-redo entry. Test in test_flywheel.py.

- [ ] **KAN-EVOLVE-4. ANSWERER brain reads project conventions from a file**
  Load project conventions from doc/conventions.md instead of hardcoding in the ANSWERER prompt. Write a test that verifies the conventions file is loaded.

- [ ] **KAN-EVOLVE-5. Decomposer brain role: slice big tasks**
  Replace RESEARCHER/TRIAGER with one DECOMPOSER that reads todo and slices items into Jules-sized specs. Test that the decomposer produces tasks under 300 chars.

- [ ] **KAN-EVOLVE-6. Flywheel reports landed count from git, not counter**
  Verify landed count by counting merge commits in git log since dont-redo was last modified. Test in test_flywheel.py.

- [ ] **KAN-EVOLVE-7. State.json records todo/dont-redo checksum**
  Persist a checksum of todo and dont-redo in state.json. If either changes, reset the dedup ledger. Test in test_flywheel.py.
