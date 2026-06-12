package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DHT Transport interface — abstracts network I/O for DHT operations.
 */
interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo>
}

/**
 * HTX DHT Transport using io_uring via FunctionalUringFacade.
 * Integrates with CCEK reactor's shared io_uring ring and FanoutDispatcher.
 */
class HtxDhtTransport(
    private val facade: FunctionalUringFacade,
    private val fanoutDispatcher: borg.trikeshed.userspace.FanoutDispatcherElement? = null,
) : DhtTransport {

    private val providerRegistry: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val registryMutex = Mutex()

    override suspend fun announceProviderRemote(cid: CID, address: String) {
        val key = cid.hex()
        facade.enqueue(UringOp.Companion.Submissions.nop(1L))
        facade.submit()
        facade.waitCqe()
        registryMutex.withLock {
            providerRegistry.computeIfAbsent(key) { mutableSetOf() }.add(address)
        }
        fanoutDispatcher?.dispatchUring(UringCompletion(userData = 0, res = 1, flags = 0))
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> {
        val key = cid.hex()
        facade.enqueue(UringOp.Companion.Submissions.nop(2L))
        facade.submit()
        facade.waitCqe()
        return registryMutex.withLock { providerRegistry[key]?.toList() ?: emptyList() }
    }

    override suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo> {
        facade.enqueue(UringOp.Companion.Submissions.nop(3L))
        facade.submit()
        facade.waitCqe()
        return emptyList()
    }

    fun registerFanoutHandler(handler: (UringCompletion) -> Unit) {
        fanoutDispatcher?.registerHandler(0) { event ->
            if (event is UringCompletion) handler(event)
        }
    }
}

/** Factory for HtxDhtTransport with CCEK wiring. */
object HtxDhtTransportFactory {
    suspend fun create(
        entries: Int = 256,
        fanoutDispatcher: borg.trikeshed.userspace.FanoutDispatcherElement? = null,
    ): HtxDhtTransport {
        val facade = FunctionalUringFacade(entries)
        return HtxDhtTransport(facade, fanoutDispatcher)
    }

    suspend fun CoroutineScope.installHtxDhtTransport(
        entries: Int = 256,
    ): Triple<borg.trikeshed.userspace.LiburingElement, 
              borg.trikeshed.userspace.FanoutDispatcherElement, 
              HtxDhtTransport> {
        val liburing = borg.trikeshed.userspace.LiburingElement(this.coroutineContext[kotlinx.coroutines.Job])
        liburing.open()
        val fanout = borg.trikeshed.userspace.FanoutDispatcherElement(this.coroutineContext[kotlinx.coroutines.Job])
        val transport = create(entries, fanout)
        return withContext(liburing + fanout) { liburing to fanout to transport }
    }
}