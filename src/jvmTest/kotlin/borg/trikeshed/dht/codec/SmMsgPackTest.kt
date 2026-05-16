package borg.trikeshed.dht.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

typealias ReifiedMessage = Pair<List<Pair<CharSequence, CharSequence>>, CharSequence>

val debug = { }

fun send(event: ReifiedMessage): ByteBuffer =
    ByteBuffer.wrap(
        ByteArrayOutputStream().use { out ->
            ObjectOutputStream(out).use { it.writeObject(event) }
            out.toByteArray()
        }
    )

fun recv(ser: ByteBuffer): ReifiedMessage {
    val byteArray = ByteArray(ser.remaining())
    ser.get(byteArray)
    return ByteArrayInputStream(byteArray).use { input ->
        @Suppress("UNCHECKED_CAST")
        ObjectInputStream(input).use { it.readObject() as ReifiedMessage }
    }
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
