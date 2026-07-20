package borg.trikeshed.reactor

import borg.trikeshed.lcnc.reactor.ReactorAction

interface SctpReactorEndpoint {
    suspend fun bind(port: Int): Int
    suspend fun send(peer: PeerAddress, action: ReactorAction): MeshActionResult
    suspend fun receive(): Pair<PeerAddress, ReactorAction>
    suspend fun close()
}

data class PeerAddress(val host: String, val port: Int)
