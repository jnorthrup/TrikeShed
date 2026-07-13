# TrikeShed Package Job Program

## Summary

TrikeShed is **one root Kotlin Multiplatform Gradle project**. `./gradlew projects` reports no subprojects. The source tree currently contains **130 declared Kotlin packages**; those are grouped below into **12 independent remedial jobs**, not 130 modules.

The dependency direction is fixed:

```text
Distance 3   Forge / Kanban / CCEK / graph surfaces
                 ↑ typed Confix events and cursor projections
Distance 2   integration, compute, transports, external ingress
                 ↑ schema-bearing ingestion streams
Distance 1   structured ingestion, reduction, ISAM/persistence
                 ↑ metadata-preserving Cursor / Series values
Distance 0   TrikeShed algebra, collections, cursor, platform substrate
```

Maximum architectural distance from the TrikeShed kernel is 3. Pure aliases, generated bindings, logging shims, and tiny compatibility utilities do not receive standalone tasks; they remain owned by their nearest package job.

## Mandatory preamble for every package job

Every Jules/local task uses this preamble verbatim before its package-specific goal:

> Work only in the root TrikeShed KMP project. Do not restore, create, modify, or reference `libs/`; historical donor code may be inspected only through a disposable `/tmp/trikeshed-<topic>-donor.XXXXXX` extraction. Preserve all user-owned deletions and the modified `liburing` submodule.
>
> Pin the installed GraalVM Community Edition as the build JDK:
>
> ```sh
> export JAVA_HOME=/Users/jim/.sdkman/candidates/java/25.0.2-graalce
> export PATH="$JAVA_HOME/bin:$PATH"
> "$JAVA_HOME/bin/java" -version
> ./gradlew --version
> ```
>
> Required runtime is GraalVM CE 25.0.2. Project Kotlin is plugin/compiler 2.4.0 with API/language 2.4 and JVM target 25. Gradle wrapper is 9.6.1. Gradle's embedded Kotlin 2.3.21 is implementation detail, not the project language version. Do not change these versions or substitute another JDK.
>
> `commonMain`/`commonTest` dominate. Use `jvmMain`/`jvmTest` only for GraalVM, Java ClassFile API, Panama, or other concrete JVM-only APIs. Use `jsMain`/`jsTest` only for concrete Node.js APIs. Do not move shared policy, data models, parsers, reducers, or protocols out of common code merely to make a platform build convenient.
>
> Do not add a package switch by default. If a package genuinely requires a platform/build compromise, define one typed root Gradle property with a safe default, document exactly which source sets it changes, and test both switch states. No new subprojects, included builds, ad-hoc environment switches, or package-specific build files.
>
> Follow `PRELOAD.md`: `Join`, `Twin`, `Series`, `j`, `α`, `Cursor`, metadata-preserving projections, explicit CCEK lifecycle, and structured fanout are the integration contract. Stay lazy and indexed until a real I/O/serialization boundary requires materialization. Avoid duplicate domain models and dead bridges with no emission site.
>
> TDD is mandatory: write the new behavioral test first and run it red; make the smallest production change; run focused tests green; then run the affected package gate. A legitimate contract change may invalidate previous tests: update or delete stale tests in the same change rather than disabling, excluding, or preserving tests that no longer describe the product.
>
> DRY and computational efficiency are acceptance criteria. Minimize churn, avoid helper proliferation, preserve source metadata across seams, and justify every materialization, allocation, dispatch, or platform split. Update `PRELOAD.md` only when a tested cross-package contract actually changes; do not duplicate package-local details there.

## Package/source-set switch DSL contract

The root build is the only DSL owner. Each package job must report one of:

- `switch: none` — normal case; common code and existing targets compile together.
- `switch: graal` — only JVM/Graal/ClassFile/Panama implementation wiring differs.
- `switch: nodejs` — only Node transport/process/filesystem implementation wiring differs.
- `switch: focused transport` — only when an existing native interop build cannot be part of the default target gate.

If a new switch is unavoidable, its proposed shape is:

```kotlin
val <name> = providers.gradleProperty("trikeshed.<package>.<capability>")
    .map(String::toBooleanStrict)
    .orElse(false)
```

The task must specify default behavior, affected source sets, enabled/disabled tests, and why commonMain cannot express the implementation. Switches may select implementations; they must not select competing domain models or contracts.

## The 12 package jobs

### J01 — Kernel algebra

- Distance: 0
- Packages: `borg.trikeshed.lib`, `borg.trikeshed.charstr`, `borg.trikeshed.num`
- Switch: none
- Existing interfaces: `Join`, `Twin`, `Series`, `j`, `α`, `MutableSeries`, `CharStr`, packed twins.
- Direction: remain the smallest allocation-conscious algebra used by every upper layer.
- Goalset:
  - establish one canonical constructor/import path for `j`, `joins`, `Series`, and metadata products;
  - remove package-level semantic duplication only when tests prove equivalent behavior;
  - preserve JVM auto-vectorization-friendly indexed loops and dense primitive representations;
  - prevent platform APIs from leaking into kernel types.
- TDD: algebra laws, Series bounds/index semantics, primitive packing, source compatibility.
- Seam acceptance: all upper packages consume kernel types directly; no adapter exists without a real caller/emitter.

### J02 — Collections and mutation

- Distance: 0
- Packages: `borg.trikeshed.collections`, `borg.trikeshed.common.collections`, `borg.trikeshed.mutable`
- Switch: none
- Existing interfaces: `MutableSeries`, `ChunkedMutableSeries`, `RingSeries`, `JournalSeries`, associative/trie types, `FacetedRow`.
- Direction: one mutation/storage family beneath cursors and ingestion.
- Goalset:
  - identify canonical versus duplicate collection packages and converge without broad renames;
  - make snapshot versus live-view behavior explicit;
  - keep mutation journals compatible with cursor and CCEK consumers;
  - eliminate tests/types for deleted mutation strategies rather than preserving exclusions.
- TDD: mutation order, snapshot isolation, ring boundaries, trie/range behavior, facet lookup.
- Efficiency: no accidental List materialization or boxed primitive hot paths.

### J03 — Cursor and Confix schema substrate

- Distance: 0
- Packages: `borg.trikeshed.cursor`, `borg.trikeshed.confix`, `borg.trikeshed.parse.confix`
- Switch: none
- Existing interfaces: `Cursor`, `RowVec`, `ColumnMeta`, `ConfixDoc`, `ConfixIndexK`, `Spans`, `Tags`, `Depths`, `DirectChildren`, `TreeCursor`, `KeyToChild`.
- Direction: the facetted cursor is the canonical navigation, provenance, and autoschema substrate for JSON/YAML/CBOR.
- Goalset:
  - derive every facet and tree cursor from one scanner geometry;
  - retain source order and inclusive span semantics;
  - make syntax-aware scalar reification explicit without detached DOM ownership;
  - project JSON Schema/RecordMeta lazily from cursor evidence;
  - keep OpenAPI, CCEK, and Forge on this same schema surface.
- TDD: duplicate object keys, nested object/array paths, JSON/YAML/CBOR round trips, facet/tree identity, source spans.
- Efficiency: one scan, lazy projection, no parallel Map/JsonElement schema owner.

### J04 — Platform, context, and userspace NIO substrate

- Distance: 0
- Packages: `borg.trikeshed.common`, `borg.trikeshed.context`, `borg.trikeshed.platform`, `borg.trikeshed.native`, `borg.trikeshed.runtime`, `borg.trikeshed.userspace.context`, `borg.trikeshed.userspace.nio`
- Switch: none by default; focused transport only for real native CInterop
- Existing interfaces: `AsyncContextKey`, `AsyncContextElement`, `SystemOperations`, `NioSupervisor`, channel/file/process/reactor SPI, lifecycle states.
- Direction: common policy with narrow platform implementations.
- Goalset:
  - unify duplicate context/key semantics without forcing platform code upward;
  - keep lifecycle forward-only and service lookup identity-safe;
  - prove JVM/JS/Wasm/Posix implementations satisfy common contracts;
  - contain native interop behind existing SPI rather than source-set domain forks.
- TDD: key identity, lifecycle transitions, provider selection, file/channel parity, disabled/enabled transport switch gates.
- Efficiency: structured fanout, bounded buffers, no hidden thread pools or callback duplication.

### J05 — Structured document ingestion

- Distance: 1
- Packages: `borg.trikeshed.parse.json`, `borg.trikeshed.parse.yaml`, `borg.trikeshed.parse.csv`, `borg.trikeshed.parse.kursive`, `borg.trikeshed.parse.interop`, `borg.trikeshed.parser.simple`
- Switch: none
- Existing interfaces: Confix facetted cursor, parser Series inputs, CSV bitmaps, Kursive trace/evidence, descriptor fragments.
- Direction: all structured inputs become metadata-preserving cursor facts before downstream interpretation.
- Goalset:
  - route JSON/YAML/CBOR through Confix without duplicate mutable schema paths;
  - define CSV/Kursive descriptor projections into cursor metadata;
  - make malformed-input and depth behavior explicit;
  - remove parser demos/placeholders that do not test production ingestion.
- TDD: format parity, malformed boundaries, deep nesting, numeric/string fidelity, source spans.
- Efficiency: index first/reify later; bounded scans; avoid whole-document maps.

### J06 — ISAM and persistence ingestion

- Distance: 1
- Packages: `borg.trikeshed.isam`, `borg.trikeshed.couch`
- Switch: none; JVM/Posix actual I/O only through existing operations interfaces
- Existing interfaces: `RecordMeta`, `IsamOperations`, `MonoCursor`, `ConfixIsamIsomorphism`, `ConfixPersistence`, WAL.
- Direction: cursor facts become append-only columnar/storage facts without losing metadata.
- Goalset:
  - replace placeholder Confix→RecordMeta inference with tested facet-derived fields/types;
  - preserve one-WAL-per-table, multi-tenant, append-only semantics;
  - keep Couch persistence as a consumer of Confix/ISAM contracts, not a competing document model;
  - reject legacy `libs/couch` and `libs/ipfs` topology; use `/tmp` only for donors.
- TDD: schema inference, WAL replay, cursor/RecordMeta round trip, persistence durability and ordering.
- Efficiency: mmap/MemorySegment-ready layouts, lazy columns, no row-object expansion in hot paths.

### J07 — LCNC reduction ingestion

- Distance: 1
- Packages: `borg.trikeshed.lcnc`
- Switch: none
- Existing interfaces: `ReductionCarrier`, `ConfixReducers`, `LcncKeyAlg`, `LcncValueAlg`, `IngestCodec`, `IngestStateElement`.
- Direction: composable reduction from Confix/cursor facts into typed ingestion states.
- Goalset:
  - replace opaque Map carriers at package seams with TrikeShed algebra where contracts permit;
  - keep phase/key/value reductions independent and composable;
  - connect reducers to actual ingestion emission sites;
  - make Forge taxonomy projection a downstream output, not a duplicate state owner.
- TDD: reduction laws, ordering, associativity where valid, patch cable composition, malformed carrier behavior.
- Efficiency: no repeated tree reification; stream/facet reductions over indexed facts.

### J08 — Transport and protocol ingestion

- Distance: 2
- Packages: `borg.trikeshed.userspace` excluding J04-owned NIO/context, `borg.trikeshed.reactor`, `borg.trikeshed.ws`, `borg.trikeshed.htx`
- Switch: focused transport only when native CInterop requires it
- Existing interfaces: `StreamTransport`, `FanoutDispatcherElement`, userspace channels, TLS codec, WebSocket frames, HTX requests/reactor elements.
- Direction: bytes/events enter through explicit lifecycle and become typed ingestion streams.
- Goalset:
  - align TLS/WebSocket/HTX framing on common byte-region/channel contracts;
  - keep SCTP direction and reject revived QUIC/demo topology;
  - make backpressure, closure, and fanout observable through CCEK;
  - prove platform adapters preserve common ordering and cancellation semantics.
- TDD: framing, partial reads, cancellation, fanout, lifecycle, focused native switch states.
- Efficiency: zero/low-copy slices, bounded queues, structured concurrency.

### J09 — Distributed identity and routing

- Distance: 2
- Packages: `borg.trikeshed.dht`, `gk.kademlia`
- Switch: none
- Existing interfaces: `NUID`, primitive `BitOps`, `RoutingTable`, DHT agents/events.
- Direction: one canonical identity/routing algebra consumed by transport and ingestion.
- Goalset:
  - resolve duplicate `borg.trikeshed.dht` versus `gk.kademlia` ownership by usages and tests;
  - retain primitive-specialized distance operations;
  - connect routing events to real transport emission sites;
  - do not rehome deleted `libs/ipfs` code directly; assess donors only in `/tmp`.
- TDD: XOR distance/order, bucket boundaries, identity serialization, route update/eviction.
- Efficiency: primitive operations, no BigInteger fallback on fixed-width IDs, bounded routing scans.

### J10 — External model/API ingress

- Distance: 2
- Packages: `keymux`, `modelmux`, `modelmux.acp`, `borg.trikeshed.jules.client`, the forbidden legacy `borg.trikeshed.hermes.tool` naming to rehome/remove, and planned root `borg.trikeshed.reactor.openapi`
- Switch: nodejs only for concrete Node process/network APIs; otherwise none
- Existing interfaces: `KeyMux`, `ModelMux`, `AcpProtocol`, Jules eventual-delivery client/FSM, Confix schema facts.
- Direction: external APIs/models become OpenAPI/Confix-driven reactor streams.
- Goalset:
  - introduce the smallest root-only Confix-first OpenAPI parser/resolver/stream slice;
  - keep code generation deferred until root target-package DSL is stable;
  - keep Jules eventual-delivery and non-blocking; no polling loop in orchestrator-facing code;
  - make Node transport an implementation seam, not the protocol owner;
  - remove hardcoded provider/package roots and libs-relative fixtures.
- TDD: OpenAPI JSON/YAML parity, operation/schema resolution, ACP framing, Jules FSM transitions, Node/common interface parity.
- Efficiency: stream operations, injected dispatch context, no eager detached OpenAPI DOM.

### J11 — Compute, classfile, Panama, and Graal seam

- Distance: 2
- Packages: `borg.trikeshed.classfile`, `borg.trikeshed.pointcut`, `borg.trikeshed.graal`, `borg.trikeshed.panama`, `borg.trikeshed.mlir`, `borg.trikeshed.manifold`, `borg.trikeshed.indicator`
- Switch: graal
- Existing interfaces: Java 25 public ClassFile API, `PointcutReporter`, `ConfixBlackboard`, Polyglot CCEK bridge, Panama/MLIR/tensor contracts.
- Direction: JVM/Graal implementations project into common TrikeShed/Confix facts.
- Goalset:
  - use installed GraalCE 25.0.2 and public `java.lang.classfile` APIs;
  - keep common pointcut/event models outside JVM-only code;
  - attach classfile/Panama/MLIR outputs to cursor or CCEK emission sites;
  - prefer JVM auto-vectorization and MemorySegment-compatible layout over manual Vector API wrappers;
  - remove stale internal-classfile export flags only with a passing Graal gate.
- TDD: transformed class verification, pointcut emission/veto, polyglot event flow, memory layout/indicator numerical parity.
- Efficiency: no reflective hot path where public Java 25 API exists; bounded event buffers; columnar compute.

### J12 — Forge top surface

- Distance: 3
- Packages: `borg.trikeshed.blackboard`, `borg.trikeshed.dag`, `borg.trikeshed.graph`, `borg.trikeshed.ccek`, `borg.trikeshed.kanban`, `borg.trikeshed.forge`, `borg.trikeshed.cli`
- Switch: none; UI/platform launchers may remain platform-specific while state stays common
- Existing interfaces: `ForgeDoc`, `ForgeBoardFSM`, `ForgeKanbanSignal`, `ForgeKanbanConduit`, CCEK scopes/services, causal graph, Confix persistence.
- Direction: Forge is the sole top-level Kanban/product surface over the shared Confix event/schema bus.
- Goalset:
  - consolidate Kanban ownership in Forge without a second mutable board model;
  - project ingestion/reduction events into Forge signals and causal graph nodes;
  - make persistence and network conduits consume the same ForgeDoc/Confix contract;
  - remove Dreamer/demo-only topology and tests rather than excluding them;
  - keep CLI/UI adapters thin and driven by common state.
- TDD: state transitions, concurrent moves, Confix round trips, causal edges, conduit ordering, restart durability.
- Efficiency: one shared event stream, incremental projections, bounded history, no full-board reification per event.

## Packages without standalone jobs

These remain subordinate to the nearest job and do not justify another abstraction or Jules session:

- `simple.*` compatibility types;
- `mu.*` and `org.slf4j.*` logging shims;
- `kotlin.jvm.*` annotations;
- generated `linux_uring.include.*` bindings;
- test-only shims and package-name anomalies;
- demo packages, which should not define architecture.

## Integration order

1. J01–J04 establish bottom contracts.
2. J03 must clear facetted-cursor format parity before J05, J06, or J10 consumes autoschema.
3. J05–J07 establish ingestion and schema/storage projections.
4. J08–J11 attach effects and external systems through existing interfaces.
5. J12 integrates only stable tested seams into Forge.
6. Cross-package contract changes are consolidated into `PRELOAD.md` once, after focused package tests prove them.

## Completion report required from every job

Each Jules task must return:

1. exact package/source-set paths changed;
2. red test command and observed failure;
3. green focused/package test commands and results;
4. package switch state (`none`, `graal`, `nodejs`, or `focused transport`);
5. allocation/materialization/platform compromises introduced;
6. `PRELOAD.md` contract impact or explicit `none`;
7. proof that no `libs/` path was added, restored, or referenced.

## Initial Jules dispatch handles

These are non-blocking eventual-delivery sessions. They are recorded here without polling or applying their branches.

| Job | Jules session | URL |
|---|---:|---|
| J01 Kernel algebra | `14840399884225250297` | https://jules.google.com/session/14840399884225250297 |
| J02 Collections/mutation | `13311535945178380322` | https://jules.google.com/session/13311535945178380322 |
| J03 Cursor/Confix | `12639689876623846507` | https://jules.google.com/session/12639689876623846507 |
| J04 Platform/NIO | `16539428462314468046` | https://jules.google.com/session/16539428462314468046 |
| J05 Structured ingestion | `588221054199733845` | https://jules.google.com/session/588221054199733845 |
| J06 ISAM/persistence | `12153660722558468718` | https://jules.google.com/session/12153660722558468718 |
| J07 LCNC reduction | `5764198754886875321` | https://jules.google.com/session/5764198754886875321 |
| J08 Transport/protocol | `16533995807755492147` | https://jules.google.com/session/16533995807755492147 |
| J09 DHT/routing | `6179399533503718869` | https://jules.google.com/session/6179399533503718869 |
| J10 External API ingress | `10615492983242580647` | https://jules.google.com/session/10615492983242580647 |
| J11 Compute/Graal | `18292688095299401383` | https://jules.google.com/session/18292688095299401383 |
| J12 Forge top | `13413214599923051064` | https://jules.google.com/session/13413214599923051064 |
