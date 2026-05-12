# libs/couch

The largest TrikeShed module. A multi-protocol CouchDB-inspired database kernel
combining HTTP framing, WAL storage, structured-concurrency reactor, crypto
primitives, financial time-series algebra, and a GWT RequestFactory transport
bridge. Targets JVM, JS, WasmJS, and native (macOS/Linux).

## What It Is (Mechanically)

couch is the integration point where TrikeShed's kernel algebra meets
persistence, networking, and domain logic:

- **HTX block framing** — an internal HTTP representation modeled after
  HAProxy's `htx_blk` ring-buffer layout. HtxBlock carries 4-bit type +
  20-bit value-length + 8-bit name-length in a single `UInt info` word.
  HtxMessage is a mutable list of HtxBlockData sealed variants (StartLine,
  Header, Data, Trailer, EndHeaders, EndTrailers) with CRC32-guarded
  binary serialization.

- **WAL + BPlusTree storage** — LSMRWal interface (append, read, snapshot,
  compact) backed by BtrfsWal which stores entries in a tiny-btrfs
  BPlusTree<Long,CharSequence>. BtrfsSandboxElement wraps the tree as an
  AsyncContextElement with SupervisorJob lifecycle.

- **Reactor** — ReactorSupervisor is a CoroutineContext.Element that manages
  named BranchScope children (each a Channel<HtxBlock> + coroutine). State
  machine: CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED. ParseSupervisor
  delegates to ParseScope for concurrent parse fanout. SessionContext provides
  tag-based dispatch (HtxBlockType.name -> MessageHandler).

- **ngSCTP/QUIC transport** — QuicChannelService and NgSctpService implement
  StreamTransport with channelized stream multiplexing. ngSCTPChannel is a
  CoroutineContext.Element carrying rendezvous send/recv channels.
  ngSCTPMultiplexer maps sessionIds to channels.

- **Crypto primitives** — expect/actual for X25519 DH, HKDF-SHA256, AES-256-GCM,
  and HMAC-SHA256. JVM uses javax.crypto; other targets have platform-specific
  implementations. Htx ticket verification uses constant-time XOR comparison.

- **CouchDB 1.1 API layer** — ViewQuery, ViewQueryEncoder, CouchDb11RowSet (JSON
  -> BlockRowVec via TrikeShed JsonParser), CouchDb11DesignDocument, CouchDb11Spec.

- **RequestFactory/GWT-RPC transport** — Full schema (RequestFactorySpec,
  RequestContextSpec, ProxySpec, etc.), JSON codec, HtxRequestFactoryBridge for
  dispatch routing, and OpenAPI YAML generation. GwtRpcRequest/GwtRpcResponse
  for legacy GWT compatibility.

- **RelaxFactory** — Annotation-driven CouchService<T> compiler (JVM-only via
  kotlin-reflect) that produces CouchViewManifest from annotated interface
  methods. @View, @Key, @StartKey, @EndKey, @Limit etc. map to CouchDB query
  parameters.

- **Finance domain** — RegimeDetector (threshold cascade: BULL_RUSH, BEAR_CRASH,
  etc.), DoubleArrayMath (mean, variance, ema, windowSum), MiniCursor finance
  extensions (DoubleArray -> price cursor, MiniCursor -> double series).

- **Kline (OHLCV)** — Kline/KlineBlock with MUTABLE->SEALED state machine,
  KlineCollector draining Channel<Kline> into fixed-capacity sealed blocks,
  ExtendedKline with 12-field Binance schema, DocRowVec projection.

- **AdmissionControl** — Simple semaphore with seal/close semantics.

- **ProtocolDetector** — Incremental byte-peeking to classify HTTP/TLS/SSH/HTTP2.

- **ProcessShell** — expect/actual for exec(); JVM uses ProcessBuilder, others
  throw UnsupportedOperationException.

- **ViewServer (subproject)** — JS-target Node.js process implementing the
  CouchDB view-server JSON-lines protocol (reset/add_fun/map_doc). Compiles
  Kotlin map functions via JursiveInterop + kursive parser.

## Source Layout

```
libs/couch/
  build.gradle.kts              # applies trikeshed-lib.gradle :libs:couch case
  src/
    commonMain/kotlin/borg/trikeshed/
      couch/
        htx/                    # HTX block framing (10 files)
          HtxBlock.kt           # data class: addr+info word
          HtxBlockType.kt       # enum: ReqSl..DHTX_RES
          HtxFlags.kt           # HtxSlFlags, HtxFlags (BitMasked)
          HtxStartLine.kt       # request/response start-line with parse()
          HtxMessage.kt         # mutable block list, HTTP/1 parser, Trie-based protocol detection
          HtxBlockData.kt       # sealed class: StartLine|Header|Data|Trailer|End*
          HttpMethod.kt         # enum
          HtxCrc32.kt           # pure-Kotlin CRC32 table + compute/computeStream
          HtxAlgebraRed.kt      # search/transform/serialize/deserialize algebra, DSL factories
          Htx.kt                # ticket verification (HKDF + constant-time compare)
        ccek/                   # Transport service duplicates
          QuicChannelService.kt # data class: channelized QUIC transport
          NgSctpService.kt      # data class: SCTP multi-homing + failover
        btrfs/                  # BPlusTree-backed storage
          BtrfsSandboxElement.kt # AsyncContextElement wrapping BPlusTree
          BtrfsWal.kt           # LSMRWal impl over BtrfsSandboxElement
          BtrfsHarness.kt       # demo: WAL + TableSource structured concurrency
          BtrfsTableSource.kt   # TableSource impl over BPlusTree
        handle/                 # Collection handle
          CollectionHandle.kt   # in-memory row appender, seal(), snapshot()
          HandleState.kt        # enum: OPEN|SEALED|CLOSED
        wal/                    # WAL interface
          LSMRWal.kt            # interface + placeholder types
        stream/                 # Change stream
          ChangeStream.kt       # Change<T> sealed + ChangeEmitter fanout
        userspace/
          nio/                  # Reactor core
            ReactorSupervisor.kt # CoroutineContext.Element, branch/session management
            BranchScope.kt      # channelized branch + BranchDispatch
            SessionDispatch.kt  # SessionContext + MessageHandler
            ParseSupervisor.kt  # parse task SupervisorJob host
            BlockIndex.kt       # compression index interface + PointRowVec
          transport/
            ngSCTPChannel.kt    # rendezvous channel + ngSCTPMultiplexer
          network/              # (jvmMain has ProtocolDetector/Recognizer)
        transport/htx/          # HTTP transport over HTX
          HtxRequest.kt         # simple data class
          HtxRequestFactoryBridge.kt # dispatch routing (RequestFactory vs standard)
          HtxBackedCouchTransport.kt # view() -> HtxCouchExchange
          HtxCouchExchange.kt   # wraps HtxRequest
        crypto/
          CryptoPrimitives.kt   # expect: X25519, HKDF, AES-256-GCM
        minidsl/
          InfixMiniDsl.kt       # CouchMiniDsl: infix DSL for view queries
        viewserver/
          JursiveHeuristics.kt  # isLikelyJsFn (commonMain stub)
        control/
          AdmissionControl.kt   # permit-based concurrency limiter
        finance/
          RegimeDetector.kt     # market regime classifier
          DoubleArrayMath.kt    # autovectorized numeric kernels
          MiniCursorFinanceExtensions.kt # price cursor / double series
        kline/
          Kline.kt              # Kline + ExtendedKline data classes
          KlineBlock.kt         # MUTABLE->SEALED chunk
          KlineCollector.kt     # Channel->sealed block drain
        api/
          ViewQuery.kt          # CouchDB 1.1 query params
          ViewQueryEncoder.kt   # URL query string encoder
          CouchDb11RowSet.kt    # JSON -> BlockRowVec
          CouchDb11DesignDocument.kt # design doc JSON builder
          CouchDb11Spec.kt      # endpoint paths
          CouchViewDefinition.kt # map/reduce pair
        requestfactory/
          RequestFactorySchema.kt    # full RF schema types
          RequestFactoryTransport.kt # contract + call/response types
          RequestFactoryJsonCodec.kt # JSON codec for RF + GWT-RPC
          RequestFactoryHtxStub.kt   # HTX client/server stubs
          RequestFactoryOpenApiYamlCodec.kt # OpenAPI YAML
          GwtRpcSchema.kt           # GWT-RPC message types
          TransportValue.kt         # sealed JSON value type
        relaxfactory/
          Annotations.kt        # @View, @Key, @StartKey, etc.
          CouchService.kt       # marker interface
          CouchViewManifest.kt  # compiled view output
          CouchViewInvocation.kt # template-based URL builder
        runtime/
          Reactor.kt            # thin facade over ReactorSupervisor
          CouchRuntime.kt       # Reactor + HtxBackedCouchTransport
        internal/
          UrlEncoding.kt        # multiplatform URL encoder
        UrlEncode.kt            # (duplicate, package-level)
      process/
        ProcessShell.kt         # expect class for exec()

    jvmMain/kotlin/borg/trikeshed/
      couch/
        crypto/CryptoPrimitivesJvm.kt  # actual: javax.crypto
        htx/HtxCryptoJvm.kt            # actual: hmacSha256
        relaxfactory/CouchServiceCompiler.kt # kotlin-reflect annotation processor
        instrument/
          InstrumentedHandle.kt  # lock-instrumented handle wrapper
          Probes.kt              # AtomicLong counters
        userspace/network/
          ProtocolDetector.kt    # byte-peeking protocol classifier
          ProtocolRecognizer.kt  # Reactor branch for protocol detection
      process/
        ProcessJvm.kt            # actual: ProcessBuilder

    posixMain/kotlin/borg/trikeshed/
      couch/htx/HtxCryptoPosix.kt
      couch/crypto/CryptoPrimitivesPosix.kt
      process/ProcessPosix.kt

    jsMain/kotlin/borg/trikeshed/
      couch/htx/HtxCryptoJs.kt
      couch/crypto/CryptoPrimitivesJs.kt
      process/ProcessJs.kt

    wasmJsMain/kotlin/borg/trikeshed/
      couch/htx/HtxCryptoWasm.kt
      couch/crypto/CryptoPrimitivesWasm.kt
      process/ProcessWasm.kt

    jvmTest/          (13 test files)
    commonTest/       (18 test files)

  viewserver/         # nested subproject (KMP, depends on couch + kursive)
    src/
      commonMain/     # JursiveInterop.kt
      jsMain/         # ViewServer.kt (Node.js CouchDB view-server protocol)
      commonTest/     # JursiveInteropTddTest.kt
```

## Key/Element/Reactor Status

| Type | Status | Notes |
|------|--------|-------|
| BtrfsSandboxElement | AsyncContextElement | Has Companion Key, SupervisorJob, lifecycle |
| BtrfsWal | AsyncContextElement | Has Companion Key, wraps BtrfsSandboxElement |
| ReactorSupervisor | AbstractCoroutineContextElement | Has singleton Key, SupervisorJob, CREATED->CLOSED lifecycle |
| ParseSupervisor | AbstractCoroutineContextElement | Has singleton Key, SupervisorJob, CREATED->CLOSED lifecycle |
| SessionContext | AbstractCoroutineContextElement | Has singleton Key, tag->handler dispatch |
| ngSCTPChannel | AbstractCoroutineContextElement | Has singleton Key, rendezvous channels |
| QuicChannelService | CoroutineContext.Element | Has Companion Key, StreamTransport |
| NgSctpService | CoroutineContext.Element | Has Companion Key, StreamTransport |
| ContextElementImpl | AbstractCoroutineContextElement | Generic key-value wrapper for palette injection |
| HandleState | enum (3 states) | OPEN/SEALED/CLOSED — not BitMasked |
| ReactorState | enum (5 states) | CREATED/OPEN/ACTIVE/DRAINING/CLOSED — not BitMasked |
| HtxSlFlags | BitMasked | 15 flags for start-line |
| HtxFlags | BitMasked | 6 message flags |
| HtxBlockType | enum (not BitMasked) | HAProxy-style 4-bit type codes |

## Dependencies

From `trikeshed-lib.gradle` :libs:couch case:

- **Root TrikeShed** (`api`) — kernel algebra: Series, Twin, Join, j infix, BitMasked, Trie
- **miniduck** (`api`) — MiniCursor, DocRowVec, BlockRowVec, ViewRowVec, MiniRowVec, ExecutionContext, TableSource, JsonParser
- **tiny-btrfs** (`implementation`) — BPlusTree
- **kursive** (`implementation`) — JursiveCharSeries parser
- **kotlinx-coroutines-core** (from kmpHost) — coroutines, channels, SupervisorJob
- **kotlinx-datetime** (from kmpHost datetimeMain: true) — Clock.System
- **kotlin-reflect** (jvmMain) — CouchServiceCompiler reflection
- **couch-viewserver** (jvmTest) — nested subproject for integration testing

Not direct deps (but code exists as local duplicates):
- quic/ngsctp — QuicChannelService/NgSctpService are *duplicated* in couch/ccek/ rather than depending on libs/quic and libs/ngsctp
- htx-client — not a dependency; couch has its own HTX implementation
