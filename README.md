# TrikeShed `src/` — Core Substrate

Shared IO and choreography substrate for all `libs/*` modules. Targets commonMain, nativeMain, posixMain, linuxMain, macosMain, jvmMain, jsMain, wasmJsMain.

Kernel algebra (`Join`, `Series<T>`, `Twin`, `α`, `j`) is defined in `lib/` and documented in [`PRELOAD.md`](../PRELOAD.md). This README covers the IO transport and choreography layers only.

---

## Layers

```
┌──────────────────────────────────────────────────────────────────────┐
│  NIO COMPATIBILITY SURFACE  (nio/channels/, nio/file/)               │
│  UringSocketChannel  UringFileChannel  UringServerSocketChannel      │
│  COMPATIBILITY SURFACE ONLY.  DO NOT ROUTE NEW IO THROUGH HERE.      │
├──────────────────────────────────────────────────────────────────────┤
│  REAL TRANSPORT SUBSTRATE  (userspace/)                              │
│                                                                      │
│  File          handle lifecycle (open/close/isOpen/id)               │
│  Channel       operation queue (read/write/accept/connect/close)     │
│    │            submit/wait/peek                                     │
│    └─ ChannelImpl  (expect → actual per platform)                    │
│         └─ FunctionalUringFacade  (op batching + backend dispatch)   │
│              └─ UserspaceChannelBackend  (expect → actual)           │
│                   └─ Liburing  →  LiburingImpl  (expect → actual)    │
│                                                                      │
│  data types:                                                         │
│    ByteBuffer  → NIO buffer backed by ByteArray                     │
│    ByteRegion  → sub-range view of ByteBuffer                       │
│    ByteSeries  → lazy Series<Byte> over ByteRegion (zero copy)      │
├──────────────────────────────────────────────────────────────────────┤
│  CHOREOGRAPHY  (context/)                                            │
│                                                                      │
│  AsyncContextElement   SupervisorJob, lifecycle, fanout subscribers  │
│  AsyncContextKey<E>    type-safe context lookup                      │
│  ConcreteElements      NioUserspaceElement, LiburingElement,         │
│                        FanoutDispatcherElement                       │
│  LiburingFacadeSpi     SPI for pluggable uring backends              │
│  UserspaceNioSpi       SPI for pluggable NIO backends                │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL INTERFACES  (userspace/kernel/)                              │
│                                                                      │
│  SelectableChannelOps  pollReadable/pollWritable + tryRead/tryWrite │
│  KernelUring           SQE/CQE abstraction ported from literbike     │
│  KernelSQE / KernelCQE kernel uring structs                          │
│  OpCode / UringSetupFlags                                            │
├──────────────────────────────────────────────────────────────────────┤
│  NETWORK PROTOCOL SURFACE  (userspace/network/)                     │
│                                                                      │
│  Channel               protocol session surface                      │
│  ChannelMetadata       remoteAddr, localAddr, protocol               │
│  ProtocolDetector      auto-detect protocol from first bytes         │
├──────────────────────────────────────────────────────────────────────┤
│  LIBRARY  (lib/)                                                     │
│                                                                      │
│  Join<A,B>  Series<T>  Twin<T>  ByteSeries  Cursor  HashSeriesSet   │
│  RadixTree  CircularQueue  etc.                                      │
│  Documented in PRELOAD.md                                            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Transport Substrate: the only IO path

All IO routes through one surface:

```kotlin
val file  = Files.open("/data/foo.db")
val sock  = Channels.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
val ch    = Channels.open(entries = 256)

ch.read(file, buffer, offset = 0L, userData = 1L)
ch.submit()
val results = ch.wait(minComplete = 1)
```

This is synchronous submit+wait for the compatibility surface. The coroutine path uses `ChannelRunner`:

```kotlin
val runner = ChannelRunner(channel, scope)
runner.start()   // poll loop: channel.wait(0), resume deferreds, yield

suspend fun doRead(buf: ByteBuffer): SelectionResult = runner.runOp { token ->
    channel.read(file, buf, 0L, token)
}
```

### Platform wiring (expect/actual)

| Symbol | commonMain | posixMain | linuxMain | jvmMain | js/wasm |
|--------|-----------|-----------|-----------|---------|---------|
| `FileImpl` | expect class | POSIX fd wrapper | inherits posix | JDK Path | — |
| `ChannelImpl` | expect class | `FunctionalUringFacade` | inherits posix | SPI delegate | — |
| `LiburingImpl` | expect object | — | cinterop `io_uring_*` | ServiceLoader | `UnsupportedOperationException` |
| `FilesImpl` | expect object | POSIX `open()` | inherits posix | `java.nio.file` | — |
| `ChannelsImpl` | expect object | POSIX `socket()` | inherits posix | fd emulation | — |

`openUserspaceChannelBackend()` — expect function, POSIX actual returns `PosixUserspaceChannelBackend`.

---

## Data Flow: ByteArray → Kernel

```
ByteBuffer.read(src)                     UringSocketChannel / UringFileChannel
  → channel.read(file, buf, offset, tok) userspace.Channel
    → ChannelImpl.read()                 expect → actual
      → FunctionalUringFacade.read()     enqueue PreparedChannelOp.Read
  → channel.submit()
    → FunctionalUringFacade.submit()     drain pending → backend.read()
      → PosixUserspaceChannelBackend.read()
        → PosixUringIO.readAt(fd, bytes, start, len, offset)
          → bytes.usePinned { pinned.addressOf(start) }
            → Liburing.prepRead(fd, address, len, offset, userData)
              → LiburingImpl.prepRead()  [linux: io_uring_prep_read via cinterop]
            → Liburing.submit()          [linux: io_uring_submit]
            → Liburing.waitCqe()         [linux: io_uring_wait_cqe]
              → publish(completion)      channelized fanout to handlers
  → channel.wait(minComplete = 1)        drain completions → SelectionResult list
```

Fallback on POSIX when uring unavailable: `pread`/`pwrite` (seekable) or `read`/`write` (streams).

---

## Choreography: Element Lifecycle

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Every IO component that needs coroutine context and lifecycle extends `AsyncContextElement`:

```kotlin
class MyElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MyElement>()  // Pattern A (canonical)

    override suspend fun open() {
        super.open()          // CREATED → OPEN
        state = ElementState.ACTIVE
        // register uring fanout handlers here
    }

    override suspend fun drain() {
        state = ElementState.DRAINING
        // drain completion queue
        super.close()         // → CLOSED, cancels SupervisorJob
    }
}
```

### Rules

1. Use `AsyncContextKey<E>` (not raw `CoroutineContext.Key<E>`)
2. Companion `Key` is the type-safe lookup: `coroutineContext[MyElement.Key]`
3. `SupervisorJob(parentJob)` is inherited from `AsyncContextElement` — children survive sibling failures
4. `fanoutSubscribers` is the ordered downstream delivery list
5. Lifecycle is forward-only — no transitions backward

---

## Kernel Surfaces

### SelectableChannelOps

Reactor-level readiness surface. Speaks `ByteRegion`/`ByteSeries` — no raw `ByteArray`:

```kotlin
interface SelectableChannelOps {
    suspend fun pollReadable(timeout: Duration? = null): Boolean
    suspend fun pollWritable(timeout: Duration? = null): Boolean
    fun tryRead(dst: ByteRegion): Int
    fun tryWrite(src: ByteSeries): Int
}
```

Used by protocol transport implementations in `libs/` (e.g., `HtxTransport`).

### KernelUring

Low-level SQE/CQE abstraction ported from literbike. Separate from `LiburingFacade` — this is the kernel struct surface, the facade is the op-queue surface.

```kotlin
interface KernelUring {
    fun fd(): Int
    fun submitDirect(sqe: KernelSQE): Result<Unit>
    fun submitBulk(sqes: List<KernelSQE>): Result<Int>
    fun reapCompletions(): List<KernelCQE>
}
```

---

## Network Protocol Surface

Protocol-agnostic session layer. Auto-detects protocol from first bytes:

```kotlin
interface Channel {
    fun channelType(): String
    fun isConnected(): Boolean
    fun metadata(): ChannelMetadata?
    fun read(dst: ByteRegion): Int
    fun write(src: ByteSeries): Int
}
```

`ProtocolDetector` handles ALPN-like initial byte inspection for QUIC vs HTTP vs websocket routing.

---

## What NOT to do

- **Do not route new IO through `nio/channels/`.** The UringSocketChannel/UringFileChannel stubs are JDK compatibility only. Implement `UserspaceChannelBackend` or wire through `userspace.Channel` instead.
- **Do not create a second IO path.** There is one ring, one facade, one channelized fanout. Epoll/kqueue wrappers would be a bifurcation.
- **Do not use raw `CoroutineContext.Key<E>`.** Use `AsyncContextKey<E>`.
- **Do not manage `ElementState` manually.** Extend `AsyncContextElement`.

---

## Consuming from libs/

A `libs/` module declares `api(project(":"))` and receives all of the above. Typical pattern:

```kotlin
class MyProtocolElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MyProtocolElement>()

    private lateinit var channel: Channel
    private lateinit var file: File

    override suspend fun open() {
        super.open()
        channel = Channels.open(256)
        file = Channels.socket(AF_INET, SOCK_STREAM, 0)
        state = ElementState.ACTIVE
    }

    suspend fun request(buf: ByteBuffer): SelectionResult {
        val runner = ChannelRunner(channel, coroutineScope)
        return runner.runOp { token ->
            channel.write(file, buf, 0L, token)
        }
    }
}
```

---

## Related

- [`PRELOAD.md`](../PRELOAD.md) — kernel algebra: `Join`, `Series<T>`, `Twin`, `α`, `j`
- [`docs/plans/PLAN.md`](../docs/plans/PLAN.md) — consolidation roadmap
- [`io_uring_interop/`](../io_uring_interop/) — C interop headers for Linux io_uring
- [`src/linuxMain/.../Liburing.linux.kt`](linuxMain/kotlin/borg/trikeshed/userspace/Liburing.linux.kt) — cinterop actual
- [`src/posixMain/.../PosixUringIO.kt`](posixMain/kotlin/borg/trikeshed/PosixUringIO.kt) — uring-or-POSIX fallback
