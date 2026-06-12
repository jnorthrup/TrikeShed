package borg.trikeshed.sctp

import kotlin.coroutines.CoroutineContext

/**
 * SCTP Element for the HTX client - Stub implementation.
 * Wraps an SCTP association placeholder and provides the CoroutineContext.Element interface
 * for structured concurrency integration with the TrikeShed context system.
 * 
 * TODO: Replace with actual ngsctp.NgSctpAssociation integration when ngsctp compiles.
 */
class SctpElement(
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 9899,
    val localPort: Int = 0
) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = SctpKey

    /** Association state */
    @Volatile
    var state: AssociationState = AssociationState.CLOSED
        private set

    /** Placeholder for underlying association (actual ngsctp implementation pending) */
    // private var association: NgSctpAssociation? = null

    suspend fun open() {
        state = AssociationState.COOKIE_WAIT
        // TODO: Perform actual SCTP handshake
        // association = NgSctpAssociation.connect(...)
        state = AssociationState.ESTABLISHED
    }

    suspend fun drain() {
        // Wait for all streams to complete
        // association?.coroutineContext?.ensureActive()
    }

    suspend fun close() {
        state = AssociationState.SHUTDOWN_PENDING
        // association?.close()
        state = AssociationState.CLOSED
    }

    /** Get a stream by ID */
    fun getStream(streamId: Int): NgSctpStream? = null // TODO: association?.getStream(streamId)

    /** Open a new outbound stream */
    suspend fun openStream(streamId: Int = 0): NgSctpStream {
        // return association?.openStream(streamId) ?: throw IllegalStateException("Association not established")
        throw NotImplementedError("ngsctp integration pending - SctpStream not available")
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

/** SCTP Association states */
enum class AssociationState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED
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