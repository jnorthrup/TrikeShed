package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class JsTunnel(
    override val id: String,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {
    override suspend fun connect() {
        println("Opening JS Tunnel to $remoteHost:$remotePort")
    }

    override suspend fun close() {
        println("Closing JS Tunnel to $remoteHost:$remotePort")
    }

    override fun read(): Flow<ByteArray> {
        return emptyFlow()
    }

    override suspend fun write(data: ByteArray) {
    }
}

object JsTunnelBackend : TunnelBackend {
    override fun createTunnel(id: String, protocol: Protocol, remoteHost: String, remotePort: Int): Tunnel {
        return JsTunnel(id, protocol, remoteHost, remotePort)
    }
}
