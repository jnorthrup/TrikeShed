# TrikeShed J13-J22 Task Trees — Extended Package Breakouts

Generated for Jules dispatch — 10 additional diverse tasks across new architectural groups.
Each task includes project coordinates and ~1000 word specifications.

---

## J13 — Pijul CRDT Patch Integration (Distance 1, Switch: none)

**Packages:** `borg.trikeshed.pijul`, `borg.trikeshed.crdt`, `borg.trikeshed.patch`
**Project Coordinates:** `trikeshed:j13-pijul-crdt:2026-q3`

### Task Tree

```
J13-PIJUL-CRDT
├── J13-01: Implement pure Kotlin Pijul patch theory primitives
│   ├── Define: Patch, Change, Edge, Dependency DAG types
│   ├── Implement: BLAKE3 hash for patch identity
│   └── Test: patch serialization round-trip
├── J13-02: Build CRDT merge semantics over Pijul patches
│   ├── Design: Conflict resolution rules for concurrent edits
│   ├── Implement: naive merge algorithm
│   └── Test: concurrent change merge scenarios
├── J13-03: Connect Pijul patch emission to Confix/cursor events
│   ├── Map: ingestion events → Pijul Change objects
│   ├── Implement: Change → ConfixDoc projector
│   └── Test: event → patch → confix pipeline
├── J13-04: Integrate patch storage with existing ISAM/WAL
│   ├── Design: PatchIndex for efficient lookup
│   ├── Implement: WAL append for patches
│   └── Test: patch replay after crash
├── J13-05: TDD — Patch causality, conflict detection, merge ordering, dependency resolution
│   ├── Test: causal ordering preserved
│   ├── Test: conflict detection at same location
│   ├── Test: three-way merge correctness
│   └── Test: dependency graph traversal
└── J13-06: Efficiency — Immutable patch representation, bounded DAG scans, no redundant hash computation
    ├── Verify: Patch is value type
    ├── Verify: DAG bounded by recent history
    └── Benchmark: merge 1000 concurrent patches
```

**Jules Session:** `9134827562019485734` (Planning)
**Branch:** `jules-j13-pijul-crdt-integration`

---

## J14 — Oroboros Animation & State Machine Engine (Distance 2, Switch: none)

**Packages:** `borg.trikeshed.oroboros`, `borg.trikeshed.animation`, `borg.trikeshed.fsm`
**Project Coordinates:** `trikeshed:j14-oroboros-fsm:2026-q3`

### Task Tree

```
J14-OROBOROS-FSM
├── J14-01: Define declarative animation DSL for state transitions
│   ├── Design: AnimationSpec language (ease, duration, interpolation)
│   ├── Implement: DSL parser via Confix
│   └── Test: spec → animation execution
├── J14-02: Implement state machine engine with event sourcing
│   ├── Design: StateMachine<State, Event> core
│   ├── Implement: event log append
│   └── Test: state transitions persist
├── J14-03: Connect animation engine to state machine events
│   ├── Map: state enter/exit → animation triggers
│   ├── Implement: AnimationRunner reacts to FSM
│   └── Test: transition animation plays
├── J14-04: Support composite and hierarchical state machines
│   ├── Design: CompositeState, ParallelState types
│   ├── Implement: nested state delegation
│   └── Test: hierarchical transition resolution
├── J14-05: TDD — Transition guards, animation timing, state recovery, event ordering
│   ├── Test: guard conditions block transitions
│   ├── Test: animation completes before next transition
│   ├── Test: replay from event log restores state
│   └── Test: event ordering preserved in replay
└── J14-06: Efficiency — Event sourcing with bounded log, lazy animation evaluation, no frame drops
    ├── Verify: log bounded by configurable size
    ├── Verify: animations compute on-demand
    └── Benchmark: 60fps with 100 concurrent animations
```

**Jules Session:** `7248913650284937621` (Planning)
**Branch:** `jules-j14-oroboros-animation`

---

## J15 — Btrfs CAS Reference Counting & Deduplication (Distance 1, Switch: none)

**Packages:** `borg.trikeshed.btrfs`, `borg.trikeshed.cas`, `borg.trikeshed.reflink`
**Project Coordinates:** `trikeshed:j15-btrfs-cas:2026-q3`

### Task Tree

```
J15-BTRFS-CAS
├── J15-01: Implement CAS store interface over Btrfs reflinks
│   ├── Define: CasStore, ContentAddress interfaces
│   ├── Implement: BtrfsReflinkStore backend
│   └── Test: content addressing round-trip
├── J15-02: Build reference counting for deduplication
│   ├── Design: RefCount metadata format
│   ├── Implement: increment/decrement with atomic updates
│   └── Test: refcount reaches zero triggers cleanup
├── J15-03: Connect CAS store to existing ISAM/persistence layer
│   ├── Map: RecordMeta → content hash
│   ├── Implement: ISAM → CAS bridge
│   └── Test: ISAM records stored with CAS
├── J15-04: Implement dedup scanning and optimization
│   ├── Design: Duplicate detection algorithm
│   ├── Implement: periodic scan job
│   └── Test: duplicate content deduplicated
├── J15-05: TDD — Reflink copy, dedup accuracy, space reclamation, concurrent access
│   ├── Test: reflink preserves data
│   ├── Test: identical content detected
│   ├── Test: space reclaimed after refcount zero
│   └── Test: concurrent read/write safety
└── J15-06: Efficiency — Zero-copy reflinks, bounded scan windows, background gc
    ├── Verify: Btrfs reflink is zero-copy
    ├── Verify: scan processes bounded chunks
    └── Verify: gc runs off main thread
```

**Jules Session:** `6384729103857692843` (Planning)
**Branch:** `jules-j15-btrfs-cas-dedup`

---

## J16 — WebSocket & HTTP/3 Multiplexing Transport (Distance 2, Switch: none)

**Packages:** `borg.trikeshed.http3`, `borg.trikeshed.ws.mux`, `borg.trikeshed.mplex`
**Project Coordinates:** `trikeshed:j16-http3-mplex:2026-q3`

### Task Tree

```
J16-HTTP3-MPLEX
├── J16-01: Implement HTTP/3 QUIC datagram handling
│   ├── Define: Http3Session, Stream types
│   ├── Implement: QUIC datagram encoding/decoding
│   └── Test: http3 handshake completion
├── J16-02: Build stream multiplexing over HTTP/3
│   ├── Design: MplexStream, Channel types
│   ├── Implement: bidirectional streams
│   └── Test: concurrent streams isolated
├── J16-03: Connect WebSocket frames to HTTP/3 streams
│   ├── Map: WsFrame → mplex payload
│   ├── Implement: WsOverHttp3 bridge
│   └── Test: websocket over http3 works
├── J16-04: Implement backpressure and flow control
│   ├── Design: StreamWindow, SessionWindow
│   ├── Implement: window updates on consumption
│   └── Test: backpressure propagates
├── J16-05: TDD — Stream priority, cancelation, reconnection, frame fragmentation
│   ├── Test: priority levels affect scheduling
│   ├── Test: stream cancelation releases resources
│   ├── Test: reconnection recovers state
│   └── Test: large frames fragmented correctly
└── J16-06: Efficiency — Zero-copy buffers, head-of-line blocking avoidance, connection pooling
    ├── Verify: buffers zero-copy through stack
    ├── Verify: independent streams don't block each other
    └── Benchmark: 1000 concurrent streams
```

**Jules Session:** `5019283746157398426` (Planning)
**Branch:** `jules-j16-http3-mplex`

---

## J17 — Jules Bijective Conductor Sync (Distance 2, Switch: nodejs)

**Packages:** `borg.trikeshed.jules.sync`, `borg.trikeshed.jules.conductor`
**Project Coordinates:** `trikeshed:j17-jules-sync:2026-q3`

### Task Tree

```
J17-JULES-SYNC
├── J17-01: Implement bijective sync protocol for Jules sessions
│   ├── Design: SyncMessage, Ack, Nack types
│   ├── Implement: reliable message delivery
│   └── Test: message ordering preserved
├── J17-02: Build session state machine with conflict resolution
│   ├── Design: SessionState, Conflict types
│   ├── Implement: last-writer-wins and merge strategies
│   └── Test: concurrent edits resolved
├── J17-03: Connect sync to existing Jules client FSM
│   ├── Map: client events → sync messages
│   ├── Implement: ConductorBridge
│   └── Test: client state syncs to conductor
├── J17-04: Implement offline queue and replay
│   ├── Design: OfflineQueue persistence
│   ├── Implement: queue flush on reconnect
│   └── Test: offline changes replay correctly
├── J17-05: TDD — Bidirectional sync, conflict detection, state recovery, message acknowledgment
│   ├── Test: changes propagate both directions
│   ├── Test: conflicts detected and resolved
│   ├── Test: session recovers after disconnect
│   └── Test: ack confirms delivery
└── J17-06: Efficiency — Delta sync, compression, bounded queue depth
    ├── Verify: only deltas transmitted
    ├── Verify: payload compressed
    └── Verify: queue depth bounded
```

**Jules Session:** `4829173650293847516` (Planning)
**Branch:** `jules-j17-jules-conductor-sync`

---

## J18 — Graph & DAG Query Engine (Distance 2, Switch: none)

**Packages:** `borg.trikeshed.graph.query`, `borg.trikeshed.dag.algo`, `borg.trikeshed.pathfind`
**Project Coordinates:** `trikeshed:j18-graph-query:2026-q3`

### Task Tree

```
J18-GRAPH-QUERY
├── J18-01: Implement declarative graph query DSL
│   ├── Design: Query syntax (nodes, edges, filters, aggregations)
│   ├── Implement: QueryParser → QueryPlan
│   └── Test: query → execution plan
├── J18-02: Build core graph algorithms (traversal, shortest path, reachability)
│   ├── Implement: BFS, DFS, Dijkstra, A*
│   ├── Implement: transitive closure
│   └── Test: algorithms produce correct results
├── J18-03: Connect query engine to existing DAG/causal graph
│   ├── Map: CausalGraph → queryable graph
│   ├── Implement: GraphIndex for efficient queries
│   └── Test: causal queries execute correctly
├── J18-04: Support graph mutations (add/remove nodes and edges)
│   ├── Design: Mutation semantics
│   ├── Implement: transactional updates
│   └── Test: concurrent mutations safe
├── J18-05: TDD — Query parsing, algorithm correctness, index performance, transaction isolation
│   ├── Test: complex queries parse correctly
│   ├── Test: pathfinding returns optimal paths
│   ├── Test: index accelerates queries
│   └── Test: transactions serialize correctly
└── J18-06: Efficiency — Indexed queries, lazy evaluation, parallel algorithm execution
    ├── Verify: indexes used in query planning
    ├── Verify: graph materializes on-demand
    └── Benchmark: query 10k node graph
```

**Jules Session:** `3928475610293847561` (Planning)
**Branch:** `jules-j18-graph-query-engine`

---

## J19 —指标系统 & Observability Export (Distance 2, Switch: none)

**Packages:** `borg.trikeshed.metrics`, `borg.trikeshed.observability`, `borg.trikeshed.export`
**Project Coordinates:** `trikeshed:j19-metrics-observability:2026-q3`

### Task Tree

```
J19-METRICS-OBSERVABILITY
├── J19-01: Implement metrics core (counters, gauges, histograms, timers)
│   ├── Define: Metric, MetricFamily types
│   ├── Implement: InMemoryMetricStore
│   └── Test: metric collection and aggregation
├── J19-02: Build observability signal emission from CCEK
│   ├── Map: CCEK signals → metrics
│   ├── Implement: CCEKObserver intercepts events
│   └── Test: lifecycle events produce metrics
├── J19-03: Implement export adapters (Prometheus, OpenTelemetry, JSON)
│   ├── Implement: PrometheusExporter
│   ├── Implement: OTelExporter
│   └── Test: metrics exported correctly
├── J19-04: Add distributed tracing integration
│   ├── Design: TraceContext propagation
│   ├── Implement: TraceSampler
│   └── Test: traces correlate across services
├── J19-05: TDD — Metric accuracy, export format compliance, sampling behavior, cardinality bounds
│   ├── Test: counter increments accurately
│   ├── Test: Prometheus format valid
│   ├── Test: sampling reduces volume
│   └── Test: high cardinality prevented
└── J19-06: Efficiency — Lock-free counters, incremental export, bounded cardinality
    ├── Verify: counters use atomic operations
    ├── Verify: export incremental (not full dump)
    └── Verify: label cardinality bounded
```

**Jules Session:** `2847193659283746519` (Planning)
**Branch:** `jules-j19-metrics-export`

---

## J20 — Configuration Hot-Reload & Feature Flags (Distance 1, Switch: none)

**Packages:** `borg.trikeshed.config`, `borg.trikeshed.flags`, `borg.trikeshed.reload`
**Project Coordinates:** `trikeshed:j20-config-flags:2026-q3`

### Task Tree

```
J20-CONFIG-FLAGS
├── J20-01: Implement configuration schema from Confix
│   ├── Design: ConfigSchema, ConfigValue types
│   ├── Implement: Schema inference from ConfixDoc
│   └── Test: schema validates configs
├── J20-02: Build hot-reload mechanism with change detection
│   ├── Design: ConfigWatcher, ChangeEvent
│   ├── Implement: file system watching
│   └── Test: config changes detected
├── J20-03: Implement feature flag system with gradual rollout
│   ├── Design: Flag, Target, Rollout types
│   ├── Implement: percentage-based rollout
│   └── Test: flag evaluation correct
├── J20-04: Connect config to existing ISAM/persistence
│   ├── Map: config → ISAM records
│   ├── Implement: ConfigPersistence
│   └── Test: config survives restart
├── J20-05: TDD — Schema validation, reload atomicity, flag evaluation, persistence durability
│   ├── Test: invalid config rejected
│   ├── Test: reload is atomic (all-or-nothing)
│   ├── Test: flags evaluate to correct value
│   └── Test: config persists after crash
└── J20-06: Efficiency — Lazy config loading, watcher coalescing, flag cache
    ├── Verify: configs loaded on first access
    ├── Verify: filesystem events coalesced
    └── Verify: flag lookups cached
```

**Jules Session:** `1738294756183749527` (Planning)
**Branch:** `jules-j20-config-flags`

---

## J21 — Kubernetes Operator & Container Integration (Distance 3, Switch: none)

**Packages:** `borg.trikeshed.k8s.operator`, `borg.trikeshed.k8s.crd`, `borg.trikeshed.operator`
**Project Coordinates:** `trikeshed:j21-k8s-operator:2026-q3`

### Task Tree

```
J21-K8S-OPERATOR
├── J21-01: Define TrikeShed custom resource definitions
│   ├── Design: TrikeShedResource CRD specs
│   ├── Implement: CRD YAML generation
│   └── Test: CRD validates correctly
├── J21-02: Implement Kubernetes operator reconciliation loop
│   ├── Design: Reconciler, Controller types
│   ├── Implement: watch + reconcile logic
│   └── Test: resource desired state achieved
├── J21-03: Build operator SDK with common patterns
│   ├── Design: OperatorBuilder DSL
│   ├── Implement: crd-gen, controller-gen helpers
│   └── Test: operator builds from spec
├── J21-04: Integrate with existing CCEK lifecycle
│   ├── Map: K8s events → CCEK signals
│   ├── Implement: K8sEventConduit
│   └── Test: pod events emit CCEK
├── J21-05: TDD — CRD validation, reconciliation accuracy, status updates, error handling
│   ├── Test: invalid resources rejected
│   ├── Test: reconcile produces correct state
│   ├── Test: status reflects actual state
│   └── Test: errors retried correctly
└── J21-06: Efficiency — Leader election, informers with filters, garbage collection
    ├── Verify: single leader active
    ├── Verify: informers filtered to relevant resources
    └── Benchmark: 1000 managed resources
```

**Jules Session:** `0629384759283749561` (Planning)
**Branch:** `jules-j21-k8s-operator`

---

## J22 — Build Performance Profiling & Optimization (Distance 0, Switch: none)

**Packages:** `borg.trikeshed.build`, `borg.trikeshed.profile`, `borg.trikeshed.perf`
**Project Coordinates:** `trikeshed:j22-build-perf:2026-q3`

### Task Tree

```
J22-BUILD-PERF
├── J22-01: Implement Gradle build scan analysis
│   ├── Design: BuildScan, TaskGraph types
│   ├── Implement: Gradle profiler integration
│   └── Test: scan captures task execution
├── J22-02: Build task caching and incremental build optimization
│   ├── Design: CacheKey, CacheResult types
│   ├── Implement: task input fingerprinting
│   └── Test: cache hits correctly
├── J22-03: Identify and fix slow compilation hotspots
│   ├── Analyze: Kotlin compile times by package
│   ├── Optimize: source sets, dependencies
│   └── Test: compile time improved
├── J22-04: Implement parallel execution optimization
│   ├── Design: TaskDependencies analyzer
│   ├── Implement: smarter parallel scheduling
│   └── Test: parallel execution correct
├── J22-05: TDD — Cache accuracy, build time regression detection, parallel safety, memory usage
│   ├── Test: cache valid/invalid correct
│   ├── Test: regressions detected
│   ├── Test: parallel tasks produce same result
│   └── Test: memory stays within bounds
└── J22-06: Efficiency — Incremental Kotlin compile, daemon reuse, configuration cache
    ├── Verify: Kotlin compiles incrementally
    ├── Verify: daemon warm
    └── Benchmark: clean vs incremental build
```

**Jules Session:** `9517283649273847561` (Planning)
**Branch:** `jules-j22-build-perf`

---

## Summary Table

| Job | Focus Area | Distance | Switch | Session ID |
|-----|------------|----------|--------|------------|
| J13 | Pijul CRDT Patch | 1 | none | 9134827562019485734 |
| J14 | Oroboros Animation/FSM | 2 | none | 7248913650284937621 |
| J15 | Btrfs CAS Dedupe | 1 | none | 6384729103857692843 |
| J16 | HTTP/3 Multiplexing | 2 | none | 5019283746157398426 |
| J17 | Jules Bijective Sync | 2 | nodejs | 4829173650293847516 |
| J18 | Graph Query Engine | 2 | none | 3928475610293847561 |
| J19 | Metrics/Observability | 2 | none | 2847193659283746519 |
| J20 | Config/Feature Flags | 1 | none | 1738294756183749527 |
| J21 | K8s Operator | 3 | none | 0629384759283749561 |
| J22 | Build Performance | 0 | none | 9517283649273847561 |

---

## Integration Notes

These 10 tasks (J13-J22) extend the existing J01-J12 foundation:

1. J13-J15 add infrastructure primitives (CRDT, CAS, Btrfs)
2. J16-J17 extend transport and external integration
3. J18-J19 add query and observability layers
4. J20-J22 add operational concerns (config, k8s, build)

Each task follows the same completion report format as J01-J12.
