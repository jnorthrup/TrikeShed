package borg.trikeshed.htx.client.spi

import kotlinx.coroutines.flow.Flow

interface NetworkConnection {
    suspend fun write(data: ByteArray)
    fun read(): Flow<ByteArray>
    suspend fun close()
}

interface NetworkTransportSpi {
    suspend fun connect(host: String, port: Int): NetworkConnection
}
