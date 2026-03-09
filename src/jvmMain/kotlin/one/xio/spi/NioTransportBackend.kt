package one.xio.spi

import borg.trikeshed.net.spi.TransportEvent
import borg.trikeshed.net.spi.TransportRegistration
import borg.trikeshed.net.spi.TransportSpi
import borg.trikeshed.net.spi.TransportBackendKind
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SelectableChannel
import java.nio.channels.Selector
import java.nio.channels.WritableByteChannel

/**
 * Internal JVM transport SPI.
 *
 * The public portable boundary remains raw Java NIO types; this SPI only
 * coordinates backend strategy and coroutine dispatch inside the JVM runtime.
 */
interface NioTransportBackend : TransportSpi {

    fun openSelector(): Selector = Selector.open()

    fun register(
        channel: SelectableChannel,
        selector: Selector,
        registration: TransportRegistration,
    ): SelectionKey

    fun readyEvent(key: SelectionKey): TransportEvent

    fun close(channel: SelectableChannel)

    fun isOpen(channel: SelectableChannel): Boolean

    suspend fun read(channel: ReadableByteChannel, buffer: ByteBuffer): Int

    suspend fun write(channel: WritableByteChannel, buffer: ByteBuffer): Int

    suspend fun dispatch(
        selector: Selector,
        timeoutMillis: Long = 0,
        onReady: suspend (SelectionKey) -> Unit,
    ): Int
}
