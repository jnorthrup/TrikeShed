# Track: Channelized Blackboard Platform

**Track ID:** `channelized-blackboard-platform_20260309`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Build the smallest viable channelized platform around TrikeShed's existing strengths:

- `Cursor` as dataframe substrate
- `RowVec` as row/x-projection
- CCEK assemblies as keyed attraction points
- transport backends as lower-level projections, not architecture owners

This track exists to stop future model passes from skipping to the end-state and normalizing the design into DI, reactor boilerplate, or socket-wrapper sludge.

---

## Hard Rules

- Do not skip phases.
- Do not create a new outer abstraction just to sound complete.
- Do not move transport mechanism details above the semantic layer.
- Do not claim a blackboard overlay by stuffing provenance or derivation semantics into `TypeMemento`.
- Do not replace CCEK with strings, registries, or service-locator maps.
- Do not delete red code or red tests to fake progress.
- Do not widen scope from one protocol slice to "universal listener done".
- Do not let NIO become the public architecture just because it is convenient on JVM.

If a phase is incomplete, the next phase is blocked.

---

## Current Ground Truth

- Focused transport slice exists and passes:
  - `src/commonMain/kotlin/borg/trikeshed/net/spi/TransportSpi.kt`
  - `src/jvmMain/kotlin/one/xio/spi/NioTransportBackend.kt`
  - `src/jvmMain/kotlin/one/xio/spi/SelectorTransportBackend.kt`
  - `src/jvmMain/kotlin/one/xio/spi/LinuxNativeTransportBackend.kt`
- Thin channelization selection exists:
  - `src/commonMain/kotlin/borg/trikeshed/net/channelization/Channelization.kt`
  - `src/jvmTest/kotlin/borg/trikeshed/net/channelization/ChannelizationSelectionTest.kt`
- `Cursor` and `RowVec` are already open-ended enough to carry structured payloads:
  - `Cursor = Series<RowVec>`
  - `RowVec = Series2<Any?, () -> ColumnMeta>`
- `TypeMemento` is still too small to serve as a blackboard overlay by itself.

This means the next work is semantic tightening, not more backend breadth.

---

## Vocabulary Lock

These words are fixed for this track:

- **assembly**: keyed installed arrangement surface, usually exposed through CCEK/context
- **graph**: relation or dependency shape between protocol/runtime facts
- **job**: scheduled keyed unit of work under coroutine/worker ownership
- **block**: smallest executable exchange or reduction step
- **channelization**: choosing and projecting the lightest viable operational path from assembly to block
- **blackboard overlay**: metadata about epistemic/operational role, provenance, or derivation, separate from storage codec/type

No future slice gets to redefine these midstream.

---

## Phase Schema

### phase-00 — Vocabulary Freeze and Anti-Shortcut Guardrails
**Status:** [x] closed
**Owner:** master
**Corpus:** `conductor/tracks/channelized-blackboard-platform_20260309/`

**Deliverables:**
- record the vocabulary lock
- record hard anti-shortcut rules
- record the current ground truth

**Verification:** this plan exists and is indexed in `conductor/tracks.md`

---

### phase-01 — Minimal Channelization Planner
**Status:** [x] closed
**Owner:** master
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/borg/trikeshed/net/channelization/`

**Deliverables:**
- select the lightest viable path from installed services/providers/backend
- keep QUIC direct-service path distinct from transport-backend fallback
- keep NIO as backend projection, not public architecture

**Verification:** `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

**Evidence:**
- `selectChannelization(...)` exists
- provider/direct-service/backend fallback order exists
- focused channelization tests exist

---

### phase-02 — Session and Block Core
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-01

**Deliverables:**
- introduce the smallest commonMain execution core above transport:
  - `ChannelBlock`
  - `ChannelSession`
  - `ChannelEnvelope` or equivalent minimal exchange carrier
- keep these semantic, not NIO-shaped
- support only what the current planner needs
- no protocol-specific channel classes in this phase

**Explicit non-goals:**
- no universal listener
- no QUIC handshake runtime
- no blackboard provenance model yet
- no parser framework

**Verification:**
- focused tests for session open/close, block exchange, and backend projection boundaries
- `./gradlew focusedTransportTest -PfocusedTransportSlice=true`

**Exit gate:**
- one HTTP-like byte-stream session can be expressed without mentioning `Selector`, `SelectionKey`, `SocketChannel`, or `io_uring` in commonMain

#### phase-02a — Session Identity Only
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`
**Blocked by:** phase-01

**Deliverables:**
- add only identity/lifecycle types:
  - `ChannelSessionId`
  - `ChannelSessionState`
  - `ChannelSession`
- no payload exchange yet
- no backend binding yet

**Verification:**
- compile plus focused type tests

**Exit gate:**
- a session can be represented in commonMain without transport imports

#### phase-02b — Block Exchange Only
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-02a

**Deliverables:**
- add only block/exchange carrier types:
  - `ChannelBlock`
  - `ChannelEnvelope` or equivalent
- define minimal read/write or ingress/egress shape
- no protocol specialization

**Verification:**
- focused tests for block/envelope exchange semantics

**Exit gate:**
- block exchange is representable without backend-specific classes

#### phase-02c — Planner Projection Hook
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/borg/trikeshed/net/channelization/`
**Blocked by:** phase-02b

**Deliverables:**
- let `selectChannelization(...)` project to the new session/block core
- keep the projection minimal
- do not add protocol-specific factories

**Verification:**
- extend focused channelization tests to inspect projected session/block shape

**Exit gate:**
- channelization chooses a path and yields enough structure for one generic session

#### phase-02d — Single HTTP-like Proof
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-02c

**Deliverables:**
- prove one HTTP-like byte-stream session can be expressed through the new core
- no HTTP parser, no handler stack, no router widening

**Verification:**
- focused proof test only

**Exit gate:**
- the phase-02 top-level gate is satisfied

---

### phase-03 — Blackboard Overlay Core
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/cursor/`, `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/commonTest/kotlin/`
**Blocked by:** phase-02

**Deliverables:**
- add a separate overlay model for cursor/cell/column use:
  - role
  - provenance
  - derivation or dependency handle
  - optional confidence/evidence slot
- keep `TypeMemento` unchanged except where minimal plumbing is required
- allow `RowVec` cells to carry structured payloads without pretending they are flat scalars

**Explicit non-goals:**
- do not rework ISAM storage model
- do not force all existing cursor usage through overlay logic
- do not encode overlay semantics inside `IOMemento`

**Verification:**
- focused tests showing a `RowVec` can carry a structured payload with overlay metadata
- focused tests proving flat ISAM-style rows still work unchanged

**Exit gate:**
- overlay exists as a separate semantic layer and does not require rewriting `IOMemento`

---

### phase-04 — Graph and Job Surface
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/channelization/`, `src/commonMain/kotlin/borg/trikeshed/context/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-03

**Deliverables:**
- introduce a minimal graph/job layer:
  - `ChannelGraph`
  - `ChannelJob`
  - ownership key or worker key
- make channelization choose not only backend path, but the smallest graph/job activation path
- keep CCEK as installation/context only

**Explicit non-goals:**
- no scheduler rewrite
- no actor framework import
- no general workflow engine

**Verification:**
- focused tests for keyed job ownership and graph-to-job activation

**Exit gate:**
- a protocol slice can activate jobs from graph facts without transport details leaking upward

---

### phase-05 — First Protocol Slice Only
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/`, `src/jvmTest/kotlin/borg/trikeshed/net/`
**Blocked by:** phase-04

**Deliverables:**
- pick exactly one slice:
  - HTTP ingress, or
  - QUIC stream selection, or
  - SSH handshake prelude
- express it through assembly -> graph -> job -> block
- keep typed routing

**Explicit non-goals:**
- no "support all protocols"
- no overlay classifier zoo
- no backend proliferation

**Verification:**
- focused tests for the chosen slice only

**Exit gate:**
- one protocol story works end-to-end through the staged model

---

### phase-06 — Backend Tightening
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/jvmMain/kotlin/one/xio/spi/`, `src/linuxMain/`, `src/posixMain/`, `src/jvmTest/kotlin/`
**Blocked by:** phase-05

**Deliverables:**
- make backends obey the staged semantic core rather than inventing their own shape
- keep JVM/NIO as shim/projection
- keep Linux-native path aligned with real capability probing and XDP-first steering
- prepare, but do not force, a POSIX handle path that is not `select()`-centric

**Explicit non-goals:**
- no fake off-Linux `io_uring`
- no premature `kqueue` or `epoll` empire
- no public NIO architecture

**Verification:**
- focused backend tests still pass
- stage-05 protocol slice still passes under selector backend

**Exit gate:**
- backends are clearly projections of the semantic core, not rival architectures

---

## Next Slice

- `phase-02a` session identity only

Execution order lock:
- `phase-02a`
- `phase-02b`
- `phase-02c`
- `phase-02d`

Do not open `phase-03` before all `phase-02*` slices are closed with tests and a concrete commonMain session/block shape.

---

## Evidence Log

- 2026-03-09: Focused transport/backend slice landed with typed routing and backend capability probing.
- 2026-03-09: Thin channelization planner landed to keep NIO as backend projection rather than architectural center.
- 2026-03-09: Confirmed `Cursor`/`RowVec` are open enough for structured payloads; flat ISAM is a current usage, not an ontology.
- 2026-03-09: Confirmed `TypeMemento` is too small to act as a blackboard overlay and should remain separate from provenance/derivation semantics.
