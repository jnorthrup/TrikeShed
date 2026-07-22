# todo — 39 landable TDD tasks for flywheel dispatch
# 80% project work (32 items) + 20% kanban evolution (7 items)
# Each task scoped for ONE Jules pass: one test file + one implementation file.
# MAX_LIVE=15 → 12 project workers : 3 kanban-evolution workers

## GATE-CONFIX-CBOR (decomposed into 6 small tasks)

- [ ] **CBOR-1. Confix Cbor encoder: uint/int test vectors**
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
