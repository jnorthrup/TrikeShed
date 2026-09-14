# TrikeShed `src/` — Core Substrate

Shared IO substrate, HTX tokenizer, and choreography base classes for all targets.
Targets commonMain, nativeMain, posixMain, linuxMain, macosMain, jvmMain, jsMain, wasmJsMain.

Kernel algebra (`Join`, `Series<T>`, `Twin`, `α`, `j`) is defined in `lib/` and documented in
[`PRELOAD.md`](../PRELOAD.md). This README covers the IO transport, reactor, and choreography layers.

> **Lineage.** The kernel is not novel — it is a clean-room Kotlin port of four
> well-established array-algebra traditions, dense-composed for JVM/native/Wasm/JS:
> - **`Series<T> = Join<Int, (Int) -> T>`** is K's enumerable (size + index oracle),
>   the same primitive kdb+ has shipped since 1993. `α` is `each`/`map`, `j` is K's join
>   (`,`), `s_[...]` is K's `enlist`, `/` (split) and `%` (filter-indices) are K's
>   reshape and where. Read PRELOAD.md as K clothing.
> - **`Cursor = Series<RowVec>` + `ColumnMeta` + `IOMemento`** is Apache Arrow's
>   `RecordBatch` + `FieldVector` + `ArrowType`, lazified. `get(range)`/`get(IntArray)`
>   reorder is Arrow/NumPy fancy indexing. Columnar, zero-copy, type-tagged.
> - **The typed-facet / GADT-key pattern** (`ConfixIndexK<R>`, `facet(key): R`) is
>   Haskell `Lens' s a` / Scala Monocle optics, expressed as sealed-key singletons
>   whose result type is fixed by the key. No runtime casts at the call site.
> - **The CCEK lifecycle** (`CREATED → OPEN → ACTIVE → DRAINING → CLOSED` + fanout) is
>   an Rx `Subject` / Project Reactor `Flux` operator state machine, just renamed.
>
> The gaps you will hit (lazy `filter` returning `Iterator` not `Series`; `fold`
> having two cost models under one name) are where this port skipped a page from
> the originals. K's `&` filter and Arrow's `filter(mask)` both return a same-typed
> lazy structure; TrikeShed's `%` and `[Predicate]` return `Iterator`, breaking
> further composition. Fix-by-matching-the-original, not by inventing new behavior.

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
│  REACTOR  (userspace/ · FunctionalUringFacade)                       │
│                                                                      │
│  The scoped facade owns bounded batch admission and CQE dispatch.    │
│  Its worker runs under a SupervisorJob parented to the caller.       │
│  batchEnqueue suspends until the admitted batch settles.             │
│  userData correlates terminal completions with submitted requests.   │
│  drain stops admission and awaits cleanup.                           │
│                                                                      │
│  Implemented callers include UringBenchmark, TorrentSocketRing       │
│  and BtrfsUringFileVolume. Their ownership belongs in their code;    │
│  a diagram does not establish a shared ring across protocols.        │
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
│  FunctionalUringFacade  op batching + backend dispatch               │
│    └─ UserspaceChannelBackend  platform impl per backend              │
│         └─ Liburing → LiburingImpl  (linux: cinterop, jvm: SPI,       │
│              macos/js/wasm: fallback)                                 │
│                                                                      │
│  data types:                                                         │
│    ByteBuffer  → ByteArray-backed NIO buffer                        │
│    ByteRegion  → sub-range view of ByteBuffer                       │
│    ByteSeries  → lazy Series<Byte> over ByteRegion (zero copy)      │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL SURFACES  (userspace/kernel/)                                │
│                                                                      │
│  SelectableChannelOps  pollReadable/pollWritable + tryRead/tryWrite │
│  KernelUring           SQE/CQE abstraction (literbike port)          │
│  PosixSocket           bind/listen/accept/connect/send/recv          │
├──────────────────────────────────────────────────────────────────────┤
│  NIO COMPAT STUBS  (nio/channels/, nio/file/)                        │
│                                                                      │
│  UringSocketChannel, UringFileChannel, UringServerSocketChannel      │
│  COMPATIBILITY SURFACE ONLY.  DO NOT ROUTE NEW IO THROUGH HERE.       │
├──────────────────────────────────────────────────────────────────────┤
│  LIBRARY  (lib/)  ·  PRELOAD.md                                      │
│                                                                      │
│  Join<A,B>  Series<T>  Twin<T>  ByteSeries  Cursor  HashSeriesSet   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Reactor — scoped submissions and terminal completions

[`FunctionalUringFacade`](commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt)
owns bounded submission and completion accounting. The scoped factory parents its
worker to the caller's job through a `SupervisorJob`. Callers provide unique
outstanding `userData` values and await `batchEnqueue`; the worker correlates
terminal CQEs with their batches. Drain stops admission and waits for admitted
work and cleanup.

The former `userspace.ChannelRunner` and `userspace.nio.channels.ChannelRunner`
had no source callers and were removed. Earlier examples of `runOp`, a shared
HTX/couch/IPFS ring, and fire-and-forget close were not implemented integrations.

The executable example is
[`UringBenchmark.run`](commonMain/kotlin/borg/trikeshed/userspace/benchmark/UringBenchmark.kt):
it creates a scoped facade, admits batches, checks completion identities and
read-back bytes, submits CLOSE explicitly, and awaits drain in `finally`.
[`TorrentSocketRing`](commonMain/kotlin/borg/trikeshed/torrent/TorrentTransport.kt)
and [`BtrfsUringFileVolume`](commonMain/kotlin/borg/trikeshed/btrfs/BtrfsUringFileVolume.kt)
are source callers of the same batch API.

Typed context lookup uses each element's singleton `CoroutineContext.Key`.
`FunctionalUringFacade` implements `CoroutineContext.Element` directly; its
admission, completion and cleanup code supplies its lifecycle behavior.

---

## HTX — Version-Agnostic HTTP 1-3 Tokenizer

HTX is the common tokenizer for HTTP/1.x, HTTP/2, and HTTP/3. It models HTTP messages
as a sequence of typed blocks, following HAProxy's internal `htx_blk` format. The block
sequence is identical regardless of transport — only the *parser* that produces it changes.

```
HTTP/1.1 text on wire   →  parseHttp1()  ─┐
HTTP/2 binary frames    →  (H2 parser)   ─┼→  [ReqSl · Hdr · Hdr · EOH · Data · EOT · EOM]
HTTP/3 QUIC stream      →  (H3 mapping)  ─┘
DHTX internal           →  DHTX_REQ/RES  ─┘   (non-HTTP, same framing)
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
| `LiburingImpl` | expect object | — | cinterop `io_uring_*` | ServiceLoader | `UnsupportedOperationException` |
| `FilesImpl` | expect object | POSIX `open()` | inherits posix | `java.nio.file` | — |
| `ChannelsImpl` | expect object | POSIX `socket()` | inherits posix | fd emulation | — |
| `openUserspaceChannelBackend()` | expect fun | `PosixUserspaceChannelBackend` | inherits posix | `JvmUserspaceChannelBackend` | — |

`FunctionalUringFacade` is a regular class (not expect/actual) that wraps a `UserspaceChannelBackend` interface implementation per platform.

### Data Flow: ByteArray → io_uring

```
ByteBuffer.read(dst)                     SocketChannel / FileChannel
  → channel.read(file, buf, offset, tok) userspace.Channel
    → FunctionalUringFacade.read()       enqueue PreparedChannelOp.Read
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

## Consuming the substrate

Open a scoped facade through
[`UringChannels.open(scope, entries)`](commonMain/kotlin/borg/trikeshed/userspace/nio/channels/UringChannels.kt),
submit through `batchEnqueue`, and await `drain` during cleanup. Resolve required
services through their existing context keys. The benchmark and transport callers
linked above show the concrete token, buffer and descriptor ownership at each call
site.

The root `src/` tree is authoritative. Composite builds consume it through
`includeBuild("../..")`; no separate `ChannelRunner` or lifecycle superclass is
required to call the scoped facade.

---

## Related

- [`PRELOAD.md`](../PRELOAD.md) — kernel algebra: `Join`, `Series<T>`, `Twin`, `α`, `j`
- [`README.md`](../README.md) — maintainer-facing architecture map + spine (concept map merged here)
- [`doc/concepts-gap-analysis.md`](../doc/concepts-gap-analysis.md) — doc-vs-code drift audit
- [`src/linuxMain/.../Liburing.linux.kt`](linuxMain/kotlin/borg/trikeshed/userspace/Liburing.linux.kt) — cinterop actual
- [`src/posixMain/.../PosixUringIO.kt`](posixMain/kotlin/borg/trikeshed/PosixUringIO.kt) — uring-or-POSIX fallback
- [`src/commonMain/.../couch/htx/`](commonMain/kotlin/borg/trikeshed/couch/htx/) — HTX tokenizer source
