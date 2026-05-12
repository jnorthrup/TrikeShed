# libs/ngsctp — TODO

## Status: ALPHA (protocol model solid, transport is stub)

## Pure Boundary Audit

### Keys (correct)
- `SctpElement.Key : AsyncContextKey<SctpElement>` — singleton routing identity, no state. Pure.

### Elements (stateful — needs real transport)
- `SctpElement` extends `AsyncContextElement` + `StreamTransport`
  - Lifecycle: CREATED → OPEN → (ACTIVE) → DRAINING → CLOSED works
  - SCTP state machine: client (connect → COOKIE_WAIT → handleInitAck → COOKIE_ECHOED → handleCookieAck → ESTABLISHED) and server (bind → CLOSED → handleCookieEcho → ESTABLISHED) both modeled
  - `streams: MutableMap<Int, StreamHandle>` and `associations: MutableMap<Long, SctpState>` are in-memory only
  - [ ] Replace with real SCTP socket backend
  - [ ] Wire to `LiburingFacadeElement` or kernel sctp socket

### Statics → Keys classification
- `SctpChunkType` enum — protocol discriminator, stays enum. Not a routing key.
- `SctpState` enum — association lifecycle, SCTP-specific. Intentionally distinct from `ElementState` (COOKIE_WAIT etc. have no ElementState equivalent). Keep.
- `SctpError` sealed class — error taxonomy, correct.
- `SctpChunkHeader`, chunk data classes — pure values. Correct.
- Primitive encoding helpers (`putUShort`, `getUInt`, etc.) — pure functions. Correct.

### Missing from this module (present in root CCEK copy)
- Root `ccek/NgSctpService` has: `paths: List<CharSequence>` (multi-homing), `congestionControl: CharSequence`
- [ ] Migrate multi-homing path management into `SctpElement`
- [ ] Migrate congestion control policy into `SctpElement` or `SctpConfig`

### Duplicate collision with couch/ccek
- Root `src/commonMain/.../ccek/NgSctpService` duplicates `SctpElement` (both `StreamTransport`)
- `libs/couch/.../couch/ccek/NgSctpService` is a third copy
- [ ] **Eliminate**: keep `SctpElement` here as canonical. Delete root and couch copies. Couch imports this module.

## Integration Steps

1. **couch**: replace `couch/ccek/NgSctpService` imports with `borg.trikeshed.sctp.SctpElement`
2. **root ccek**: delete `src/commonMain/.../ccek/NgSctpService.kt`
3. **quic**: sibling transport — both implement `StreamTransport`. Consider shared `TransportElement` base if divergence grows.
4. **uring**: `SctpElement` should detect `LiburingFacadeElement` in context for zero-copy I/O
5. **combined-client / server**: already depend on `:libs:ngsctp`

## Path to Stable

1. Migrate multi-homing logic (`paths`, path failover/recovery) from root `ccek/NgSctpService` into `SctpElement`
2. Delete `couch/ccek/NgSctpService` and root `ccek/NgSctpService` — redirect imports
3. Flesh out `SctpElement.connect()` / `bind()` with real SCTP socket backend
4. Add HEARTBEAT chunk codec (enum entry exists, no data class)
5. Add DATA chunk codec
6. Add shutdown handshake (SHUTDOWN_PENDING → SHUTDOWN_SENT → SHUTDOWN_ACK_SENT)
7. Add congestion control hooks (cubic / hstcp / rack)
8. Integration test: `SctpElement` + `LiburingFacadeElement` in shared coroutine context
