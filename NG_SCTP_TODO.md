# ngsctp (Next-Generation SCTP) Testing and Development Plan

This document details the necessary steps to make the `ngsctp` module testable and interoperable with standard implementations like Pion SCTP.

## Context
The `ngsctp` library represents a high-performance SCTP implementation utilizing Kotlin Coroutines and a zero-copy parser (Spirit parser). However, the implementation is currently incomplete and excluded from the main Gradle build.

## Blocking Issues for Interop Testing

1. **Pervasive Unsigned/Signed Type Mismatches**
   - **Problem:** Many chunks (e.g. `NgChunk.kt`) and congestion models attempt to write unsigned Kotlin types (`UByte`, `UShort`, `UInt`) directly into `java.nio.ByteBuffer`, which only accepts signed primitives.
   - **Resolution:** Refactor serialization code to cast to standard primitive types (e.g., `.toByte()`, `.toShort()`, `.toInt()`) immediately before interacting with `ByteBuffer`, or adopt a more robust, multiplatform-friendly byte stream library like `okio`.

2. **Incomplete Transport Implementations**
   - **Problem:** `IoUringSctpTransport.kt` misses Netty io_uring dependencies and contains unwritten functions. The general abstraction in `SctpEngine.kt` uses `throw NotImplementedError("Chunk waiting mechanism needs implementation")`.
   - **Resolution:** Complete the minimal UDP-based datagram socket transport layer (perhaps avoiding kernel bypass constraints initially) to allow standard packet transmission.

## Proposed Interoperability Testing Plan (vs. Pion)

Once the above blocking issues are resolved, follow this plan to test against the standard Pion SCTP implementation:

### 1. Launch a Reference UDP SCTP Server (Pion)
Use the `pion/sctp` Go library's `ping-pong` example.
```bash
git clone https://github.com/pion/sctp.git
cd sctp/examples/ping-pong
go run pong/*.go
```

### 2. Implement a Client Integration Test in `ngsctp`
Write a Kotlin test to attempt the standard 4-way SCTP handshake using raw UDP encapsulation against the Pion server.
```kotlin
suspend fun testInteropWithPion() = coroutineScope {
    val transport = UdpSctpTransport(InetSocketAddress("127.0.0.1", 9899))

    // The connect method performs: INIT -> INIT_ACK -> COOKIE_ECHO -> COOKIE_ACK
    val assoc = NgSctpAssociation.connect(
        remote = InetSocketAddress("127.0.0.1", 9899),
        transport = transport
    )

    val stream = assoc.openStream(0)
    stream.write("ping 1".toByteArray())

    val response = stream.receive()
    assertEquals("pong 1", String(response))
}
```

### 3. Verify Wireshark / PCAP Observability
Ensure the `ngsctp` UDP payloads are decodable by standard network analysis tools (Wireshark decode as SCTP-over-UDP).
