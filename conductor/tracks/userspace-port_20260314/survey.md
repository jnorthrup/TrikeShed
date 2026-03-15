# Userspace Architectural Survey

**Date:** 2026-03-14
**Track:** userspace-port_20260314
**Slice:** userspace-01

---

## Executive Summary

The `../userspace` Rust library implements structured concurrency patterns explicitly mirroring Kotlin coroutines, plus low-level kernel bypass features (io_uring, eBPF) and network protocol abstractions. This survey catalogs bounded behaviors and classifies them for Kotlin porting.

**Key Finding:** The structured concurrency implementation provides architectural guidance for Kotlin coroutines but requires no direct port. Kotlin coroutines already provide these primitives natively.

---

## Bounded Behavior Catalog

### 1. Structured Concurrency Module

**Location:** `/Users/jim/work/userspace/src/concurrency/`

#### 1.1 Job Trait and Implementation

**File:** `job.rs`

**Bounded Behavior:**
- `Job` trait with lifecycle methods: `start()`, `cancel()`, `join()`, state queries
- `JobImpl` with hierarchical cancellation (children cancel when parent cancels)
- `SupervisorJobImpl` with independent child cancellation
- `JobState` enum: New, Active, Completing, Completed, Cancelling, Cancelled

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `Job` interface already provides these primitives
- Rust implementation uses `Arc<RwLock<JobState>>` + `Notify` for state transitions
- Kotlin uses `CompletableJob` + `suspendCancellableCoroutine`
- **No port needed** — Kotlin coroutines already have superior implementations

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 1.2 CoroutineScope and CoroutineContext

**File:** `scope.rs`

**Bounded Behavior:**
- `CoroutineScope` trait with `get_coroutine_context()` method
- `CoroutineContext` containing Job + Dispatcher + extensible elements
- `StandardCoroutineScope` implementation
- `GlobalScope` singleton
- Context elements stored as `HashMap<TypeId, Arc<dyn Any + Send + Sync>>`

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `CoroutineScope` and `CoroutineContext` are built-in
- Rust uses `TypeId` for element keys; Kotlin uses `CoroutineContext.Key<T>`
- Kotlin's `CoroutineContext.Element` pattern is more type-safe
- **No port needed** — Kotlin already has native implementations

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 1.3 Dispatcher

**File:** `dispatcher.rs`

**Bounded Behavior:**
- `Dispatcher` trait for thread execution context
- `Dispatchers::Default` (multi-threaded pool)
- `Dispatchers::Main` (main thread confined)
- `Dispatchers::Io` (IO-optimized)
- `Dispatchers::Cpu` (CPU-intensive)
- `LimitedDispatcher` (parallelism constraint)

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `CoroutineDispatcher` and `Dispatchers` object provide identical functionality
- Rust implementation uses `tokio::runtime::Runtime` with custom thread pools
- Kotlin uses `ExecutorCoroutineDispatcher` over JVM thread pools
- **No port needed** — Kotlin has native dispatcher implementations

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 1.4 Deferred

**File:** `deferred.rs`

**Bounded Behavior:**
- `Deferred<T>` trait for async result handles
- `DeferredImpl<T>` with `await()`, `is_completed()`, cancellation support
- Similar to Kotlin's `Deferred<T>`

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `Deferred<T>` is built-in via `async { }` coroutine builder
- Rust uses `tokio::sync::RwLock` for state management
- Kotlin uses `CompletableDeferred` + `suspend` functions
- **No port needed** — Kotlin coroutines already provide this

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 1.5 Launch

**File:** `launch.rs`

**Bounded Behavior:**
- `launch()` coroutine builder (fire-and-forget)
- `async_coroutine()` coroutine builder (result-returning)
- `run_blocking()` bridge to synchronous code

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `launch { }`, `async { }`, `runBlocking { }` are built-in
- Rust uses `tokio::spawn` and `tokio::task::spawn_blocking`
- **No port needed** — Kotlin has identical coroutine builders

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 1.6 Cancellation

**File:** `cancel.rs`

**Bounded Behavior:**
- `CancellationToken` for cooperative cancellation
- Hierarchical cancellation propagation
- `CancellationException` signaling

**Kotlin Relevance:** **Architecture guidance only**
- Kotlin's `CancellableContinuation` and `CancellationException` already exist
- Rust uses `AtomicBool` + `Notify` for cancellation signaling
- Kotlin uses `Continuation<T>` + `suspendCancellableCoroutine`
- **No port needed** — Kotlin coroutines have built-in cancellation

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

### 2. Kernel Bypass Module

**Location:** `/Users/jim/work/userspace/src/kernel/`

#### 2.1 io_uring Implementation

**File:** `io_uring.rs`

**Bounded Behavior:**
- Direct kernel io_uring syscalls: `io_uring_setup`, `io_uring_enter`, `io_uring_register`
- Memory-mapped submission/completion queues (`KernelSQE`, `KernelCQE`, `MappedRing`)
- Zero-copy I/O operations: `readv`, `writev`, `recv`, `send`
- SQPOLL mode for kernel-side polling
- SIMD-aligned operation codes for autovectorization

**Kotlin Relevance:** **Kotlin/Native port candidate**
- Requires Kotlin/Native Linux target
- Direct syscall bindings needed: inline assembly or foreign function interface
- Memory-mapped rings require `mmap` interop
- Performance-critical for high-throughput transport layers

**Kotlin/Native Required:** YES
**Kotlin/JVM Required:** No (JVM NIO is separate path)

**Port Strategy:**
1. Create Kotlin/Native Linux target
2. Define `io_uring_setup`, `io_uring_enter`, `io_uring_register` as `external` functions
3. Map `KernelSQE` and `KernelCQE` structs to Kotlin data classes
4. Create `MappedRing` wrapper with `mmap`/`munmap` lifecycle

---

#### 2.2 eBPF VM and JIT

**File:** `ebpf.rs`, `ebpf_mmap.rs`

**Bounded Behavior:**
- eBPF bytecode VM for in-kernel packet processing
- JIT compilation to native code
- Memory-mapped eBPF program loading
- `bpf_syscall` bindings

**Kotlin Relevance:** **Kotlin/Native port candidate (future)**
- Requires Kotlin/Native Linux target
- eBPF syscall bindings needed
- JIT compilation would require LLVM interop or pure Kotlin implementation
- Advanced feature for future transport layers

**Kotlin/Native Required:** YES
**Kotlin/JVM Required:** No

**Port Strategy:** Defer to later slice (eBPF is advanced feature)

---

#### 2.3 Syscall Interface

**File:** `syscall.rs`

**Bounded Behavior:**
- Unified syscall abstraction: `Syscall` trait
- `syscall_net.rs` for network syscalls
- `posix_sockets.rs` for POSIX socket operations
- Cross-platform abstraction for kernel features

**Kotlin Relevance:** **Kotlin/Native port candidate**
- Requires Kotlin/Native Linux target
- Syscall numbers and argument marshaling
- Platform-specific (Linux x86_64, aarch64, etc.)

**Kotlin/Native Required:** YES
**Kotlin/JVM Required:** No

---

#### 2.4 NIO (Non-blocking I/O)

**File:** `nio.rs`

**Bounded Behavior:**
- Cross-platform non-blocking I/O abstraction
- Event loop integration
- File descriptor management

**Kotlin Relevance:** **Platform-specific**
- Kotlin/JVM already has `java.nio` channels
- Kotlin/Native would need platform-specific implementations
- Not a direct port candidate (use platform-native NIO)

**Kotlin/Native Required:** Depends on platform
**Kotlin/JVM Required:** No (already exists)

---

#### 2.5 Densified Operations (SIMD)

**File:** `densified_ops.rs`

**Bounded Behavior:**
- AVX2 vectorized `memcpy` (32-byte chunks)
- SSE2 fallback (16-byte chunks)
- Cache prefetching for reduced latency
- 3-5x faster than standard `memcpy`

**Kotlin Relevance:** **Kotlin/Native port candidate**
- Requires Kotlin/Native with SIMD intrinsics
- Platform-specific: x86_64 AVX2/SSE2
- Performance-critical for network packet processing

**Kotlin/Native Required:** YES
**Kotlin/JVM Required:** No

---

### 3. Network Module

**Location:** `/Users/jim/work/userspace/src/network/`

#### 3.1 Protocol Adapters

**File:** `adapters.rs`

**Bounded Behavior:**
- `NetworkAdapter` trait: `HttpAdapter`, `HttpsAdapter`, `QuicAdapter`, `SshAdapter`, `WebSocketAdapter`
- `NetworkStream` trait: combines `AsyncRead + AsyncWrite`
- Adapter type enum: HTTP, HTTPS, QUIC, SSH, WebSocket, Raw
- Connection lifecycle: `is_connected()`, `close()`

**Kotlin Relevance:** **Architectural pattern for TrikeShed**
- Aligns with TrikeShed's `ProtocolRouter` and `HttpEndpointType` patterns
- Rust uses `AsyncRead + AsyncWrite` traits; Kotlin uses `ByteReadChannel + ByteWriteChannel`
- **Port pattern** - extract adapter abstraction pattern for TrikeShed protocol layer

**Kotlin/Native Required:** No (common pattern)
**Kotlin/JVM Required:** No (common pattern)

**Port Strategy:**
1. Extract `NetworkAdapter` trait concept
2. Map to TrikeShed's `ProtocolRouter.detectProtocol()` + handler dispatch
3. Document adapter lifecycle pattern (connect, read/write, close)

---

#### 3.2 Protocol Detection

**File:** `protocols.rs`

**Bounded Behavior:**
- Protocol type detection from wire format
- Byte-prefix-based protocol identification
- Protocol registry for handler dispatch

**Kotlin Relevance:** **Direct pattern match**
- Aligns with TrikeShed's `detectProtocol()` in arrange-03/04
- Rust uses byte comparison; Kotlin uses same approach
- **Port pattern** - already implemented in TrikeShed

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

#### 3.3 Channel Providers

**File:** `channels.rs`

**Bounded Behavior:**
- Channel-based I/O abstraction
- Send/receive channels for packet processing
- Ring buffer integration with io_uring

**Kotlin Relevance:** **Channel pattern**
- Kotlin has `Channel<T>` from kotlinx-coroutines
- Rust uses `tokio::sync::mpsc`; Kotlin uses `Channel(capacity)`
- **No port needed** — Kotlin channels already exist

**Kotlin/Native Required:** No
**Kotlin/JVM Required:** No

---

### 4. Tensor Module

**Location:** `/Users/jim/work/userspace/src/tensor/`

#### 4.1 Core Tensor Operations

**File:** `core.rs`

**Bounded Behavior:**
- Tensor type with shape, strides, data
- Row-major and column-major layouts
- Scalar operations

**Kotlin Relevance:** **Out of scope for TrikeShed**
- TrikeShed uses `Series`/`Cursor`/`RowVec` for data
- Tensor operations are for ML workloads
- **No port** — not relevant to current TrikeShed scope

---

#### 4.2 MLIR Integration

**File:** `mlir.rs`, `mlir_sys.rs`

**Bounded Behavior:**
- MLIR (Multi-Level IR) integration for optimization
- LLVM IR generation

**Kotlin Relevance:** **Out of scope for TrikeShed**
- MLIR is compiler infrastructure
- Not relevant to network/transport layer
- **No port**

---

### 5. DSEL Module

**File:** `dsel.rs`

**Bounded Behavior:**
- Domain-Specific Embedded Language for drawdown calculations
- Indicator DSL

**Kotlin Relevance:** **Already exists in TrikeShed**
- TrikeShed has `DrawdownDsel.kt` in kotlingrad track
- Rust implementation mirrors TrikeShed's DSEL patterns
- **No port needed** — already covered by Kotlingrad integration

---

### 6. Database Module

**Location:** `/Users/jim/work/userspace/src/database/`

#### 6.1 CouchDB Emulator

**File:** `couch.rs`

**Bounded Behavior:**
- CouchDB protocol emulation
- Document storage/retrieval

**Kotlin Relevance:** **Out of scope for current track**
- Database integration is separate concern
- Could be future track for data persistence layer
- **Defer for future consideration**

---

## Kotlin Port Classification Summary

### Category 1: Architecture Guidance Only (No Port)

- Job, CoroutineScope, CoroutineContext, Dispatcher, Deferred, Launch, Cancellation
- Channel abstractions (Kotlin `Channel<T>` already exists)

**Reason:** Kotlin coroutines already provide superior implementations of structured concurrency primitives. The Rust code is valuable as architectural reference for understanding how to structure similar systems in Kotlin, but direct porting would be redundant and inferior to Kotlin's native implementations.

### Category 2: Kotlin/Native Port Required

- io_uring ring buffers and syscalls
- eBPF VM and JIT (future)
- Syscall interface
- SIMD densified operations

**Reason:** These require low-level kernel access that only Kotlin/Native can provide. JVM abstractions are insufficient for kernel bypass operations. Platform-specific (Linux).

### Category 3: Pattern Port (Architectural Abstraction)

- NetworkAdapter trait pattern
- Protocol detection pattern
- Adapter lifecycle management

**Reason:** The architectural patterns (not the code) are valuable for structuring TrikeShed's protocol layer. Extract design patterns for protocol routing.

### Category 4: Out of Scope

- Tensor operations (TrikeShed uses Series/Cursor)
- MLIR integration (compiler infrastructure)
- Database modules (separate concern)

---

## Triage Verdicts

| Behavior | Kotlin/Native | Kotlin/JVM | Pattern Port | Defer | Reason |
|---|---|---|---|---|---|
| Job/Scope/Dispatcher | ❌ | ❌ | ❌ | ❌ | Kotlin coroutines already provide |
| io_uring rings + syscalls | ✅ | ❌ | ❌ | ❌ | Requires kernel bypass |
| eBPF VM + JIT | ✅ | ❌ | ❌ | ✅ | Advanced feature, future slice |
| Syscall interface | ✅ | ❌ | ❌ | ❌ | Kotlin/Native syscall bindings |
| SIMD memcpy | ✅ | ❌ | ❌ | ❌ | Performance-critical for transport |
| NetworkAdapter trait | ❌ | ❌ | ✅ | ❌ | Pattern for protocol routing |
| Protocol detection | ❌ | ❌ | ✅ | ❌ | Already in TrikeShed arrange-04 |
| Channels | ❌ | ❌ | ❌ | ❌ | Kotlin `Channel<T>` exists |
| Tensor ops | ❌ | ❌ | ❌ | ❌ | Out of scope (use Series/Cursor) |
| MLIR | ❌ | ❌ | ❌ | ❌ | Out of scope |
| Database modules | ❌ | ❌ | ❌ | ✅ | Defer to future track |

---

## Recommended Slice Order

### userspace-02: Structured Concurrency Pattern Guide
**Status:** [ ] pending → **SKIP** (Kotlin coroutines already superior)

**Rationale:** No port needed. Kotlin has `Job`, `CoroutineScope`, `CoroutineContext`, `Dispatcher`, `Deferred`, launch/async/runBlocking built-in. The Rust implementation is valuable as architectural reference only.

**New Deliverable:** 
- Document how Rust structured concurrency maps to Kotlin coroutines
- Create comparison guide showing Kotlin coroutine equivalents
- **No code changes**

### userspace-03: Kotlin/Native Target Setup
**Status:** [ ] pending → **REQUIRED**

**Rationale:** Kernel bypass (io_uring, eBPF, syscalls) requires Kotlin/Native Linux target. Must establish this infrastructure before porting kernel features.

**Deliverables:**
- Add `linuxX64` target to TrikeShed `build.gradle.kts`
- Create `src/nativeMain/kotlin/` source set
- Configure Kotlin/Native compiler options
- Verify compilation with native target

### userspace-04: Syscall Interface Design
**Status:** [ ] pending → **AFTER userspace-03**

**Rationale:** Design syscall bindings for Kotlin/Native before implementing io_uring. Simpler bounded behavior than io_uring.

**Deliverables:**
- `src/nativeMain/kotlin/borg/trikeshed/syscall/Syscall.kt`
- `external` function declarations for Linux syscalls
- Failing tests for syscall operations

### userspace-05: io_uring Bindings Prototype
**Status:** [ ] pending → **AFTER userspace-04**

**Rationale:** Most complex bounded behavior. Requires syscall interface as foundation.

**Deliverables:**
- `src/nativeMain/kotlin/borg/trikeshed/io_uring/`
- `KernelSQE`, `KernelCQE` data classes
- `MappedRing` wrapper with mmap lifecycle
- Failing tests for submission/completion queue operations

---

## Evidence Log

- **2026-03-14**: Surveyed userspace concurrency module — all structured concurrency primitives have Kotlin equivalents
- **2026-03-14**: Surveyed kernel module — io_uring requires Kotlin/Native; eBPF is future work
- **2026-03-14**: Surveyed network module — adapter pattern aligns with TrikeShed protocol routing
- **2026-03-14**: Classified behaviors into: Architecture Guidance, Kotlin/Native Port, Pattern Port, Out of Scope
- **2026-03-14**: Recommended slice order: skip userspace-02, prioritize userspace-03 (Kotlin/Native setup)