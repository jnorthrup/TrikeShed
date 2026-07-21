package borg.trikeshed.reactor.ngsctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.subnet
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.reactor.MeshActionResult
import borg.trikeshed.reactor.PeerAddress
import borg.trikeshed.reactor.SctpReactorEndpoint
import borg.trikeshed.wireproto.WireprotoCodec
import dev.jnorthrup.ngsctp.AssociationInfo
import dev.jnorthrup.ngsctp.AssociationState
import dev.jnorthrup.ngsctp.NgSctpAssociation
import dev.jnorthrup.ngsctp.NgSctpStream
import dev.jnorthrup.ngsctp.SctpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Bridges KMPngSCTP [NgSctpAssociation] to TrikeShed's [SctpReactorEndpoint] interface.
 *
 * Integration points:
 * - [NgSctpAssociation] lifecycle → [ReactorAction] sealed class events
 * - [NgSctpStream] channels → per-stream [ReactorAction] dispatch via NuidFanoutElement
 * - Implements [StreamTransport] so Oroboros Network gateway can use it as a transport
 * - Uses [WireprotoCodec] for ReactorAction ↔ ByteArray serialization (zero new codec)
 *
 * Subnet: [Subnet.Mesh] for SCTP associations (multi-homed, cross-node).
 * Capability: `sctp.association.<verTag>` for the association; `sctp.stream.<id>` per stream.
 */
class SctpReactorEndpoint private constructor(
    private val association: NgSctpAssociation,
    private val streams: ConcurrentHashMap<Int, NgSctpStream>,
    private val inboundActions: MutableSharedFlow<Pair<PeerAddress, ReactorAction>>,
) : SctpReactorEndpoint, CoroutineScope by association, StreamTransport {

    private val outboundChunks = Channel<ByteBuffer>(Channel.BUFFERED)

    companion object {
        private val scopeLevel = Subnet.Mesh.level

        /**
         * Client connect — dials a remote SCTP peer and upgrades to ReactorEndpoint.
         */
        suspend fun connect(
            remote: InetSocketAddress,
            local: InetSocketAddress = InetSocketAddress(0),
            outboundStreams: UShort = 10u,
            inboundStreams: UShort = 10u,
        ): SctpReactorEndpoint {
            val assoc = NgSctpAssociation.connect(remote, local, outboundStreams, inboundStreams)
            return make(assoc, PeerAddress(remote.address.hostAddress ?: remote.hostName, remote.port))
        }

        /**
         * Server accept — from a [SctpServer.associations] receive channel.
         */
        suspend fun accept(
            assoc: NgSctpAssociation,
            peer: PeerAddress,
        ): SctpReactorEndpoint = make(assoc, peer)

        private fun make(assoc: NgSctpAssociation, peer: PeerAddress): SctpReactorEndpoint {
            val inbound = MutableSharedFlow<Pair<PeerAddress, ReactorAction>>(
                replay = 0,
                extraBufferCapacity = Channel.BUFFERED
            )
            val endpoint = SctpReactorEndpoint(assoc, ConcurrentHashMap(), inbound)

            // Open reactor stream (stream 0) for control-plane ReactorAction dispatch
            val reactorStream = assoc.openStream(priority = 0, intent = "reactor")
            endpoint.streams[reactorStream.streamId] = reactorStream

            // Wire inbound chunks → ReactorAction flow
            endpoint.launchStreamDispatcher(reactorStream, peer)

            // Send opened lifecycle event
            endpoint.launch {
                val nuid = nuid(
                    Capability.Custom("sctp", "association"),
                    Nonce.RandomNonce(assoc.localVerificationTag.toString()),
                    Subnet.Mesh
                )
                inbound.emit(peer to ReactorAction.opened(nuid))
            }

            return endpoint
        }

        /**
         * Wrap a server-side [SctpServer]'s accepted association into a [SctpReactorEndpoint].
         * Use this inside the server accept loop:
         * ```
         * val server = NgSctpAssociation.listen(localAddr)
         * for (assoc in server.associations) {
         *     val peer = PeerAddress(assoc.remoteAddress ...)
         *     val ep = SctpReactorEndpoint.accept(assoc, peer)
         *     // handle ReactorAction via ep.receive()
         * }
         * ```
         */
        suspend fun fromServer(
            assoc: NgSctpAssociation,
            remoteAddr: InetSocketAddress,
        ): SctpReactorEndpoint {
            val peer = PeerAddress(
                remoteAddr.address.hostAddress ?: remoteAddr.hostName,
                remoteAddr.port
            )
            return accept(assoc, peer)
        }
    }

    // ── SctpReactorEndpoint interface ──────────────────────────────

    private var boundPort: Int = 0

    override suspend fun bind(port: Int): Int {
        boundPort = port
        return port
    }

    override suspend fun send(peer: PeerAddress, action: ReactorAction): MeshActionResult {
        return try {
            val bytes = WireprotoCodec.encode(action)
            val bb = ByteBuffer.wrap(bytes)

            // Send on stream 0 (reactor stream)
            val reactorStream = streams[0]
            if (reactorStream == null || !reactorStream.isOpen) {
                return MeshActionResult.Failed(borg.trikeshed.reactor.MeshErrorCode.BAD_FRAME)
            }
            association.sendData(0.toUShort(), bb)
            MeshActionResult.Ok(bytes)
        } catch (e: Exception) {
            MeshActionResult.Failed(borg.trikeshed.reactor.MeshErrorCode.INTERNAL)
        }
    }

    override suspend fun receive(): Pair<PeerAddress, ReactorAction> {
        // This suspends until a ReactorAction arrives on the inbound flow.
        // The flow is fed by launchStreamDispatcher for each stream.
        return inboundActions.receiveAsFlow().let { flow ->
            kotlinx.coroutines.flow.first(flow)
        }
    }

    override suspend fun close() {
        association.close()
    }

    // ── StreamTransport interface ──────────────────────────────────

    override suspend fun openStream(): borg.trikeshed.context.StreamHandle {
        val stream = association.openStream(priority = 0, intent = "data")
        val id = stream.streamId
        streams[id] = stream

        val sendChannel = kotlinx.coroutines.channels.Channel<ByteArray>(Channel.UNLIMITED)
        val recvChannel = kotlinx.coroutines.channels.Channel<ByteArray>(Channel.BUFFERED)

        // Wire stream sendChannel → association.sendData
        launch {
            for (data in sendChannel) {
                val bb = ByteBuffer.wrap(data)
                association.sendData(id.toUShort(), bb)
            }
        }

        // Wire stream receiveChannel → inbound ReactorAction
        launchStreamDispatcher(stream, PeerAddress("", 0))

        return borg.trikeshed.context.StreamHandle(
            id = id,
            send = sendChannel,
            recv = recvChannel
        )
    }

    override val activeStreams: Int
        get() = streams.count { it.value.isOpen }

    // ── internal ──────────────────────────────────────────────────

    private fun launchStreamDispatcher(stream: NgSctpStream, peer: PeerAddress) {
        launch {
            for (bb in stream.receiveChannel) {
                try {
                    val bytes = ByteArray(bb.remaining())
                    bb.get(bytes)
                    val action = WireprotoCodec.decode(bytes)
                    inboundActions.emit(peer to action)
                } catch (e: Exception) {
                    // Log and continue — skip malformed frames
                }
            }
        }
    }

    /** The live [AssociationInfo] for monitoring. */
    val info: AssociationInfo
        get() = association.info

    /** Current SCTP association state. */
    val state: AssociationState
        get() = association.state

    /** Number of open streams. */
    val streamCount: Int
        get() = streams.count { it.value.isOpen }
}

/**
 * CCEK [AsyncContextElement] wrapper that owns a [SctpReactorEndpoint] and manages its lifecycle.
 * Install into a CoroutineContext to expose SCTP reactor capabilities to NuidFanoutElement.
 */
class SctpReactorElement(
    val endpoint: SctpReactorEndpoint,
    override val fanoutSubscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement(ElementState.OPEN), StreamTransport by endpoint {

    companion object Key : AsyncContextKey<SctpReactorElement>()

    override val key: CoroutineContext.Key<*> = Key
}
