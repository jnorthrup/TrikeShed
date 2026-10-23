package borg.trikeshed.skinnerbox

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import borg.trikeshed.userspace.nio.platform.spi.platformCacheTopology
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** The concrete application composition. Providers outlive this owner; the listener admits work last. */
class MeshRuntime private constructor(
    val node: MeshNode,
    val listener: MeshListener,
    val twins: MeshTwinElement?,
    val persistence: MeshPersistence,
    private val htx: HtxElement,
    private val supervisor: CompletableJob,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshRuntime> {
        suspend fun open(
            scope: CoroutineScope,
            files: FileOperations,
            configuration: MeshConfiguration,
            identity: IpnsKeyPair,
            state: String,
            address: String,
            allowExternalBind: Boolean = false,
        ): MeshRuntime {
            val parent = requireNotNull(scope.coroutineContext[Job]) { "Mesh runtime requires an owning Job" }
            parent.ensureActive()
            require(identity.publicKey.contentEquals(configuration.local.publicKey)) { "Local signing identity differs" }
            val root = withContext(fileIoContext) {
                files.canonicalPath(state).also(files::mkdirs)
            }
            val supervisor = SupervisorJob(parent)
            val owner = CoroutineScope(scope.coroutineContext + supervisor + files)
            var htx: HtxElement? = null
            var persistence: MeshPersistence? = null
            var twins: MeshTwinElement? = null
            var node: MeshNode? = null
            var listener: MeshListener? = null
            try {
                htx = withContext(owner.coroutineContext) { openHtxElement(parentJob = supervisor) }
                persistence = MeshPersistence.open(owner, FileCasStore(files, files.resolvePath(root, "cas")),
                    files, files.resolvePath(root, "db"), configuration.persistenceGrants)
                // Refuse a changed ownership epoch before replay or network admission can expose this state.
                for (cell in configuration.cells.view) persistence.execute(configuration.local.id,
                    MeshTask(cell.namespace, "cell.bind", CanonicalCbor.encodeMap(mapOf("epoch" to cell.epoch.value))))
                if (configuration.twinGrants.isNotEmpty()) {
                    twins = MeshTwinElement.open(owner, persistence, configuration.twinGrants,
                        configuration.twinNamespaces, configuration.local.id)
                }
                val data = persistence
                val observations = twins
                node = MeshNode(configuration.local, identity, configuration.peers, configuration.cells,
                    data, owner.coroutineContext + htx, application = { peer, task ->
                        if (task.verb.startsWith("twin.")) requireNotNull(observations) { "Twins are not configured" }.execute(peer, task)
                        else data.execute(peer, task)
                    }, exchangeLimits = MeshExchangeLimits(stroke = MeshStroke.from(platformCacheTopology)))
                val active = node
                val api = if (configuration.apiKeys.size == 0) null
                    else MeshDataSurface(configuration.apiKeys, active::submit, configuration.allowedOrigins)
                val exchange = active.exchange.route()
                listener = MeshListener.open(owner, address, allowExternalBind = allowExternalBind) { method, path, headers, body ->
                    exchange(method, path, body, null) ?: api?.handle(method, path, headers, body)
                }
                return MeshRuntime(active, listener, observations, data, htx, supervisor)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    suspend fun attempt(close: suspend () -> Unit) {
                        try { close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                    }
                    listener?.let { attempt(it::drain) }
                    node?.let { attempt(it::drain) }
                    twins?.let { attempt(it::drain) }
                    persistence?.let { attempt(it::drain) }
                    htx?.let { attempt(it::close) }
                    supervisor.cancelAndJoin()
                }
                throw failure
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private val closing = Mutex()
    private var drained = false

    /** Stop network admission, finish accepted work, then close storage and providers in dependency order. */
    suspend fun drain() = withContext(NonCancellable) {
        closing.withLock {
            if (drained) return@withLock
            var failure: Throwable? = null
            suspend fun attempt(close: suspend () -> Unit) {
                try { close() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
                }
            }
            attempt(listener::drain)
            attempt(node::drain)
            twins?.let { attempt(it::drain) }
            attempt(persistence::drain)
            attempt(htx::close)
            // A failed component close is not a successful drain; stop any child it left behind before joining.
            if (failure == null) supervisor.complete() else supervisor.cancel(CancellationException("Mesh runtime cleanup failed", failure))
            supervisor.join()
            drained = true
            failure?.let { throw it }
        }
    }
}
