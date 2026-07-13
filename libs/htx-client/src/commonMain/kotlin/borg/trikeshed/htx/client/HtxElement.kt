package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/**
 * HTX as the version-agnostic tokenizer powers all HTTP versions. The htx-general-client
 * targets three transports under one Element:
 *   - TCP        → Channels.socket(SOCK_STREAM) → FunctionalUringFacade
 *   - QUIC       → QuicElement openStream() → UDP via uring
 *   - ngSCTP     → SctpElement association → SCTP via uring
 *   - IPFS       → IpfsElement DHT + content routing
 */
class HtxElement(
    initialState: ElementState = ElementState.CREATED,
    parentJob: Job? = null
) : AsyncContextElement(initialState, parentJob) {

    companion object Key : AsyncContextKey<HtxElement>()
    override val key: AsyncContextKey<*> get() = Key

    private var reactorOps: ReactorOperations? = null

    override suspend fun open() {
        super.open()

        // Resolve the reactor operations from the context
        reactorOps = coroutineContext[ReactorOperations.Key]

        // Start HTX multi-transport listener / setup
    }

    override suspend fun drain() {
        super.drain()
        // Drain any pending HTTP streams, unregister from ReactorOperations
    }

    override suspend fun close() {
        super.close()
        // Clean up connections, QUIC/TCP streams
    }
}
