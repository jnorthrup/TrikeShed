# Unmerged Jules Branches — Octopus Merge Task Trees

**10 unmerged branches** → 2 octopus merges (8 + 2)

---

## BATCH 1: Octopus Merge #1 (8 branches)

```bash
git merge \
  jules-10202803023969344265-4e55e8fb \
  jules-10615492983242580647-f9e18600 \
  jules-15122543767313606929-b01333ce \
  jules-2915443630466069339-d092a4fd \
  jules-couchdb-1.7-graalvm-ipfs-12607174353741030785 \
  jules-mutation-test-consolidation-13311535945178380322 \
  jules-ngsctp-interop-plan-16483552040203607726 \
  jules-nio-uring-dht-transport-16256141276505889727
```

### Branch 1: `jules-10202803023969344265-4e55e8fb` — LCNC WAL + Couch ISAM
**Task Tree:**
```
OCTO-01-LCNC-WAL
├── 01: Merge couch/isam/ (ConfixWal, Stringpool, ConfixIsamFactory, ConfixIsamIsomorphism)
├── 02: Merge couch/CouchReportReactorElement.kt
├── 03: Merge context/AsyncContextElement.kt + sctp/SctpElement.kt
├── 04: Merge lcnc/isam/ForgeTaxonomy.kt + lcnc/ccek/IngestStateElement.kt
├── 05: Merge lcnc/collections/associative/LcncAssociative.kt
├── 06: Merge common/CSVUtil.kt + common/FileBuffer.kt
├── 07: Merge cli/htx/HtxAria2Cli.kt + HtxAria2CliArgs.kt + HtxAria2Engine.kt
├── 08: Merge forge/net/kanban/ForgeKanbanConduit.kt + ForgeKanbanSignalSink.kt
├── 09: Conflict resolution: FileBuffer.kt (duplicate with jules-series-buffer-*)
├── 10: Conflict resolution: AsyncContextElement.kt (duplicate with jules-wire-codec-rename-*)
├── 11: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*Couch*"
└── 12: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*Lcnc*"
```

### Branch 2: `jules-10615492983242580647-f9e18600` — OpenAPI → Confix Port (PR #76)
**Task Tree:**
```
OCTO-02-OPENAPI-CONFIX
├── 01: Merge reactor/openapi/OpenApiCallPipeline.kt
├── 02: Merge reactor/openapi/OpenApiRawParser.kt
├── 03: Merge reactor/openapi/OpenApiReactorModel.kt
├── 04: Merge reactor/openapi/OpenApiReactorResolver.kt
├── 05: Merge commonTest/openapi/OpenApiRawParserTest.kt
├── 06: Conflict: build.gradle.kts (OpenAPI deps vs root-only)
├── 07: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*OpenApi*"
└── 08: Verify: no libs/ references in generated code
```

### Branch 3: `jules-15122543767313606929-b01333ce` — lib_cursor Refactor
**Task Tree:**
```
OCTO-03-LIB-CURSOR
├── 01: Audit: cursor/ lazy α projection changes (Map → Series)
├── 02: Merge: cursor/ alpha projection refactor
├── 03: Conflict: commonMain/kotlin/borg/trikeshed/cursor/*.kt
├── 04: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*Cursor*"
└── 05: Verify: no Map materialization in hot paths
```

### Branch 4: `jules-2915443630466069339-d092a4fd` — Aria2/RPC Mapping
**Task Tree:**
```
OCTO-04-ARIA2-RPC
├── 01: Merge: cli/htx/ Aria2 RPC mappings
├── 02: Conflict: cli/htx/ (overlaps OCTO-01)
├── 03: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*Aria2*"
└── 04: Verify: ngSCTP restored, QUIC rejected
```

### Branch 5: `jules-couchdb-1.7-graalvm-ipfs-12607174353741030785` — CouchDB 1.7 Parity
**Task Tree:**
```
OCTO-05-COUCHDB-17
├── 01: Merge: couch/ GraalVM + IPFS integration
├── 02: Conflict: couch/ (overlaps OCTO-01)
├── 03: REJECT: any JNA/JNI deps (root has none)
├── 04: REJECT: IPFS libs/ reintroduction
├── 05: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*Couch*"
└── 06: Verify: GraalVM CE 25.0.2 only
```

### Branch 6: `jules-mutation-test-consolidation-13311535945178380322` — PointcutMutableSeries Consolidation
**Task Tree:**
```
OCTO-06-MUTATION-CONSOLIDATION
├── 01: Merge: mutable/ PointcutMutableSeriesTest consolidation
├── 02: Delete: stale test files (ReduxListBridgeTest, PointcutMutableSeriesTest, etc.)
├── 03: Conflict: mutable/ package structure
├── 04: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*MutableSeries*"
└── 05: Verify: single canonical MutableSeries impl
```

### Branch 7: `jules-ngsctp-interop-plan-16483552040203607726` — ngSCTP Interop Plan
**Task Tree:**
```
OCTO-07-NGSCTP-PLAN
├── 01: Merge: docs/plans/ ngSCTP interop plan
├── 02: Merge: userspace/network/ SCTP type fixes
├── 03: Verify: no QUIC revival
├── 04: Verify: kmpngsctp (liburing + custom SCTP) is transport path
└── 05: Test: ./gradlew :compileKotlinJvm --tests "*Sctp*"
```

### Branch 8: `jules-nio-uring-dht-transport-16256141276505889727` — NioUringDhtTransport
**Task Tree:**
```
OCTO-08-NIO-URING-DHT
├── 01: Merge: userspace/nio/ NioUringDhtTransport.kt
├── 02: Merge: userspace/nio/ tests
├── 03: Conflict: linuxMain/ liburing cinterop
├── 04: Verify: focusedTransportSlice gate works
├── 05: Test: ./gradlew :compileKotlinLinuxX64 :linuxX64Test --tests "*NioUringDht*"
└── 06: Verify: no libs/ipfs code rehomed
```

---

## BATCH 2: Octopus Merge #2 (2 branches)

```bash
git merge \
  jules-j01-series-buffer-fresh-14840399884225250297 \
  jules-series-buffer-5441988913878849229 \
  jules-wire-codec-rename-11567899858115704036
```

### Branch 9: `jules-j01-series-buffer-fresh-14840399884225250297` — J01 SeriesBuffer (PR #78)
**Task Tree:**
```
OCTO-09-J01-SERIES-BUFFER
├── 01: Merge: lib/SeriesBuffer.kt (canonical location)
├── 02: Merge: parse/kursive/legacy/Jursive.kt (uses SeriesBuffer)
├── 03: Merge: commonTest/lib/SeriesBufferTest.kt
├── 04: Conflict: FileBuffer.kt vs SeriesBuffer.kt naming
├── 05: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*SeriesBuffer*"
└── 06: Verify: J01 algebra laws (append order, capacity growth, snapshot isolation)
```

### Branch 10: `jules-series-buffer-5441988913878849229` — SeriesBuffer Extraction
**Task Tree:**
```
OCTO-10-SERIES-BUFFER-EXTRACT
├── 01: Merge: lib/SeriesBuffer.kt (duplicate of OCTO-09)
├── 02: Merge: Jursive.kt refactor
├── 03: CONFLICT RESOLUTION: deduplicate SeriesBuffer.kt (keep OCTO-09 version)
├── 04: Test: ./gradlew :compileKotlinJvm :jvmTest --tests "*SeriesBuffer*"
└── 05: Verify: single canonical SeriesBuffer in lib/
```

### Branch 11: `jules-wire-codec-rename-11567899858115704036` — Wire Endian Rename
**Task Tree:**
```
OCTO-11-WIRE-CODEC-RENAME
├── 01: Merge: network-endian → wire-endian rename across codebase
├── 02: Conflict: AsyncContextElement.kt (overlaps OCTO-01, OCTO-03)
├── 03: Conflict: FileBuffer.kt (overlaps OCTO-01, OCTO-09)
├── 04: Grep: verify no "network-endian" remains
├── 05: Test: ./gradlew :compileKotlinJvm :jvmTest
└── 06: Verify: consistent wire-endian naming
```

---

## Execution Order

```bash
# 1. Stash any local changes
git stash

# 2. Batch 1: 8-way octopus
git merge --no-ff \
  jules-10202803023969344265-4e55e8fb \
  jules-10615492983242580647-f9e18600 \
  jules-15122543767313606929-b01333ce \
  jules-2915443630466069339-d092a4fd \
  jules-couchdb-1.7-graalvm-ipfs-12607174353741030785 \
  jules-mutation-test-consolidation-13311535945178380322 \
  jules-ngsctp-interop-plan-16483552040203607726 \
  jules-nio-uring-dht-transport-16256141276505889727

# 3. Resolve conflicts per task tree above
# 4. Test batch 1
./gradlew :compileKotlinJvm :jvmTest

# 5. Batch 2: 3-way octopus (2 new + 1 PR branch)
git merge --no-ff \
  jules-j01-series-buffer-fresh-14840399884225250297 \
  jules-series-buffer-5441988913878849229 \
  jules-wire-codec-rename-11567899858115704036

# 6. Resolve conflicts (dedupe SeriesBuffer, wire-endian rename)
# 7. Full test
./gradlew :test
```

---

## Conflict Hotspots (Pre-Identified)

| File | Conflicting Branches | Resolution Strategy |
|------|---------------------|---------------------|
| `common/FileBuffer.kt` | OCTO-01, OCTO-03, OCTO-09, OCTO-10, OCTO-11 | Keep root version; apply wire-endian rename |
| `context/AsyncContextElement.kt` | OCTO-01, OCTO-11 | Keep root; apply wire-endian rename |
| `lib/SeriesBuffer.kt` | OCTO-09, OCTO-10 | Keep OCTO-09 (PR #78); discard OCTO-10 duplicate |
| `cli/htx/*.kt` | OCTO-01, OCTO-04 | Union of Aria2 CLI + Engine |
| `couch/*.kt` | OCTO-01, OCTO-05 | Keep OCTO-01 WAL/ISAM; reject OCTO-05 IPFS/JNA |
| `cursor/*.kt` | OCTO-03, OCTO-11 | Apply wire-endian rename to cursor types |