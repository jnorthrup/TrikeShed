package borg.trikeshed.userspace.context

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD: Key instances are singleton-comparable by identity.
 * Element abstract bases carry expected key associations and lifecycle semantics.
 */
class AsyncContextKeyElementTest {

    // --- Key singleton identity tests ---

    @Test
    fun nioUserspaceKey_isSingleton_sameReference() {
        val k1: CoroutineContext.Key<*> = AsyncContextKey.NioUserspaceKey
        val k2: CoroutineContext.Key<*> = AsyncContextKey.NioUserspaceKey
        assertSame(k1, k2, "NioUserspaceKey must be the same object reference (singleton)")
    }

    @Test
    fun fanoutDispatcherKey_isSingleton_sameReference() {
        val k1: CoroutineContext.Key<*> = AsyncContextKey.FanoutDispatcherKey
        val k2: CoroutineContext.Key<*> = AsyncContextKey.FanoutDispatcherKey
        assertSame(k1, k2, "FanoutDispatcherKey must be the same object reference (singleton)")
    }

    @Test
    fun keys_areMutuallyDistinct_byIdentity() {
        val nio = AsyncContextKey.NioUserspaceKey
        val fanout = AsyncContextKey.FanoutDispatcherKey
        assertNotEquals<Any>(nio, fanout)
    }

    @Test
    fun keys_areSubtypesOfAsyncContextKey() {
        assertTrue(AsyncContextKey.NioUserspaceKey is AsyncContextKey)
        assertTrue(AsyncContextKey.FanoutDispatcherKey is AsyncContextKey)
    }

    // --- Element lifecycle state tests ---

    @Test
    fun elementLifecycleState_createdIsFirstState() {
        assertEquals(ElementLifecycleState.CREATED, ElementLifecycleState.entries.first())
    }

    @Test
    fun elementLifecycleState_closedIsLastState() {
        assertEquals(ElementLifecycleState.CLOSED, ElementLifecycleState.entries.last())
    }

    @Test
    fun elementLifecycleState_forwardOnlyOrdinals() {
        val states = ElementLifecycleState.entries
        for (i in 1 until states.size) {
            assertTrue(states[i].ordinal > states[i - 1].ordinal,
                "State ${states[i]} must have higher ordinal than ${states[i-1]}")
        }
    }

    @Test
    fun elementLifecycleState_allExpectedStatesPresent() {
        val names = ElementLifecycleState.entries.map { it.name }.toSet()
        assertTrue("CREATED" in names)
        assertTrue("OPEN" in names)
        assertTrue("ACTIVE" in names)
        assertTrue("DRAINING" in names)
        assertTrue("CLOSED" in names)
    }

    // --- Concrete anonymous element tests ---

   fun makeNioElement(state: ElementLifecycleState = ElementLifecycleState.CREATED) =
        object : NioUserspaceElement() {
            override val lifecycleState = state
            override val fanoutSubscribers = emptyList<AsyncContextElement>()
            override suspend fun open() {}
            override suspend fun drain() {}
            override suspend fun close() {}
        }

   fun makeFanoutElement() =
        object : FanoutDispatcherElement() {
            override val lifecycleState = ElementLifecycleState.CREATED
            override val fanoutSubscribers = emptyList<AsyncContextElement>()
            override suspend fun open() {}
            override suspend fun drain() {}
            override suspend fun close() {}
        }

    @Test
    fun nioUserspaceElement_keyIsNioUserspaceKey() {
        assertSame(AsyncContextKey.NioUserspaceKey, makeNioElement().key)
    }

    @Test
    fun fanoutDispatcherElement_keyIsFanoutDispatcherKey() {
        assertSame(AsyncContextKey.FanoutDispatcherKey, makeFanoutElement().key)
    }

    @Test
    fun element_initialLifecycleState_isCreated() {
        assertEquals(ElementLifecycleState.CREATED, makeNioElement().lifecycleState)
    }

    @Test
    fun element_fanoutSubscribers_defaultEmpty() {
        assertTrue(makeFanoutElement().fanoutSubscribers.isEmpty())
    }

    @Test
    fun element_coroutineContextElement_getWithKey() {
        val element = makeNioElement()
        val ctx: CoroutineContext = element
        assertSame(element, ctx[AsyncContextKey.NioUserspaceKey])
    }

    @Test
    fun element_differentKeys_returnNullForOtherKeys() {
        val element = makeNioElement()
        val ctx: CoroutineContext = element
        // Fetching a different key must return null (not found)
        assertEquals(null, ctx[AsyncContextKey.FanoutDispatcherKey])
    }
}
