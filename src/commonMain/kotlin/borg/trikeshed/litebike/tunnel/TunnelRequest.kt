package borg.trikeshed.litebike.tunnel

import borg.trikeshed.litebike.taxonomy.Protocol

/**
 * Reactor request to open a tunnel.
 */
data class TunnelRequest(
    val protocol: Protocol,
    val remoteHost: String,
    val remotePort: Int,
    val options: Map<String, String> = emptyMap()
)

/**
 * Tunnel response wrapping a tunnel instance or an error.
 */
sealed class TunnelResponse {
    data class Success(val tunnelId: String) : TunnelResponse()
    data class Failure(val reason: String) : TunnelResponse()
}
