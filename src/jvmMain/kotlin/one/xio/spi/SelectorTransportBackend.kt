package one.xio.spi

import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.detectProtocol
import borg.trikeshed.net.spi.IngressSteeringDecision
import borg.trikeshed.net.spi.ReadinessInterest
import borg.trikeshed.net.spi.SteeringSource
import borg.trikeshed.net.spi.TransportCapabilities
import borg.trikeshed.net.spi.TransportEvent
import borg.trikeshed.net.spi.TransportRegistration
import borg.trikeshed.net.spi.TransportBackendKind
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SelectableChannel
import java.nio.channels.Selector
import java.nio.channels.WritableByteChannel

/**
 * Default portable JVM backend backed by Selector/NIO.
 */
class SelectorTransportBackend : NioTransportBackend {
    override val kind: TransportBackendKind = TransportBackendKind.SELECTOR

    override fun capabilities(): TransportCapabilities = TransportCapabilities(
        backendKind = kind,
        nativeLinux = false,
        ioUringRequested = false,
        ioUringAvailable = false,
        xdpSteeringRequested = false,
        xdpAvailable = false,
    )

    override fun classifyIngress(payload: ByteArray): IngressSteeringDecision {
        val protocol = detectProtocol(payload)
        val queueId = when (protocol) {
            ProtocolId.HTTP -> 0
            ProtocolId.QUIC -> 1
            ProtocolId.SSH -> 2
            ProtocolId.UNKNOWN -> 3
        }
        return IngressSteeringDecision(
            protocol = protocol,
            queueId = queueId,
            workerId = 0,
            source = SteeringSource.SOFTWARE_FALLBACK,
        )
    }

    override fun register(
        channel: SelectableChannel,
        selector: Selector,
        registration: TransportRegistration,
    ): SelectionKey {
        channel.configureBlocking(false)
        return channel.register(selector, registration.toSelectionOps(), registration.attachment)
    }

    override fun readyEvent(key: SelectionKey): TransportEvent {
        val interests = buildSet {
            if (key.isReadable) add(ReadinessInterest.READ)
            if (key.isWritable) add(ReadinessInterest.WRITE)
            if (key.isAcceptable) add(ReadinessInterest.ACCEPT)
            if (key.isConnectable) add(ReadinessInterest.CONNECT)
        }
        return TransportEvent(interests = interests, attachment = key.attachment())
    }

    override fun close(channel: SelectableChannel) {
        channel.close()
    }

    override fun isOpen(channel: SelectableChannel): Boolean = channel.isOpen

    override suspend fun read(channel: ReadableByteChannel, buffer: ByteBuffer): Int =
        channel.read(buffer)

    override suspend fun write(channel: WritableByteChannel, buffer: ByteBuffer): Int =
        channel.write(buffer)

    override suspend fun dispatch(
        selector: Selector,
        timeoutMillis: Long,
        onReady: suspend (SelectionKey) -> Unit,
    ): Int {
        val readyCount = if (timeoutMillis <= 0) selector.selectNow() else selector.select(timeoutMillis)
        if (readyCount <= 0) {
            return 0
        }

        val selected = selector.selectedKeys().iterator()
        while (selected.hasNext()) {
            val key = selected.next()
            selected.remove()
            if (key.isValid) {
                onReady(key)
            }
        }
        return readyCount
    }
}

private fun TransportRegistration.toSelectionOps(): Int {
    var ops = 0
    if (ReadinessInterest.READ in interests) ops = ops or SelectionKey.OP_READ
    if (ReadinessInterest.WRITE in interests) ops = ops or SelectionKey.OP_WRITE
    if (ReadinessInterest.ACCEPT in interests) ops = ops or SelectionKey.OP_ACCEPT
    if (ReadinessInterest.CONNECT in interests) ops = ops or SelectionKey.OP_CONNECT
    return ops
}
