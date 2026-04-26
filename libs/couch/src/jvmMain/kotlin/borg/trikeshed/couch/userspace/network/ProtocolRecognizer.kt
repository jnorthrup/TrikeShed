package borg.trikeshed.couch.userspace.network

import borg.trikeshed.couch.htx.HtxBlock
import borg.trikeshed.couch.userspace.nio.ReactorSupervisor
import borg.trikeshed.couch.userspace.nio.BranchDispatch
import borg.trikeshed.couch.userspace.nio.MessageHandler
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope

/**
 * Protocol recognizer as a Reactor branch.
 *
 * Feeds detected protocol → session routing.
 * The detector is stateful but the recognizer itself is stateless between
 * detections — each connection gets its own branch.
 *
 * Usage:
 *   reactor.launchBranch("protocol-recognizer", channel) {
 *       ProtocolRecognizer(channel, realm).run()
 *   }
 */
class ProtocolRecognizer(
    private val channel: KChannel<HtxBlock>,
    private val realm: String,
) : BranchDispatch() {

    private val detector = ProtocolDetector()
    private var sessionId: String? = null

    /**
     * Run the protocol recognition loop.
     * Feeds bytes from HtxBlock payloads into the ProtocolDetector.
     * On first detection, creates a session context and registers the protocol handler.
     */
    suspend fun run(reactor: ReactorSupervisor): Unit = coroutineScope {
        while (true) {
            val result = channel.receiveCatching()
            if (!result.isSuccess) break
            val block = result.getOrThrow()
            dispatch(block)
            val proto = detector.protocol()
            if (proto != null && sessionId == null) {
                val sid = "${realm}-${this@ProtocolRecognizer.hashCode().toString().replace("-", "n")}"
                sessionId = sid
                reactor.withSessionContext<Unit>(sid) {
                    register(proto.name, object : MessageHandler() {
                        override suspend fun handle(block: HtxBlock) {
                            // delegate to parse supervisor
                        }
                    })
                }
            }
        }
    }

    override fun dispatch(block: HtxBlock) {
        val bytes = block.payloadBytes()
        detector.feed(bytes)
    }
}

/**
 * Extract the payload bytes from an HtxBlock.
 * The block carries addr/info metadata; the actual data is read from the
 * block store via the addr field.
 */
private fun HtxBlock.payloadBytes(): ByteArray {
    // HtxBlock.addr points to the block data in the block store.
    // Read bytes from block store — placeholder returns zeros of valueLen.
    return ByteArray(valueLen)
}
