# libs/couch — TODO

## Intent
CouchDB-semantics document store. Owns the Reactor pattern, session dispatch, HTX server-side block framing, CCEK transport services, BTRFS persistence, WAL, and the MiniDuck integration layer. KMP full (jvm + js + wasm + macos + linux).

## Status: ALPHA (large surface, many moving parts, some duplication)

## Pure boundary audit

### Keys (need cleanup)
- `ReactorSupervisorKey : CoroutineContext.Key<ReactorSupervisor>` ✓ (bare object, not AsyncContextKey)
- `ParseSupervisorKey : CoroutineContext.Key<ParseSupervisor>` ✓ (same)
- `SessionContextKey : CoroutineContext.Key<SessionContext>` ✓ (same)
- `BtrfsSandboxElement.Key : CoroutineContext.Key<BtrfsSandboxElement>` (in btrfs/)
- `BtrfsWal.Key : CoroutineContext.Key<BtrfsWal>` (in btrfs/)
- `QuicChannelService.Key : CoroutineContext.Key<QuicChannelService>` — **DUPLICATE of libs/quic**
- `NgSctpService.Key : CoroutineContext.Key<NgSctpService>` — **DUPLICATE of libs/ngsctp**
- [ ] Unify: migrate bare `CoroutineContext.Key` usage to `AsyncContextKey<T>` from root project

### Elements (stateful — core of the module)
- `ReactorSupervisor` — the main SupervisorJob host ✓
  - Has own `ReactorState` enum — **duplicates `ElementState`**
  - [ ] Replace `ReactorState` with `ElementState` (CREATED→OPEN→ACTIVE→DRAINING→CLOSED maps 1:1)
  - [ ] Extend `AsyncContextElement` instead of bare `AbstractCoroutineContextElement`
- `ParseSupervisor` — parse tree SupervisorJob host
  - Has own `ParseState` enum — **duplicates `ElementState`**
  - [ ] Same: replace `ParseState` with `ElementState`, extend `AsyncContextElement`
- `SessionContext` — tag-based handler registry per session ✓
  - [ ] Extend `AsyncContextElement` for lifecycle (register handlers in open, clear in close)
- `BranchScope` — one concurrent IO branch ✓ (delegates SupervisorJob)
- `BtrfsSandboxElement` — extends `AsyncContextElement` ✓
- `BtrfsWal` — extends `AsyncContextElement`, wraps LSMRWal ✓
- `ngSCTPChannel` — CoroutineContext.Element per stream ✓
- `ngSCTPMultiplexer` — NOT a context element, should it be?

### Statics → refactor into Keys
- `HtxBlockType` enum — HAProxy block type discriminator
  - Has `companion object { fromCode() }` factory — correct
  - Stays enum: it's a PDU field, not a routing key ✓
- `HtxSlFlags` enum : BitMasked — flag set for start-lines ✓
- `HtxFlags` enum : BitMasked — flag set for messages ✓
- `HttpMethod` enum → **move to htx-client** (see htx-client todo)
- `HandleState` enum (OPEN/SEALED/CLOSED) — collection handle lifecycle
  - [ ] Unify with `ElementState` or keep separate (3-state vs 5-state — keep separate, different semantics)
- `PathState` enum — multi-homing path state → **move to ngsctp** (see ngsctp todo)

### Duplicate removal
- [ ] Delete `couch/ccek/QuicChannelService.kt` — import from `:libs:quic`
- [ ] Delete `couch/ccek/NgSctpService.kt` — import from `:libs:ngsctp`
- [ ] Move multi-homing logic (PathStatus, failover, recoverPath) to ngsctp module

### State machine unification
- `ReactorSupervisor.ReactorState` ≡ `ElementState` → unify
- `ParseSupervisor.ParseState` ≡ `ElementState` → unify
- Both should extend `AsyncContextElement` and use the base class lifecycle

### Runtime/Reactor facade
- `Reactor` (runtime/Reactor.kt) — thin wrapper over `ReactorSupervisor`. Question: does this add value?
  - [ ] Consider folding into `ReactorSupervisor` directly or making `Reactor` itself the `AsyncContextElement`

## Integration partners
- **miniduck**: couch `api` main; depends on miniduck for BlockRowVec, MiniCursor, ColumnSchema. Tight coupling — correct per AGENTS.md (couch subsumes miniduck algebra).
- **tiny-btrfs**: couch imports tiny-btrfs for B+Tree and disk adapters. BtrfsSandboxElement/BtrfsWal live in couch, not tiny-btrfs — correct (they're couch-specific).
- **kursive**: couch imports kursive for parser types used in JursiveHeuristics.
- **quic, ngsctp**: couch should IMPORT these, not duplicate them in ccek/. **Blocking cleanup.**
- **htx-client**: couch has server-side HTX; htx-client has client-side. HttpMethod should live in htx-client.

## Path to stable
1. **Delete ccek/ duplicates** — replace with imports from quic + ngsctp modules
2. **Unify ReactorState/ParseState → ElementState** across ReactorSupervisor and ParseSupervisor
3. **Extend AsyncContextElement** on ReactorSupervisor, ParseSupervisor, SessionContext
4. **Move HttpMethod** to htx-client module
5. **Move PathState/PathStatus** to ngsctp module
6. Add lifecycle tests for ReactorSupervisor (already has some ✓)
7. Add integration test: ReactorSupervisor → BranchScope → SessionContext → ngSCTPChannel round-trip
