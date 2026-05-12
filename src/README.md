# TrikeShed `src/` — Core Substrate

Shared IO substrate, HTX tokenizer, and choreography base classes for all `libs/*` modules.
Targets commonMain, nativeMain, posixMain, linuxMain, macosMain, jvmMain, jsMain, wasmJsMain.

Kernel algebra (`Join`, `Series<T>`, `Twin`, `α`, `j`) is defined in `lib/` and documented in
[`PRELOAD.md`](../PRELOAD.md). This README covers the IO transport, reactor, and choreography layers.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  CHOREOGRAPHY  (context/)                                            │
│                                                                      │
│  AsyncContextElement   SupervisorJob, lifecycle, fanout subscribers  │
│  AsyncContextKey<E>    type-safe context lookup                      │
│  ConcreteElements      LiburingElement, FanoutDispatcherElement,     │
│                        NioUserspaceElement                           │
├──────────────────────────────────────────────────────────────────────┤
│  REACTOR  (userspace/ · ChannelRunner · FunctionalUringFacade)       │
│                                                                      │
│  Multiple concurrent Jobs sharing one io_uring ring. Each Job is     │
│  a coroutine with its own File handle. IO steps chain as FSM         │
│  transitions via continuation — the RelaxFactory attachment model    │
│  translated to coroutine suspension.                                 │
│                                                                      │
│  Channel        operation queue (read/write/accept/connect/close,    │
│                  submit/wait/peek)                                   │
│  ChannelRunner  coroutine FSM: runOp → CompletableDeferred → resume  │
│  File           handle lifecycle (open/close/isOpen/id)              │
│                                                                      │
│      io_uring ring (one CQE loop — LiburingImpl.publish)             │
│         │                                                            │
│         ├─ Job 1: htx-general-client (QUIC, TCP, ngSCTP)             │
│         │    File(fd) ─ Channel ─ submit ─ waitCqe ─ fanout          │
│         │                                                            │
│         ├─ Job 2: couch (HTX over transport)                         │
│         │    File(fd) ─ Channel ─ submit ─ waitCqe ─ fanout          │
│         │                                                            │
│         └─ Job 3: ipfs (DHT UDP, content routing)                    │
│              File(fd) ─ Channel ─ submit ─ waitCqe ─ fanout          │
├──────────────────────────────────────────────────────────────────────┤
│  HTX TOKENIZER  (tokenized in couch/htx/, wired through Channel)     │
│                                                                      │
│  Version-agnostic HTTP 1-3 message tokenizer. HAProxy htx_blk model. │
│  Block sequence (ReqSl·Hdr·EOH·Data·EOT·EOM) is identical whether   │
│  the bytes arrived via HTTP/1.1 text, HTTP/2 frames, or HTTP/3 QUIC. │
│  DHTX_REQ/DHTX_RES block types carry non-HTTP protocols.             │
│                                                                      │
│  HtxMessage  block list with flags                                   │
│  HtxBlock    packed metadata (type·nameLen·valueLen·addr)            │
│  HtxStartLine request|response with version Pair<Int,Int>            │
│  normalizeToHtx() auto-detect transport version                     │
├──────────────────────────────────────────────────────────────────────┤
│  TRANSPORT SUBSTRATE  (userspace/)                                   │
│                                                                      │
│  ChannelImpl  expect → actual per platform                           │
│    └─ FunctionalUringFacade  op batching + backend dispatch          │
│         └─ UserspaceChannelBackend  expect → per-platform actual     │
│              └─ Liburing → LiburingImpl  expect → actual             │
│                   (linux: cinterop io_uring, posix: uring-or-pread,  │
│                    jvm: ServiceLoader SPI, macos/js/wasm: fallback)  │
│                                                                      │
│  data types:                                                         │
│    ByteBuffer  → ByteArray-backed NIO buffer                         │
│    ByteRegion  → sub-range view of ByteBuffer                        │
│    ByteSeries  → lazy Series<Byte> over ByteRegion (zero copy)       │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL SURFACES  (userspace/kernel/)                                │
│                                                                      │
│  SelectableChannelOps  pollReadable/pollWritable + tryRead/tryWrite  │
│  KernelUring           SQE/CQE abstraction (literbike port)          │
│  PosixSocket           bind/listen/accept/connect/send/recv          │
├──────────────────────────────────────────────────────────────────────┤
│  NIO COMPAT STUBS  (nio/channels/, nio/file/)                        │
│                                                                      │
│  UringSocketChannel, UringFileChannel, UringServerSocketChannel      │
│  COMPATIBILITY SURFACE ONLY.  DO NOT ROUTE NEW IO THROUGH HERE.      │
├──────────────────────────────────────────────────────────────────────┤
│  LIBRARY  (lib/)  ·  PRELOAD.md                                      │
│                                                                      │
│  Join<A,B>  Series<T>  Twin<T>  ByteSeries  Cursor  HashSeriesSet   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Reactor — Multiple Concurrent Jobs, One Uring Ring

The reactor models RelaxFactory's single-threaded selector + attachment-chain pattern,
translated to coroutines and io_uring. Every Job runs its own FSM but shares the same ring.

Each `Job` gets its own `File` handle (unique fd) but shares the `Channel` (and therefore
the uring ring). The ring concurrently processes SQEs for all fds. The CQE loop fans
completions back to the correct coroutine via `userData` token.

### Continuation Model (RelaxFactory `Helper.toRead` → `runOp`)

In RelaxFactory Java, an HTTP operation chains: `Helper.toRead(key, nextF)` attaches a
new `AsioVisitor` to the `SelectionKey`, creating a linear FSM:

```
OP_CONNECT → OP_WRITE(request) → OP_READ(headers) → OP_READ(body) → deliver
```

In TrikeShed, the same chain is coroutine suspension via `ChannelRunner.runOp()`:

```
connect  → runOp(token1) → suspend  → CQE → resume
write    → runOp(token2) → suspend  → CQE → resume
read     → runOp(token3) → suspend  → CQE → resume
close    → submit() (fire-and-forget)
```

```kotlin
class HtxTransport(channel: Channel) : SelectableChannelOps {
    suspend fun execute(request: HtxClientRequest): HtxClientMessage {
        val runner = ChannelRunner(channel, coroutineScope)
        val file = Channels.socket(AF_INET, SOCK_STREAM, 0)

        // Step 1: connect
        val connected = runner.runOp { token ->
            channel.connect(file, host, port, token)
        }

        // Step 2: write request
        val written = runner.runOp { token ->
            channel.write(file, requestBuf, 0L, token)
        }

        // Step 3: read response
        val result = runner.runOp { token ->
            channel.read(file, responseBuf, 0L, token)
        }

        channel.close(file, nextToken)
        channel.submit()
        return parseResponse(result)
    }
}
```

### Concurrent Jobs over One Ring

```kotlin
val ch = Channels.open(256)
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// 1,000 concurrent HTTP requests, one uring ring
val results = (1..1000).map { i ->
    scope.async {
        HtxTransport(ch).execute(HtxClientRequest("GET", "https://host$i/api"))
    }
}.awaitAll()
```

| RelaxFactory | TrikeShed |
|-------------|-----------|
| `AsyncSingletonServer.innerloop()` | one `io_uring_wait_cqe` loop |
| `SelectionKey.attach(AsioVisitor)` | `CompletableDeferred` map by `userData` |
| `Helper.toRead(key, nextF)` | `runner.runOp { channel.read(...) }` (suspend) |
| `Helper.finishRead(key, payload, success)` | `deferred.complete(SelectionResult)` |
| `CouchConnectionFactory.createCouchConnection()` | `Channels.socket()` → `File` |
| `Phaser.arrive()` (sync bridge) | `CompletableDeferred.await()` |
| `RpcHelper.EXECUTOR_SERVICE` thread pool | `CoroutineScope(Dispatchers.Default)` |
| `NAMESPACE` dispatch (`EnumMap<Method, Map<Pattern, Class>>`) | `AsyncContextKey` context lookup |

### Cross-Module Context Access

Protocol Elements share context via their `AsyncContextKey`. An IPFS DHT query can
reach into couch to inspect collection state, and vice versa:

```kotlin
// Inside IpfsElement:
val couchElement = currentCoroutineContext()[CouchElement.Key]
val collections = couchElement?.activeCollections()
```

---

## HTX — Version-Agnostic HTTP 1-3 Tokenizer

HTX is the common tokenizer for HTTP/1.x, HTTP/2, and HTTP/3. It models HTTP messages
as a sequence of typed blocks, following HAProxy's internal `htx_blk` format. The block
sequence is identical regardless of transport — only the *parser* that produces it changes.

```
HTTP/1.1 text on wire   →  parseHttp1()  ─┐
HTTP/2 binary frames    →  (H2 parser)    ─┼→  [ReqSl · Hdr · Hdr · EOH · Data · EOT · EOM]
HTTP/3 QUIC stream      →  (H3 mapping)    ─┘
DHTX internal           →  DHTX_REQ/RES   ─┘   (non-HTTP, same framing)
```

### Block Model

```
HtxMessage
  ├─ StartLine   (method · uri · version) or (version · status · reason)
  ├─ Header × N  (name:value byte pairs)
  ├─ EndHeaders  (EOH marker)
  ├─ Data × N    (body payload blocks)
  ├─ Trailer × N (trailing headers, chunked encoding)
  ├─ EndTrailers (EOT marker)
  └─ flags       (EOM, PARSING_ERROR, FRAGMENTED, UNORDERED)
```

`HtxBlock` is the HAProxy wire-compatible block descriptor — `addr` (ring offset) plus packed
`info` (type·4bits | valueLen·20bits | nameLen·8bits). The Kotlin implementation uses
`MutableList<HtxBlockData>` (linear list) with the same descriptor format preserved for
future ring-buffer integration.

### Version Independence

| StartLine field | HTTP/1.1 | HTTP/2 | HTTP/3 |
|-----------------|---------|--------|--------|
| `version` | `1 to 1` | `2 to 0` | `3 to 0` |
| `method` | same `HttpMethod` enum | same | same |
| `uri` | `/path?query` | `:path` pseudo-header | same as H2 |
| `status` | same `Int` | same | same |

`HtxStartLine.parseHttpVersion()` handles all three. `HtxMessage.normalizeToHtx()` auto-detects
transport via trie lookup (HTTP/1.x methods, `HTTP/` prefix) and connection-preface matching
(24-byte `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`).

### Non-HTTP Protocols

`DHTX_REQ` and `DHTX_RES` block types (codes 16-17, beyond the HAProxy 0-15 range) carry
non-HTTP protocols within the same HTX framing. `HtxSlFlags.NOT_HTTP` marks a start-line
as non-HTTP. The same block model, serialization, and reactor dispatch handle both HTTP and
non-HTTP protocols identically.

### Serialization

Binary frame format with CRC32 integrity:

```
HTX_MAGIC(4) | flags(4) | blockCount(2) | [(type·len, data) × N] | CRC32(4)
```

Used for inter-process HTX transport and on-disk message logs.

---

## Transport Substrate — the Only IO Path

All IO routes through one surface:

```kotlin
val file  = Files.open("/data/foo.db")
val sock  = Channels.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
val ch    = Channels.open(entries = 256)

ch.read(file, buffer, offset = 0L, userData = 1L)
ch.submit()
val results = ch.wait(minComplete = 1)
```

### Platform Wiring (expect/actual)

| Symbol | commonMain | posixMain | linuxMain | jvmMain | js/wasm |
|--------|-----------|-----------|-----------|---------|---------|
| `FileImpl` | expect class | POSIX fd wrapper | inherits posix | JDK Path | — |
| `ChannelImpl` | expect class | `FunctionalUringFacade` | inherits posix | SPI delegate | — |
| `LiburingImpl` | expect object | — | cinterop `io_uring_*` | ServiceLoader | `UnsupportedOperationException` |
| `FilesImpl` | expect object | POSIX `open()` | inherits posix | `java.nio.file` | — |
| `ChannelsImpl` | expect object | POSIX `socket()` | inherits posix | fd emulation | — |

`openUserspaceChannelBackend()` — expect function, POSIX actual returns `PosixUserspaceChannelBackend`.

### Data Flow: ByteArray → io_uring

```
ByteBuffer.read(dst)                     SocketChannel / FileChannel
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

---

## Choreography — Element Lifecycle

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Every IO component that needs coroutine context and lifecycle extends `AsyncContextElement`:

```kotlin
class MyProtocolElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MyProtocolElement>()  // canonical

    override suspend fun open() {
        super.open()          // CREATED → OPEN
        state = ElementState.ACTIVE
        // register uring fanout handlers here
    }

    override suspend fun drain() {
        state = ElementState.DRAINING
        super.close()         // → CLOSED, cancels SupervisorJob
    }
}
```

### Rules

1. Use `AsyncContextKey<E>` (not raw `CoroutineContext.Key<E>`)
2. Companion `Key` is the type-safe lookup: `coroutineContext[MyProtocolElement.Key]`
3. `SupervisorJob(parentJob)` is inherited — children survive sibling failures
4. `fanoutSubscribers` is the ordered downstream delivery list
5. Lifecycle is forward-only — no transitions backward

### Cross-Module Element Access

```kotlin
// QuicElement can find CouchElement in context:
val couch = currentCoroutineContext()[CouchElement.Key]
couch?.collections?.forEach { ... }

// HtxClient can find IpfsElement in context:
val ipfs = currentCoroutineContext()[IpfsElement.Key]
ipfs?.dhtService?.store(key, value)
```

---

## What NOT to do

- **Do not route new IO through `nio/channels/`.** The stubs are JDK compatibility only.
  Use `userspace.Channel` and `FunctionalUringFacade` instead.
- **Do not create a second IO path.** There is one ring, one facade, one channelized fanout.
  Epoll/kqueue wrappers would be a bifurcation.
- **Do not use raw `CoroutineContext.Key<E>`.** Use `AsyncContextKey<E>`.
- **Do not manage `ElementState` manually.** Extend `AsyncContextElement`.
- **Do not bypass HTX for HTTP.** Even internal HTTP goes through `HtxMessage` → `Channel`.
  HTX is the tokenizer, not an optional wrapper.

---

## Consuming from `libs/`

A `libs/` module declares `api(project(":"))` and receives all of the above:

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

    suspend fun sendRequest(msg: HtxMessage): HtxMessage {
        val runner = ChannelRunner(channel, coroutineScope)

        val writeOk = runner.runOp { token ->
            channel.write(file, msg.toHttp1().asByteBuffer(), 0L, token)
        }

        val responseBuf = ByteBuffer.allocate(65536)
        val readOk = runner.runOp { token ->
            channel.read(file, responseBuf, 0L, token)
        }

        return parseHttp1(responseBuf.array())!!
    }
}
```

---

## Related

- [`PRELOAD.md`](../PRELOAD.md) — kernel algebra: `Join`, `Series<T>`, `Twin`, `α`, `j`
- [`docs/plans/PLAN.md`](../docs/plans/PLAN.md) — consolidation roadmap
- [`io_uring_interop/`](../io_uring_interop/) — C interop headers for io_uring
- [`src/linuxMain/.../Liburing.linux.kt`](linuxMain/kotlin/borg/trikeshed/userspace/Liburing.linux.kt) — cinterop actual
- [`src/posixMain/.../PosixUringIO.kt`](posixMain/kotlin/borg/trikeshed/PosixUringIO.kt) — uring-or-POSIX fallback
- [`libs/couch/src/commonMain/.../couch/htx/`](../libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/htx/) — HTX tokenizer source
