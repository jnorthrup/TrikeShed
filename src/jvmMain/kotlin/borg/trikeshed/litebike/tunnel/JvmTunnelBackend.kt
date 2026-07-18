package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow


class JvmSshTunnel(
    override val id: String,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {
    private var process: Any? = null

    override suspend fun connect() {
        // Mock native SSH exec round-trip
        println("Opening JVM SSH Tunnel to $remoteHost:$remotePort")
    }

    override suspend fun close() {
        println("Closing JVM SSH Tunnel to $remoteHost:$remotePort")
    }

    override fun read(): Flow<ByteArray> {
        return emptyFlow()
    }

    override suspend fun write(data: ByteArray) {
    }
}

object JvmTunnelBackend : TunnelBackend {
    override fun createTunnel(id: String, protocol: Protocol, remoteHost: String, remotePort: Int): Tunnel {
        return JvmSshTunnel(id, protocol, remoteHost, remotePort)
    }
}
