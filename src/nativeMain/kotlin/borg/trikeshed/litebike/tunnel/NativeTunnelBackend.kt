package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class NativeSshTunnel(
    override val id: String,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {
    override suspend fun connect() {
        println("Opening Native SSH Tunnel to $remoteHost:$remotePort")
    }

    override suspend fun close() {
        println("Closing Native SSH Tunnel to $remoteHost:$remotePort")
    }

    override fun read(): Flow<ByteArray> {
        return emptyFlow()
    }

    override suspend fun write(data: ByteArray) {
    }
}

object NativeTunnelBackend : TunnelBackend {
    override fun createTunnel(id: String, protocol: Protocol, remoteHost: String, remotePort: Int): Tunnel {
        return NativeSshTunnel(id, protocol, remoteHost, remotePort)
    }
}
