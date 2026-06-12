package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * Bitswap Transport for HTX Client — stub implementation.
 */
class HtxBitswapTransport(
    private val scope: CoroutineScope = kotlinx.coroutines.CoroutineScope(Job()),
) : AutoCloseable {

    private val messageChannel: Channel<BitswapEngine.BitswapMessage> = Channel()
    private val pendingWants = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ByteArray>>()

    val messages: ReceiveChannel<BitswapEngine.BitswapMessage> = messageChannel

    suspend fun send(message: BitswapEngine.BitswapMessage) {
        // Stub - would send over HTX Channel in real implementation
    }

    suspend fun registerWant(cid: CID): kotlinx.coroutines.CompletableDeferred<ByteArray> {
        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        pendingWants[cid.hex()] = deferred
        return deferred
    }

    fun completeWant(cid: CID, data: ByteArray) {
        pendingWants.remove(cid.hex())?.complete(data)
    }

    override fun close() {
        messageChannel.close()
    }
}