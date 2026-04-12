package one.xio.spi

import borg.trikeshed.common.System
import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.detectProtocol
import borg.trikeshed.net.spi.IngressSteeringDecision
import borg.trikeshed.net.spi.SteeringSource
import borg.trikeshed.net.spi.TransportCapabilities
import borg.trikeshed.net.spi.TransportEvent
import borg.trikeshed.net.spi.TransportRegistration
import borg.trikeshed.net.spi.TransportBackendKind
3
data class LinuxNativeBackendConfig(
    val enableIoUring: Boolean = true,
    val enableXdpSteering: Boolean = true,
    val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
)

/**
 * Linux-oriented backend façade.
 *
 * Until the JNI/liburing bridge is landed on the JVM path, this backend probes
 * Linux-native intent and adapts readiness/completion flow through the selector
 * backend.
 */
class LinuxNativeTransportBackend(
    private val config: LinuxNativeBackendConfig = LinuxNativeBackendConfig(),
    private val fallback: SelectorTransportBackend = SelectorTransportBackend(),
) : NioTransportBackend {
    override val kind: TransportBackendKind = TransportBackendKind.LINUX_NATIVE

    override fun capabilities(): TransportCapabilities {
        val nativeLinux = System.getenv("os.name", "false").equals("Linux", ignoreCase = true)
        val ioUringAvailable = nativeLinux && config.enableIoUring &&
            System.getenv("trikeshed.transport.linux.native", "false").toBoolean()
        val xdpAvailable = nativeLinux && config.enableXdpSteering &&
            System.getenv("trikeshed.transport.linux.xdp", "false").toBoolean()
        return TransportCapabilities(
            backendKind = kind,
            nativeLinux = nativeLinux,
            ioUringRequested = config.enableIoUring,
            ioUringAvailable = ioUringAvailable,
            xdpSteeringRequested = config.enableXdpSteering,
            xdpAvailable = xdpAvailable,
        )
    }

    override fun classifyIngress(payload: ByteArray): IngressSteeringDecision {
        val capabilities = capabilities()
        val protocol = detectProtocol(payload)
        val queueId = when (protocol) {
            ProtocolId.HTTP -> 0
            ProtocolId.QUIC -> 1
            ProtocolId.SSH -> 2
            ProtocolId.UNKNOWN -> 3
        }
        val workerId = queueId % config.workerCount
        val source = if (capabilities.xdpAvailable) SteeringSource.XDP else SteeringSource.SOFTWARE_FALLBACK
        return IngressSteeringDecision(protocol, queueId, workerId, source)
    }

    override fun register(
        channel: SelectableChannel,
        selector: Selector,
        registration: TransportRegistration,
    ): SelectionKey = fallback.register(channel, selector, registration)

    override fun readyEvent(key: SelectionKey): TransportEvent = fallback.readyEvent(key)

    override fun close(channel: SelectableChannel) {
        fallback.close(channel)
    }

    override fun isOpen(channel: SelectableChannel): Boolean = fallback.isOpen(channel)

    override suspend fun read(channel: ReadableByteChannel, buffer: ByteBuffer): Int =
        fallback.read(channel, buffer)

    override suspend fun write(channel: WritableByteChannel, buffer: ByteBuffer): Int =
        fallback.write(channel, buffer)

    override suspend fun dispatch(
        selector: Selector,
        timeoutMillis: Long,
        onReady: suspend (SelectionKey) -> Unit,
    ): Int = fallback.dispatch(selector, timeoutMillis, onReady)
}
