package gk.kademlia.codec

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import junit.framework.TestCase
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.nio.ByteBuffer


typealias ReifiedMessage = Pair<List<Pair<String, String>>, String>

val debug = { }

fun send(event: ReifiedMessage) =
    ByteBuffer.wrap(MsgPack.Default.encodeToByteArray(event))

fun recv(ser: ByteBuffer): ReifiedMessage {
    val byteArray = ByteArray(ser.remaining())
    ser.put(byteArray)
    val decodeFromByteArray = MsgPack.Default.decodeFromByteArray<ReifiedMessage>(byteArray)
    debug()
    return decodeFromByteArray
}

class SmMsgPackTest : TestCase() {

//    val sm1: SimpleMessage = _v["Alice" t2 "Bob",
//            "Charley" t2 "Delta"] t2 "Random message Body here"
//    fun testMsgPack() {
//        val byteBuffer = SmMsgPack.send(sm1)
//        debug{}
//        val done=SmMsgPack.recv(byteBuffer!!)
//        debug{}
//    }

    val sm1: ReifiedMessage = listOf("Alice" to "Bob", "Charley" to "Delta") to "Random message Body here"

    fun testMsgPack() {
        val byteBuffer = send(sm1)
        debug()
        val done = recv(byteBuffer!!)
        debug()


    }
}

fun main() {
    SmMsgPackTest().testMsgPack()
}