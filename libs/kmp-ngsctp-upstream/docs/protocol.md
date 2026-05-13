# ngSCTP Protocol Specification

## Overview

ngSCTP (Next Generation SCTP) is a modern implementation of the Stream Control Transmission Protocol, enhanced with features inspired by QUIC while maintaining backward compatibility with traditional SCTP.

## Key Features

### 1. TLV-Based Chunk Format

All chunks use Type-Length-Value format for forward compatibility:

```
+--------+--------+--------+--------+
|  Type  | Flags  |   Length (16-bit)   |
+--------+--------+--------+--------+
|              Value (variable)         |
+--------------------------------------+
```

- **Type**: 1 byte - Chunk type identifier
- **Flags**: 1 byte - Chunk-specific flags
- **Length**: 2 bytes - Total chunk length including header
- **Value**: Variable - Chunk-specific data

**Unknown chunks are automatically skipped** - no parsing errors, Wireshark compatible forever.

### 2. Chunk Types

| Type | Value | Description |
|------|-------|-------------|
| DATA | 0x00 | User data |
| INIT | 0x01 | Initialize association |
| INIT_ACK | 0x02 | Initialize acknowledgment |
| SACK | 0x03 | Selective acknowledgment |
| HEARTBEAT | 0x04 | Heartbeat request |
| HEARTBEAT_ACK | 0x05 | Heartbeat acknowledgment |
| ABORT | 0x06 | Abort association |
| SHUTDOWN | 0x07 | Shutdown |
| SHUTDOWN_ACK | 0x08 | Shutdown acknowledgment |
| ERROR | 0x09 | Error indication |
| COOKIE_ECHO | 0x0A | State cookie echo |
| COOKIE_ACK | 0x0B | State cookie acknowledgment |
| ECNE | 0x0C | Explicit congestion notification |
| CWR | 0x0D | Congestion window reduced |
| SHUTDOWN_COMPLETE | 0x0E | Shutdown complete |

### 3. 4-Way Handshake

```
Client                                          Server
  |                                               |
  |---------------- INIT ------------------------>|
  |<--------------- INIT-ACK --------------------|
  |         (includes state cookie)               |
  |                                               |
  |---------------- COOKIE-ECOHO ---------------→|
  |<-------------- COOKIE-ACK --------------------|
  |                                               |
  |================ ESTABLISHED =================|
  |                                               |
```

### 4. Stream Multiplexing

- Up to 65535 streams per association
- Each stream is an independent ordered message sequence
- Streams map to `kotlinx.coroutines.channels.Channel`
- Structured concurrency ensures automatic cleanup

### 5. Partial Reliability (PR-SCTP)

- `maxLifetimeMs` parameter controls message lifetime
- Messages expire if not delivered within timeout
- Supports "time-based" and "priority-based" delivery

### 6. Multi-Homing

- Multiple IP addresses per endpoint
- Primary path with failover
- Path verification via heartbeats

## Structured Concurrency Model

### Association as Coroutine Scope

```kotlin
class NgSctpAssociation : CoroutineScope {
    // SupervisorJob - failure in one stream doesn't kill others
}
```

### Stream as Child Coroutine

```kotlin
class NgSctpStream : CoroutineScope {
    // Child of association - cancellation cascades
    val sendChannel: SendChannel<ByteBuffer>
    val receiveChannel: ReceiveChannel<ByteBuffer>
}
```

### Cancellation Semantics

- Closing a stream sends FIN (SHUTDOWN)
- Cancelling association cancels all streams
- Automatic cleanup via structured concurrency

## Transport Layer

### JVM: io_uring + eBPF

- io_uring for async I/O
- AF_XDP for zero-copy packet processing
- eBPF JIT for packet steering

### Native: POSIX + DPDK

- Raw sockets for DPDK-style performance
- POSIX threads for multi-core scaling

## ML Congestion Control

### Feature Set

- RTT and RTT variance
- Bytes in flight
- Loss rate
- Path count
- Stream count
- Traffic priority/intent

### Model Slot

- TinyONNX models (< 100KB)
- TFLite Micro for embedded
- Default: CUBIC-like fallback

## Wire Format

### Common Header (12 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+----------------------------------------------------------------+
|        Source Port         |       Destination Port          |
+----------------------------------------------------------------+
|                     Verification Tag                          |
+----------------------------------------------------------------+
|                           Checksum                             |
+----------------------------------------------------------------+
```

### DATA Chunk

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+----------------------------------------------------------------+
|   Type=0   |  Flags |              Length                       |
+----------------------------------------------------------------+
|        Stream ID          |    Stream Sequence Number         |
+----------------------------------------------------------------+
|                    Payload Protocol Identifier                  |
+----------------------------------------------------------------+
|                  Transmission Sequence Number                  |
+----------------------------------------------------------------+
|                      User Data (variable)                      |
+----------------------------------------------------------------+
```

### Flags

- **E** (0x01): End of fragment
- **B** (0x02): Beginning of fragment  
- **U** (0x04): Unordered delivery

## Migration from QUIC

ngSCTP addresses QUIC's problems:

| QUIC Issue | ngSCTP Solution |
|------------|-----------------|
| UDP overhead | Native SCTP (no UDP tax) |
| Hidden ACKs | Explicit SACK |
| Parsing complexity | TLV with skip-unknown |
| CPU tax | Hardware offload friendly |
| Observability | Wireshark compatible |
| ML CC | Built-in model slot |

## References

- RFC 4960: SCTP
- RFC 3758: Partial Reliability
- RFC 4820: Stream Schedulers
- RFC 8260: Stream Control Transmission Protocol
