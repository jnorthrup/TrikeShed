# TDD Specification: Async Context Element (CCEK) Pattern

> **Module:** `borg.trikeshed.context`
> **Version:** 0.1.0
> **Spec status:** Draft, pending test gates
> **Last updated:** 2026-05-09

## Abstract

This specification defines the Coroutine Context Element Key (CCEK) pattern used
throughout TrikeShed for lifecycle-managed platform services. The pattern replaces
Kotlin Multiplatform `expect`/`actual` declarations with composition-based service
resolution — platform implementations are "laced in" at composition time, never at
compile time.

## 1. Core Types

### 1.1 `ElementState`

```kotlin
enum class ElementState {
    CREATED, OPEN, ACTIVE, DRAINING, CLOSED
}
```

State transitions:
```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Behavioral contracts:

| State      | May call `open()` | May call `close()` | May use service |
|------------|-------------------|---------------------|------------------|
| `CREATED`  | ✓                 | ✗                   | ✗                |
| `OPEN`     | ✗ (no-op)         | ✓                   | ✗ (not yet ready)|
| `ACTIVE`   | ✗ (no-op)         | ✓                   | ✓                |
| `DRAINING` | ✗                 | ✓ (idempotent)      | ✓ (graceful)     |
| `CLOSED`   | ✗                 | ✗ (no-op)           | ✗                |

### 1.2 `AsyncContextElement`

Abstract base class. Subclasses override `open()` and `close()` to manage
platform resources (file handles, sockets, TLS sessions, DB connections).

```kotlin
abstract class AsyncContextElement : CoroutineContext.Element {
    var state: ElementState = ElementState.CREATED
    abstract override val key: CoroutineContext.Key<*>

    open suspend fun open() { ... }
    open suspend fun close() { ... }
}
```

**Test gates:**

- [ ] `open()` transitions `CREATED → ACTIVE`
- [ ] Second `open()` is idempotent (no state change)
- [ ] `close()` transitions `ACTIVE → DRAINING → CLOSED`
- [ ] `open()` after `CLOSED` throws `StateException`
- [ ] `close()` on `CREATED` is no-op

## 2. CCEK Composition Pattern

### 2.1 Service Registration

Every SPI interface has a `companion object Key : CoroutineContext.Key<Service>`:

```kotlin
interface FileOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FileOperations>
    override val key get() = Key

    suspend fun write(path: CharSequence, data: ByteArray): Unit
    suspend fun readAllLines(path: CharSequence): List<CharSequence>
    // ...
}
```

### 2.2 Platform Lacing

Platform providers are registered at `NioSupervisor.open()` time via
`platformNioProviders()`. The supervisor hosts all services in the
coroutine context:

```kotlin
// commonMain
expect fun platformNioProviders(): List<CoroutineContext.Element>

// jvmMain
actual fun platformNioProviders() = listOf(
    JvmFileOperations(),
    JvmSystemOperations(),
    JvmChannelOperations(),
    // ...
)
```

**Test gates:**

- [ ] `NioSupervisor.open()` auto-registers all platform providers
- [ ] `supervisor.service<FileOperations>()` resolves each type
- [ ] Missing service returns `null` (graceful fallback)
- [ ] Platform provider list is idempotent (calling twice doesn't double-register)

## 3. Lifecycle Integration

### 3.1 `NioSupervisor` Hierarchy

```
CoroutineContext
 ├── NioSupervisor
 │    ├── FileOperations (JVM/POSIX/JS/WASM)
 │    ├── SystemOperations
 │    ├── ChannelOperations
 │    ├── ReactorOperations
 │    └── ProcessOperations
 ├── TlsElement
 │    └── sha256: Sha256 (DefaultSha256 | JvmSha256)
 │    └── hkdf: HkdfSha256
 │    └── aes: Aes128Gcm
 │    └── x25519: X25519
 ├── ReactorSupervisor (couch)
 └── MiniDuckElement
      ├── NioBlockStore
      └── NioBlockWal
```

### 3.2 Ordering

`open()` is called depth-first: NioSupervisor → children → grandchildren.
`close()` is called reverse depth-first: grandchildren → children → supervisor.

**Test gates:**

- [ ] Elements open in registration order
- [ ] Elements close in reverse registration order
- [ ] Element opening failure does not leave partial state

## 4. Crypto Primitive Lacing (libs/tls)

### 4.1 Default Implementations

All cryptographic primitives have pure-Kotlin defaults in commonMain:

- `DefaultSha256` — FIPS 180-4 SHA-256 (no platform deps)
- `DefaultAes128Gcm` — AES-128-GCM (pure Kotlin)
- `DefaultHkdfSha256` — HKDF-SHA-256 (pure Kotlin)
- `DefaultX25519` — Curve25519 scalar multiplication (pure Kotlin)

### 4.2 Platform Acceleration

Platform implementations register themselves:
```kotlin
// JVM: register hardware-accelerated provider
class JvmSha256 : Sha256 { ... }  // calls MessageDigest.getInstance("SHA-256")
```

**Test gates:**

- [ ] `Sha256.hash("")` produces correct digest (test vector)
- [ ] `Sha256.hmac(key, data)` produces correct HMAC (test vector)
- [ ] `Aes128Gcm.encrypt(key, nonce, plain, aad)` round-trips through decrypt
- [ ] `X25519.generateKeyPair()` + `X25519.dh(sk, pk)` produces shared secret
- [ ] Default and JVM providers produce identical output

## 5. Reactor Context Lacing (libs/couch, libs/htx-client)

### 5.1 `ReactorSupervisor`

Manages poll loop, event registration, and channel fds.

```kotlin
interface ReactorOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ReactorOperations>
    suspend fun poll(timeoutMs: Long): List<ReactorEvent>
    fun register(fd: Int, interests: Interest): Unit
}
```

**Test gates:**

- [ ] `poll(timeout=0)` returns immediately with readable events
- [ ] `register(fd, READ)` fires event when data available
- [ ] `register(fd, WRITE)` fires event when buffer writable
- [ ] Deregistered fd produces no events

## 6. Cross-Platform Verification

The following source sets must compile and pass tests:

| Platform  | NIO SPI | TLS | Reactor | Dreamer | Test status |
|-----------|---------|-----|---------|---------|-------------|
| JVM       | ✓       | ✓   | ✓       | ✓       | `compileKotlinJvm` ✓ |
| JS        | ✓       | ✓   | ✓       | ✓       | `compileKotlinJs` ✓ |
| WASM      | ✓       | ✓   | ✓       | ✓       | `compileKotlinWasmJs` ✓ |
| macOS     | ✓       | ✓   | ✓       | ✓       | `compileKotlinMacos` ✓ |
| Linux     | ✗       | ✗   | ✗       | ✗       | not yet configured |

## 7. Performance Gates

### 7.1 crypto-ops/s

```kotlin
// dreamer-kmm performance gauge
class DreamerGauge {
    fun sha256Throughput(dataSize: Int): Double  // > 50 MB/s on JVM
    fun aesGcmThroughput(): Double              // > 100 MB/s on JVM
    fun simulationThroughput(): Double          // > 10k bars/s on JVM
}
```

**Gate:** DreamerGauge.smoke() must complete in < 2s on CI hardware.

## 8. Migration Status

| Service           | Old Pattern     | New Pattern        | Status    |
|-------------------|-----------------|--------------------|-----------|
| File IO           | `expect object Files` | `FileOperations` CCEK | **Deprecated Files, SPI active** |
| System env        | `expect object System` | `SystemOperations` CCEK | **Deprecated System, SPI active** |
| FileBuffer        | `expect class FileBuffer` | `FileBuffer` CCEK | **Converting** |
| IntAccumulator    | `expect class IntAccumulator` | `IntAccumulator` CCEK | **Converting** |
| CacheTopology     | `expect val platformCacheTopology` | `CacheTopologyProvider` CCEK | **Migrated** |
| SeekHandle        | `expect fun platformSeekHandle()` | `NioSupervisor` registration | **Migrated** |
| TLS Engine        | `expect fun createTlsEngine()` | `TlsElement` + CCEK crypto | **In progress** |
| WebSocket handler | `expect fun createWsHandler()` | `ReactorWebSocketHandler` | **In progress** |

## 9. References

- Kotlin coroutines: https://kotlinlang.org/docs/coroutines-overview.html
- RFC 8446 (TLS 1.3): https://datatracker.ietf.org/doc/html/rfc8446
- FIPS 180-4 (SHA-256): https://csrc.nist.gov/publications/detail/fips/180/4/final
- Curve25519: https://cr.yp.to/ecdh.html
