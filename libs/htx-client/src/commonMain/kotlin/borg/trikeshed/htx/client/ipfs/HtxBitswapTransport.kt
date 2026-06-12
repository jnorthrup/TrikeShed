package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.FanoutDispatcherElement
import borg.trikeshed.userspace.network.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Bitswap Transport for HTX Client — wraps HTX Channel for Bitswap message I/O.
 * Integrates with CCEK reactor's FanoutDispatcher for message fanout.
 */
class HtxBitswapTransport(
    private val channel: Channel,
    private val scope: CoroutineScope,
    private val fanoutDispatcher: FanoutDispatcherElement? = null,
) : AutoCloseable {

    private val messageChannel: KChannel<BitswapEngine.BitswapMessage> = KChannel()
    private val pendingWants = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ByteArray>>()
    private var readerJob: Job? = null

    /** Receive channel for decoded Bitswap messages. */
    val messages: ReceiveChannel<BitswapEngine.BitswapMessage> = messageChannel

    /** Start the transport reader loop. */
    fun start() {
        readerJob = scope.launch { readLoop() }
    }

    suspend fun send(message: BitswapEngine.BitswapMessage) {
        val encoded = message.encode()
        val region = ByteRegion(encoded)
        channel.write(region)
    }

    suspend fun registerWant(cid: CID): kotlinx.coroutines.CompletableDeferred<ByteArray> {
        val key = cid.hex()
        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        pendingWants[key] = deferred
        return deferred
    }

    fun completeWant(cid: CID, data: ByteArray) {
        val key = cid.hex()
        pendingWants.remove(key)?.complete(data)
    }

    private fun onBlockReceived(cid: CID, data: ByteArray) {
        completeWant(cid, data)
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(65536)
        while (channel.isConnected()) try {
            val bytesRead = channel.read(ByteRegion(buffer))
            if (bytesRead <= 0) {
                delay(10)
                continue
            }
            val data = buffer.copyOfRange(0, bytesRead)
            val message = BitswapEngine.BitswapMessage.decode(data)

            fanoutDispatcher?.dispatch(BitswapFanoutEvent(message))
            messageChannel.trySend(message)

            when (message) {
                is BitswapEngine.BitswapMessage.Block -> onBlockReceived(message.cid, message.data)
                is BitswapEngine.BitswapMessage.Have -> { }
                is BitswapEngine.BitswapMessage.DontHave -> { }
                else -> { }
            }
        } catch (e: Exception) {
            println("Bitswap read error: ${e.message}")
            delay(100)
        }
    }

    override fun close() {
        readerJob?.cancel()
        messageChannel.close()
    }
}

/** Fanout event for Bitswap messages. */
data class BitswapFanoutEvent(
    val message: BitswapEngine.BitswapMessage
) : borg.trikeshed.userspace.FanoutEvent {
    override val eventType: Int = 100
}

/** Factory for HtxBitswapTransport. */
object HtxBitswapTransportFactory {
    suspend fun create(
        channel: Channel,
        scope: CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        fanoutDispatcher: FanoutDispatcherElement? = null,
    ): HtxBitswapTransport {
        val transport = HtxBitswapTransport(channel, scope, fanoutDispatcher)
        transport.start()
        return transport
    }
}