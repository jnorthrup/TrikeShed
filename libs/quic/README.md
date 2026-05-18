# libs/quic

QUIC protocol model and AsyncContextElement transport.

## What It Is

A Kotlin Multiplatform library providing:
- **QuicElement**: an `AsyncContextElement` + `StreamTransport` that models a QUIC connection as a coroutine-context entry with channelized streams.
- **QUIC wire protocol types**: RFC 9000 variable-length integer codec (`QuicVarInt`), version constants (`QuicVersions`), long/short packet type enums, and a sealed `QuicPacketHeader` hierarchy (Initial, ZeroRtt, Handshake, Retry, Short).
- **QuicConfig**: data class for connection parameters (ALPN, idle timeout, UDP payload size, initial version).

The transport layer is currently a **stub** — `openStream()` creates in-memory `Channel<ByteArray>` pairs. No real UDP socket or TLS handshake is wired.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/quic/
    QuicElement.kt          # QuicConfig, QuicVersions, QuicVarInt, QuicPacketHeader,
                            # QuicKey, openQuicElement(), QuicElement class
  commonTest/kotlin/borg/trikeshed/quic/
    QuicElementTest.kt      # Context-key resolution, cross-key isolation
    QuicElementTddTest.kt   # Lifecycle, streams, factory, varint codec, packet headers
    QuicSearchRedTest.kt    # Config algebra (withAlpn, withTimeout), search specs
```

## Key / Element / Reactor Status

| Artifact | Role | Status |
|---|---|---|
| `QuicElement.Key` (`companion object`) | **AsyncContextKey** | Correct — singleton routing identity |
| `QuicKey` (top-level val) | Alias to `QuicElement.Key` | Convenience accessor |
| `QuicElement` | **AsyncContextElement** + **StreamTransport** | Shell; lifecycle transitions work, stream map is in-memory only |
| `QuicConfig` | Pure data class | Complete |
| `QuicVarInt` | Pure static codec | Complete — encode/decode with offset, covers all 4 widths |
| `QuicPacketHeader` sealed class | Protocol PDU model | Complete — Long.{Initial,ZeroRtt,Handshake,Retry}, Short |
| `QuicVersions` | Pure constants | Complete |
| `QuicLongPacketType`, `QuicShortPacketType` | Enum discriminators | Complete — header field tags, not routing keys |

No `ReactorSupervisor` integration yet. Element holds its own `SupervisorJob` via `AsyncContextElement.supervisor`.

## Dependencies

- **Root project** `borg.trikeshed.context`: `AsyncContextElement`, `AsyncContextKey`, `ElementState`, `StreamHandle`, `StreamTransport`
- **Root project** `borg.trikeshed.lib`: `Join<A,B>`, `Series<T>`, `j` infix, `Join.emptySeriesOf()`
- **kotlinx.coroutines**: `Channel`, `SupervisorJob`

## Known Duplicates

Two copies of this functionality exist outside this module:
1. `src/commonMain/.../ccek/QuicChannelService.kt` — root CCEK copy. Implements `StreamTransport` with raw `CoroutineContext.Key`.
2. `libs/couch/src/commonMain/.../couch/ccek/QuicChannelService.kt` — couch copy with XDP/io_uring fields.

Both duplicate `QuicElement`'s stream-map pattern. This module is canonical.
