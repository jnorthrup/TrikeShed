package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

// ---------------------------------------------------------------------------
// Concrete test keys and elements (inside same module so sealed is fine)
// ---------------------------------------------------------------------------

private class ElementA : AsyncContextElement() {
    companion object Key : AsyncContextKey<ElementA>("KeyA")
    override val key: AsyncContextKey<ElementA> get() = Key
}

private class ElementB : AsyncContextElement() {
    companion object Key : AsyncContextKey<ElementB>("KeyB")
    override val key: AsyncContextKey<ElementB> get() = Key
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class AsyncContextInvariantsTest {

    /** (1) Two Key companion singletons must not be equal to each other. */
    @Test
    fun keySingletonsAreDistinct() {
        assertNotEquals<CoroutineContext.Key<*>>(ElementA.Key, ElementB.Key)
        // Also verify identity: each key is the same object as itself
        assertSame(ElementA.Key, ElementA.Key)
        assertSame(ElementB.Key, ElementB.Key)
    }

    /** (2) Context built with KeyA[elemA] returns elemA on KeyA lookup and null on KeyB. */
    @Test
    fun contextLookupReturnsCorrectElement(): Unit = runBlocking {
        val elemA = ElementA()
        // Build a CoroutineContext containing elemA
        val ctx: CoroutineContext = elemA
        // KeyA lookup must return elemA
        assertSame(elemA, ctx[ElementA.Key])
        // KeyB lookup must return null
        assertNull(ctx[ElementB.Key])
    }

    /** (3) Element moves CREATED -> OPEN after open(), then OPEN -> CLOSED after close(). */
    @Test
    fun elementLifecycleTransitions(): Unit = runBlocking {
        val elem = ElementA()
        assertEquals(ElementState.CREATED, elem.state)
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }
}
