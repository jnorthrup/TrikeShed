package borg.trikeshed.net.spi

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.net.ProtocolId
import kotlin.coroutines.CoroutineContext

enum class TransportBackendKind {
    SELECTOR,
    LINUX_NATIVE,
}

enum class ReadinessInterest {
    READ,
    WRITE,
    ACCEPT,
    CONNECT,
}

data class TransportRegistration(
    val interests: Set<ReadinessInterest>,
    val attachment: Any? = null,
)

data class TransportEvent(
    val interests: Set<ReadinessInterest>,
    val attachment: Any? = null,
)

enum class SteeringSource {
    XDP,
    SOFTWARE_FALLBACK,
}

data class IngressSteeringDecision(
    val protocol: ProtocolId,
    val queueId: Int,
    val workerId: Int,
    val source: SteeringSource,
)

data class TransportCapabilities(
    val backendKind: TransportBackendKind,
    val nativeLinux: Boolean,
    val ioUringRequested: Boolean,
    val ioUringAvailable: Boolean,
    val xdpSteeringRequested: Boolean,
    val xdpAvailable: Boolean,
)

/**
 * Portable transport SPI for commonMain orchestration.
 *
 * Backends can adapt this SPI to raw NIO, native liburing, or other runtime
 * surfaces without leaking those types across the common boundary.
 */
interface TransportSpi {
    val kind: TransportBackendKind

    fun capabilities(): TransportCapabilities

    fun classifyIngress(payload: ByteArray): IngressSteeringDecision
}

data class TransportBackendService(
    val backend: TransportSpi,
) : KeyedService {
    companion object Key : CoroutineContext.Key<TransportBackendService>
    override val key: CoroutineContext.Key<*> get() = Key
}
