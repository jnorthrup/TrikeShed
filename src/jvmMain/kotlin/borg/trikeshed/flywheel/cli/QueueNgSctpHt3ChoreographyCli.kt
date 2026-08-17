package borg.trikeshed.flywheel.cli

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Queue the 15-take ngSCTP HTTP/3 reactor choreography as durable WorkQueued
 * envelopes. Each take is a disjoint file surface with TDD-RED-first acceptance,
 * PRELOAD binding, QUIC rejection, and the jvmMainClasses gate.
 *
 * "Pound for pound better than QUIC" means: every QUIC capability (no head-of-line
 * blocking, 0-RTT resume, connection migration, per-stream loss recovery,
 * mandatory encryption) is matched or exceeded via SCTP's native primitives:
 *   - SCTP multistreaming = QUIC multiplexing without HOL blocking (independent TSNs per stream)
 *   - SCTP 4-way handshake + cookie = QUIC 1-RTT; 0-RTT via cookie reuse = QUIC 0-RTT
 *   - SCTP multi-homing + path failover = QUIC connection migration (SCTP had it first, RFC 4960 §6.4)
 *   - SCTP per-TSN SACK gap blocks = QUIC per-stream ACK ranges
 *   - SCTP partial reliability (PR-SCTP, RFC 3758) = QUIC unilateral stream reset
 *
 * The choreography maps HTTP/3 semantics onto ngSCTP streams:
 *   QUIC stream → SCTP stream (one bidirectional SCTP stream per HTTP/3 request)
 *   QUIC packet → SCTP DATA chunk (TLV-framed, per-TSN)
 *   QUIC ACK → SCTP SACK (gap-ack blocks = ACK ranges)
 *   QUIC connection migration → SCTP multi-homing failover
 *   QUIC 0-RTT → SCTP cookie reuse
 *   QUIC mandatory TLS → DTLS over SCTP (or SCTP-AUTH, RFC 4895)
 *
 * Usage: QueueNgSctpHt3ChoreographyCli
 */
fun main(args: Array<String>) = runBlocking {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))

    val takes: List<Triple<String, String, String>> = listOf(
        // ── Take 1: SCTP association lifecycle → CCEK element ──────────────
        Triple(
            "ngsctp-ht3-01-association-ccek-element",
            "SCTP association as CCEK element: CREATED→OPEN→ACTIVE→DRAINING→CLOSED",
            """
            TARGET: Map the SCTP association state machine (RFC 4960 §13) onto the
            CCEK 5-state lifecycle. SctpElement already has CLOSED/COOKIE_WAIT/
            COOKIE_ECHOED/ESTABLISHED/SHUTDOWN_* states — these must project onto
            the CCEK lifecycle so associations compose into the reactor hub.

            CURRENT STATE (audit 566e429a9, Aug 16 2026):
            - SctpElement (context/sctp/SctpElement.kt:337) extends AsyncContextElement,
              implements StreamTransport. Has bind/connect/handleInitAck/handleCookieAck.
            - SctpState enum has 8 states but no mapping to CCEK ElementState.
            - SctpAssociationScope (reactor/ngsctp/SctpReactorSpine.kt:34) is a bare
              CoroutineScope with SupervisorJob — NOT a CCEK element, no lifecycle.
            - QUIC is REJECTED — do not reference QuicElement/QuicKey (OpenAPI stubs only).

            RED TEST FIRST:
            - commonTest: SctpElement.open() transitions CREATED→OPEN; an association
              reaching ESTABLISHED maps to ACTIVE; SHUTDOWN_PENDING maps to DRAINING;
              SHUTDOWN_ACK_SENT maps to CLOSED. Assert the projection function
              SctpState.toElementState() returns the correct ElementState for each.
              Observe RED (projection does not exist).

            IMPLEMENTATION SURFACE (disjoint — do not touch other takes' files):
            - src/commonMain/kotlin/borg/trikeshed/context/sctp/SctpElement.kt
              (add toElementState() extension on SctpState, add CCEK state assertions
              in bind/connect/handleCookieAck/handleShutdown)

            PRELOAD CONTRACT (binding):
            - SctpAssociation = Join<Long, SctpState> typealias — keep it, do not
              introduce an interface.
            - No .toList() demotion; state transitions are Series<SctpState> projections.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed and pasted in the PR body before implementation
            [ ] SctpState.toElementState() maps all 8 SCTP states to 5 CCEK states
            [ ] SctpElement lifecycle transitions assert CCEK state progression
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 2: SCTP multistreaming = HTTP/3 multiplexing ─────────────
        Triple(
            "ngsctp-ht3-02-multistream-mux",
            "SCTP multistreaming as HTTP/3 multiplexing: one bidirectional SCTP stream per request",
            """
            TARGET: Replace Http3Session's QUIC-simulated multiplexing with real SCTP
            multistreaming. HTTP/3 maps one QUIC stream per request/response; ngSCTP
            maps one SCTP stream (SID) per request/response. The key advantage over
            QUIC: SCTP streams have independent TSN sequences, so a lost DATA chunk
            on stream N does not block stream M (no head-of-line blocking — the same
            property QUIC advertises, but SCTP has had since RFC 2960).

            CURRENT STATE:
            - Http3Session (http3/Http3Session.kt:9) uses MplexStream with a simulated
              datagram send: ByteArray(8 + data.size) with manual streamId encoding.
              Comment at line 24: "In a real QUIC implementation, this would format
              a STREAM frame" — this IS the QUIC dependency to replace.
            - StreamTransport (context/StreamTransport.kt:15) is the transport-agnostic
              abstraction. SctpElement already implements it (openStream() at line 385).
            - MplexStream (mplex/Mplex.kt:55) has session+stream window flow control.
              This is QUIC-shaped (session-level flow control). SCTP uses aRwnd
              (advertised receiver window) per association + per-stream credit.

            RED TEST FIRST:
            - commonTest: Http3Session.createStream() backed by SctpElement returns a
              stream whose send goes through SctpElement.openStream().send, not through
              the simulated datagram ByteArray copy. Assert the SCTP stream's send
              channel receives the data, not the onSendDatagram callback. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/http3/Http3Session.kt
              (replace onSendDatagram callback with StreamTransport injection; createStream
              delegates to transport.openStream(); receiveDatagram routes via SctpElement)
            - src/commonMain/kotlin/borg/trikeshed/context/StreamTransport.kt
              (add streamCount() and associationId() if needed for the session to
              query its transport backing — but prefer keeping the interface minimal)

            PRELOAD CONTRACT: Join/Series typealiases; no interface Stream.
            QUIC REJECTION: Do not import or reference quic.* package.
            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed and pasted in the PR body
            [ ] Http3Session constructor takes StreamTransport, not onSendDatagram
            [ ] SCTP multistreaming (per-SID channels) replaces simulated QUIC frames
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 3: SACK gap-ack blocks = QUIC ACK ranges ─────────────────
        Triple(
            "ngsctp-ht3-03-sack-gap-ack-ack-ranges",
            "SCTP SACK gap-ack blocks as QUIC ACK ranges: per-TSN selective ACK",
            """
            TARGET: Wire SctpSackChunk's gap-ack blocks (RFC 4960 §3.3.4) as the
            per-stream loss recovery mechanism that QUIC advertises as "ACK ranges."
            SCTP SACK already carries gap-ack blocks (SctpGapAckBlock = Join<UShort,UShort>
            in context/sctp/SctpElement.kt:172). The encode/decode exists. What is
            missing: a reactor pipeline that feeds received DATA chunks → gap tracker
            → emits SACK with correct cumulative TSN + gap blocks.

            CURRENT STATE:
            - SctpSackChunk (SctpElement.kt:189) has full encode/decode, gap-ack Series.
            - No production caller constructs a SACK from received TSNs. The chunk
              types are defined but the tracking logic is absent.
            - QUIC's "per-stream ACK ranges" = SCTP's "gap-ack blocks per TSN" —
              same math, SCTP had it first.

            RED TEST FIRST:
            - commonTest: given a sequence of received TSNs [1,2,4,5,6,8] (3 and 7
              lost), the SackTracker produces cumulativeTsnAck=2, gapAckBlocks=
              [(4,6),(8,8)]. Assert the encoded SACK matches expected bytes. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/SackTracker.kt (NEW)
              (pure function: Series<UInt> receivedTsns → SctpSackChunk)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/SackTrackerTest.kt (NEW)

            PRELOAD CONTRACT:
            - SackTracker is a pure projection: Series<UInt> → SctpSackChunk.
            - gap-ack blocks use Join<UShort,UShort> typealias (already defined).
            - No mutable List accumulation — use Series projections or a bounded
              mutable buffer that is frozen into a Series on emit.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: SackTracker does not exist
            [ ] SackTracker produces correct cumulative TSN + gap blocks for gap patterns
            [ ] Output is SctpSackChunk (not a parallel type)
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 4: Multi-homing failover = QUIC connection migration ─────
        Triple(
            "ngsctp-ht3-04-multihoming-migration",
            "SCTP multi-homing failover as QUIC connection migration: path failover choreography",
            """
            TARGET: Wire SctpElement's existing multi-homing path tracking into a
            reactor choreography that fails over on heartbeat timeout — the SCTP
            equivalent of QUIC connection migration. SCTP multi-homing (RFC 4960 §6.4)
            predates QUIC migration by 20+ years: an association binds multiple
            destination addresses; if the primary path fails heartbeats, traffic
            moves to an alternate path WITHOUT dropping the association.

            CURRENT STATE:
            - SctpElement (SctpElement.kt:337) has paths: List<String>, _pathStatuses
              with PathStatus/PathState (ACTIVE/INACTIVE/UNKNOWN), failover() method
              (line 362), recoverPath() (line 372), primaryPath (line 354).
            - No reactor pipeline consumes these. The failover() method exists but
              nothing calls it — no heartbeat timer, no path-failure detection loop.

            RED TEST FIRST:
            - commonTest: a PathFailoverChoreography with 2 paths where path[0]
              accumulates 3 heartbeat failures must transition primaryPath to path[1]
              and emit a PathFailoverSignal. Assert the signal is observable in the
              CCEK fanout. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/PathFailoverChoreography.kt (NEW)
              (suspend coroutine: periodic heartbeat → failover() on threshold → emit signal)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/PathFailoverChoreographyTest.kt (NEW)

            PRELOAD CONTRACT:
            - PathFailoverSignal is a Join<String, String> (failedPath j newPath).
            - Coroutine-based: launch in SctpAssociationScope, no Thread.sleep,
              use delay() or a Channel<HeartbeatResult>.
            - Do NOT use java.net.* — path addresses are NUID-routed, not IP sockets.

            QUIC REJECTION: "connection migration" is the QUIC term; SCTP's term is
            "multi-homing failover." Use SCTP terminology in all code and comments.
            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: PathFailoverChoreography does not exist
            [ ] 3 heartbeat failures trigger failover to alternate path
            [ ] PathFailoverSignal emitted through CCEK fanout
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 5: Cookie reuse 0-RTT = QUIC 0-RTT ────────────────────────
        Triple(
            "ngsctp-ht3-05-cookie-reuse-0rtt",
            "SCTP cookie reuse as 0-RTT: fast-path association establishment",
            """
            TARGET: Implement SCTP cookie reuse as the 0-RTT equivalent. QUIC 0-RTT
            sends request data in the first flight using a cached ticket. SCTP's
            equivalent: a client that has a valid cookie from a prior association can
            include DATA chunks in the COOKIE_ECHO (RFC 4960 §5.2.1 allows piggybacking
            data on the COOKIE_ECHO when the cookie is still valid). This is a 1-RTT
            optimization to 0-RTT for the common case of reconnecting to a known peer.

            CURRENT STATE:
            - SctpCookieEchoChunk (SctpElement.kt:252) carries an opaque cookie ByteArray.
            - handleInitAck (line 441) transitions COOKIE_WAIT→COOKIE_ECHOED but does
              not cache the cookie for reuse.
            - connect() (line 430) always starts from CLOSED → COOKIE_WAIT — no
              fast-path for a cached cookie.

            RED TEST FIRST:
            - commonTest: a client with a cached cookie calls connectWithCookie()
              which sends COOKIE_ECHO + DATA in one flight (0-RTT). The server
              handleCookieEchoWithData() validates the cookie and processes the DATA
              in the same handler. Assert the data is delivered before a second RTT.
              Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/context/sctp/SctpElement.kt
              (add cookieCache: MutableMap<String, ByteArray>, connectWithCookie(),
              handleCookieEchoWithData(); do NOT break existing connect/handleCookieEcho)
            - src/commonTest/kotlin/borg/trikeshed/sctp/CookieReuseTest.kt (NEW)

            PRELOAD CONTRACT:
            - Cookie cache is keyed by Join<String, Int> (host j port) → typealias.
            - DATA piggybacked on COOKIE_ECHO is a Series<SctpDataChunk> appended
              after the cookie in the chunk body.
            - No QUIC "EarlyData" or "0-RTT ticket" terminology — use "cookie reuse."

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: connectWithCookie() does not exist
            [ ] Cookie cached from prior association enables piggybacked DATA
            [ ] Server validates cookie + processes DATA in one handler
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 6: PR-SCTP = QUIC stream reset ───────────────────────────
        Triple(
            "ngsctp-ht3-06-pr-sctp-stream-reset",
            "PR-SCTP (RFC 3758) partial reliability as QUIC unilateral stream reset",
            """
            TARGET: Implement PR-SCTP (Partial Reliability extension, RFC 3758) as
            the equivalent of QUIC's unilateral stream reset. QUIC allows either
            endpoint to reset a stream (RESET_STREAM frame, RFC 9000 §19.4),
            abandoning in-flight data. SCTP's PR-SCTP does the same via FORWARD_TSN
            chunks: a sender can declare that TSNs up to N are no longer needed,
            freeing the receiver to skip them. This is pound-for-pound better because
            SCTP's FORWARD_TSN can skip arbitrary TSN ranges, not just whole streams.

            CURRENT STATE:
            - SctpChunkType enum (SctpElement.kt:11) has DATA, INIT, INIT_ACK, SACK,
              HEARTBEAT, COOKIE_ECHO, COOKIE_ACK — no FORWARD_TSN.
            - MplexStream.close() (mplex/Mplex.kt:121) just closes the read channel;
              no mechanism to signal the peer to abandon in-flight data.

            RED TEST FIRST:
            - commonTest: a sender that calls stream.abandon() emits a FORWARD_TSN
              chunk with the new cumulative TSN. The receiver processes FORWARD_TSN
              and skips the abandoned TSNs, advancing its reassembly queue. Assert
              the receiver's next read returns data after the abandoned range.
              Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/context/sctp/SctpForwardTsnChunk.kt (NEW)
              (FORWARD_TSN chunk: newCumulativeTsn + Series<StreamId> with per-stream
              new cumulative TSN, per RFC 3758 §3)
            - src/commonMain/kotlin/borg/trikeshed/mplex/Mplex.kt
              (add abandon() to MplexStream: sends FORWARD_TSN via onWrite callback)
            - Add FORWARD_TSN to SctpChunkType enum (SctpElement.kt:11) — this is a
              shared enum, coordinate by adding AFTER the existing entries

            PRELOAD CONTRACT:
            - SctpForwardTsnChunk uses Series<SctpGapAckBlock>-shaped per-stream
              entries: Join<UShort, UInt> (streamId j newCumulativeTsn).
            - abandon() is a suspend function on Stream interface.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: FORWARD_TSN chunk type does not exist
            [ ] stream.abandon() emits FORWARD_TSN with correct TSN
            [ ] Receiver skips abandoned TSNs and continues reassembly
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 7: DTLS-over-SCTP = QUIC mandatory encryption ─────────────
        Triple(
            "ngsctp-ht3-07-dtls-sctp-encryption",
            "DTLS over SCTP (RFC 6083) as QUIC mandatory encryption: CCEK codec element",
            """
            TARGET: Wire DTLS-over-SCTP (RFC 6083) as the mandatory encryption layer
            that QUIC bakes into the transport. QUIC encrypts all packet headers and
            payload by default (TLS 1.3 internal). SCTP has no native encryption, but
            RFC 6083 defines DTLS over SCTP: each SCTP stream is a DTLS record, so
            per-stream encryption without encrypting SCTP control chunks. This is
            pound-for-pound better than QUIC because SACK/INIT/HEARTBEAT are not
            encrypted — lower overhead on the control plane while data is protected.

            CURRENT STATE:
            - JvmTlsCodecBackend (the HTX TLS layer) uses SSLEngine per connection
              ordinal. This is TCP-shaped (one TLS session per TCP connection).
            - No DTLS (datagram TLS) codec exists. DTLS is needed because SCTP is
              message-oriented, not stream-oriented like TCP.
            - HtxReactorElement exchanges TLS via SSLEngine — cannot be reused for
              DTLS without a DtlsEngine abstraction.

            RED TEST FIRST:
            - jvmTest: a DtlsSctpCodec wrapping an SctpElement encrypts DATA chunk
              payloads before send and decrypts on receive. Assert the wire bytes
              (chunk payload) are ciphertext, not plaintext. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/jvmMain/kotlin/borg/trikeshed/reactor/ngsctp/DtlsSctpCodec.kt (NEW)
              (wraps SctpElement; encrypts DATA chunk payloads per-stream using a
              per-association DTLS session; passes control chunks through unencrypted)
            - src/jvmTest/kotlin/borg/trikeshed/reactor/ngsctp/DtlsSctpCodecTest.kt (NEW)

            PRELOAD CONTRACT:
            - DtlsSctpCodec is a CCEK element (AsyncContextElement subclass) with
              its own AsyncContextKey.
            - Per-stream encryption key derived from associationId + streamId:
              typealias DtlsStreamKey = Join<Long, Int>.
            - Use JDK SSLEngine in DTLS mode if available; otherwise document the
              SPI seam for a native DTLS backend (BoringSSL via cinterop on linux).

            QUIC REJECTION: Do not reference QUIC's "packet protection" or "header
            protection" — use DTLS/SCTP terminology (RFC 6083).
            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: DtlsSctpCodec does not exist
            [ ] DATA chunk payloads are encrypted on the wire
            [ ] Control chunks (INIT/SACK/HEARTBEAT) pass through unencrypted
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 8: QPACK over SCTP streams = HPACK/QPACK over QUIC ────────
        Triple(
            "ngsctp-ht3-08-qpack-over-sctp",
            "QPACK header compression over SCTP streams: HTTP/3 headers on ngSCTP",
            """
            TARGET: Map HTTP/3's QPACK header compression onto SCTP streams. HTTP/3
            uses QPACK (RFC 9204) for header compression, with instructions sent on
            dedicated QUIC streams. Over ngSCTP, QPACK encoder/decoder instructions
            ride on dedicated SCTP streams (e.g., SID 0 for encoder, SID 1 for
            decoder), and request headers ride on per-request bidirectional streams.
            This is the same mapping but SCTP's independent TSN per stream means
            QPACK instruction loss does not block request streams (unlike QUIC where
            QPACK instruction loss can block the connection).

            CURRENT STATE:
            - WsHttp3Mux (ws/mux/WsHttp3Mux.kt:7) wraps Http3Session for WebSocket
              framing. No QPACK implementation exists.
            - Http3Session.createStream() allocates stream IDs 0,4,8 (client) or
              1,5,9 (server) — these are QUIC stream ID conventions. SCTP SIDs are
              16-bit and bidirectional by default.
            - No QPACK or HPACK codec in the repo.

            RED TEST FIRST:
            - commonTest: an H3HeaderEncoder compresses a Map<String, String> of
              request headers into QPACK-encoded bytes on SCTP stream 0 (encoder
              instructions) and the request stream. The receiver decodes back to
              the original Map. Assert round-trip equality. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/http3/QpackCodec.kt (NEW)
              (static field table per RFC 9204 §3; encode/decode as pure functions:
              Series<Pair<String, String>> → ByteArray, ByteArray → Series<Pair<String,String>>)
            - src/commonTest/kotlin/borg/trikeshed/http3/QpackCodecTest.kt (NEW)

            PRELOAD CONTRACT:
            - QpackCodec uses Series<Pair<String, String>> for header lists.
            - Static table is a Series<QpackEntry> where QpackEntry = Join<String, String>.
            - No external QPACK library — pure Kotlin, commonMain-safe.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: QpackCodec does not exist
            [ ] Headers round-trip through encode → decode with identical content
            [ ] Encoder/decoder instructions on dedicated SCTP streams
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 9: SCTP reactor spine → CCEK fanout wiring ────────────────
        Triple(
            "ngsctp-ht3-09-reactor-spine-fanout",
            "SctpReactorSpine CCEK fanout: wire association lifecycle into reactor hub",
            """
            TARGET: Wire SctpReactorSpine into the CCEK reactor hub as a proper
            AsyncContextElement with fanout subscribers. Currently SctpReactorSpine
            (reactor/ngsctp/SctpReactorSpine.kt:170) implements SctpReactorEndpoint
            but is NOT a CCEK element — no AsyncContextElement, no fanoutSubscribers,
            no lifecycle. The send/receive methods use a BoundedChannelStream and
            SubnetJobAssembly but do not emit CCEK events.

            CURRENT STATE:
            - SctpReactorSpine implements SctpReactorEndpoint (send/receive/close/bind).
            - send() (line 204) enqueues to jobAssembly and optionally trySend to
              activeStream — no CCEK event emission.
            - receive() (line 225) parses TLV chunks and returns a placeholder
              ReactorAction.Opened with a fabricated NUID — HOLLOW.
            - SctpAssociationScope (line 34) is a bare CoroutineScope, not a CCEK scope.

            RED TEST FIRST:
            - commonTest: SctpReactorSpine.send() emits a SctpDataEvent to registered
              FanoutEventSubscribers. A test subscriber receives the event with the
              encoded MeshActionFrame payload. Assert subscriber.onFanoutEvent is
              called. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/SctpReactorSpine.kt
              (make SctpReactorSpine extend AsyncContextElement; add fanoutSubscribers;
              emit SctpDataEvent on send; replace placeholder receive with real TLV
              → ReactorAction decode)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/SctpReactorSpineFanoutTest.kt (NEW)

            PRELOAD CONTRACT:
            - SctpDataEvent : FanoutEvent with eventType = 0x53435450 ("SCTP").
            - fanoutSubscribers injected via constructor, same pattern as HtxElement.
            - receive() returns Series<Pair<PeerAddress, ReactorAction>> not a single
              Pair — use Series projection, not a mutable List.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: SctpReactorSpine does not emit fanout events
            [ ] send() emits SctpDataEvent to subscribers
            [ ] receive() decodes real ReactorAction, not placeholder
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 10: HtxElement → SCTP transport wiring ──────────────────
        Triple(
            "ngsctp-ht3-10-htx-sctp-transport",
            "HtxElement dispatch over SCTP: wire HTX request/response onto ngSCTP streams",
            """
            TARGET: Wire HtxElement.exchange() to dispatch over SCTP streams instead
            of TCP. Currently HtxElement (htx/HtxElement.kt:105) delegates to
            HtxRouteService.exchange() which routes through JvmTlsCodecBackend over
            TCP. The SCTP path: HtxElement opens a bidirectional SCTP stream via
            SctpElement.openStream(), writes the HtxRequest wire bytes to the stream's
            send channel, reads the HtxResponse from the recv channel. This makes
            HTTP/3 over ngSCTP a peer of HTTP/1.1 and HTTP/2 in the HTX tokenizer.

            CURRENT STATE:
            - HtxElement.exchange() (line 117) calls routeService.exchange() which is
              a synchronous request → response. No stream abstraction.
            - HtxRequest.renderWireRequest() emits the HTTP/1.1 wire format. For
              HTTP/3, the same HTX block sequence maps to SCTP DATA chunks.
            - src/README.md:70 confirms: "Block sequence is identical whether the
              bytes arrived via HTTP/1.1 text, HTTP/2 frames, or HTTP/3 QUIC."

            RED TEST FIRST:
            - commonTest: an SctpHtxRouteService.exchange() opens an SCTP stream,
              writes HtxRequest wire bytes, reads HtxResponse wire bytes from the
              stream, and returns HtxExchangeResult. Assert the exchange completes
              over SCTP, not TCP. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/SctpHtxRouteService.kt (NEW)
              (implements HtxRouteService; exchange() opens SCTP stream, writes request,
              reads response, constructs HtxExchangeResult)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/SctpHtxRouteServiceTest.kt (NEW)

            PRELOAD CONTRACT:
            - SctpHtxRouteService implements HtxRouteService (htx/HtxElement.kt:93).
            - StreamHandle.send/recv are kotlinx.coroutines Channels — non-blocking.
            - HtxExchangeResult = Join<HtxExchangeState, HtxFrames> (existing typealias).
            - No java.net.* imports; SCTP stream is the only transport.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: SctpHtxRouteService does not exist
            [ ] HTX exchange completes over an SCTP stream
            [ ] No TCP/Socket imports
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 11: Backpressure signals = QUIC flow control ─────────────
        Triple(
            "ngsctp-ht3-11-backpressure-flow-control",
            "SCTP aRwnd backpressure as QUIC flow control: observable CCEK signals",
            """
            TARGET: Wire SCTP's advertised receiver window (aRwnd) as the flow control
            mechanism that QUIC implements as session-level and per-stream flow
            control. SCTP's aRwnd is advertised in every SACK and INIT/INIT_ACK. When
            aRwnd reaches 0, the sender must stop sending DATA chunks. This is the
            same mechanism as QUIC's MAX_DATA and MAX_STREAM_DATA frames, but SCTP
            has had it since RFC 2960. Wire it as observable CCEK signals so the
            reactor hub can react to backpressure.

            CURRENT STATE:
            - SctpInitChunk.aRwnd (SctpElement.kt:81) and SctpSackChunk.aRwnd (line 189)
              carry the window — but nothing consumes it.
            - FlowWindow/MplexStream (mplex/Mplex.kt:18) has session+stream window
              with MutableStateFlow — this is the QUIC-shaped abstraction. SCTP's
              aRwnd is association-level, not session+stream split.
            - J08-03 in JULES_TASK_TREES.md asks for BackpressureSignal but no
              implementation exists.

            RED TEST FIRST:
            - commonTest: an SctpFlowController tracking aRwnd starts at 65535. After
              receiving 60000 bytes of DATA chunks, the aRwnd drops to 5535. When
              aRwnd < highWatermark (80%), emit BackpressureSignal.High. When the
              receiver sends SACK updating aRwnd back above lowWatermark (20%),
              emit BackpressureSignal.Low. Assert both signals fire. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/SctpFlowController.kt (NEW)
              (tracks sent/received bytes, current aRwnd; emits BackpressureSignal
              on watermark crossings; pure suspend functions, no blocking)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/SctpFlowControllerTest.kt (NEW)

            PRELOAD CONTRACT:
            - BackpressureSignal = Join<BackpressureLevel, Int> where level is an
              enum (HIGH, LOW) and Int is current aRwnd.
            - Watermarks are Join<Int, Int> (highWatermark j lowWatermark).
            - No MutableStateFlow — use Channel<BackpressureSignal> (CONFLATED).

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: SctpFlowController does not exist
            [ ] aRwnd tracking + watermark signals fire correctly
            [ ] BackpressureSignal emitted through Channel, not StateFlow
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 12: Congestion control = QUIC's per-path ─────────────────
        Triple(
            "ngsctp-ht3-12-congestion-control",
            "SCTP congestion control (cubic) as QUIC's per-path congestion controller",
            """
            TARGET: Implement the SCTP congestion control loop (cubic, matching
            SctpElement's congestionControl field) as a reactor coroutine that
            adjusts cwnd based on SACK feedback. QUIC mandates per-path congestion
            control (RFC 9002). SCTP has the same requirement (RFC 4960 §7) but
            operates per-association with multi-homing awareness. Pound-for-pound
            better: SCTP can run independent cwnd per path in a multi-homed
            association, while QUIC runs one cwnd per path (connection migration
            resets the cwnd).

            CURRENT STATE:
            - SctpElement (SctpElement.kt:341) has congestionControl: String = "cubic"
              — a config field with no implementation.
            - No cwnd tracking, no slow-start / congestion-avoidance state machine.
            - SACK feedback (cumulativeTsnAck + gap blocks) is the input signal but
              nothing consumes it for cwnd adjustment.

            RED TEST FIRST:
            - commonTest: a CubicCongestionController starts in slow-start with
              cwnd=MSS. After 3 SACKs without loss, cwnd increases (slow-start).
              After a SACK with gap-ack blocks (loss detected), cwnd is halved
              (congestion avoidance). Assert cwnd values at each transition.
              Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/reactor/ngsctp/CubicCongestionController.kt (NEW)
              (state: cwnd, ssthresh, state (SLOW_START/CONGESTION_AVOIDANCE/FAST_RECOVERY);
              onSack(SctpSackChunk) adjusts cwnd; pure, no I/O)
            - src/commonTest/kotlin/borg/trikeshed/reactor/ngsctp/CubicCongestionControllerTest.kt (NEW)

            PRELOAD CONTRACT:
            - CongestionState is a sealed class (SLOW_START, CONGESTION_AVOIDANCE,
              FAST_RECOVERY) — not an enum, to carry per-state data (e.g., recovery
              point TSN in FAST_RECOVERY).
            - cwnd/ssthresh are UInt (bytes), not Int — matches SctpInitChunk.aRwnd.
            - onSack is a pure function: (CubicCongestionController, SctpSackChunk) ->
              CubicCongestionController (immutable transition, not mutation).

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: CubicCongestionController does not exist
            [ ] Slow-start → congestion-avoidance → fast-recovery transitions correct
            [ ] cwnd adjustment on SACK with and without gap-ack blocks
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 13: SCTP data chunk framing = QUIC STREAM frame ──────────
        Triple(
            "ngsctp-ht3-13-data-chunk-framing",
            "SCTP DATA chunk framing as QUIC STREAM frame: per-stream TLV wire format",
            """
            TARGET: Implement the SCTP DATA chunk (RFC 4960 §3.3.1) as the wire
            framing that replaces QUIC STREAM frames. A QUIC STREAM frame carries
            (streamId, offset, FIN, payload). An SCTP DATA chunk carries (TSN,
            streamId, streamSeqNum, protocolId, payload) — the same information
            plus a per-stream sequence number for ordered delivery. Pound-for-pound
            better: SCTP's per-stream sequence number allows ordered delivery
            within a stream while maintaining unordered delivery across streams
            (QUIC's offset field is per-stream, but SCTP's TSN gives cross-stream
            ordering semantics that QUIC lacks).

            CURRENT STATE:
            - SctpChunkType.DATA (SctpElement.kt:11) exists in the enum.
            - TlvChunkParser (sctp/TlvChunkParser.kt:6) parses TLV chunks with
              type=0x00 as DATA — but there is no SctpDataChunk data class with
              TSN, streamId, streamSeqNum, payload.
            - Http3Session.createStream() (http3/Http3Session.kt:19) manually
              encodes streamId as 8 bytes + data — this is the ad-hoc framing
              that SctpDataChunk should replace.

            RED TEST FIRST:
            - commonTest: SctpDataChunk(tsn=1, streamId=0, streamSeqNum=0,
              payload=byteArrayOf(0x48,0x54,0x58)).encode() produces the correct
              RFC 4960 wire bytes: type=0, flags=0, length=16+payload, TSN(4),
              streamId(2), streamSeqNum(2), protocolId(4... actually 32-bit
              unordered/fragment flags). decode() round-trips. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/context/sctp/SctpDataChunk.kt (NEW)
              (data class: tsn: UInt, streamId: UShort, streamSeqNum: UShort,
              unordered: Boolean, beginning: Boolean, ending: Boolean, payload: ByteArray;
              encode/decode per RFC 4960 §3.3.1)
            - src/commonTest/kotlin/borg/trikeshed/sctp/SctpDataChunkTest.kt (NEW)

            PRELOAD CONTRACT:
            - SctpDataChunk uses Series<Byte> for payload (via ByteSeries) not
              ByteArray — but ByteArray is acceptable at the wire boundary per
              existing SctpInitChunk.encode() pattern.
            - Fragment flags (B/E bits) in the chunk flags byte, not a separate field.
            - TSN is UInt (32-bit), streamId is UShort (16-bit) per RFC 4960.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: SctpDataChunk does not exist
            [ ] encode/decode round-trip with correct RFC 4960 wire format
            [ ] Fragment flags (B/E) and unordered bit handled
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 14: NUID routing for SCTP associations = QUIC conn ID ────
        Triple(
            "ngsctp-ht3-14-nuid-routing-association-id",
            "NUID-based association routing as QUIC connection ID: subnet-aware association IDs",
            """
            TARGET: Use NUID (Network Unique ID) as the SCTP association identifier,
            replacing the current host-hash approach. QUIC uses a connection ID
            (CID) so packets survive IP/port changes. SCTP associations are keyed
            by (host, port) — but TrikeShed's NUID system (context/nuid/Nuid.kt)
            provides a subnet-aware identity that is better: NUID encodes capability
            + subnet + nonce, so an SCTP association can be routed by NUID subnet
            (core, process.self, local, lan.localhost) without exposing IP addresses.

            CURRENT STATE:
            - SctpElement.assocId() (SctpElement.kt:399) = (host.hashCode().toLong()
              shl 32) xor port.toLong() — a host:port hash, not a NUID.
            - SctpReactorSpine.extractSubnet() (SctpReactorSpine.kt:260) extracts
              subnet from ReactorAction's NUID, but the association ID itself is
              not NUID-based.
            - NuidFanoutElement (context/nuid/) is already imported in
              SctpReactorSpine.kt:20 — the wiring point exists but is unused.

            RED TEST FIRST:
            - commonTest: SctpElement.assocIdFromNuid(nuid) returns a Long derived
              from the NUID's packed value (capability + subnet + nonce), not from
              host:port hash. Two associations to the same host:port but different
              NUIDs produce different assocIds. Observe RED.

            IMPLEMENTATION SURFACE (disjoint):
            - src/commonMain/kotlin/borg/trikeshed/context/sctp/SctpElement.kt
              (add assocIdFromNuid(nuid: Nuid): Long; keep existing assocId() as
              fallback for non-NUID peers; SctpAssociation stays Join<Long, SctpState>)
            - src/commonTest/kotlin/borg/trikeshed/sctp/NuidAssocIdTest.kt (NEW)

            PRELOAD CONTRACT:
            - Nuid is already a Join<Capability, Join<Nonce, Subnet>> — use its
              packed Long representation.
            - assocIdFromNuid is a pure function: Nuid → Long.
            - No IP address in the association identity — NUID subnet routes.

            QUIC REJECTION: Do not use "connection ID" terminology. SCTP uses
            "association ID" keyed by NUID, not CID.
            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: assocIdFromNuid does not exist
            [ ] NUID-based assocId is deterministic and collision-resistant
            [ ] Same host:port, different NUID → different assocId
            [ ] Gate green
            """.trimIndent()
        ),

        // ── Take 15: Integration — end-to-end HTTP/3-over-ngSCTP exchange ─
        Triple(
            "ngsctp-ht3-15-e2e-exchange",
            "End-to-end HTTP/3 over ngSCTP: loopback exchange with all takes composed",
            """
            TARGET: Prove the full choreography: an HTTP/3 request/response over
            ngSCTP in a loopback test. This take does NOT add new implementation —
            it composes takes 1-14 into one end-to-end test proving the reactor
            choreography is pound-for-pound better than QUIC:

            1. SctpElement opens association (CCEK lifecycle, take 1)
            2. Http3Session creates a stream via SctpElement.openStream() (take 2)
            3. Data flows as SctpDataChunk with TSNs (take 13)
            4. SACK with gap-ack blocks acknowledges (take 3)
            5. aRwnd backpressure signals fire (take 11)
            6. Congestion controller adjusts cwnd (take 12)
            7. Multi-homing failover works if a path fails (take 4)
            8. Cookie reuse enables 0-RTT on reconnect (take 5)
            9. PR-SCTP abandons a stream (take 6)
            10. DTLS encrypts DATA payloads (take 7)
            11. QPACK compresses headers (take 8)
            12. SctpReactorSpine emits fanout events (take 9)
            13. HtxElement dispatches over SCTP (take 10)
            14. NUID routes the association (take 14)

            CURRENT STATE:
            - All individual components are targeted by takes 1-14.
            - No end-to-end test exists. The README describes the architecture
              (src/README.md:57: "Job 1: htx-general-client (QUIC, TCP, ngSCTP)")
              but no test proves the ngSCTP path works end-to-end.

            RED TEST FIRST:
            - jvmTest: an E2E test that opens an SctpElement, creates an Http3Session
              backed by it, sends a GET request via HtxElement with
              SctpHtxRouteService, and receives a 200 response. Assert the response
              body matches the loopback server's body. Observe RED (components not
              composed yet — this test WILL fail until takes 1-14 land).

            IMPLEMENTATION SURFACE (disjoint):
            - src/jvmTest/kotlin/borg/trikeshed/reactor/ngsctp/E2eHt3OverNgSctpTest.kt (NEW)
            - This test file ONLY depends on public APIs from takes 1-14. It does
              not modify any production file. If any take 1-14 has not landed,
              this test stays RED — it is the acceptance gate for the choreography.

            PRELOAD CONTRACT:
            - Test uses coroutineScope { launch { } } for parallel stream tests.
            - No Thread.sleep, no runBlocking inside coroutines.
            - No java.net.* — loopback via in-memory Channels (StreamHandle with
              Channel.BUFFERED send/recv), not TCP sockets.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed: E2E test fails because components are not composed
            [ ] As takes 1-14 land, this test goes GREEN incrementally
            [ ] Full HTTP/3 request/response over ngSCTP completes end-to-end
            [ ] Gate green
            [ ] Zero QUIC imports in the entire ngSCTP HTTP/3 path
            """.trimIndent()
        ),
    )

    takes.forEach { (workId, title, spec) ->
        val cause = JulesCause.WorkQueued(
            workId = workId,
            tier = "trikeshed",
            title = title,
            spec = spec,
            parent = null,
            score = 0.75,
            at = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        store.appendWork(workId, cause)
        println("Appended work: $workId")
    }
    println("Total takes queued: ${takes.size}")
    println("Queue entry count: ${store.loadQueue().size}")
}
