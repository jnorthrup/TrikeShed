package borg.trikeshed.quic

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class QuicElementTest {
    @Test
    fun contextLookupReturnsQuicElement() {
        val element = QuicElement()
        val context: CoroutineContext = element

        assertSame(element, context[QuicElement.Key])
        assertNull(context[OtherQuicElement.Key])
    }
}

private class OtherQuicElement : borg.trikeshed.context.AsyncContextElement() {
    companion object Key : borg.trikeshed.context.AsyncContextKey<OtherQuicElement>("OtherQuicKey")
    override val key: borg.trikeshed.context.AsyncContextKey<OtherQuicElement>
        get() = Key

    override suspend fun open() = Unit
    override suspend fun close() = Unit
}
