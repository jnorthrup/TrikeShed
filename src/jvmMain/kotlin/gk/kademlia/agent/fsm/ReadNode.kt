package gk.kademlia.agent.fsm

import java.nio.channels.SelectionKey.OP_READ

class ReadNode(override val process: KeyAction) : FsmNode {
    override val interest: Int = OP_READ
}

