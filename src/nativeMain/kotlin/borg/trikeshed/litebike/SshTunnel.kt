package borg.trikeshed.litebike

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class SshTunnel(
    override val id: String,
    override val protocol: Protocol,
    override val remoteHost: String,
    override val remotePort: Int
) : Tunnel {
    override suspend fun connect() {
        println("Connecting SSH Tunnel to $remoteHost:$remotePort")
    }

    override suspend fun close() {
        println("Closing SSH Tunnel to $remoteHost:$remotePort")
    }

    override fun read(): Flow<ByteArray> = emptyFlow()

    override suspend fun write(data: ByteArray) {}
}
