package borg.trikeshed.sctp

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SctpElementTest {
    @Test
    fun contextLookupReturnsSctpElement() {
        val element = SctpElement()
        val context: CoroutineContext = element

        assertSame(element, context[SctpElement.Key])
        assertNull(context[OtherSctpElement.Key])
    }
}

private class OtherSctpElement : borg.trikeshed.context.AsyncContextElement() {
    companion object Key : borg.trikeshed.context.AsyncContextKey<OtherSctpElement>()
    override val key: borg.trikeshed.context.AsyncContextKey<OtherSctpElement>
        get() = Key

    override suspend fun open() = Unit
    override suspend fun close() = Unit
}