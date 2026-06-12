package borg.trikeshed.sctp

import kotlinx.serialization.Serializable
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlin.coroutines.CoroutineContext

/**
 * SCTP Association information.
 */
@Serializable
data class AssociationInfo(
    val localPort: Int,
    val remotePort: Int,
    val state: AssociationState,
    val streams: Int,
    val nextTSN: UInt,
    val cwnd: UInt,
    val ssthresh: UInt,
    val bytesInFlight: Long,
    val outstandingChunks: Int
)

/**
 * SCTP Association states
 */
enum class AssociationState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED
}

/**
 * SCTP Element for the HTX client - Stub implementation.
 * Extends AsyncContextElement for proper lifecycle integration with TrikeShed context system.
 * 
 * TODO: Replace with actual ngsctp.NgSctpAssociation integration when ngsctp compiles.
 */
class SctpElement(
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 9899,
    val localPort: Int = 0
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = SctpKey

    /** Placeholder for underlying association (actual ngsctp implementation pending) */
    // private var association: NgSctpAssociation? = null

    override suspend fun open() {
        super.open()
        // TODO: Perform actual SCTP handshake
        // association = NgSctpAssociation.connect(...)
    }

    override suspend fun drain() {
        super.drain()
        // Wait for all streams to complete
        // association?.coroutineContext?.ensureActive()
    }

    override suspend fun close() {
        super.close()
    }

    /** Get a stream by ID */
    fun getStream(streamId: Int): NgSctpStream? = null // TODO: association?.getStream(streamId)

    /** Open a new outbound stream */
    suspend fun openStream(streamId: Int = 0): NgSctpStream {
        // return association?.openStream(streamId) ?: throw IllegalStateException("Association not established")
        throw NotImplementedError("ngsctp integration pending - SctpStream not available")
    }

    /** Get association info */
    fun getInfo(): AssociationInfo = AssociationInfo(
        localPort = localPort,
        remotePort = remotePort,
        state = mapElementStateToAssociationState(state),
        streams = 0,
        nextTSN = 0u,
        cwnd = 0u,
        ssthresh = 0u,
        bytesInFlight = 0L,
        outstandingChunks = 0
    )

    /** Map internal ElementState to SCTP AssociationState */
    private fun mapElementStateToAssociationState(es: ElementState): AssociationState = when (es) {
        ElementState.CREATED -> AssociationState.CLOSED
        ElementState.OPEN -> AssociationState.ESTABLISHED
        ElementState.ACTIVE -> AssociationState.ESTABLISHED
        ElementState.DRAINING -> AssociationState.SHUTDOWN_PENDING
        ElementState.CLOSED -> AssociationState.CLOSED
    }

    /** Send data on a stream (convenience method) */
    suspend fun send(streamId: Int, data: ByteArray): Boolean {
        val stream = getStream(streamId)
        if (stream == null) return false
        stream.write(data)
        return true
    }

    /** Receive data from a stream (convenience method) */
    suspend fun receive(streamId: Int): ByteArray? {
        val stream = getStream(streamId)
        return stream?.receive()
    }
}

/**
 * Creates and opens an SCTP element by connecting to a remote endpoint.
 */
suspend fun openSctpElement(
    remoteHost: String = "127.0.0.1",
    remotePort: Int = 9899,
    localPort: Int = 0,
    outboundStreams: UShort = 10.toUShort(),
    inboundStreams: UShort = 10.toUShort()
): SctpElement {
    val element = SctpElement(remoteHost, remotePort, localPort)
    element.open()
    return element
}

/** Placeholder for SCTP Stream - will be replaced by ngsctp.NgSctpStream */
class NgSctpStream(
    val streamId: Int
) {
    suspend fun write(data: ByteArray) {
        throw NotImplementedError("ngsctp integration pending")
    }
    
    suspend fun receive(): ByteArray {
        throw NotImplementedError("ngsctp integration pending")
    }
}

/** Context key for SctpElement in structured concurrency */
object SctpKey : CoroutineContext.Key<SctpElement>