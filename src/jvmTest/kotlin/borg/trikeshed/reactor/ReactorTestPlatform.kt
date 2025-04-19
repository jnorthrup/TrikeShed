package borg.trikeshed.reactor

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer

actual class TestReactorPlatform {
    actual fun createServerSocket(): SelectableChannel {
        return ServerSocketChannel.open().apply {
            configureBlocking(false)
            bind(InetSocketAddress(0))
        }
    }

    actual fun createClientSocket(port: Int): SelectableChannel {
        return SocketChannel.open().apply {
            configureBlocking(false)
            connect(InetSocketAddress("localhost", port))
        }
    }

    actual fun getLocalPort(serverChannel: SelectableChannel): Int {
        return (serverChannel as ServerSocketChannel).socket().localPort
    }

    actual fun writeToChannel(channel: SelectableChannel, data: ByteArray) {
        (channel as SocketChannel).write(ByteBuffer.wrap(data))
    }

    actual fun readFromChannel(channel: SelectableChannel): ByteArray {
        val buffer = ByteBuffer.allocate(8192)
        val bytesRead = (channel as SocketChannel).read(buffer)
        buffer.flip()
        return ByteArray(bytesRead).apply { buffer.get(this) }
    }
}
