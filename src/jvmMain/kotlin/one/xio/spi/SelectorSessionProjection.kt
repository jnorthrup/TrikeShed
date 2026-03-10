package one.xio.spi

import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.channelization.ChannelSession
import borg.trikeshed.net.channelization.ChannelSessionId
import borg.trikeshed.net.channelization.ChannelSessionState
import borg.trikeshed.net.spi.IngressSteeringDecision

/**
 * Thin bridge that projects NIO selector backend bytes into a semantic ChannelSession.
 *
 * This class translates raw bytes from the NIO selector backend into a
 * ChannelSession bound to the detected protocol. It acts as a projection
 * layer that keeps NIO types (Selector, SelectionKey, SelectableChannel)
 * hidden from the public API.
 *
 * IMPORTANT: This projection does NOT activate the graph/job layer - it only
 * creates and returns a session ready for graph/job activation by callers.
 *
 * Hard rules:
 * - Must NOT expose java.nio.channels.Selector, SelectionKey, or SelectableChannel in public API
 * - Must NOT import borg.trikeshed.net.channelization.* graph types - only session/block types allowed
 */
class SelectorSessionProjection(
    private val backend: SelectorTransportBackend,
) {

    /**
     * Project raw bytes into a ChannelSession bound to the detected protocol.
     *
     * @param bytes The raw bytes received from the NIO selector backend
     * @return A ChannelSession bound to the detected protocol, ready for graph/job activation
     * @throws IllegalArgumentException if the protocol cannot be determined
     */
    fun projectSession(bytes: ByteArray): ChannelSession {
        // Call the backend to classify the ingress bytes
        val decision = backend.classifyIngress(bytes)

        // Create a session bound to the detected protocol
        return createSessionForProtocol(decision.protocol)
    }

    /**
     * Project raw bytes and return both the session and steering decision.
     *
     * This overload is useful when callers need access to queue/worker assignment
     * from the ingress steering decision.
     *
     * @param bytes The raw bytes received from the NIO selector backend
     * @return Pair of ChannelSession and IngressSteeringDecision
     */
    fun projectSessionWithDecision(bytes: ByteArray): Pair<ChannelSession, IngressSteeringDecision> {
        val decision = backend.classifyIngress(bytes)
        val session = createSessionForProtocol(decision.protocol)
        return session to decision
    }

    /**
     * Get the backend capabilities without exposing NIO types.
     *
     * @return Transport backend kind as a string for logging/display
     */
    fun backendKind(): String = backend.capabilities().backendKind.name

    /**
     * Check if the backend is available and ready.
     *
     * @return true if the backend can accept new sessions
     */
    fun isAvailable(): Boolean = true // Selector backend is always available when this projection exists

    private fun createSessionForProtocol(protocol: ProtocolId): ChannelSession {
        return object : ChannelSession {
            override val id: ChannelSessionId = ChannelSessionId("session-${protocol.name}-${System.nanoTime()}")

            override val protocol: ProtocolId = protocol

            override var state: ChannelSessionState = ChannelSessionState.Initialized

            override fun transitionTo(newState: ChannelSessionState) {
                state = newState
            }

            override fun canAcceptFrames(): Boolean = state == ChannelSessionState.Active
        }
    }
}
