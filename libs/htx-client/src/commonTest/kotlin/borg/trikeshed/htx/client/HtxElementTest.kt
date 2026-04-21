package borg.trikeshed.htx.client

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class HtxElementTest {
    @Test
    fun contextLookupReturnsHtxElement() {
        val element = HtxElement()
        val context: CoroutineContext = element

        assertSame(element, context[HtxElement.Key])
        assertNull(context[OtherHtxElement.Key])
    }
}

private class OtherHtxElement : borg.trikeshed.context.AsyncContextElement() {
    companion object Key : borg.trikeshed.context.AsyncContextKey<OtherHtxElement>("OtherHtxKey")
    override val key: borg.trikeshed.context.AsyncContextKey<OtherHtxElement>
        get() = Key

    override suspend fun open() = Unit
    override suspend fun close() = Unit
}
