# libs/ngsctp

SCTP protocol model and AsyncContextElement transport.

## What It Is

A Kotlin Multiplatform library providing:
- **SctpElement**: an `AsyncContextElement` + `StreamTransport` that models an SCTP association as a coroutine-context entry with channelized streams and RFC 4960 four-way handshake state machine (client and server paths).
- **SCTP wire protocol types**: chunk type enum (`SctpChunkType`), association state enum (`SctpState`), sealed error hierarchy (`SctpError`), association typealias (`SctpAssociation = Join<Long, SctpState>`).
- **Chunk codecs**: `SctpChunkHeader`, `SctpInitChunk`, `SctpInitAckChunk`, `SctpSackChunk` (with gap-ack blocks), `SctpCookieEchoChunk`, `SctpCookieAckChunk` — all with `encode()`/`decode()` round-trips.
- **Big-endian primitive helpers**: `putUShort`, `putUInt`, `getUShort`, `getUInt`.

The transport layer is currently a **stub** — `openStream()` creates in-memory `Channel<ByteArray>` pairs. Association state machine is modeled but not backed by a real socket.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/sctp/
    SctpElement.kt          # Enums (SctpChunkType, SctpState), SctpError sealed class,
                            # SctpAssociation typealias, chunk codecs (INIT, INIT_ACK, SACK,
                            # COOKIE_ECHO, COOKIE_ACK), primitive encoding helpers,
                            # SctpKey, openSctpElement(), SctpElement class
  commonTest/kotlin/borg/trikeshed/sctp/
    SctpElementTest.kt      # Context-key resolution, cross-key isolation
    SctpElementTddTest.kt   # Lifecycle, streams, association state machine, chunk codec round-trips
    SctpSearchRedTest.kt    # Config/spec algebra, error taxonomy, search specs, free functions
```

## Key / Element / Reactor Status

| Artifact | Role | Status |
|---|---|---|
| `SctpElement.Key` (`companion object`) | **AsyncContextKey** | Correct — singleton routing identity |
| `SctpKey` (top-level val) | Alias to `SctpElement.Key` | Convenience accessor |
| `SctpElement` | **AsyncContextElement** + **StreamTransport** | Shell; lifecycle + handshake state machine work, no real I/O |
| `SctpChunkType` enum | Protocol discriminator | Complete (7 entries) |
| `SctpState` enum | Association lifecycle | Complete (8 entries) — SCTP-specific, intentionally distinct from `ElementState` |
| `SctpError` sealed class | Error taxonomy | Complete (BindFailed, ConnectFailed, Closed) |
| `SctpAssociation` | `Join<Long, SctpState>` typealias | Correct |
| Chunk codecs | Pure encode/decode | Complete for INIT, INIT_ACK, SACK, COOKIE_ECHO, COOKIE_ACK |

No `ReactorSupervisor` integration yet. Element holds its own `SupervisorJob` via `AsyncContextElement.supervisor`.

## Dependencies

- **Root project** `borg.trikeshed.context`: `AsyncContextElement`, `AsyncContextKey`, `ElementState`, `StreamHandle`, `StreamTransport`
- **Root project** `borg.trikeshed.lib`: `Join<A,B>`, `Series<T>`, `j` infix, `Join.emptySeriesOf()`
- **kotlinx.coroutines**: `Channel`, `SupervisorJob`

## Known Duplicates

Two copies of this functionality exist outside this module:
1. `src/commonMain/.../ccek/NgSctpService.kt` — root CCEK copy. Implements `StreamTransport` with multi-homing paths and congestion control fields.
2. `libs/couch/src/commonMain/.../couch/ccek/NgSctpService.kt` — couch copy with similar fields.

The root CCEK copy has **multi-homing** fields (`paths: List<String>`, `congestionControl: String`) that this module lacks. This module has the complete RFC 4960 chunk codec and state machine that the CCEK copies lack. This module is canonical; multi-homing should migrate here.
