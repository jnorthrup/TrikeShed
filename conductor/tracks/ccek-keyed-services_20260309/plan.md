# Track: CCEK Keyed Services Infrastructure

**Track ID:** `ccek-keyed-services_20260309`
**Branch:** `master` (merged from former `feat/ccek-keyed-services`)
**Worktree:** `../TrikeShed-ccek` detached at merged commit
**Status:** 🔄 Course-correcting

---

## Purpose

Add minimal CCEK (Coroutine Context Element Key) infrastructure to TrikeShed `commonMain`.
Keep only typed coroutine-key service plumbing as accepted scope.
Transport architecture ownership is now being reassigned via `relaxfactory-literbike-arrangement_20260309`.

## Invariants

- CCEK = `CoroutineContext.Element` + companion `CoroutineContext.Key`. No forced fields.
- Kotlin native type-safe keys — no string dispatch.
- Reject the string-keyed `ContextElement` / `EmptyContext` map pattern seen in `../literbike/src/concurrency/ccek.rs` and `../literbike/src/reactor/context.rs`.
- Do NOT port: `CcekContext` 4-field structure, `CCEKEngine`, `TransformationPipeline`, ML hooks.
- Flat package layout — `ccek` goes under existing `commonMain`, no new subproject.
- `ccek/transport/*` is not the canonical owner of universal-listener or QUIC runtime architecture.

## Course Correction

- `ccek-01`, `ccek-03`, `ccek-04`, and `ccek-05` were materially merged.
- The original `ccek-02` transport-design framing overstated CCEK as the transport architecture owner.
- Bike-line CCEK experiments are useful as cautionary examples, but not as the target architecture for TrikeShed.
- Follow-on transport arrangement now lives in `conductor/tracks/relaxfactory-literbike-arrangement_20260309/`.

---

## Slice Schema

### ccek-01 — Base Interface
**Status:** [ ] open
**Owner:** slave
**Corpus:** new files under `src/commonMain/kotlin/borg/trikeshed/ccek/`

**Deliverables:**

`src/commonMain/kotlin/borg/trikeshed/ccek/KeyedService.kt`
```kotlin
package borg.trikeshed.ccek

import kotlin.coroutines.CoroutineContext

/** Base marker for all CCEK keyed services. No forced fields. */
interface KeyedService : CoroutineContext.Element
```

`src/commonMain/kotlin/borg/trikeshed/ccek/CcekScope.kt`
```kotlin
package borg.trikeshed.ccek

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/** Retrieve a keyed service from the current coroutine context, or null if absent. */
suspend fun <T : KeyedService> coroutineService(key: CoroutineContext.Key<T>): T? =
    currentCoroutineContext()[key]
```

**Verification:** `./gradlew compileKotlinJvm`
**Depends on:** nothing

---

### ccek-02 — Transport Coexistence Designs
**Status:** [ ] open
**Owner:** slave
**Corpus:** new files under `src/commonMain/kotlin/borg/trikeshed/ccek/transport/`
**Depends on:** ccek-01 (KeyedService must exist)

**Design 3 (ngSCTP spirit):** kernel-first, SCTP semantics, multi-homing, message-oriented, deterministic CC.
**Design 4 (channelized QUIC):** user-space, Kotlin channels per stream, io_uring zero-copy, XDP steering.
Both coexist under the same `StreamTransport` interface. Client code picks SCTP first, falls back to QUIC.

**Deliverables:**

`src/commonMain/kotlin/borg/trikeshed/ccek/transport/StreamTransport.kt`
```kotlin
package borg.trikeshed.ccek.transport

import borg.trikeshed.ccek.KeyedService
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/** A single logical stream — independent send/receive channels backed by installed transport. */
data class StreamHandle(
    val id: Int,
    val send: SendChannel<ByteArray>,
    val recv: ReceiveChannel<ByteArray>
)

/** Transport-agnostic multi-stream abstraction. Both NgSctpService and QuicChannelService implement this. */
interface StreamTransport : KeyedService {
    suspend fun openStream(): StreamHandle
    val activeStreams: Int
}
```

`src/commonMain/kotlin/borg/trikeshed/ccek/transport/NgSctpService.kt`
```kotlin
package borg.trikeshed.ccek.transport

import kotlin.coroutines.CoroutineContext

/**
 * SCTP-semantics transport CCEK service (Design 3).
 * - Streams independent: HOL blocking per-stream only
 * - Multi-homing: multiple paths, automatic failover
 * - Message-oriented: ByteArray boundaries preserved
 * - Chunk TLV: unknown chunks skipped by length (parse-forward)
 * - CC: cubic/hstcp/rack — deterministic only, no ML
 */
data class NgSctpService(
    val streams: Map<Int, StreamHandle> = emptyMap(),
    val paths: List<String> = emptyList(),
    val congestionControl: String = "cubic"
) : StreamTransport {
    companion object Key : CoroutineContext.Key<NgSctpService>
    override val key: CoroutineContext.Key<*> get() = Key
    override suspend fun openStream(): StreamHandle = TODO("NgSctp stream factory")
    override val activeStreams: Int get() = streams.size
}
```

`src/commonMain/kotlin/borg/trikeshed/ccek/transport/QuicChannelService.kt`
```kotlin
package borg.trikeshed.ccek.transport

import kotlin.coroutines.CoroutineContext

/**
 * Channelized QUIC transport CCEK service (Design 4).
 * - Each QUIC stream → Kotlin Channel<ByteArray> under structured concurrency
 * - io_uring ring fd for zero-copy async I/O (system liburing / JNI binding)
 * - XDP/eBPF for deterministic packet → per-core io_uring ring steering (hash-based)
 * - Cancellation free via structured concurrency scope
 * - ioUringFd = -1 → epoll fallback
 */
data class QuicChannelService(
    val streams: Map<Int, StreamHandle> = emptyMap(),
    val ioUringFd: Int = -1,
    val xdpProg: String? = null
) : StreamTransport {
    companion object Key : CoroutineContext.Key<QuicChannelService>
    override val key: CoroutineContext.Key<*> get() = Key
    override suspend fun openStream(): StreamHandle = TODO("QUIC stream factory")
    override val activeStreams: Int get() = streams.size
}
```

**Coexistence usage pattern (for tests/docs only):**
```kotlin
val ctx = NgSctpService(paths = listOf("10.0.0.1", "10.0.0.2")) +
          QuicChannelService(ioUringFd = -1)
withContext(ctx) {
    val transport = coroutineContext[NgSctpService.Key]
        ?: coroutineContext[QuicChannelService.Key]
        ?: error("no transport in context")
}
```

**Verification:** `./gradlew compileKotlinJvm`

---

### ccek-03 — HomeDirService
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/common/HomeDir.kt` (modify only — append, do not restructure)
**Depends on:** ccek-01

Current file has `val homedir`, `val homedirGet`, and `expect` declarations. Read it first.
Append to bottom of file (add import at top):

```kotlin
// --- CCEK ---
import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/** CCEK keyed service wrapping the home directory path. */
data class HomeDirService(val path: String) : KeyedService {
    companion object Key : CoroutineContext.Key<HomeDirService>
    override val key: CoroutineContext.Key<*> get() = Key
}
```

**Verification:** `./gradlew compileKotlinJvm`

---

### ccek-04 — SeekHandleService
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/common/SeekHandle.kt` (modify only — append)
**Depends on:** ccek-01

Read `SeekHandle.kt` first to understand the type. Append `SeekHandleService` wrapping it.
Use the actual `SeekHandle` type name as found in the file.

```kotlin
// --- CCEK ---
import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/** CCEK keyed service exposing an open SeekHandle to the coroutine context. */
data class SeekHandleService(val handle: SeekHandle) : KeyedService {
    companion object Key : CoroutineContext.Key<SeekHandleService>
    override val key: CoroutineContext.Key<*> get() = Key
}
```

**Verification:** `./gradlew compileKotlinJvm`

---

### ccek-05 — IndicatorContextService
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/signal/SignalGenerator.kt` (modify only — append)
**Depends on:** ccek-01

Read `SignalGenerator.kt` first. `SampleStrategySignals.Indicators` is a nested data class inside `object SampleStrategySignals`.
Append to the end of the file:

```kotlin
// --- CCEK ---
import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * CCEK keyed service wrapping precomputed SampleStrategy indicators.
 * Install via withContext(IndicatorContextService(indicators)) so all coroutines
 * in the scope share one indicator computation instead of recomputing per-candle.
 */
data class IndicatorContextService(
    val indicators: SampleStrategySignals.Indicators
) : KeyedService {
    companion object Key : CoroutineContext.Key<IndicatorContextService>
    override val key: CoroutineContext.Key<*> get() = Key
}
```

**Verification:** `./gradlew compileKotlinJvm`

---

### ccek-06 — Tests
**Status:** [ ] open
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/ccek/KeyedServiceTest.kt` (create new)
**Depends on:** ccek-01 through ccek-05

Test cases (use `runTest` from `kotlinx-coroutines-test`):

1. **base composition** — `HomeDirService("~") + QuicChannelService()` context: retrieve both services, assert non-null
2. **absent service** — empty context: `coroutineContext[HomeDirService.Key]` returns null without throwing
3. **withContext round-trip** — `withContext(HomeDirService("/tmp")) { coroutineContext[HomeDirService.Key]!!.path }` == `"/tmp"`
4. **transport coexistence** — `NgSctpService() + QuicChannelService()` context: both keys resolve; SCTP-first fallback pattern works
5. **IndicatorContextService** — construct minimal `SampleStrategySignals.Indicators` (use `emptyList().size j { 0.0 }` for series); retrieve from context

**Verification:** `./gradlew jvmTest --tests "borg.trikeshed.ccek.*"`

---

## Execution Order

```
ccek-01  →  ccek-02
         →  ccek-03
         →  ccek-04
         →  ccek-05
                    →  ccek-06
```

Slices 02–05 can run in parallel after ccek-01 compiles.

## Completion Criteria

- All 6 slices `[x]` closed
- `./gradlew jvmTest --tests "borg.trikeshed.ccek.*"` passes
- `./gradlew jvmTest --tests "borg.trikeshed.signal.*"` no regressions
- Committed on `feat/ccek-keyed-services`
