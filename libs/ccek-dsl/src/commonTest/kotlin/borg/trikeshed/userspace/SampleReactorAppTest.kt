package borg.trikeshed.userspace

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.context.buildReactorContext
import borg.trikeshed.userspace.context.closeReactorContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

// ==========================================
// STABLE APP ELEMENTS (Pattern A)
// ==========================================

class MockHtxElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MockHtxElement>()
    override val key: CoroutineContext.Key<*> get() = Key
}

class MockUringFacadeElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MockUringFacadeElement>()
    override val key: CoroutineContext.Key<*> get() = Key
}

class MockIpfsElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<MockIpfsElement>()
    override val key: CoroutineContext.Key<*> get() = Key
}

class SampleReactorAppTest {

    @Test
    fun testReactorAppWithCcekDsl() = runTest {
        // 1. Build the context using the DSL
        // This abstracts away the `EmptyCoroutineContext + el1 + el2` syntax
        // and automatically handles the `open()` lifecycle transition.
        val appContext = buildReactorContext {
            element { MockUringFacadeElement() }
            element { MockHtxElement() }
            element { MockIpfsElement() }
        }

        // 2. Verify context and lifecycle
        val uring = appContext[MockUringFacadeElement.Key]
        assertNotNull(uring, "Uring facade should be in context")
        assertEquals(ElementState.OPEN, uring.state, "Element should be opened by DSL")

        val htx = appContext[MockHtxElement.Key]
        assertNotNull(htx, "HTX element should be in context")
        assertEquals(ElementState.OPEN, htx.state, "Element should be opened by DSL")

        val ipfs = appContext[MockIpfsElement.Key]
        assertNotNull(ipfs, "IPFS element should be in context")
        assertEquals(ElementState.OPEN, ipfs.state, "Element should be opened by DSL")

        // 3. Graceful shutdown using the DSL utility
        val registeredKeys = listOf(
            MockUringFacadeElement.Key,
            MockHtxElement.Key,
            MockIpfsElement.Key
        )

        closeReactorContext(appContext, registeredKeys)

        assertEquals(ElementState.CLOSED, uring.state, "Uring facade should be closed")
        assertEquals(ElementState.CLOSED, htx.state, "HTX element should be closed")
        assertEquals(ElementState.CLOSED, ipfs.state, "IPFS element should be closed")
    }
}
