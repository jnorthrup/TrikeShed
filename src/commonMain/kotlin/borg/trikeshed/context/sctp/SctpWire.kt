package borg.trikeshed.sctp

import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** One SCTP packet on the wire, tagged with the multi-homing path it arrived on / should leave by. */
class SctpDatagram(val path: String, val bytes: ByteArray)

/**
 * SctpWire — the packet boundary SPI for [SctpElement].
 *
 * [SctpElement] owns chunks, associations, streams and path failover; it does not own the socket.
 * This is the only seam between the RFC 4960 state machine and a transport. Pattern is the house
 * single-seam SPI (interface + [Key] + [register] + [default], as `SystemOperations`):
 *
 *  - default: [LoopbackSctpWire] — in-process, deterministic, every target. This is what the element
 *    effectively had before (channels, no socket), made explicit.
 *  - kernel SCTP (linux/jvm), userspace-over-UDP, DataChannel, …: implement this and call [register].
 *    Nothing else in the element changes.
 *
 * [kernelOffload] is a fact, not a promise: true only when the backing rides a kernel SCTP socket and
 * therefore inherits NIC CRC32c / GSO. A userspace packetizer must report false — it bypasses offload
 * the same way QUIC does.
 */
interface SctpWire : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SctpWire> {
        private var installed: SctpWire? = null

        /** Install a backing for [default]. Last registration wins. */
        fun register(wire: SctpWire) {
            installed = wire
        }

        /** The installed backing, or [LoopbackSctpWire] when none was registered. */
        val default: SctpWire
            get() = installed ?: LoopbackSctpWire.also { installed = it }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    /** Human-readable backing identity, e.g. "loopback", "kernel-sctp", "udp-userspace". */
    val backing: String

    /** True iff packets go through a kernel SCTP socket (NIC CRC32c/GSO apply). */
    val kernelOffload: Boolean

    /** Bind a local listening port; returns a wire-local handle. Idempotent per port. */
    suspend fun bind(port: Int): Int

    /** Send one encoded SCTP packet toward [path]. */
    suspend fun send(path: String, packet: ByteArray)

    /** Receive the next packet for [port], suspending until one arrives; null once the wire is closed. */
    suspend fun receive(port: Int): SctpDatagram?

    /** Release all sockets/handles. Further sends fail; pending receives return null. */
    suspend fun close()
}

/**
 * In-process wire: a bounded channel per bound port. A [send] whose [path] is `"<host>:<port>"`
 * (or just `"<port>"`) is delivered to that port's channel if it is bound, else dropped — mirroring
 * an unreachable path, which is what drives [SctpElement.failover] in tests.
 */
object LoopbackSctpWire : SctpWire {
    override val backing: String = "loopback"
    override val kernelOffload: Boolean = false

    private val inboxes = mutableMapOf<Int, Channel<SctpDatagram>>()
    private var closed = false

    override suspend fun bind(port: Int): Int {
        check(!closed) { "loopback wire closed" }
        inboxes.getOrPut(port) { Channel(Channel.BUFFERED) }
        return port
    }

    override suspend fun send(path: String, packet: ByteArray) {
        check(!closed) { "loopback wire closed" }
        val port = path.substringAfterLast(':').toIntOrNull() ?: return
        inboxes[port]?.send(SctpDatagram(path, packet))
    }

    override suspend fun receive(port: Int): SctpDatagram? {
        val inbox = inboxes[port] ?: return null
        return inbox.receiveCatching().getOrNull()
    }

    override suspend fun close() {
        closed = true
        inboxes.values.forEach { it.close() }
        inboxes.clear()
    }

    /** Test seam: forget all ports and reopen. */
    fun reset() {
        inboxes.values.forEach { it.close() }
        inboxes.clear()
        closed = false
    }
}
