package borg.trikeshed.reactor

import borg.trikeshed.context.Drain
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.forEach
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalAtomicApi::class)
private val nextTlsConnectionOrdinal = AtomicLong(1L)

interface TlsEndpoint {
    val route: TlsRoute
    val flowState: TlsFlowState
    val session: TlsSession? get() = flowState.session

    suspend fun handshake(): TlsFrames
    suspend fun upstream(payload: TlsPayload): TlsFrames
    suspend fun downstream(payload: TlsPayload): TlsFrames
    suspend fun close(): TlsFrames

    val role: TlsRole get() = route.a
    val remoteHost: String get() = route.b.a
    val remotePort: Int get() = route.b.b

    val isHandshakeComplete: Boolean
        get() = flowState.lifecycle == TlsConnectionState.OPEN && session != null
}

@OptIn(ExperimentalAtomicApi::class)
class TlsElement(
    val config: TlsConfig,
    private val backend: TlsCodecBackend,
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
    val fanoutSubscribers: List<CoroutineContext.Element> = emptyList(),
) : CoroutineContext.Element, Drain {
    companion object Key : CoroutineContext.Key<TlsElement>

    override val key: CoroutineContext.Key<*> get() = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    private val endpoints = linkedSetOf<BackendTlsEndpoint>()

    fun clientEndpoint(hostname: String, port: Int = 443): TlsEndpoint =
        register(TlsRole.CLIENT j (hostname j port))

    fun serverEndpoint(bindHost: String = "0.0.0.0", port: Int = 443): TlsEndpoint =
        register(TlsRole.SERVER j (bindHost j port))

    private fun register(route: TlsRoute): TlsEndpoint =
        BackendTlsEndpoint(
            owner = this,
            config = config,
            backend = backend,
            initialState = TlsFlowState(
                route = route,
                connectionOrdinal = nextTlsConnectionOrdinal.fetchAndAdd(1L),
            ),
        ).also { endpoints += it }

    internal fun release(endpoint: TlsEndpoint) {
        if (endpoint is BackendTlsEndpoint) {
            endpoints -= endpoint
        }
    }

    internal suspend fun channelize(frames: TlsFrames) {
        // Bolt: Prevent intermediate List allocations with filterIsInstance<T>()
        fanoutSubscribers.forEach {
            if (it is TlsChannelSubscriber) {
                it.onTlsFrames(frames)
            }
        }

        // Bolt: Prevent intermediate List allocations with filterIsInstance<T>()
        // and avoid O(F*S) scaling by fast-path checking subscriber presence first
        if (fanoutSubscribers.any { it is FanoutEventSubscriber }) {
            frames.forEach { frame ->
                fanoutSubscribers.forEach {
                    if (it is FanoutEventSubscriber) {
                        it.onFanoutEvent(frame)
                    }
                }
            }
        }
    }

    override suspend fun drain() {
        endpoints.toList().forEach { it.close() }
        supervisor.complete()
        supervisor.join()
        close()
    }

    suspend fun close() {
        endpoints.toList().forEach { it.close() }
        endpoints.clear()
        ownedSupervisor?.close()
        supervisor.cancelAndJoin()
    }
}

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    backend: TlsCodecBackend,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<CoroutineContext.Element> = emptyList(),
): TlsElement =
    TlsElement(
        config = config,
        backend = backend,
        parentJob = parentJob,
        fanoutSubscribers = subscribers,
    )

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    nioSupervisor: NioSupervisor? = null,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<CoroutineContext.Element> = emptyList(),
): TlsElement {
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (!activeSupervisor.opened.isCompleted && activeSupervisor.admission.isActive) {
        activeSupervisor.open()
    }

    val backend = activeSupervisor.service<TlsCodecBackend>()
        ?: error("TlsElement requires TlsCodecBackend in NioSupervisor. Open the supervisor before installing TLS.")

    return TlsElement(
        config = config,
        backend = backend,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
        fanoutSubscribers = subscribers,
    )
}

private class BackendTlsEndpoint(
    private val owner: TlsElement,
    private val config: TlsConfig,
    private val backend: TlsCodecBackend,
    initialState: TlsFlowState,
) : TlsEndpoint {
    /** Completes at the first [close]; the codec API ends with it. */
    private val closed: CompletableJob = Job()

    override var flowState: TlsFlowState = initialState
        private set

    override val route: TlsRoute
        get() = flowState.route

    override suspend fun handshake(): TlsFrames {
        ensureOpen()
        return apply(backend.handshake(config, flowState))
    }

    override suspend fun upstream(payload: TlsPayload): TlsFrames {
        ensureOpen()
        return apply(backend.upstream(config, flowState, payload))
    }

    override suspend fun downstream(payload: TlsPayload): TlsFrames {
        ensureOpen()
        return apply(backend.downstream(config, flowState, payload))
    }

    override suspend fun close(): TlsFrames {
        if (!closed.complete()) {
            return emptyTlsFrames()
        }
        val frames = apply(backend.close(config, flowState))
        owner.release(this)
        return frames
    }

    private fun ensureOpen() {
        check(!closed.isCompleted) { "TLS endpoint is closed" }
    }

    private suspend fun apply(result: TlsCodecResult): TlsFrames {
        flowState = result.state
        owner.channelize(result.frames)
        return result.frames
    }
}
