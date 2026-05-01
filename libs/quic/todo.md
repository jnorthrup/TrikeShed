# libs/quic — TODO

## Status: ALPHA (protocol model solid, transport is stub)

## Pure Boundary Audit

### Keys (correct)
- `QuicElement.Key : AsyncContextKey<QuicElement>` — singleton routing identity, no state. Pure.

### Elements (stateful — needs real transport)
- `QuicElement` extends `AsyncContextElement` + `StreamTransport`
  - Lifecycle: CREATED → OPEN → (ACTIVE) → DRAINING → CLOSED works
  - `streams: MutableMap<Int, StreamHandle>` is in-memory only
  - [ ] Replace with real QUIC stream state machine
  - [ ] Wire to socket backend or `LiburingFacadeElement`

### Statics → Keys classification
- `QuicVersions` object (VERSION_1, DRAFT_29, DRAFT_27, NEGOTIATION) — pure constants. Keep as object.
- `QuicVarInt` object (encode/decode) — pure codec, no state. Keep as object.
- `QuicLongPacketType` enum — protocol discriminator, stays enum. Not a routing key.
- `QuicShortPacketType` enum — protocol discriminator, stays enum. Not a routing key.
- `QuicConfig` data class — pure value. Correct.

### Sealed hierarchy
- `QuicPacketHeader` sealed class (Long.Initial, Long.ZeroRtt, Long.Handshake, Long.Retry, Short) — protocol PDU. Not a Key or Element. Correct as-is.

### Duplicate collision with couch/ccek
- Root `src/commonMain/.../ccek/QuicChannelService` duplicates `QuicElement` (both `StreamTransport`)
- `libs/couch/.../couch/ccek/QuicChannelService` is a third copy with XDP/io_uring fields
- [ ] **Eliminate**: keep `QuicElement` here as canonical. Delete root and couch copies. Couch imports this module.

## Integration Steps

1. **couch**: replace `couch/ccek/QuicChannelService` imports with `borg.trikeshed.quic.QuicElement`
2. **root ccek**: delete `src/commonMain/.../ccek/QuicChannelService.kt`
3. **uring**: `QuicElement` should detect `LiburingFacadeElement` in context and use it for zero-copy I/O
4. **combined-client / server**: already depend on `:libs:quic`

## Path to Stable

1. Delete `couch/ccek/QuicChannelService` and root `ccek/QuicChannelService` — redirect imports
2. Flesh out `QuicElement.connect()` with real UDP socket backend
3. Add `QuicPacketHeader` wire encode/decode (currently models structure only, no serialize-to-bytes for Long headers)
4. Add QUIC TLS handshake integration point (CRYPTO frame transport)
5. Add stream flow-control and congestion control hooks
6. Integration test: `QuicElement` + `LiburingFacadeElement` in shared coroutine context
