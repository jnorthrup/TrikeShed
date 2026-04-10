package borg.literbike.ccek.sctp

/**
 * SCTP event handler for reactor integration
 *
 * Implements EventHandler for dispatching SCTP events through the reactor.
 */

/**
 * Callback for received SCTP data
 */
typealias SctpDataCallback = (UShort, ByteArray) -> Unit

/**
 * Callback for association state changes
 */
typealias SctpStateCallback = (AssociationState) -> Unit

/**
 * EventHandler for SCTP
 */
interface EventHandler {
    fun onReadable(fd: Int)
    fun onWritable(fd: Int)
    fun onError(fd: Int, error: Throwable)
}

/**
 * SCTP handler for reactor integration
 */
class SctpHandler(
    private val socket: SctpSocket
) : EventHandler {
    private var onData: SctpDataCallback? = null
    private var onStateChange: SctpStateCallback? = null

    /**
     * Set the data receive callback
     */
    fun onData(callback: SctpDataCallback): SctpHandler {
        this.onData = callback
        return this
    }

    /**
     * Set the state change callback
     */
    fun onStateChange(callback: SctpStateCallback): SctpHandler {
        this.onStateChange = callback
        return this
    }

    /**
     * Get the underlying socket
     */
    fun socket(): SctpSocket = socket

    override fun onReadable(fd: Int) {
        val buf = ByteArray(65536)
        socket.recv(buf).onSuccess { (streamId, n) ->
            val data = buf.sliceArray(0 until n)
            onData?.invoke(streamId, data)
        }.onFailure { e ->
            if (e !is IllegalStateException) {
                // Log error - WouldBlock errors are ignored
            }
        }
    }

    override fun onWritable(fd: Int) {
        // Socket is writable for sending
    }

    override fun onError(fd: Int, error: Throwable) {
        // Log socket error
        onStateChange?.invoke(AssociationState.Closed)
    }
}
