package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * TunnelBackend provides a factory or SPI to create Tunnels.
 */
interface TunnelBackend {
    fun createTunnel(
        id: String,
        protocol: Protocol,
        remoteHost: String,
        remotePort: Int
    ): Tunnel
}

/**
 * Mock implementation of a Tunnel for commonMain.
 */
class MockTunnel(
    override val id: String,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {
    private var isConnected = false

    override suspend fun connect() {
        isConnected = true
    }

    override suspend fun close() {
        isConnected = false
    }

    override fun read(): Flow<ByteArray> {
        return emptyFlow()
    }

    override suspend fun write(data: ByteArray) {
        // Mock tunnel does nothing on write
    }
}

/**
 * Mock implementation of TunnelBackend.
 */
object MockTunnelBackend : TunnelBackend {
    override fun createTunnel(id: String, protocol: Protocol, remoteHost: String, remotePort: Int): Tunnel {
        return MockTunnel(id, protocol, remoteHost, remotePort)
    }
}
