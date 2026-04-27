package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies SctpElement context-key integration.
 */
class SctpElementTest {
    @Test
    fun sctpElementKeyIsAccessible() {
        val element = SctpElement()
        val ctx: CoroutineContext = element
        // element.key returns the companion object instance
        assertSame(element.key, SctpElement.Key)
        // cross-check with distinct key
        assertNull(ctx[OtherSctpElement.Key])
    }
}

/** Other element with distinct key for cross-check. */class OtherSctpElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<OtherSctpElement>()

    override val key: AsyncContextKey<OtherSctpElement>
        get() = Key
}
