package gk.kademlia.agent.fsm

import java.nio.channels.SelectionKey

class Terminal(val housekeeping: ((SelectionKey) -> Unit)? = Companion::defaultCloseOp) : FsmNode {
    override val interest: Int = (0x7fff_ffff)
    override val process: KeyAction
        get() = {
            housekeeping?.invoke(it)
            null
        }

    companion object {
        fun defaultCloseOp(it: SelectionKey) {
            it.apply { channel().close();cancel() };Unit
        }
    }
}