package borg.trikeshed.reactor

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.context.nuid.subnet
import borg.trikeshed.context.nuid.Nuid
import kotlinx.coroutines.withTimeout

// Stub for KademliaNode as it isn't in master yet
interface KademliaNode {
    suspend fun lookup(subnet: String): List<PeerAddress>
}

open class MeshReactorEndpoint(
    private val dht: KademliaNode,
    private val config: MeshConfig = MeshConfig(),
) {
    suspend fun invoke(action: ReactorAction): MeshActionResult {
        // Find NUID according to ReactorAction format
        // This handles both the legacy Join and the sealed class (which the prompt implies with action.a.a.subnet)
        // Wait, action is explicitly of type ReactorAction.
        // We handle sealed class.
        val nuid = when(action) {
            is ReactorAction.Opened -> action.nuid
            is ReactorAction.Activated -> action.nuid
            is ReactorAction.PublishEntity -> action.nuid
            is ReactorAction.Draining -> action.nuid
            is ReactorAction.Closed -> action.nuid
        }

        val subnetStr = nuid.subnet.toString()
        val actualPeers = dht.lookup(subnetStr)

        if (actualPeers.isEmpty()) return MeshActionResult.Failed(MeshErrorCode.PEER_NOT_FOUND)

        val frame = MeshActionFrame.encode(action)
        if (frame.payload.size > config.maxPayloadBytes) {
            return MeshActionResult.Failed(MeshErrorCode.PAYLOAD_TOO_LARGE)
        }

        repeat(config.maxRetries) { attempt ->
            val peer = actualPeers[attempt % actualPeers.size]
            val response = try {
                withTimeout(config.timeoutMs) {
                    sendFrame(peer, frame)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                MeshActionResult.TimedOut
            }
            when (response) {
                is MeshActionResult.Ok -> return response
                is MeshActionResult.Failed -> {
                    if (response.code == MeshErrorCode.TIMEOUT) return@repeat
                    return response
                }
                MeshActionResult.TimedOut -> return@repeat
            }
        }
        return MeshActionResult.Failed(MeshErrorCode.TIMEOUT)
    }

    protected open suspend fun sendFrame(peer: PeerAddress, frame: MeshActionFrame): MeshActionResult {
        return MeshActionResult.Failed(MeshErrorCode.BAD_FRAME)
    }
}
