package borg.trikeshed.context

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ──────────────────────────────────────────────────────────────────────────────
// Test element types unique to this file (ElementA/B/C live in AsyncContextInvariantsTest)
// ──────────────────────────────────────────────────────────────────────────────

private class TestElementX : AsyncContextElement() {
    companion object Key : AsyncContextKey<TestElementX>()
    override val key: AsyncContextKey<TestElementX> get() = Key
}

private class TestElementY : AsyncContextElement() {
    companion object Key : AsyncContextKey<TestElementY>()
    override val key: AsyncContextKey<TestElementY> get() = Key
}

private class TestElementZ : AsyncContextElement(ElementState.OPEN) {
    companion object Key : AsyncContextKey<TestElementZ>()
    override val key: AsyncContextKey<TestElementZ> get() = Key
}

// ──────────────────────────────────────────────────────────────────────────────
// Tests: Key singleton identity
// ──────────────────────────────────────────────────────────────────────────────

class AsyncContextSupervisorTest {

    /** Key companion objects are identity-singleton — same object each time. */
    @Test
    fun `Key companion is identity singleton`() {
        assertSame(TestElementX.Key, TestElementX.Key)
        assertSame(TestElementY.Key, TestElementY.Key)
        assertSame(TestElementZ.Key, TestElementZ.Key)
    }

    /** Distinct element types have distinct key singletons. */
    @Test
    fun `Keys for different element types are not equal`() {
        // Use explicit type to satisfy type inference on different generic parameters
        @Suppress("UNCHECKED_CAST")
        assertNotSame(
            TestElementX.Key as CoroutineContext.Key<*>,
            TestElementY.Key as CoroutineContext.Key<*>
        )
        @Suppress("UNCHECKED_CAST")
        assertNotSame(
            TestElementY.Key as CoroutineContext.Key<*>,
            TestElementZ.Key as CoroutineContext.Key<*>
        )
    }

    /** key returns the companion singleton. */
    @Test
    fun `key returns the companion singleton`() {
        val elem = TestElementX()
        assertSame(TestElementX.Key, elem.key)
        val elemY = TestElementY()
        assertSame(TestElementY.Key, elemY.key)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests: CoroutineContext lookup
    // ──────────────────────────────────────────────────────────────────────────

    /** Element IS a CoroutineContext.Element; context[Key] returns the element. */
    @Test
    fun `context lookup returns the element for matching key`() = runTest {
        val elem = TestElementX()
        val ctx: CoroutineContext = elem
        assertSame(elem, ctx[TestElementX.Key])
    }

    /** context[OtherKey] returns null — cross-key isolation. */
    @Test
    fun `context lookup returns null for mismatched key`() = runTest {
        val elem = TestElementX()
        val ctx: CoroutineContext = elem
        assertSame(null, ctx[TestElementY.Key])
    }

    /** Multiple elements can coexist in a composite context. */
    @Test
    fun `composite context resolves both elements`() = runTest {
        val elemX = TestElementX()
        val elemY = TestElementY()
        val ctx: CoroutineContext = elemX + elemY
        assertSame(elemX, ctx[TestElementX.Key])
        assertSame(elemY, ctx[TestElementY.Key])
        assertSame(null, ctx[TestElementZ.Key])
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests: Lifecycle state machine
    // ──────────────────────────────────────────────────────────────────────────

    /** Element starts at CREATED by default. */
    @Test
    fun `default initial state is CREATED`() {
        val elem = TestElementX()
        assertEquals(ElementState.CREATED, elem.state)
        assertEquals(ElementState.CREATED, elem.lifecycleState)
    }

    /** Element can start at a given state. */
    @Test
    fun `explicit initial state is respected`() {
        val elem = TestElementZ()
        assertEquals(ElementState.OPEN, elem.state)
    }

    /** open() transitions CREATED → OPEN idempotently. */
    @Test
    fun `open transitions CREATED to OPEN`() = runTest {
        val elem = TestElementX()
        assertEquals(ElementState.CREATED, elem.state)
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    /** open() on OPEN is idempotent. */
    @Test
    fun `open is idempotent when already OPEN`() = runTest {
        val elem = TestElementZ() // starts OPEN
        assertEquals(ElementState.OPEN, elem.state)
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    /** close() transitions OPEN → CLOSED. */
    @Test
    fun `close transitions OPEN to CLOSED`() = runTest {
        val elem = TestElementX()
        elem.open()
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }

    /** supervisor is cancelled after close. */
    @Test
    fun `supervisor cancelled after close`() = runTest {
        val elem = TestElementX()
        elem.open()
        elem.close()
        assertFalse(elem.supervisor.isActive)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests: Parent job cancellation propagation
    // ──────────────────────────────────────────────────────────────────────────

    /** SupervisorJob inherits parent cancellation. */
    @Test
    fun `supervisor inherits parent cancellation`() = runTest {
        val parent = Job()
        val elem = TestParentElement(parent)
        assertTrue(elem.supervisor.isActive)
        parent.cancel()
        assertFalse(elem.supervisor.isActive)
    }

    /** SupervisorJob is cancelled on close regardless of parent. */
    @Test
    fun `supervisor cancelled on close even with live parent`() = runTest {
        val parent = Job()
        val elem = TestParentElement(parent)
        elem.open()
        elem.close()
        assertFalse(elem.supervisor.isActive)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests: fanoutSubscribers (structural, no-op base)
    // ──────────────────────────────────────────────────────────────────────────

    /** fanoutSubscribers is empty by default. */
    @Test
    fun `fanoutSubscribers is empty by default`() {
        val elem = TestElementX()
        assertTrue(elem.fanoutSubscribers.isEmpty())
    }

    /** lifecycleState alias returns state. */
    @Test
    fun `lifecycleState returns state`() {
        val elem = TestElementZ()
        assertSame(elem.state, elem.lifecycleState)
    }
}

/** Test element that accepts a parent Job. Must be top-level for visibility. */
private class TestParentElement(parent: Job?) : AsyncContextElement(parentJob = parent) {
    companion object Key : AsyncContextKey<TestParentElement>()
    override val key: CoroutineContext.Key<*> get() = Key
}
