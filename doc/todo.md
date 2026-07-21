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

- [ ] **GATE-CONFIX-CBOR. One portable serializer and one canonical CBOR path**
  - Contract: `KSerializer<T> ↔ Confix Encoder/Decoder ↔ ConfixDoc/RowVec ↔ JSON/YAML/CBOR bytes`. Confix is the `SerialFormat`; Kotlin serialization supplies generated serializers and the base `kotlinx-serialization-core` library only.
  - Classpath invariant: beside the Kotlin serialization base/core library, Confix is the only serialization format. No `kotlinx-serialization-json`, `kotlinx-serialization-cbor`, protobuf, or properties runtime may remain on a product runtime classpath or act as an intermediate DOM.
  - Current verified gap: `jvmRuntimeClasspath` contains `kotlinx-serialization-json:1.11.0`; `commonMain` contains forbidden `JsonElement`/`JsonObject`/`JsonPrimitive` references; `parse/confix/ConfixSerialization.kt` is in `jvmMain` and routes through the kotlinx JSON DOM. The existing `ConfixSerializationBoundaryTest` states the intended boundary but the tree currently violates it.
  - Canonical CBOR must be one Confix-owned RFC 8949 implementation, not `CanonicalCbor` plus an unrelated Confix scanner. Pin deterministic map ordering, definite lengths, minimal integer widths, nested arrays/maps, byte/text strings, tags, floats, null/bool, malformed/truncated rejection, and Confix `(value,key)` kid order. Live processing, CID computation, WAL replay, and cross-target decode use the same bytes and the same lowering path.
  - Evidence: boundary test scans all product source sets and resolved runtime classpaths; dependency reports show only `kotlinx-serialization-core`; RFC 8949 vectors and malformed-input tests pass; JVM/JS/Wasm/Native encode identical fixtures byte-for-byte; every encoded `ConfixDoc` decodes to the same facets and canonical re-encoding is idempotent.

- [ ] **GATE-NGSCTP. Finish TrikeShed ngSCTP from the KMPngSCTP README contract**
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

- [ ] **GATE-CLEAN-MASTER. Clean master of integrity debt and branch drift** (POSITIONING PAPER §7.1.1, §7.3.1)
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

## Task DAG (Jules-sized domains)

### Foundation layer (must land first)

- [x] **T1. Reactor algebra in commonMain** (DRAINED 2026-07-20, commit 114f5314)
  - ChannelMessage / ChannelResponse / ReactorConfig / ReactorError / SessionState / TransformCode / WamBlock landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (T-jules session `13631575799754534320`); agent did not push a PR.
  - Port `Protocol`, `WamBlock`, `SessionState`, `TransformCode` from litebike taxonomy.
  - Define `ReactorError`, `ChannelMessage`, `ChannelResponse`, `ReactorConfig`.
  - Keep it pure Join/Series/Cursor-shaped.
  - Targets: `commonMain`.
  - Evidence: compiles in `commonMain`, unit tests for protocol ID round-trip and transform identity.

- [x] **T2. NUID / authorization algebra in commonMain** (DRAINED 2026-07-21, commit ed8d5a79)
  - `Nuid`, `Capability`, `Nonce`, `Subnet` as data classes / typealiases.
  - `TraitSpace` matching `capability` against a worker's advertised traits.
  - Subnet routing prefix rules.
  - Targets: `commonMain`.
  - Evidence: compiles, tests for trait matching and subnet containment.

- [x] **T3. Volume / BlockArray / BootBlock interface in commonMain**
  - `Volume` interface: `blockSize`, `capacity`, `read(lba, count)`, `write(lba, data)`, `sync()`.
  - `BlockArray` and `BootBlock` helpers on top of `Volume`.
  - Targets: `commonMain`.
  - Evidence: compiles, tests for in-memory `Volume` backend.

- [x] **T4. ReactorEndpoint / confix wire transport in commonMain** (DRAINED 2026-07-20, commit faa2619d)
  - `ConfixEnvelopeCodec` + `ReactorEnvelopAction` landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (Jules session `5891915718907135319`).
  - `ReactorAction` and `ReactorResult` as `Join<Nuid, Join<Verb, Payload>>` and response envelope.
  - `ReactorEndpoint` interface: `invoke(action) -> result`.
  - Confix serialization for action/result payloads.
  - Targets: `commonMain`.
  - Evidence: compiles, round-trip serialize a NUID-authorized action.

### Storage backend layer (parallel after T3)

- [ ] **T5. Native Volume backend**
  - `PosixVolume` using existing `PosixFileOperations`.
  - `LiburingVolume` for async batching on Linux.
  - Targets: `nativeMain` (`posixMain` / `linuxMain`).
  - Evidence: native tests pass on Linux; macOS uses posix fallback.

- [x] **T6. Btrfs userspace JBOD backend** (DRAINED 2026-07-21, commit da20abcd)
  - `BtrfsVolume` implementing `Volume` by parsing superblock, chunk tree, device tree.
  - Built on top of `Volume`, not replacing it.
  - Targets: `jvmMain`/`nativeMain` (mmap + io_uring).
  - Evidence: can read a raw Btrfs image or JBOD array metadata.

- [x] **T7. Browser storage backend** (DRAINED 2026-07-20, commit 9f2ab178)
  - `OpfsVolume`, `IndexedDbVolume`, `BlockDevice`, browser-storage test landed in `src/commonMain/kotlin/borg/trikeshed/browser/storage/`.
  - Recovered via missing-PR pattern (Jules session `15876474675057978179`).
  - `OpfsVolume` and `IndexedDbVolume` implementing `Volume` over browser storage APIs.
  - Block semantics emulated; no real Btrfs in the browser.
  - Targets: `jsMain`/`wasmJsMain`.
  - Evidence: browser tests or headless JS test for read/write block round-trip.

### Transport / proxy layer (parallel after T1, T2, T4)

- [x] **T8. Node localhost proxy** (DRAINED 2026-07-21, commit ed8d5a79)
  - `NodeReactorEndpoint` in `jsMain` that wraps `FetchReactorEndpoint`.
  - Server-side forwarder that accepts `/api` actions and routes to a local `Reactor`.
  - Targets: `jsMain` + JVM/Native server.
  - Evidence: PWA can connect to `localhost:PORT` and invoke a ping action.

- [x] **T9a. Mesh / SCTP reactor adapter** (DRAINED 2026-07-20, commit 19a84b2d)
  - `MeshActionFrame`, `MeshErrorCode`, `MeshActionResult`, `MeshConfig`, `SctpReactorEndpoint`, `MeshReactorEndpoint` landed in `src/commonMain/kotlin/borg/trikeshed/reactor/`.
  - Recovered via missing-PR pattern (Jules session `13098165998827396591`).
  - `MeshReactorEndpoint` and `SctpReactorEndpoint` implementing `ReactorEndpoint`.
  - Peer discovery over the reactor blackboard.
  - This landed the reactor/frame adapter only. It is not completion of the ngSCTP transport.

- [ ] **T9b. Finish the existing TrikeShed SCTP/reactor spine** (GATE-NGSCTP)
  - Read the KMPngSCTP README/source as donor evidence, then port only missing behavior into the canonical TrikeShed implementation.
  - No nested donor checkout, new subproject, external transport framework, or duplicate protocol types.
  - Evidence: loopback action exchange plus multihoming failover, partial reliability, migration, and structured cancellation tests.

- [x] **T10. litebike gate / tunnel adaptation** (DRAINED 2026-07-21, PR #241, commit c7cd42059)
  - `Protocol`, `Tunnel`, `SshTunnel` interfaces landed in `src/commonMain/kotlin/borg/trikeshed/litebike/`.
  - `ProtocolDetector` for protocol identification.
  - `LitebikeListenerElement` with protocol-keyed channel slots.
  - Browser uses `ReactorEndpoint` to ask a native/node peer to open a tunnel.
  - Targets: `commonMain` + `nativeMain`/`jvmMain` + `jsMain`.
  - Evidence: protocol detection test; native SSH exec round-trip (or mock).

### Workers / capabilities layer (parallel after T2, T4, T7, T8, T9, T10)

- [x] **T11. CAS worker** (DRAINED 2026-07-20, commit 42f3b209)
  - `BlockIndex` (and supporting CAS worker types) landed in `src/commonMain/kotlin/borg/trikeshed/cas/`.
  - Recovered via missing-PR pattern (Jules session `6719119381933539177`).
  - Content-addressed store (`CasStore`) on `Volume`.
  - Manifest CIDs, deterministic archives, replication hooks.
  - Targets: `commonMain` + platform backends.
  - Evidence: `ContentId` round-trip, manifest CID deterministic across runs.

- [x] **T12. Process worker** (DRAINED 2026-07-20, commit f1ee66394)
  - `ProcessCapability` / `ProcessResult` / `ProcessSpec` / `ProcessWorker` / per-platform Factories (`Jvm`, `Native`) and `ProcessWorkerContractTest` landed in `src/{commonMain,jvmMain,nativeMain}/kotlin/borg/trikeshed/userspace/nio/process/`.
  - Recovered via missing-PR pattern (Jules session `9179777146483861444`).
  - `Process` capability using existing `PosixProcessOperations` (moved to macOS/linux).
  - NUID-authorized process spawn/exec over the reactor.
  - Targets: `nativeMain`.
  - Evidence: spawn `echo` via reactor action, receive stdout as result.

- [ ] **T13. Wireproto / Confix worker**
  - Serialize/deserialize `ReactorAction` through the single Confix canonical-CBOR path from GATE-CONFIX-CBOR.
  - Path/cursor transport over `ReactorEndpoint`.
  - Targets: `commonMain`.
  - Evidence: round-trip a cursor through a wireproto-encoded action with byte-identical JVM/JS/Wasm/Native canonical output and no non-core kotlinx serialization runtime.

- [x] **T14. ModelMux worker** (DISPATCHED 2026-07-20, session 18443322164395743742, IN_PROGRESS)
- [x] **T24. LCNC ROLLUP reducer** (DRAINED 2026-07-20, PR #229, commit 98c2386db via a8dfb9ad2)
  - `RollupReducer` + supporting types landed in `src/commonMain/kotlin/borg/trikeshed/lcnc/reduction/`.
  - Agent self-PR #229 opened and merged.

### UI / blackboard layer (last)
  - Port litebike `keymux` model facade / DSEL / provider selection.
  - Model invocation as a `ReactorAction` proxied to a model worker.
  - Targets: `commonMain` + `ReactorEndpoint`.
  - Evidence: provider selection rule resolves; model request routes to a mock worker.

### UI / blackboard layer (last)

- [ ] **T15. PWA / gallery UI**
  - Forge gallery / blackboard renderer in `wasmJs`/`js`.
  - Talks to localhost Node proxy or degrades to offline OPFS.
  - Targets: `jsMain`/`wasmJsMain`.
  - Evidence: browser build passes, gallery renders from a test blackboard.

## Target-feature bijection for the HTML window manager

The Forge window manager should be a single HTML/DOM shell in
`src/commonMain/resources`, rendered on every platform by a per-target
`ForgeWindowManager` SPI. `manimwm-tk` is retained as a native desktop
render/composit layer, not as the window manager.

Per-target mapping:

| Target | `ForgeWindowManager` impl | Display surface | Storage prefix | Network |
|---|---|---|---|---|
| `jvm` | `JvmForgeWindowManager` | Compose + embedded browser (JCEF/WebView) or external browser | `.local/forge` | JVM sockets |
| `macos`/`linux` | `NativeForgeWindowManager` | system browser or embedded WebView (optional) | `.local/forge` | native sockets |
| `js` (node) | `NodeForgeWindowManager` | serve HTML to browser, or headless | `.local/forge` | node sockets |
| `wasmJs` (browser) | `BrowserForgeWindowManager` | browser DOM | OPFS/IndexedDB | fetch/WebSocket |
| `android` | `AndroidForgeWindowManager` | WebView | app storage | Android sockets |
| `wasi` | `WasiForgeWindowManager` | none / textual | WASM sandbox | WASI sockets |

- [x] **T16. Define `ForgeWindowManager` SPI in commonMain** (DRAINED 2026-07-20, PR #231, commit 0ddf1ecfa via 059612622)
  - `ForgeWindowManager` (interface) + `ScriptSnippet` / `WindowEvent` / `WindowSnapshot` data classes landed in `src/commonMain/kotlin/borg/trikeshed/forge/window/`.
  - Agent self-PR #231 opened and merged after the session reached `state: COMPLETED`.
- [x] **T17. Move HTML shell assets into `src/commonMain/resources`** (DRAINED 2026-07-20, PR #232, commit f260bb825 via 34fb5ffc8)
  - `HtmlShell`, `ShellAssetRegistry`, `ShellConfig` + `app.css`/`app.js`/`index.html` resources + per-target bindings (`jsMain`, `jvmMain`, `wasmJsMain`) + `HtmlShellTest` landed.
  - Agent self-PR #232 opened and merged.
- [x] **T18. Implement per-target window managers** (DRAINED 2026-07-20, session 717567726403101346)
  - Per-target `BrowserForgeWindowManager` / `NodeForgeWindowManager` / `JvmForgeWindowManager` / `NativeForgeWindowManager` / `WasiForgeWindowManager` landed in their respective `src/{jsMain,jvmMain,macosMain,linuxMain,wasiMain,wasmJsMain}/kotlin/borg/trikeshed/forge/window/`.
  - Session still IN_PROGRESS at doc-time; will land via standard PR cycle or missing-PR fallback.
- [ ] **T18 PR-landed condition: `WindowManagerContractTest` (`commonTest`) passes `./gradlew jvmTest`.**
- [x] **T19. Reposition `manimwm-tk` as a native render/composit layer**
  - `manimwm` keeps its SPI (`ManimWmSpi`) but is no longer the window manager.
  - Native desktop: the HTML window manager requests frames/textures from `manimwm` and positions them in the DOM via a canvas or WebGL surface.
  - Browser: `manimwm` can render to a `<canvas>`/WebGL if ported, or the browser uses its own animation layer.
  - Targets: `commonMain` interface; native/JVM implementations.
  - Evidence: a native desktop build shows the HTML shell with a manim-rendered canvas panel inside it.

- [x] **T20. Add missing targets to Gradle build**
  - `android()` target with `androidMain` source set.
  - `wasmWasi()` target with `wasiMain` source set.
  - Ensure `composeCompiler` stays restricted to `KotlinPlatformType.jvm`.
  - Targets: `build.gradle.kts`.
  - Evidence: `./gradlew build` succeeds for new targets on host; non-host targets are ignored via `kotlin.native.ignoreDisabledTargets=true`.

- [ ] **T21. Align docs/ gh-pages with the shared HTML shell**
  - The `docs/` build is the same `src/commonMain/resources` HTML, packaged for static hosting.
  - Sync task must keep `<script src="./TrikeShed.js"></script>` verbatim.
  - Targets: `build.gradle.kts` sync task + `docs/`.
  - Evidence: `curl -s <gh-pages url> | grep TrikeShed.js` matches.

## Build / gateway discipline

- Every Jules task must keep its own slice green under `./gradlew build`.
- The global `./gradlew build` is a gateway check, not a long-running Jules session.
- Stale or broken tests should be excluded from compilation, not deleted, until reconciled.
- No platform IO or Btrfs code in `commonMain` or browser targets.
- **The five gates at the top of this file outrank feature expansion.** A green slice that adds another serializer or SCTP implementation while Confix CBOR and upstream KMPngSCTP remain disconnected is not green at the architecture boundary. Likewise, feature-local green does not substitute for license, master, and maturity-map trust gates.

## Open questions / risks

- [ ] Linux `PosixProcessOperations` currently missing (file is in `macosMain`). Need `linuxMain` copy.
- [ ] `macosX64Main.dependsOn(macosMain)` triggers Gradle warning; may need to drop or rewire.
- [ ] `../litebike/` is Rust; porting `rbcursive` SIMD detection may require JVM Panama or native fallback.
- [ ] NUID key material / revocation story needs a concrete design before T2 is finalized.
- [ ] Browser PWA cannot open raw sockets; all tunneling must be proxy-mediated.
- [ ] `jvm` target currently uses Compose Desktop; embedding HTML means choosing JCEF, JavaFX WebView, or an external browser. Decision needed before T18.
- [ ] `wasmWasi` has no display; T18 will be a no-op/textual implementation. Need to confirm whether this is useful for a headless reactor worker.
- [ ] `android` target is not yet in build.gradle.kts; adding it requires Android Gradle Plugin and SDK setup.

## LCNC no-code layer — gap follow-up (Jul 2026 audit)

The `lcnc/` package is half implementation, half aspirational. The no-code
model — `LcncAssociative` (Database + PropertySchema + PropertyType),
`LcncTaxonomy` / `ForgeTaxonomy` (block-tree page model), `IngestCodec`
(Paste / FileStream / Link + IngestFormat), `IngestStateElement`, and
`LcncGrid` (Cursor surface) — is real and unit-tested at the type level.

The visual, formula, relation, and page-as-database layers exist only as
empty enum cases in `LcncAssociative.PropertyType`. No editor, no parser,
no reducer, no consumer. Each is a stub that future tasks must either
implement or remove.

- [ ] **T22. LCNC visual editor — Block + Database views**
  - Currently: `LcncAssociative` defines column types and `LcncBlock` defines
    block kinds, but there is no `BlockEditor` / `PropertyEditor` /
    `DatabaseView` implementation anywhere in `commonMain` or per-target source sets.
  - Targets: `commonMain` algebra + `jsMain`/`wasmJsMain` rendering for the PWA;
    `jvmMain` Compose Desktop view optional.
  - Evidence: a property-grid surface renders a Database with at least three
    column types (`TEXT`, `SELECT`, `CHECKBOX`), cells persist, edits round-trip
    through `IngestStateElement`.
  - Note: zero production callers today (the entire package is self-enclosed).
    This task cannot land until at least one of `forge/`, `kanban/`, or a new
    `lcnc-view/` package actually imports the editor.

- [ ] **T23. LCNC `FORMULA` parser + reducer (PropertyType.FORMULA)**
  - Currently: `PropertyType.FORMULA` enum case exists; no `Formula` AST,
    no parser, no evaluator.
  - Targets: `commonMain` parser + evaluator; `jvmMain`/`nativeMain` may want a
    Panama-backed fast path later but commonMain-only first.
  - Evidence: parse `if(prop("Done"), 1, 0)` into a Formula AST, evaluate against
    a row, return the right typed value; round-trip through a `Database`.
  - Coupling: T22 needs this for property grid cells of type FORMULA.

- [ ] **T24. LCNC `ROLLUP` reducer (PropertyType.ROLLUP)**
  - Currently: enum case exists; no `Rollup` traversal; the closest code path
    is `LcncReductions` with `BuiltinReducer.{SUM, COUNT, MIN, MAX, AVG,
    STDDEV, PERCENTILE_*}` — that algebra is the right spine but it is not
    wired to PropertyType.ROLLUP.
  - Targets: `commonMain`; reuse the `reduction/Carrier` + `LcncReduction`
    pipeline already in package.
  - Evidence: a `RereduceStage` consumer reading a related database produces the
    right rollup cell for a SUM, AVG, and PERCENTILE_95 reducer.

- [ ] **T25. LCNC `RELATION` traversal (PropertyType.RELATION)**
  - Currently: enum case exists; no `RelationIndex`, no `RelationView`,
    no query path.
  - Targets: `commonMain` algebra + per-target cursor projection.
  - Evidence: a Database with `PropertySchema(type = RELATION, target =
    otherDb)` lets a view resolve the related rows and project them through
    a Cursor.

- [ ] **T26. LCNC `PEOPLE` / `FILES` typed properties (PropertyType.PEOPLE,
      FILES)**
  - Currently: enum cases exist; no producer, no consumer, no ingest support
    beyond the bare enum case.
  - Targets: `commonMain` types + `IngestCodec` adapters that consume them.
  - Evidence: a `PEOPLE` cell serializes as `Series<UserRef>` and a `FILES`
    cell as `Series<FileRef>`; an ingest path that reads `markdown` with image
    references produces `FILES` cells.

- [ ] **T27. Ingest pipeline that actually feeds a Database / page**
  - Currently: `IngestCodec` defines `IngestSource` and `IngestFormat` (CSV,
    TSV, MARKDOWN, HTML, JSON, LCNC_NATIVE) — format/transport enums only;
    there is no parser that produces an `LcncBlock` series or a `Database`,
    and no consumer that writes one. `IngestStateElement` collects entities
    into a `mutableListOf` in-process but never emits them.
  - Targets: `commonMain` parsers + reactor binding through `IngestStateElement`.
  - Evidence: paste a CSV, see a `Database` with inferred column types;
    paste a Markdown doc, see an `LcncBlock` tree; both written through the
    CCEK element's lifecycle (CREATED → OPEN → ACTIVE → DRAINING → CLOSED),
    not just a `mutableListOf` accumulator.

- [ ] **T28. Split `lcnc/reduction/*` out of the LCNC package**
  - The reduction engine (`LcncReduction`, `ReductionCarrier`,
    `LcncCarrierAlg`, `LcncKeyAlg`, `LcncValueAlg`, `LcncPhaseAlg`,
    `LcncSupport`, `LcncReductions`, `ConfixReducers`, `ForgeReducers`,
    `CrmsReducers`) was extracted from `forge/`, `parse/confix/`, and the
    old CRMS tree ("extracted from ForgeWorkspaceImpl"; "extracted from
    the Confix parser"; "extracted from the CRMS fold"). It is misfiled
    under `lcnc/reduction/`. Functional homes: move to `reduction/`
    package at the root of `commonMain`, OR fold concrete adapters
    (`ConfixReducers`, `ForgeReducers`, `CrmsReducers`) back into their
    consumer packages and keep only the algebras in `reduction/`.
  - Targets: `commonMain` package shape.
  - Evidence: `rg 'borg.trikeshed.lcnc' src/` outside `lcnc/` and tests →
    only reduction/* imports; after the move, only `reduction/*` imports,
    LCNC taxons (Associative, Taxonomy, Grid, Ingest) are LCNC-shaped.

- [ ] **T29. Decide one of: implement or de-stub the aspirational
      PropertyType cases**
  - `LcncAssociative.PropertyType` lists TITLE, TEXT, NUMBER, SELECT,
    MULTI_SELECT, DATE, PEOPLE, FILES, CHECKBOX, URL, EMAIL, PHONE_NUMBER,
    FORMULA, RELATION, ROLLUP, CREATED_TIME, CREATED_BY, LAST_EDITED_TIME,
    LAST_EDITED_BY. Of these, only TITLE/TEXT/NUMBER/SELECT/CHECKBOX/DATE
    have any downstream treatment — and even those have minimal ingest /
    no editor. The remaining cases are vocabulary promises with no backing.
  - Decision: either implement via T22-T27 or remove the unimplemented
    cases from the enum (keeping one COMMENT note per removed value about
    what it once meant) to keep the surface honest.

## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)

- elastic/ removed — was a CRIT structural shadow of `interface Join` /
  `typealias Series` with zero importers in `src/`.
- `classfile/slab/**` excluded from `commonMain` compile path — entire layer
  of ~20 `TODO()` stubs (GraalJS eval / DuckDB c-interop / FacetedCursor /
  MiniDuck contract) with zero non-test consumers; files preserved on disk.
- `ConfixClassfileDir.kt`: dead helpers (`mkSeries`, `withFacet`, `inMode`,
  `tagged`, `ChildRowVec`, `childRowVec`) removed — they depended on the
  excluded slab layer. Real entry points (`pathOf`, `nodeVal`) remain.
- `CircularQueue` `TODO("...")` → `error(...)` in `poll`/`peek`/`iterator.remove`
  — silent-hollow stub is now loud at the call site instead of silently
  returning or throwing a misleading message.
- NUID algebra (T-NUID-1) — `src/commonMain/.../context/nuid/Nuid.kt`:
  Capability sealed hierarchy with family wildcard roots; Subnet
  concentric containment; Nonce RandomBytes + Derived (causal chain);
  `Nuid = Join<Capability, Join<Nonce, Subnet>>`; TraitSpace +
  Workgroup.canHandle; NuidElement as CCEK bearer. No platform IO,
  compiles across Macos / JVM / JS / WasmJs.
- T-CCEK-FANOUT-2 — `src/commonMain/.../context/nuid/NuidFanoutElement.kt`:
  concentric-narrowing dispatcher. Owns a registry of Workgroups; on
  `dispatch(nuid)` filters by `scope contains nuid.subnet` AND
  `TraitSpace.can(nuid.capability)`, sorts by scope level ascending,
  offers the Claim to candidates at the request's level, escalates
  outward on timeout up to `escalationBudget + 1` levels. CCEK
  lifecycle owner. Same shape as HtxElement / SctpElement.

## Running Kanban live — RGA-anchored task list (Jul 2026 audit)

The "real Kanban server driven by Hermes-donor traces → LCNC" milestone
requires the cuts in dependency order below. Gaps identified in the
post-NUID/CCEK audit. Each task is single-best-debt-reduction sized
(1-3 files, real verification, non-goals explicit).

- [x] **T-KANBAN-HTTP-1. `KanbanHttpServerJvm` in jvmMain — closes G01+G02+G06** (DRAINED 2026-07-21, commit da20abcd)
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanHttpServerJvm.kt`
  - Builds on the existing `HtxReactorElement` (no new HTTP machinery).
  - Mounts `POST /api/submit` for markdown ingest (calls
    `ForgeKanbanIngest.persistMarkdown` and returns the resulting
    `causalKey`s) and `GET /api/board` for the projected
    `ForgeAppState` JSON.
  - Owns one `NuidFanoutElement` registered with workgroups wrapping
    the existing reducers (`Process`, `Cas`, `Wireproto`).
  - Has a `KanbanServerMain` entrypoint that takes `--port` and `--donor`
    and runs forever (`runBlocking` + structured concurrency).
  - Verification: `./gradlew compileKotlinJvm`, then
    `./gradlew jvmRun -PmainClass=...KanbanServerMain --args="--port 8888 --donor /tmp/hi"`
    followed by `curl -s localhost:8888/api/board | jq . && curl -s
    -X POST -d @/tmp/hi localhost:8888/api/submit`.
  - Non-goals: do NOT add a new HTTP framework; do NOT change
    `RfxHttpServerJvm`; do NOT touch the websocket surface
    (T-KANBAN-PUSH-4 covers that).

- [ ] **T-KANBAN-LCNC-2. Replace `LcncEntityDTO` projection with
      `LcncBlock` construction (closes G05)**
  - File: `src/commonMain/kotlin/borg/trikeshed/forge/forgeAppStateLcnc.kt`
    (new) and a small edit in `src/commonMain/.../forge/ForgeApp.kt`.
  - Goal: replace the `LcncEntityDTO { entityId, lcncKind, lane, facet,
    causalKey, title, description }` factory at `ForgeApp.kt:277-289`
    with a `LcncEntity`-shape constructor. `LcncTaxonomy.kt:30-63` is
    the canonical type.
  - Verification: `./gradlew compileKotlinJvm compileKotlinMacos`,
    plus the existing `LcncReductionCoreTest` continues to compile (no
    removed surface).
  - Non-goals: do NOT add a `Database` / `PropertySchema` editor; do
    NOT change the PWA HTML; do NOT change `LcncTaxonomy` itself.

- [ ] **T-KANBAN-DONOR-3. Donor trace ingestion path (closes G04)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/donor/HermesDonorTrace.kt`
    + a small reader on the side.
  - Goal: accept either a sidecar markdown file
    (`--donor /path/to/trace.md` already supported) OR a SQLite donor
    over `~/.hermes/kanban.db` (read-only — Python's
    `~/.hermes/hermes-agent/hermes_cli/kanban_db.py` shape). One format
    per `--donor-format=md|sqlite`.
  - Verification: `curl -X POST localhost:8888/api/donor?format=sqlite`
    replays the SQLite board into the local-first `IngestStateElement`
    cycle.
  - Non-goals: do NOT import the Python module; do NOT write to the
    donor DB; do NOT change the Hermes-MCP path.

- [ ] **T-KANBAN-PUSH-4. Server-side reactive push to the PWA
      (closes G14)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanPushBus.kt`
    + a `/api/stream` WebSocket handler in the existing
    `KanbanHttpServerJvm`.
  - Goal: every successful `POST /api/submit` publishes a
    `BlackboardSurface` patch over `/api/stream` so connected PWA
    instances update without reload.
  - Verification: open PWA in browser, then `curl -X POST -d
    @/tmp/hi localhost:8888/api/submit` and observe the board change
    in the browser without a page refresh.
  - Non-goals: do NOT add a queue framework; do NOT reach into the
    PWA's IndexedDB; do NOT change the seed-JSON path.

- [ ] **T-KANBAN-LCNFANOUT-5. `LcncFanoutElement` merging LCNC +
      NUID surfaces (closes G08)**
  - File: `src/commonMain/.../context/lcnc/LcncFanoutElement.kt`.
  - Goal: extend `NuidFanoutElement` so that `dispatch(nuid, payload)`
    returns `ReducerRegistry.runFor(winningCapability, payload)`
    (closes G10 in the same cut).
  - Non-goals: do NOT replace `NuidFanoutElement`; do NOT change the
    polling primitive (T-KANBAN-FANOUT-6 covers that).

- [ ] **T-KANBAN-FANOUT-6. Replace scalar polling with
      `MutableSharedFlow` (closes G11)**
  - File: edit to `NuidFanoutElement.kt`, ~30 lines.
  - Goal: replace the `pollForWinner(...)` poll-loop with a
    `MutableSharedFlow<Claim>` and `withTimeoutOrNull`.
  - Verification: unit test for 16+ candidates, expecting sub-100ms
    selection under fanout.
  - Non-goals: do NOT change the concentric narrowing logic.

- [x] **T-KANBAN-WAL-7. WAL for causal chain recovery (closes G12)** (DRAINED 2026-07-21, commit 7c7ebd32d)
  - File: `src/jvmMain/.../forge/persistence/CausalWal.kt`.
  - `JvmKanbanServer` now has `causalWal` and `graphIndex`.
  - Adds log replay logic to reconstruct causal nodes on daemon startup.
  - Adds append logic within the `/api/submit` flow to persist changes to `.causal.wal`.
  - Leverages JsonSupport for object serialization during append operations.
  - Evidence: daemon restart replays causal chain; WAL appends on submit.

- [ ] **T-KANBAN-LCNCPIPE-8. `LcncIngestPipeline` producing
      `Series<LcncEntity>` from Paste / FileStream / Link (closes G07)**
  - File: `src/commonMain/.../lcnc/reactor/LcncIngestPipeline.kt`.
  - Goal: implement `IngestCodec.decode(IngestSource, IngestFormat):
    Series<LcncEntity>` and publish through `IngestStateElement`
    lifecycle (CREATED → OPEN → ACTIVE → DRAINING → CLOSED) with
    `Channel<ReactorAction>` fanout, not a `mutableListOf` accumulator.

- [x] **T-KANBAN-PERSIST-9. Pick a persistence surface (closes G09)**
  - Decision only — either port the Hermes SQLite schema to Kotlin
    (~300 lines) or officially adopt the JSON / ConfixDocStore path
    and document it. No code in this task — sign-off only.

- [ ] **T-KANBAN-REDUCER-10. `ReducerRegistry` for the fanout mix
      (closes G10 if not already done in T-KANBAN-LCNFANOUT-5)**

- [ ] **T-KANBAN-CROSS-11. Single submission format shared between
      Forge path and Hermes-donor path (closes G15)**

## Resume ingest → causal Kanban → ModelMux fulfillment — RGA 2026-07-20

Target runtime:

`resume + job-requisition bytes → CAS/extraction evidence → semantic/Narsese
signal bags → Couch reducer multiverse → causal facts → card reducers →
ModelMux work descriptors → fulfillment facts → NUID concentric dispatch →
Forge projections`

Multiverse model (../couchdbcascade as reference): the Narsese signal layer is
the multiverse center that several document sources feed and that Kanban reads.
Each source (`resumes | listings | coverletters`) is a Couch document domain
whose map/reduce reducers run as `ViewServer` tools and emit evidence-backed
Narsese signals into the shared signal bags. `kanban` consumes those signals
through the same reducer pipeline rather than holding a second truth. The
reference `Atlas<C,P>` / `Chart<C,P>` manifold-atlas code stays in history
(`96e0d7b0`); it is not revived for this work.

Ingest stack: local-first PWA. Tika handles office formats (PDF/DOCX/PPTX,
image OCR via Tesseract with an ffmpeg preprocessing hook). GDoc export is
pulled through the same Tika/filters pipeline once it lands as bytes. Camel
routs files only when a transport-mediated path is needed. Filters normalize
and classify the extracted streams before they reach the evidence reducers.
Non-Tika targets (plain text, markdown, JSON) are read directly. Ingest UX is
either a drag-and-drop onto a blackboard coordinate or a dialog, both of which
produce the same evidence submission. A web-scraper plugin lands later and
shares the same evidence submission contract; it is out of scope for the first
vertical.

UI contract: Kanban and the force-directed causality graph remain separate,
first-class views over the same causal/card identity. The user switches between
them; do not collapse them into a hybrid canvas. Selection, camera focus, and
card/node identity survive the switch.

Resumes and job requisitions occupy a signalling panel beside those views. The
panel is not a third mutable truth: it projects two evidence-backed bags and
their relations. Resume signals say what the candidate can evidence; requisition
signals say what the role requires or prefers. Matches, gaps, contradictions,
and missing evidence are derived signals that open/focus the same causal card.

The pieces exist, but this runtime does not. Evidence below is from live source
and `./gradlew jvmTest --tests ConcentricKanbanDemoTest --tests
LcncFanoutElementTest --tests ModelMuxTest` on 2026-07-20; compilation failed
before tests on the cited merged-source errors.

| ID | Severity | Live backing | Gap |
|---|---|---|---|
| RSM-01 | CRIT | `JvmTikaIngestAdapter.extractToMarkdown` extracts PDF/DOCX/image text (`src/jvmMain/.../kanban/JvmTikaIngestAdapter.kt:53-94`) | `ForgeKanbanIngest` accepts only a literal `6. Work packages` section with `A1 — title` headers (`ForgeKanbanIngest.kt:247-269`). An ordinary resume therefore extracts successfully and then fails with `no work packages found`; no resume facts, spans, or evidence IDs are produced. |
| RSM-02 | CRIT | `JvmKanbanServer` creates `ingestPath` for Tika output (`JvmKanbanServer.kt:178-191`) | The computed path is never consumed; line 192 calls donor ingest with the original `donorPath`. `/api/submit` writes every body to `/tmp/hi` and treats it as the board source (`JvmKanbanServer.kt:277-297`). Resume ingest is not a live endpoint. The shell has a drop zone (`resources/web/index.html:48-58`) but it only enqueues files locally; it never produces evidence, signals, or a reducer submission. |
| RSM-03 | CRIT | `LitebikeListenerElement`, `NuidFanoutElement`, and three workgroups are constructed (`JvmKanbanServer.kt:91-140`) | Wire fanout only logs and returns true (`:223-229`); it never calls `NuidFanoutElement.dispatch`. The HTTP worker looks up `slotOf("wireproto")` (`:203-220`) although registration used `kanban-wireproto-lan` (`:107-114`), so the worker returns immediately. No broadcast-node request reaches a reducer. |
| RSM-04 | HIGH | `ForgeKanbanIngest.reduce` creates cards, Rete facts, causal nodes, and correlations (`ForgeKanbanIngest.kt:105-244`) | It constructs a derived `KanbanBoard` directly. It does not submit `JobCommand`s through the durable single-writer supervisor, so card transitions are not replayable reducer outcomes. |
| RSM-05 | HIGH | `JobReducer` supports submit/start/complete/fail/retry/progress/block/move (`JobCommand.kt:9-93`, `JobReducer.kt:50-163`) | No production Kanban ingress calls it. `ConcentricKanbanDemoTest` manually copies board cards after reducer calls (`ConcentricKanbanDemoTest.kt:102-129`), proving projection and reducer are adjacent but disconnected. |
| RSM-06 | HIGH | `ForgeKanbanDaemon` can queue and execute `ModelCallDescriptor`s (`ForgeKanbanDaemon.kt:57-147`) | It has zero production callers. Results are truncated into an in-memory board copy; there is no causal output CID, fulfillment fact, or `JobCommand.Complete/Fail` lowering. WAL replay only iterates records (`:33-38`). |
| RSM-07 | HIGH | `ModelMux` performs chat/embed and reactor cache/lease handling (`modelmux/ModelMux.kt:124-279`) | Kanban uses an explicit model ID rather than capability + NUID route selection. `CreeperNode`, the only proposed bridge, is uncalled and currently fails compilation against the live NUID/AcpAction/ModelMux API (`CreeperNode.kt:36-70`). |
| RSM-08 | HIGH | `NuidFanoutElement` implements concentric eligibility and outward escalation (`NuidFanoutElement.kt:205-263`) | A winning claim means only that a worker consumed its inbox. The server workers discard every accepted claim (`JvmKanbanServer.kt:116-129`); no reducer result or fulfillment is returned to the originating connection. |
| RSM-09 | MED | `LcncFanoutElement` and `ReducerRegistry` map process/cas/wireproto capabilities to reductions (`LcncFanoutElement.kt:14-43`, `ReducerRegistry.kt:5-26`) | The registry is duplicated, generic, and disconnected from the server. It has no card reducer, resume evidence reducer, fulfillment reducer, or typed output envelope. |
| RSM-10 | HIGH | CAS identities and causal nodes exist | Resume evidence has no exact source-span contract. Tika emits one flattened string, and `ForgeKanbanIngest` hashes whole task bodies. Model enrichment could not cite or replay the resume evidence that justified a card or fulfillment. |
| RSM-11 | HIGH | The standalone board renderer is complete enough to display and mutate cards (`resources/web/script.js:440-493`); the standalone deterministic force layout is implemented and tested (`forge/blackboard/ForceLayout.kt:16-134`, `ForceLayoutTest.kt:13-48`) | The current shell exposes a Graph sidebar button (`resources/web/index.html:32-37`) but has no graph view container, render function, or click handler. `setView` recognizes only `doc` and `board` (`script.js:495-511`). The two individually useful views are not switchable and do not consume one shared selected-card/node state. |
| RSM-12 | HIGH | Root `ManifoldConcept` already carries semantic angular identity plus priority/durability/quality, and `NarsBag` supports recall/near-recall (`manifold/ManifoldConcept.kt:68-176`). The mothballed `libs/nars3/Nars3Machine.kt` adds budget decay, refeeding atoms, and pair derivation. Canonical `collections.associative.FunnelHashMap` provides the needed tiered lookup (`:25-218`). | `NarsBag` is currently a `MutableList` with no production consumers or stable evidence key. The old engine depends on the retired `libs/narsive` parser/Kursive surface. We want its typed budget/derivation behavior, not another parser dependency. There is no Narsese funnel bag, concentric work scorer, pair of source bags, match/gap reducer, or signalling-panel projection. |

### Single best debt reduction: T-RESUME-FOUNDATION-1

Build one deterministic resume/job-requisition evidence vertical before adding
model fanout:

- Add a commonMain evidence contract for `RESUME` and `JOB_REQUISITION` sources:
  source CID, extracted-text CID, stable evidence ID, exact character span,
  section/kind, normalized value, and extraction version. Raw/extracted bytes
  remain in CAS; cards and signals carry references.
- Add a deterministic ingest reducer: extracted text → evidence `Series` → one
  parent `JobCommand.Submit` plus child submits for evidence-backed sections.
  Child identity derives from `sourceCid|span|kind`; dependencies point to the
  parent. No LLM is called in this pass.
- Route those commands only through `JobSupervisorElement.commands`; publish
  causal/card projections only after durable acceptance. Do not mutate
  `KanbanBoard` directly.
- Add one JVM adapter from `JvmTikaIngestAdapter.extract` to the common contract;
  preserve the original source CID and extracted-text CID. Do not feed resume
  text into the `6. Work packages` parser.
- Add one PWA-side drop path: the existing `drop-zone` ships the file to the
  JVM ingest endpoint and shows a deterministic result; the dialog path shares
  the same submission contract.
- Verify with a real `.docx` or `.pdf` fixture: repeated ingest yields identical
  evidence/job IDs; every card resolves to exact source text; parent completion
  releases children; restart replay reconstructs the same projection.

This cut creates the canonical operand needed by every later layer. ModelMux,
fulfillment, and broadcast work remain downstream until it lands:

1. `T-RESUME-NARSESEBAG-2`: extract only the useful mothballed engine behavior
   into a root `NarseseBag<K, P>` shaped as
   `FunnelHashMap<K, ManifoldConcept<P>>`. Required operations are keyed
   upsert/get/remove, `recall()` by budget energy, `recallNear()` by angular
   Hamming distance, immutable `seal()`, decay/reinforce, and deterministic
   pair derivation. Port the existing `Nars3Budget`/derivation semantics into
   the root manifold algebra; do **not** restore `libs/nars3`, `libs/narsive`,
   their Gradle modules, or new Kursive imports. Narsese enters as a typed signal
   payload produced by reducers; text parsing remains outside this bag.
2. `T-RESUME-SIGNALS-3`: define `SemanticSignal` and typed Narsese statement
   payloads keyed by evidence ID. Maintain separate resume and requisition
   `NarseseBag<SignalId, Signal>` instances. Angular distance is semantic
   proximity; `BudgetCoord(p,d,q)` controls attention, retention, and evidence
   quality. A reducer emits `MATCH`, `GAP`, `CONTRADICTION`, and
   `MISSING_EVIDENCE` relations without changing source statements. Seal each
   committed revision into a deterministic energy-sorted `Series` for the UI.
   Similarity proposes work; it never establishes truth.
3. `T-RESUME-CONCENTRIC-SCORE-4`: score eligible work after NUID capability and
   subnet admission. Rank by local-first scope distance, semantic/angular
   distance, evidence quality, priority, durability, and worker/model health.
   The score selects among already-authorized workgroups; it never broadens
   capability or subnet authority. Persist the score components and source
   signal IDs as a causal analysis fact so the choice is replayable.
4. `T-RESUME-MODELMUX-5`: capability-routed enrichment consumes evidence/signal
   IDs and emits attributable result CIDs; it never edits cards directly. Model
   output may propose or reinforce a signal only through a validated reducer;
   ModelMux uses the concentric score after its hard eligibility filter.
5. `T-RESUME-FULFILLMENT-6`: fulfillment reducer lowers model/tool outcomes to
   accepted `Complete`, `Fail`, `Block`, or child `Submit` commands with causal
   evidence references.
6. `T-RESUME-BROADCAST-7`: Litebike ingress derives a NUID, dispatches the typed
   command envelope to concentric workgroups, executes the reducer, and writes
   the correlated result to the originating connection. Local wins first;
   unclaimed work escalates outward within budget.
7. `T-RESUME-VIEWS-8`: add a third `graph` view to the existing shell, rendering
   the same causal nodes and dependency edges as the board through
   `forceLayout`. `btn-graph` selects it; Board selects the unchanged Kanban
   renderer. Keep one `selectedCausalKey` and map it to card ID/node ID in both
   directions. A graph click followed by Board highlights the same card; a card
   click followed by Graph focuses the same node. Preserve independent board
   scroll and graph camera state. No force layout inside Kanban and no columns
   inside the graph.
8. `T-RESUME-SIGNAL-PANEL-9`: render the two bags as facing resume/requisition
   lanes with the derived relations between them. Selecting a signal highlights
   its exact source span and corresponding card/node; selecting a card filters
   both bags to its causal evidence. The panel displays budget and provenance,
   not opaque model scores. It shares `selectedCausalKey` with Board and Graph.

Non-goals: no second board truth, no model call during deterministic ingest, no
credential values in cards/claims, no unbounded channel, no detached daemon
scope, no separate scheduler beside the existing Job/Rete dependency DAG, and
no combined graph/Kanban renderer.

## Storage unification — projection registry (2026-07-19)

From `doc/rewire.md` §0 (one CID, five lenses). The blackboard causal
graph is in-memory; making it CAS-backed unifies the five lenses
(auxiliary CAS / materialized / reified / btrfs content / graph trees)
under one `project(cid)` path.

- [ ] **T-CAS-PROJ-1. Projection registry — `project(cid): Lens`**
  - File: `src/commonMain/kotlin/borg/trikeshed/job/CasProjection.kt`.
  - Goal: sealed class `Lens = Raw | Cursor | BtreePage | CausalNode |
    Manifest`; `project(cid, kind)` reads `cas.get(cid)`, parses via
    `confixDoc(bytes)`, dispatches on the doc's `kind`/`tag` field.
  - Uses existing `ConfixIndexK<R>` facet machinery — no new storage,
    no new formats.
  - Verification: store a btree page, a causal node, and a manifest;
    `project` each and confirm the correct lens resolves.

- [ ] **T-CAS-PROJ-2. Blackboard causal graph → CAS-backed**
  - File: `src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt`.
  - Goal: every causal node becomes a Confix doc `{causalKey, deps: [CID...],
    payload}` stored in CAS. Edges are CIDs, not object references.
  - Traversal: `cas.get(dep) → confixDoc → recurse`. Force-directed
    layout consumes CID=identity, deps=edge-list.
  - Snapshot: record the root CID. COW: new page on every edit,
    re-point parent path to root.
  - Depends on: T-CAS-PROJ-1.
  - Verification: submit two linked jobs, snapshot the root CID,
    restart, traverse from root CID and recover both nodes + the edge.

- [ ] **T-CAS-PROJ-3. `MmapCasStore` (closes T4 from `doc/taste.md`)**
  - File: `src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt`.
  - Goal: `get(cid)` returns a mapped slice (io_uring / Panama
    MemorySegment), not a heap copy. Composes: mmap file →
    `Series<Byte>` → Confix index over mapped bytes without copy.
  - Verification: store 1MB blob, read via mapped slice, confirm
    zero heap allocation on the read path.

## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)

## T-REWIRE-3 Follow-up Cuts (from doc/rewire.md §9)

These are the separated follow-up tasks from T-REWIRE-3 (Cuts 1 and 7 landed in T-REWIRE-3).

- [ ] **T-REWIRE-3b. Modelmux kanban agent**
  JobCommand handler routing cards through modelmux.

- [ ] **T-REWIRE-3c. UPnP workspace discovery**
  Workspace announce payload over mDNS/SSDP.

- [ ] **T-REWIRE-3d. SSH mesh transport**
  SSH tunnel over litebike Tls carrying Confix docs.

- [ ] **T-REWIRE-3e. IPFS/IPNS bridge**
  CAS blocks as IPFS blocks, IPNS names = manifest CIDs.

- [ ] **T-REWIRE-3f. Progressive rendering**
  Jules jobs reading TreeDoc archives into ForgeDoc.

## Drain cycle — 2026-07-20 (T01-T27 backfill)

Sixteen Jules sessions dispatched 2026-07-20 between 18:45-22:30 UTC
covering the gaps in the Foundation (T1, T4), Storage (T7), Transport
(T8, T9, T10), Workers (T11, T12, T14), Wire (T13), Windows (T16, T17,
T18), and LCNC (T22, T23, T24, T25, T27) layers.

**Recovery paths used:**

| Path | Count | Tasks |
|------|-------|-------|
| Agent self-PR → auto-merge | 3 | T16 (PR #231), T17 (PR #232), T24 (PR #229) |
| Missing-PR recovery (manual apply from `jules remote pull`) | 8 | T01, T04, T07, T09, T11, T12, T13, T17 (duplicate of #232) |
| Still in flight (IN_PROGRESS) | 1 | T05 |
| Re-dispatched via quota polling | 4 | T14, T18, T20, T22, T23, T25, T27 (dispatch_final.sh) |

**Recovery commit log:**

| Task | Session                    | Commit      |
|------|----------------------------|-------------|
| T01  | 13631575799754534320       | 114f5314    |
| T04  | 5891915718907135319        | faa2619d    |
| T07  | 15876474675057978179       | 9f2ab178    |
| T09  | 13098165998827396591       | 19a84b2d    |
| T11  | 6719119381933539177        | 42f3b209    |
| T12  | 9179777146483861444        | f1ee66394    |
| T13  | 9444185639294947999        | 7fa55f372   |
| T17  | 3468899038734415102        | (merged into PR #232 — leading-blocked by agent self-PR) |

**Post-drain catalog state (22:48 UTC):**

- 12 sessions IN_PROGRESS + 2 QUEUED, 1 AWAITING_USER_FEEDBACK
- Active sessions: T05, T14, J15, J19, T-CAS-PROJ-1 (awaiting), T-CAS-PROJ-2, T-TASTE-8, T-TASTE-9
- 0 PR-race duplicates (after two-session deletes for T14 and T16 dups)
- HEAD = a8dfb9ad2 (master), in sync with origin/master

**Wrapper fix shipped this session:** `bin/trikeshed-jules` had a
silent `jq` compile error from a renamed `--arg starting_branch` to
`--arg startingBranch` (commit 220e8acb). Without the fix, every
`create` invocation returned `jq: $startingBranch is not defined` and
no session could be dispatched. The fix restores the predicate-gated
dispatch path.

**Open awaiting questions:** session `16116381452107715943`
(T-CAS-PROJ-1 Projection Registry) — needs per-round-trip reply when
next opportunity arises.

## Architectural Decision Record (ADR): T-KANBAN-PERSIST-9

**Date:** 2024-07-21
**Decision:** Adopt JSON / ConfixDocStore path for Kanban persistence.
**Status:** Accepted

**Context:**
We evaluated whether to port the Hermes SQLite schema to Kotlin (approx. 300 LOC) or officially adopt the JSON / ConfixDocStore path for Kanban persistence.

**Decision:**
We officially adopt the JSON / ConfixDocStore path. CouchStore combined with ConfixDoc storage natively supports the Causal Graph and Kanban features without requiring an embedded relational database dependency like SQLite across all KMP targets. The `ConfixPersistence` and `JsonFilePersistence` implementations are already functional, tested (e.g. `ConfixPersistenceTest`), and aligned with the overarching architecture of using content-addressed JSON/Confix stores on top of our custom CAS and IO bindings.

