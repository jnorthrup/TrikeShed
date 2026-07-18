package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.flow.Flow

/**
 * Port of litebike's `Tunnel` interface for port forwarding/proxying over a transport.
 */
interface Tunnel {
    val id: String
    val protocol: Protocol
    val remoteHost: String
    val remotePort: Int

    /**
     * Open a connection through the tunnel.
     * Implementations might use SSH, SOCKS5, or Shadowsocks.
     */
    suspend fun connect()

    /**
     * Closes the tunnel connection.
     */
    suspend fun close()

    /**
     * Reads data from the tunnel.
     */
    fun read(): Flow<ByteArray>

    /**
     * Writes data to the tunnel.
     */
    suspend fun write(data: ByteArray)
}
