package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies QuicElement context-key integration.
 */
class QuicElementTest {
    @Test
    fun quicElementKeyIsAccessible() {
        val element = QuicElement()
        val ctx: CoroutineContext = element
        // element.key returns the companion object instance
        assertSame(element.key, QuicElement.Key)
        // cross-check with distinct key
        assertNull(ctx[OtherQuicElement.Key])
    }
}

/** Other element with distinct key for cross-check. */class OtherQuicElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<OtherQuicElement>("OtherQuicKey", 1L shl 6)

    override val key: AsyncContextKey<OtherQuicElement>
        get() = Key
}
