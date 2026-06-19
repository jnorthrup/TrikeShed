package borg.trikeshed.htx.client.spi

import borg.trikeshed.htx.client.HtxTarget
import kotlinx.coroutines.flow.Flow

interface NetworkConnection {
    suspend fun write(data: ByteArray)
    fun read(): Flow<ByteArray>
    suspend fun close()
}

interface NetworkTransportSpi {
    // TODO(htx-reactor): replace raw byte connections with a reactor/CCEK transport that
    // can negotiate HTTP, HTTPS/TLS, and IPFS sessions without hiding protocol identity.
    // TODO(htx-downstream): downstream reads should be channelized HTX/TLS frames, not opaque
    // byte chunks, once the root HTX/reactor move is complete and this lib can be deleted.
    suspend fun connect(target: HtxTarget): NetworkConnection
}
