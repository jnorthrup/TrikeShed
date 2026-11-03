package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LitebikeListenerOverloadTest {
    @Test fun trailingResponseLambdasRemainSupportedForBothProtocolForms() = runTest {
        val listener = LitebikeListenerElement(parentJob = coroutineContext[Job]).also { it.open() }
        val slot = listener.register(Protocol.Http)
        try {
            val first = CompletableDeferred<ByteArray>()
            assertTrue(listener.accept(Protocol.Http, "first".encodeToByteArray()) { answer -> first.complete(answer) })
            val firstMessage = slot.consume()
            assertContentEquals("first".encodeToByteArray(), firstMessage.payload)
            assertNotNull(firstMessage.respond).invoke("one".encodeToByteArray())
            assertContentEquals("one".encodeToByteArray(), first.await())

            val second = CompletableDeferred<ByteArray>()
            assertTrue(listener.accept(Protocol.Http.id, "second".encodeToByteArray()) { answer -> second.complete(answer) })
            val secondMessage = slot.consume()
            assertContentEquals("second".encodeToByteArray(), secondMessage.payload)
            assertNotNull(secondMessage.respond).invoke("two".encodeToByteArray())
            assertContentEquals("two".encodeToByteArray(), second.await())
        } finally { listener.close() }
    }
}
