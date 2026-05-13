# KMPngSCTP

**Next-gen SCTP in pure Kotlin Multiplatform** — the protocol that should have replaced QUIC in 2026.

Original SCTP glory (multi-homing, message streams, PR-SCTP) + original-QUIC spirit (0/1-RTT, migration) + 2026 AI-native superpowers, all in Kotlin coroutines + structured concurrency.

No UDP tax. No hidden ACKs. Hardware-offload friendly. SmartNIC / io_uring / eBPF first-class.

## Why this instead of QUIC?
See the 20-year war we both lived through. This fixes the parsing disaster, the CPU tax, the observability black hole, and adds ML congestion + native collectives.

## Features (v0.1)
- TLV chunks everywhere (unknown = skip, Wireshark happy forever)
- Streams = `kotlinx.coroutines.channels.Channel` + structured scopes
- Association as coroutine scope (auto-cleanup, cancellation = FIN)
- JVM: io_uring + AF_XDP + eBPF JIT packet router
- Native: linuxPosix + DPDK-style raw sockets
- Common: ML congestion slot (tiny ONNX/TFLite model loader)
- Built on `kotlin-spirit-parser` for zero-copy TLV

## Quick start
```kotlin
val assoc = NgSctpAssociation.connect(remote = InetSocketAddress(...))
val stream = assoc.openStream(priority = 1, intent = "allreduce-gradient")
stream.sendChannel.send(myTensorBytes)  // structured, cancellable
```

Full spec + chunk format in `docs/`.

Status: **Pre-alpha** — wire format frozen, transport layer 80 % done.

## Project Structure

```
KMPngSCTP/
├── .kilocode/                  # AI template rules
├── ngsctp/                     # Library module
│   ├── src/
│   │   ├── commonMain/kotlin/dev/jnorthrup/ngsctp/
│   │   │   ├── NgSctpAssociation.kt
│   │   │   ├── NgSctpStream.kt
│   │   │   ├── chunks/         # TLV chunk definitions
│   │   │   ├── parser/          # Spirit-based TLV parser
│   │   │   └── ml/              # Congestion model slot
│   │   ├── jvmMain/kotlin/      # io_uring + eBPF transport
│   │   └── nativeMain/kotlin/   # POSIX + DPDK sockets
├── demo/                       # Optional demo app
└── docs/
    └── protocol.md
```

## Building

```bash
cd ngsctp
./gradlew build
```

## License

Apache 2.0
