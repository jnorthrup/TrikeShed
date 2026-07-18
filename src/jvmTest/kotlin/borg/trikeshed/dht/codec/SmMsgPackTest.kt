package borg.trikeshed.dht.codec

import java.nio.ByteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals


typealias ReifiedMessage = Pair<List<Pair<String, String>>, String>

val debug = { }

fun send(event: ReifiedMessage) =
    ByteBuffer.wrap(
        ByteArrayOutputStream().use { out ->
            ObjectOutputStream(out).use { it.writeObject(event) }
            out.toByteArray()
        }
    )

fun recv(ser: ByteBuffer): ReifiedMessage {
    val byteArray = ByteArray(ser.remaining())
    ser.get(byteArray)
    val decodeFromByteArray = ByteArrayInputStream(byteArray).use { input ->
        ObjectInputStream(input).use { it.readObject() as ReifiedMessage }
    }
    debug()
    return decodeFromByteArray
}

class SmMsgPackTest {

    val sm1: ReifiedMessage = listOf("Alice" to "Bob", "Charley" to "Delta") to "Random message Body here"

    @Test
    fun testMsgPack() {
        val byteBuffer = send(sm1)
        debug()
        val done = recv(byteBuffer)
        debug()
        assertEquals(sm1, done)
    }
}

fun main() {
    SmMsgPackTest().testMsgPack()
}
