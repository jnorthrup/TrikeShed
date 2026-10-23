package gk.kademlia.agent.fsm

import java.nio.channels.SelectionKey.OP_ACCEPT
import java.nio.channels.ServerSocketChannel

class AcceptNode(override val process: KeyAction) : FsmNode {
    override val interest: Int = OP_ACCEPT

    companion object {
        fun genericAcceptor(handlerFactory: () -> FsmNode) = AcceptNode {
            val tcpInbound = (it.channel() as ServerSocketChannel).accept().configureBlocking(false)
            handlerFactory().run { tcpInbound.register(it.selector(), interest, this) }
            null
        }
    }
}