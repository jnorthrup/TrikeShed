# ngsctp — SCTP Transport for TrikeShed

Next-gen SCTP in pure Kotlin Multiplatform.

## Upstream

Adapted from [jnorthrup/KMPngSCTP](https://github.com/jnorthrup/KMPngSCTP) —
the protocol specification, chunk format, and wire-level TLV definitions originate there.
TrikeShed's adaptation remaps the architecture to:

- **CCEK** — `SctpElement` extends `AsyncContextElement` with companion `Key`
- **SupervisorJob choreography** — association scope managed by the parent element's SupervisorJob
- **Ring reactor** — socket IO through `ChannelOperations` SPI (not `java.net`)
- **Join/Series** — TrikeShed kernel algebra for state tuples

## Differences from upstream

| Upstream (KMPngSCTP) | TrikeShed adaptation |
|---|---|
| `dev.jnorthrup.ngsctp` | `borg.trikeshed.sctp` |
| `java.net.InetSocketAddress` | `(host: CharSequence, port: Int)` |
| `NgSctpAssociation` as `CoroutineScope` | `SctpElement` as `AsyncContextElement` |
| `SctpTransport` interface | `ChannelOperations` SPI |
| `CongestionControl` / `SendBuffer` | Pending ring reactor integration |
| JVM-only types | Kotlin Multiplatform (commonMain + native) |

## Protocol compliance

RFC 4960 (SCTP), RFC 6951 (UDP encapsulation), RFC 6458 (Sockets API).
