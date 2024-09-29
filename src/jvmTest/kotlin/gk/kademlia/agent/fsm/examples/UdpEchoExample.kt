package gk.kademlia.agent.fsm.examples

import gk.kademlia.agent.fsm.FSM
import gk.kademlia.agent.fsm.ReadNode
import gk.kademlia.agent.fsm.WriteNode
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 *
 * simple demo that echo's back the first 40 bytes or clearss the echo buffer.
 *
 * $ nc -u :: 2112
aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
 */
class UdpEchoExample {
    companion object {
        @JvmStatic
        fun main(vararg args: String) {

            val buf: ByteBuffer = ByteBuffer.allocate(20)
            lateinit var top: ReadNode
            top = ReadNode {
                val datagramChannel = it.channel() as DatagramChannel
                val sa = datagramChannel.receive(buf)
                if (buf.hasRemaining()) {
                    buf.clear()
                    top
                } else {
                    WriteNode {
                        datagramChannel.send(buf.flip(), sa)
                        if (!buf.hasRemaining()) {
                            buf.clear()
                            top
                        } else null
                    }
                }
            }
            FSM.Companion.launch(top, channel = DatagramChannel.open())
        }

    }
}