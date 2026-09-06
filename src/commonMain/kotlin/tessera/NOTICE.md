# tessera.* — provenance and attribution

The `tessera` packages in TrikeShed are ported from **Tessera**, a UDP message transport in
Kotlin — post-quantum 0-RTT connect, sliding-window random linear network coding (RLNC) for
loss recovery, receiver-driven congestion control.

    Tessera
    Copyright 2026 3x3xX3N0N
    Licensed under the Apache License, Version 2.0 (see LICENSE-tessera in this directory).

Origin: https://github.com/3x3xX3N0N/tessera, brought in through the fork
https://github.com/jnorthrup/tessera at commit 78bc042 (2026-09-03). The fork carries no
commits of its own; every mechanism here is the upstream author's. Package names are kept
(`tessera.core`, later `tessera.transport`) so the attribution reads at the import.

## What was ported, and what changed (stage 1, 2026-09-05)

| upstream | here | change |
|---|---|---|
| `core/Rlnc.kt` `GF256` | `tessera/core/Gf256.kt` | `OffHeapKernel` (Panama FFM, Rust SIMD via `:native`) dropped; `Swar` autovec kernel added — eight GF(256) lanes per `Long`, pure Kotlin, byte-identical to `Scalar`; measured slower than `Scalar` on JDK 25 (37.9 ms vs 16.8 ms, 1200 B × 20k) because commonMain packs lanes byte by byte, so `Scalar` stays the default |
| `core/Rlnc.kt` `RlncEncoder` / `RlncDecoder` | `tessera/core/Rlnc.kt` | off-heap mirror and accumulator removed; `Frame.Repair(ByteBuffer)` becomes `RepairSymbol(ByteArray)`; `java.util.Arrays.fill` → `fill` |
| `core/Compact.kt` `VarInt` | `tessera/core/VarInt.kt` | `java.nio.ByteBuffer` → a `ByteArray` cursor |
| `core/test RlncTest`, `testFixtures RlncHarness` | `commonTest/tessera/core` | the threaded soak variant is not ported (it is JVM threads; the single-loop soak carries the same loss/reorder/ARQ/rotation model) |

Not yet ported: the wire format, frames and packet crypto (`Wire.kt`, `Frames.kt`,
`PacketCrypto.kt`, `Handshake.kt`, …, BouncyCastle/zstd/JDK-NIO backed) and the transport
(`Connection.kt`, `Endpoints.kt`). Those follow on TrikeShed's userspace NIO
(`borg.trikeshed.userspace.nio.channels.DatagramChannel`) with crypto behind an SPI.

## Design provenance (from upstream NOTICE, kept verbatim in spirit)

The RLNC coder follows the sliding-window random linear network coding literature; the varint
is RFC 9000's. Tessera is an independent implementation with no code derived from any QUIC
implementation. See upstream `NOTICE` and `docs/SPEC.md` for the full ledger.
