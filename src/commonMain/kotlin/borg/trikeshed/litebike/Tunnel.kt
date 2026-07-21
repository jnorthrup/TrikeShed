package borg.trikeshed.litebike

import kotlinx.coroutines.flow.Flow

interface Tunnel {
    val id: String
    val protocol: Protocol
    val remoteHost: String
    val remotePort: Int

    suspend fun connect()
    suspend fun close()
    fun read(): Flow<ByteArray>
    suspend fun write(data: ByteArray)
}
