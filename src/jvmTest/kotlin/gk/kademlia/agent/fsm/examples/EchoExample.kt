package gk.kademlia.agent.fsm.examples

import gk.kademlia.agent.fsm.AcceptNode
import gk.kademlia.agent.fsm.FSM.Companion.launch
import gk.kademlia.agent.fsm.ReadNode
import gk.kademlia.agent.fsm.WriteNode
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class EchoExample {
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            //typical boilerplate
            launch(echoAcceptor())
        }


        fun echoAcceptor(): AcceptNode = AcceptNode {
            (it.channel() as ServerSocketChannel).accept().let { accept ->
                accept.configureBlocking(false)
                val buf = ByteBuffer.allocateDirect(80)
                val fsmNode = echoReader(buf)
                accept.register(it.selector(), fsmNode.interest, fsmNode)
                null
            }
        }

        private fun echoReader(buf: ByteBuffer): ReadNode = ReadNode {
            (it.channel() as SocketChannel).let { socketChannel: SocketChannel ->
                val read = socketChannel.read(buf)
                if (!buf.hasRemaining() || read == -1) {
                    buf.flip()
                    echoWriter(buf)
                } else null
            }
        }

        private fun echoWriter(
            buf: ByteBuffer,
        ): WriteNode = WriteNode {
            (it.channel() as SocketChannel).let { socketChannel ->
                if (buf.hasRemaining()) socketChannel.write(buf) else socketChannel.close()
                null
            }
        }
    }
}