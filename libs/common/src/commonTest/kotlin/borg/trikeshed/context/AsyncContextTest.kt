package borg.trikeshed.context

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

// Minimal concrete impls for testing
private class AlphaElement : AsyncContextElement() {
    override val key get() = Key
    companion object Key : AsyncContextKey<AlphaElement>()
    override suspend fun open() { requireState(ElementState.CREATED); state = ElementState.OPEN }
    override suspend fun close() { requireState(ElementState.OPEN); state = ElementState.CLOSING; state = ElementState.CLOSED }
}

private class BetaElement : AsyncContextElement() {
    override val key get() = Key
    companion object Key : AsyncContextKey<BetaElement>()
    override suspend fun open() { requireState(ElementState.CREATED); state = ElementState.OPEN }
    override suspend fun close() { requireState(ElementState.OPEN); state = ElementState.CLOSING; state = ElementState.CLOSED }
}

class AsyncContextTest {
    @Test fun keySingletonsAreNotEqual() {
        assertNotSame<Any>(AlphaElement.Key, BetaElement.Key)
        assertNotEquals<Any>(AlphaElement.Key, BetaElement.Key)
    }

    @Test fun contextLookupReturnsCorrectElement() {
        val elem = AlphaElement()
        val ctx: CoroutineContext = elem
        assertSame(elem, ctx[AlphaElement.Key])
        assertNull(ctx[BetaElement.Key])
    }

    @Test fun elementLifecycleCreatedToOpenToClosed() = runTest {
        val elem = AlphaElement()
        assertEquals(ElementState.CREATED, elem.state)
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }

    @Test fun closeOnCreatedThrows() = runTest {
        val elem = AlphaElement()
        assertFailsWith<IllegalStateException> { elem.close() }
    }
}
