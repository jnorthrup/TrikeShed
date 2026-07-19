# TrikeShed J01-J12 Task Tree Breakouts

Generated from PACKAGE_JOBS.md — one task tree per job for Jules dispatch.
Each job maps to a Jules session. Use these as the authoritative prompt checklist.

---

## J01 — Kernel Algebra (Distance 0, Switch: none)
**Packages:** `borg.trikeshed.lib`, `borg.trikeshed.charstr`, `borg.trikeshed.num`
**Existing interfaces:** `Join`, `Twin`, `Series`, `j`, `α`, `MutableSeries`, `CharStr`, packed twins

### Task Tree
```
J01-KERNEL-ALGEBRA
├── J01-01: Canonical constructor/import path for j, joins, Series, metadata products
│   ├── Write test: j() factory, Join.companion, Series companion methods
│   ├── Implement: single canonical import path in lib/
│   └── Verify: all upper packages consume directly (no adapters)
├── J01-02: Remove package-level semantic duplication (tests prove equivalent behavior)
│   ├── Audit: lib/, charstr/, num/ for duplicate semantics
│   ├── Write failing tests for each duplicate
│   ├── Converge implementations
│   └── Delete stale tests/types
├── J01-03: Preserve JVM auto-vectorization-friendly indexed loops & dense primitives
│   ├── Benchmark: SeriesBuffer add/remove/snapshot
│   ├── Benchmark: Join operations
│   └── Verify: no boxed primitives in hot paths
├── J01-04: Prevent platform APIs leaking into kernel types
│   ├── Scan: commonMain for platform imports
│   └── Enforce: only kotlin.stdlib, kotlinx-coroutines-core, kotlinx-datetime
├── J01-05: TDD — Algebra laws, Series bounds/index, primitive packing, source compat
│   ├── Test: Join associativity, commutativity, identity
│   ├── Test: Series bounds checking, index semantics
│   ├── Test: Twin packing round-trips
│   └── Test: CharStr encoding/decoding
└── J01-06: Seam acceptance — all upper packages consume kernel types directly
    ├── Verify: no adapter between lib/ and cursor/
    ├── Verify: no adapter between lib/ and collections/
    └── Verify: no adapter between lib/ and confix/
```

**Jules Session:** `14840399884225250297` (Completed)
**Branch:** `jules-j01-series-buffer-fresh-14840399884225250297` (PR #78)

---

## J02 — Collections and Mutation (Distance 0, Switch: none)
**Packages:** `borg.trikeshed.collections`, `borg.trikeshed.common.collections`, `borg.trikeshed.mutable`
**Existing interfaces:** `MutableSeries`, `ChunkedMutableSeries`, `RingSeries`, `JournalSeries`, associative/trie types, `FacetedRow`

### Task Tree
```
J02-COLLECTIONS-MUTATION
├── J02-01: Identify canonical vs duplicate collection packages
│   ├── Diff: collections/ vs common/collections/ vs mutable/
│   ├── Map: each type to its canonical owner
│   └── Document: convergence plan
├── J02-02: Converge without broad renames
│   ├── Move: implementation to canonical package
│   ├── Re-export: typealiases in deprecated locations
│   └── Update: all imports in repo
├── J02-03: Make snapshot vs live-view behavior explicit
│   ├── Define: Snapshotable interface with snapshot() contract
│   ├── Implement: for MutableSeries, ChunkedMutableSeries, RingSeries, JournalSeries
│   └── Test: snapshot isolation vs live mutation
├── J02-04: Mutation journals compatible with cursor & CCEK consumers
│   ├── Spec: JournalEntry format (op, index, old, new, timestamp)
│   ├── Implement: JournalSeries.journal()
│   └── Test: cursor reads see journal; CCEK replays journal
├── J02-05: Eliminate tests/types for deleted mutation strategies
│   ├── Find: excluded/disabled tests in commonTest/
│   ├── Delete: stale test files
│   └── Delete: unused types (ReduxListBridge, PointcutMutableSeries, etc.)
├── J02-06: TDD — Mutation order, snapshot isolation, ring boundaries, trie/range, facet lookup
│   ├── Test: add/remove/insert order preserved
│   ├── Test: snapshot() immune to subsequent mutations
│   ├── Test: RingSeries wrap/overwrite semantics
│   ├── Test: RadixTree/Trie range queries
│   └── Test: FacetedRow facet get/put by OpK key
└── J02-07: Efficiency — no accidental List materialization or boxed primitive hot paths
    ├── Scan: .toList(), .map{}, .filter{} in hot paths
    ├── Replace: with Series/Sequence ops
    └── Benchmark: allocation rate in mutation loops
```

**Jules Session:** `10343031295565610478` (Planning)

---

## J03 — Cursor and Confix Schema Substrate (Distance 0, Switch: none)
**Packages:** `borg.trikeshed.cursor`, `borg.trikeshed.confix`, `borg.trikeshed.parse.confix`
**Existing interfaces:** `Cursor`, `RowVec`, `ColumnMeta`, `ConfixDoc`, `ConfixIndexK`, `Spans`, `Tags`, `Depths`, `DirectChildren`, `TreeCursor`, `KeyToChild`

### Task Tree
```
J03-CURSOR-CONFIX
├── J03-01: Derive every facet and tree cursor from one scanner geometry
│   ├── Design: single-pass scanner → Spans + Tags + Depths + DirectChildren
│   ├── Implement: ConfixScanner producing FlatIndex
│   └── Derive: TreeCursor, FacetCursor from FlatIndex (no re-scan)
├── J03-02: Retain source order and inclusive span semantics
│   ├── Test: duplicate object keys → both retained with distinct spans
│   ├── Test: nested object/array paths → correct parent/child spans
│   └── Test: span inclusivity [open, close) matches source
├── J03-03: Syntax-aware scalar reification without detached DOM
│   ├── Define: ScalarReifier.visit(span, tag, depth) → IOMemento
│   ├── Implement: JSON/YAML/CBOR reifiers
│   └── Verify: no JsonElement/YamlNode/CborValue retained
├── J03-04: Project JSON Schema/RecordMeta lazily from cursor evidence
│   ├── Design: SchemaProjector.cursorToSchema(cursor) → RecordMeta
│   ├── Implement: type inference from tag sequences
│   └── Test: schema round-trip matches cursor structure
├── J03-05: Keep OpenAPI, CCEK, Forge on same schema surface
│   ├── Verify: OpenApiReactorModel uses ConfixIndexK
│   ├── Verify: CCEK FacetedSignal uses Cursor facets
│   └── Verify: Forge Kanban projection uses Cursor
├── J03-06: TDD — Duplicate keys, nested paths, format round-trips, facet/tree identity, source spans
│   ├── Test: JSON duplicate keys → both in cursor
│   ├── Test: YAML anchors/aliases resolved in spans
│   ├── Test: CBOR tag fidelity
│   ├── Test: TreeCursor navigation matches facet access
│   └── Test: span.start/end map to original source
└── J03-07: Efficiency — one scan, lazy projection, no parallel Map/JsonElement schema owner
    ├── Benchmark: scan 10MB JSON → cursor < 100ms
    ├── Verify: schema projection on-demand only
    └── Verify: no intermediate DOM allocation
```

**Jules Session:** `16753998972266025303` (Planning)

---

## J04 — Platform, Context, and Userspace NIO Substrate (Distance 0, Switch: none by default)
**Packages:** `borg.trikeshed.common`, `borg.trikeshed.context`, `borg.trikeshed.platform`, `borg.trikeshed.native`, `borg.trikeshed.runtime`, `borg.trikeshed.userspace.context`, `borg.trikeshed.userspace.nio`
**Existing interfaces:** `AsyncContextKey`, `AsyncContextElement`, `SystemOperations`, `NioSupervisor`, channel/file/process/reactor SPI, lifecycle states

### Task Tree
```
J04-PLATFORM-NIO
├── J04-01: Unify duplicate context/key semantics without forcing platform code upward
│   ├── Audit: context/ vs userspace/context/ for AsyncContextKey/Element
│   ├── Converge: single AsyncContextKey<E> hierarchy in context/
│   ├── Add: companion Key to each ConcreteElement (NioUserspaceElement, LiburingElement, etc.)
│   ├── Migrate: ctx[LegacyKey] → ctx[Element.Key]
│   └── Delete: userspace/context/AsyncContextKey.kt (sealed legacy)
├── J04-02: Keep lifecycle forward-only and service lookup identity-safe
│   ├── Verify: ElementState transitions CREATED→OPEN→ACTIVE→DRAINING→CLOSED
│   ├── Test: invalid transition throws
│   └── Test: service lookup by Key returns same instance
├── J04-03: Prove JVM/JS/Wasm/Posix implementations satisfy common contracts
│   ├── Contract: SystemOperations (file, process, network, time)
│   ├── Contract: NioSupervisor (channel, selector, timer)
│   ├── Implement: jvmMain (JDK NIO), posixMain (io_uring/epoll), jsMain (Node), wasmJsMain
│   └── Test: commonTest validates all targets
├── J04-04: Contain native interop behind existing SPI rather than source-set domain forks
│   ├── Verify: linux_uring only in linuxMain, behind ChannelImpl expect
│   ├── Verify: no cinterop in commonMain
│   └── Verify: focusedTransportSlice gate for native tests
├── J04-05: TDD — Key identity, lifecycle transitions, provider selection, file/channel parity, disabled/enabled transport switch gates
│   ├── Test: AsyncContextKey identity across platforms
│   ├── Test: Element lifecycle state machine
│   ├── Test: NioSupervisor provider selection
│   ├── Test: File/Channel operations parity
│   └── Test: focusedTransportSlice excludes/includes native targets
└── J04-06: Efficiency — Structured fanout, bounded buffers, no hidden thread pools or callback duplication
    ├── Verify: CoroutineScope structured concurrency
    ├── Verify: Channel/buffer capacities bounded
    └── Verify: no ExecutorService created outside supervisor
```

**Jules Session:** `7135063974888942038` (Planning)

---

## J05 — Structured Document Ingestion (Distance 1, Switch: none)
**Packages:** `borg.trikeshed.parse.json`, `borg.trikeshed.parse.yaml`, `borg.trikeshed.parse.csv`, `borg.trikeshed.parse.kursive`, `borg.trikeshed.parse.interop`, `borg.trikeshed.parser.simple`
**Existing interfaces:** Confix facetted cursor, parser Series inputs, CSV bitmaps, Kursive trace/evidence, descriptor fragments

### Task Tree
```
J05-STRUCTURED-INGESTION
├── J05-01: Route JSON/YAML/CBOR through Confix without duplicate mutable schema paths
│   ├── Remove: any parallel JsonElement/YamlNode schema trees
│   ├── Implement: JsonParser → ConfixScanner → FlatIndex
│   ├── Implement: YamlParser → ConfixScanner → FlatIndex
│   └── Implement: CborParser → ConfixScanner → FlatIndex
├── J05-02: Define CSV/Kursive descriptor projections into cursor metadata
│   ├── Design: CSV bitmap → ColumnMeta + Spans
│   ├── Design: Kursive trace → Cursor evidence
│   └── Test: descriptor round-trip
├── J05-03: Make malformed-input and depth behavior explicit
│   ├── Spec: maxDepth, onMalformed (strict/lenient/skip)
│   ├── Implement: in all parsers
│   └── Test: boundary conditions
├── J05-04: Remove parser demos/placeholders not testing production ingestion
│   ├── Find: demo/placeholder files in parse/
│   └── Delete: or convert to real tests
├── J05-05: TDD — Format parity, malformed boundaries, deep nesting, numeric/string fidelity, source spans
│   ├── Test: JSON/YAML/CBOR same input → isomorphic cursors
│   ├── Test: malformed input at boundaries
│   ├── Test: nesting > 1000 levels
│   ├── Test: numeric precision (int64, float64, bigint)
│   ├── Test: string escape/encoding fidelity
│   └── Test: span.start/end match source
└── J05-06: Efficiency — Index first/reify later; bounded scans; avoid whole-document maps
    ├── Verify: parser emits spans/tags directly (no intermediate Map)
    ├── Verify: lazy column access via Cursor
    └── Benchmark: 100MB file streaming parse
```

**Jules Session:** `12784702627924403935` (Planning)

---

## J06 — ISAM and Persistence Ingestion (Distance 1, Switch: none)
**Packages:** `borg.trikeshed.isam`, `borg.trikeshed.couch`
**Existing interfaces:** `RecordMeta`, `IsamOperations`, `MonoCursor`, `ConfixIsamIsomorphism`, `ConfixPersistence`, WAL

### Task Tree
```
J06-ISAM-PERSISTENCE
├── J06-01: Replace placeholder Confix→RecordMeta inference with tested facet-derived fields/types
│   ├── Design: FacetToRecordMeta converter
│   ├── Implement: Confix facet → RecordMeta.Field (name, type, nullable, array)
│   └── Test: all facet kinds map correctly
├── J06-02: Preserve one-WAL-per-table, multi-tenant, append-only semantics
│   ├── Verify: WALWriter.append(table, tenant, record)
│   ├── Verify: no in-place updates
│   └── Test: concurrent tenants isolated
├── J06-03: Keep Couch persistence as consumer of Confix/ISAM contracts, not competing document model
│   ├── Verify: Couch ISAM uses ConfixPersistence
│   ├── Verify: no separate Couch document schema
│   └── Remove: any duplicate Couch-only types
├── J06-04: Reject legacy libs/couch and libs/ipfs topology; use /tmp only for donors
│   ├── Confirm: libs/ deleted (done)
│   └── Verify: no build.gradle.kts references to libs/
├── J06-05: TDD — Schema inference, WAL replay, cursor/RecordMeta round trip, persistence durability/ordering
│   ├── Test: Confix doc → RecordMeta → ISAM write → MonoCursor read → Confix doc
│   ├── Test: WAL replay after crash
│   ├── Test: cursor metadata preserved
│   └── Test: durability (fsync) and ordering
└── J06-06: Efficiency — mmap/MemorySegment-ready layouts, lazy columns, no row-object expansion in hot paths
    ├── Design: columnar MemorySegment layout
    ├── Implement: lazy column access via MonoCursor
    └── Benchmark: scan 1M rows columnar vs row
```

**Jules Session:** `6812140497092679826` (Planning)

---

## J07 — LCNC Reduction Ingestion (Distance 1, Switch: none)
**Packages:** `borg.trikeshed.lcnc`
**Existing interfaces:** `ReductionCarrier`, `ConfixReducers`, `LcncKeyAlg`, `LcncValueAlg`, `IngestCodec`, `IngestStateElement`

### Task Tree
```
J07-LCNC-REDUCTION
├── J07-01: Replace opaque Map carriers at package seams with TrikeShed algebra where contracts permit
│   ├── Audit: all ReductionCarrier usages
│   ├── Replace: Map<String, Any> → Series<Twin<K,V>> or FacetedRow
│   └── Verify: no Map leakage across seams
├── J07-02: Keep phase/key/value reductions independent and composable
│   ├── Design: ReductionPhase enum (KEY, VALUE, AGGREGATE)
│   ├── Implement: composeReducers(phase1, phase2, ...)
│   └── Test: phase independence
├── J07-03: Connect reducers to actual ingestion emission sites
│   ├── Find: all ingestion entry points (HTX, WebSocket, file, etc.)
│   ├── Wire: reducer pipeline at each site
│   └── Test: end-to-end ingestion → reduction
├── J07-04: Make Forge taxonomy projection a downstream output, not duplicate state owner
│   ├── Verify: Forge reads from reduction output
│   ├── Remove: any Forge-local reduction state
│   └── Test: Forge projection matches reduction result
├── J07-05: TDD — Reduction laws, ordering, associativity where valid, patch cable composition, malformed carrier behavior
│   ├── Test: key reduction associativity
│   ├── Test: value reduction ordering
│   ├── Test: patch cable (phase1 → phase2) composition
│   └── Test: malformed carrier → explicit error
└── J07-06: Efficiency — No repeated tree reification; stream/facet reductions over indexed facts
    ├── Verify: reducer consumes Cursor directly
    ├── Verify: no intermediate tree build
    └── Benchmark: reduction throughput
```

**Jules Session:** `11278868115855868767` (Planning)

---

## J08 — Transport and Protocol Ingestion (Distance 2, Switch: focused transport only for native CInterop)
**Packages:** `borg.trikeshed.userspace` (excl. J04-owned NIO/context), `borg.trikeshed.reactor`, `borg.trikeshed.ws`, `borg.trikeshed.htx`
**Existing interfaces:** `StreamTransport`, `FanoutDispatcherElement`, userspace channels, TLS codec, WebSocket frames, HTX requests/reactor elements

### Task Tree
```
J08-TRANSPORT-PROTOCOL
├── J08-01: Align TLS/WebSocket/HTX framing on common byte-region/channel contracts
│   ├── Define: ByteRegion (offset, length, buffer) common type
│   ├── Implement: TlsCodec.encode/decode(ByteRegion)
│   ├── Implement: WsFrame.parse/serialize(ByteRegion)
│   └── Implement: HtxRequest/Response ↔ ByteRegion
├── J08-02: Keep SCTP direction and reject revived QUIC/demo topology
│   ├── Verify: ngsctp only (no QUIC)
│   └── Remove: any QUIC demo code
├── J08-03: Make backpressure, closure, and fanout observable through CCEK
│   ├── Emit: BackpressureSignal (highWatermark, lowWatermark)
│   ├── Emit: ChannelClosedSignal (reason)
│   ├── Emit: FanoutSignal (targetCount, successCount, failureCount)
│   └── Verify: CCEK context receives signals
├── J08-04: Prove platform adapters preserve common ordering and cancellation semantics
│   ├── Test: message order preserved across adapters
│   ├── Test: cancellation propagates to transport
│   └── Test: structured concurrency (no orphaned tasks)
├── J08-05: TDD — Framing, partial reads, cancellation, fanout, lifecycle, focused native switch states
│   ├── Test: partial TLS record read
│   ├── Test: WebSocket fragmented frame reassembly
│   ├── Test: HTX request/response correlation
│   ├── Test: fanout to N targets
│   ├── Test: lifecycle OPEN→ACTIVE→DRAINING→CLOSED
│   └── Test: focusedTransportSlice gates native tests
└── J08-06: Efficiency — Zero/low-copy slices, bounded queues, structured concurrency
    ├── Verify: ByteRegion zero-copy
    ├── Verify: channel capacities bounded
    └── Verify: coroutineScope/SupervisorJob for fanout
```

**Jules Session:** `4044040330023918642` (Planning)

---

## J09 — Distributed Identity and Routing (Distance 2, Switch: none)
**Packages:** `borg.trikeshed.dht`, `gk.kademlia`
**Existing interfaces:** `NUID`, primitive `BitOps`, `RoutingTable`, DHT agents/events

### Task Tree
```
J09-DHT-ROUTING
├── J09-01: Resolve duplicate borg.trikeshed.dht versus gk.kademlia ownership by usages and tests
│   ├── Audit: all imports of dht/ and kademlia/
│   ├── Map: each type to owning package
│   └── Consolidate: move to single owner
├── J09-02: Retain primitive-specialized distance operations
│   ├── Verify: XOR distance on UInt/ULong/UInt256
│   ├── Verify: no BigInteger fallback
│   └── Benchmark: distance ops
├── J09-03: Connect routing events to real transport emission sites
│   ├── Find: RoutingTable.update/evict call sites
│   ├── Emit: RoutingEvent via CCEK
│   └── Wire: transport sends RoutingEvent
├── J09-04: Do not rehome deleted libs/ipfs code directly; assess donors only in /tmp
│   ├── Confirm: libs/ipfs deleted
│   └── Document: any donor code extracted to /tmp
├── J09-05: TDD — XOR distance/order, bucket boundaries, identity serialization, route update/eviction
│   ├── Test: XOR distance metric properties
│   ├── Test: k-bucket split/merge boundaries
│   ├── Test: NUID serialization round-trip
│   ├── Test: route update on contact
│   └── Test: eviction policy (LRU, latency)
└── J09-06: Efficiency — Primitive ops, no BigInteger fallback on fixed-width IDs, bounded routing scans
    ├── Verify: BitOps uses intrinsics
    ├── Verify: routing table scan bounded by k
    └── Benchmark: 10k node routing
```

**Jules Session:** `4256545542739399138` (Planning)

---

## J10 — External Model/API Ingress (Distance 2, Switch: nodejs for concrete Node APIs only)
**Packages:** `keymux`, `modelmux`, `modelmux.acp`, `borg.trikeshed.jules.client`, `borg.trikeshed.reactor.openapi`
**Existing interfaces:** `KeyMux`, `ModelMux`, `AcpProtocol`, Jules eventual-delivery client/FSM, Confix schema facts

### Task Tree
```
J10-EXTERNAL-API-INGRESS
├── J10-01: Introduce smallest root-only Confix-first OpenAPI parser/resolver/stream slice
│   ├── Design: OpenApiSpec → ConfixDoc (lazy)
│   ├── Implement: parser reads spec → emits Confix FlatIndex
│   ├── Defer: code generation until root target-package DSL stable
│   └── Keep: Node transport as implementation seam, not protocol owner
├── J10-02: Keep Jules eventual-delivery and non-blocking; no polling loop in orchestrator-facing code
│   ├── Verify: JulesAgent FSM non-blocking
│   ├── Verify: jules-bijective-sync single-shot
│   └── Remove: any polling loops
├── J10-03: Make Node transport an implementation seam, not the protocol owner
│   ├── Define: HttpTransport interface (commonMain)
│   ├── Implement: NodeHttpTransport (jsMain, Node fetch)
│   ├── Implement: JvmHttpTransport (jvmMain, JDK HttpClient)
│   └── Verify: protocol logic in commonMain
├── J10-04: Remove hardcoded provider/package roots and libs-relative fixtures
│   ├── Scan: hardcoded "libs/", "borg.trikeshed.hermes.tool"
│   └── Replace: with root-relative or config-driven
├── J10-05: TDD — OpenAPI JSON/YAML parity, operation/schema resolution, ACP framing, Jules FSM transitions, Node/common interface parity
│   ├── Test: OpenAPI JSON ↔ YAML → same ConfixDoc
│   ├── Test: operation resolution by path+method
│   ├── Test: schema resolution by ref
│   ├── Test: ACP frame encode/decode
│   ├── Test: JulesAgent FSM transitions
│   └── Test: NodeHttpTransport ≈ JvmHttpTransport
└── J10-06: Efficiency — Stream operations, injected dispatch context, no eager detached OpenAPI DOM
    ├── Verify: parser streams tokens → Confix (no full DOM)
    ├── Verify: dispatch context injected
    └── Benchmark: 10k operation spec parse
```

**Jules Session:** `5512307911648352632` (Planning)

---

## J11 — Compute, Classfile, Panama, and Graal Seam (Distance 2, Switch: graal)
**Packages:** `borg.trikeshed.classfile`, `borg.trikeshed.pointcut`, `borg.trikeshed.graal`, `borg.trikeshed.panama`, `borg.trikeshed.mlir`, `borg.trikeshed.manifold`, `borg.trikeshed.indicator`
**Existing interfaces:** Java 25 public ClassFile API, `PointcutReporter`, `ConfixBlackboard`, Polyglot CCEK bridge, Panama/MLIR/tensor contracts

### Task Tree
```
J11-COMPUTE-GRAAL
├── J11-01: Use installed GraalVM CE 25.0.2 and public java.lang.classfile APIs
│   ├── Verify: JAVA_HOME=/Users/jim/.sdkman/candidates/java/25.0.2-graalce
│   ├── Use: java.lang.classfile.* (no internal APIs)
│   └── Configure: jvmToolchain(25) + --add-exports for classfile
├── J11-02: Keep common pointcut/event models outside JVM-only code
│   ├── Move: PointcutEvent, PointcutCoordinate to commonMain
│   ├── Keep: ClassFileTransformer in jvmMain
│   └── Verify: no JVM types in commonMain
├── J11-03: Attach classfile/Panama/MLIR outputs to cursor or CCEK emission sites
│   ├── Design: PointcutReporter emits to Cursor/CCEK
│   ├── Implement: classfile transform → Cursor projection
│   ├── Implement: Panama MemorySegment layout → CCEK signal
│   └── Implement: MLIR tensor → CCEK signal
├── J11-04: Prefer JVM auto-vectorization and MemorySegment-compatible layout over manual Vector API
│   ├── Annotate: @Vectorizable hot loops
│   ├── Use: MemorySegment for off-heap
│   └── Avoid: jdk.incubator.vector
├── J11-05: Remove stale internal-classfile export flags only with passing Graal gate
│   ├── Audit: --add-exports in build.gradle.kts
│   ├── Test: each flag required for Graal compilation
│   └── Remove: unused flags
├── J11-06: TDD — Transformed class verification, pointcut emission/veto, polyglot event flow, memory layout/indicator numerical parity
│   ├── Test: transformed class passes verifier
│   ├── Test: pointcut emitted at join points
│   ├── Test: veto prevents transformation
│   ├── Test: polyglot event → CCEK → handler
│   ├── Test: MemorySegment layout matches spec
│   └── Test: indicator numerical parity (JVM vs native)
└── J11-07: Efficiency — No reflective hot path where public Java 25 API exists; bounded event buffers; columnar compute
    ├── Replace: reflection with ClassFile API
    ├── Verify: event buffers bounded
    └── Verify: columnar layouts for tensor ops
```

**Jules Session:** `8536800141685782909` (Planning)

---

## J12 — Forge Top Surface (Distance 3, Switch: thin launchers only)
**Packages:** `borg.trikeshed.blackboard`, `borg.trikeshed.dag`, `borg.trikeshed.graph`, `borg.trikeshed.ccek`, `borg.trikeshed.kanban`, `borg.trikeshed.forge`, `borg.trikeshed.cli`
**Existing interfaces:** `ForgeDoc`, `ForgeBoardFSM`, `ForgeKanbanSignal`, `ForgeKanbanConduit`, CCEK scopes/services, causal graph, Confix persistence

### Task Tree
```
J12-FORGE-TOP
├── J12-01: Consolidate Kanban ownership in Forge without a second mutable board model
│   ├── Verify: single ForgeBoardFSM source of truth
│   ├── Remove: any duplicate KanbanBoard in blackboard/ or dag/
│   └── Test: all kanban signals route through ForgeBoardFSM
├── J12-02: Project ingestion/reduction events into Forge signals and causal graph nodes
│   ├── Map: ingestion event types → ForgeKanbanSignal
│   ├── Map: reduction output → CausalGraphNode
│   ├── Implement: ForgeKanbanConduit ingests signals
│   └── Test: event → signal → node → board
├── J12-03: Make persistence and network conduits consume the same ForgeDoc/Confix contract
│   ├── Verify: ForgeDoc → ConfixDoc round-trip
│   ├── Verify: WAL persistence uses ForgeDoc
│   ├── Verify: network sync uses ForgeDoc
│   └── Test: persistence ↔ network consistency
├── J12-04: Remove Dreamer/demo-only topology and tests rather than excluding them
│   ├── Find: dreamer-kmm, demo tests
│   ├── Delete: or integrate into real topology
│   └── Verify: no excluded tests
├── J12-05: Keep CLI/UI adapters thin and driven by common state
│   ├── Verify: CLI commands delegate to ForgeBoardFSM
│   ├── Verify: Compose Desktop UI observes ForgeDoc
│   └── Verify: no business logic in CLI/UI
├── J12-06: TDD — State transitions, concurrent moves, Confix round trips, causal edges, conduit ordering, restart durability
│   ├── Test: column move transitions
│   ├── Test: concurrent card moves (optimistic locking)
│   ├── Test: ForgeDoc ↔ ConfixDoc round-trip
│   ├── Test: causal edge creation on signal
│   ├── Test: conduit ordering guarantees
│   └── Test: board state after restart
└── J12-07: Efficiency — One shared event stream, incremental projections, bounded history, no full-board reification per event
    ├── Verify: single EventLog source
    ├── Verify: projections incremental
    ├── Verify: history bounded (configurable)
    └── Verify: no full board rebuild on event
```

**Jules Session:** `13413214599923051064` (In Progress — existing)

---

## Gradle Mismatch Checklist (Pre-Dispatch Gate)

Before any new Jules job runs, verify these match across:
- `build.gradle.kts` (root)
- `gradle/macros/trikeshed-lib.gradle` (legacy — now dead code)
- `gradle.properties`

| Property | Root Value | Legacy Macro | Status |
|----------|------------|--------------|--------|
| Kotlin version | 2.4.0 | 2.4.0 | ✓ |
| Coroutines | 1.11.0 | 1.11.0 | ✓ |
| Datetime | 0.8.0-0.6.x-compat | 0.8.0-0.6.x-compat | ✓ |
| JVM target | 25 | 25 | ✓ |
| GraalVM | 25.0.2 | 25.0.2 | ✓ |
| libVersion | 1.0 (root) | 0.1.0-SNAPSHOT | ⚠️ MISMATCH |
| trikeshedVersion | 1.0 (root) | 1.0 | ✓ |
| Group | org.bereft | borg.trikeshed | ⚠️ MISMATCH |

**Action Required:** Resolve group/version mismatch before J02-J11 dispatch. The root project is `org.bereft:TrikeShed:1.0`; legacy macro expects `borg.trikeshed:*:0.1.0-SNAPSHOT`. Since libs/ are deleted, the macro is dead code but any Jules task referencing it will fail.

---

## Dispatch Protocol

For each job J02-J11:
1. Verify Gradle mismatch resolved
2. Use Jules session ID from this doc
3. Task prompt = job's task tree (above) + mandatory preamble from PACKAGE_JOBS.md
4. Branch name: `jules/J<NN>-<kebab-name>-<sessionId>`
5. PR must pass: `./gradlew :test` (root-only), all job-specific tests green
6. No libs/ references, no new subprojects, no new external deps

---

## Current Jules Session Map

| Job | Session ID | Status | Branch |
|-----|------------|--------|--------|
| J01 | 14840399884225250297 | Completed | jules-j01-series-buffer-fresh-14840399884225250297 (PR #78) |
| J02 | 10343031295565610478 | Planning | — |
| J03 | 16753998972266025303 | Planning | — |
| J04 | 7135063974888942038 | Planning | — |
| J05 | 12784702627924403935 | Planning | — |
| J06 | 6812140497092679826 | Planning | — |
| J07 | 11278868115855868767 | Planning | — |
| J08 | 4044040330023918642 | Planning | — |
| J09 | 4256545542739399138 | Planning | — |
| J10 | 5512307911648352632 | Planning | — |
| J11 | 8536800141685782909 | Planning | — |
| J12 | 13413214599923051064 | In Progress | (existing) |