package gk.kademlia.codec

//import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import borg.trikeshed.lib.j
import gk.kademlia.agent.fsm.ReifiedMessage
import gk.kademlia.agent.fsm.SimpleMessage
import gk.kademlia.agent.fsm.reify
import gk.kademlia.agent.fsm.virtualize
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer


object SmCodec : Codec<SimpleMessage, ByteBuffer> {
    override fun send(event: SimpleMessage): ByteBuffer = event.let { (hdr, body) ->
        val s = hdr.joinToString("\n", postfix = "\n") { (a, c) -> "$a: $c" }
        ByteBuffer.wrap(s.toByteArray(Charsets.UTF_8) + body.toByteArray(Charsets.UTF_8))
    }

    override fun recv(ser: ByteBuffer): SimpleMessage? =
        ser.asCharBuffer().toString().split("\n\n".toRegex(), 2).takeUnless { it.size != 2 }?.let { (hdrs, bod) ->
            hdrs.split("\n").map { it.split(":\\s?".toRegex(), 2).let { (a, b) -> a j b } } j bod
        }
}

object SmJson : Codec<SimpleMessage, ByteBuffer> {
    override fun send(event: SimpleMessage): ByteBuffer {
        val serializer = event.reify
        return ByteBuffer.wrap(Json.Default.encodeToString(serializer).encodeToByteArray())
    }

    override fun recv(ser: ByteBuffer): SimpleMessage {
        val byteArray = ByteArray(ser.remaining())
        ser.put(byteArray)
        val string = String(byteArray, Charsets.UTF_8)
        val rm = Json.decodeFromString<ReifiedMessage>(string)
        return rm.virtualize
    }
}

//
//object SmMsgPack : Codec<SimpleMessage, ByteBuffer> {
//    override fun send(event: SimpleMessage): ByteBuffer =
//        ByteBuffer.wrap(MsgPack.Default.encodeToByteArray(event.reify))
//
//    override fun recv(ser: ByteBuffer): SimpleMessage {
//        val byteArray = ByteArray(ser.remaining())
//        ser.put(byteArray)
//        val pair = MsgPack.Default.decodeFromByteArray<ReifiedMessage>(byteArray)
//        return pair.virtualize
//    }
//}