package borg.trikeshed.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CcekElementFactoryTest {

    // ════════════════════════════════════════════════════════════════════════════
    // TEST ELEMENTS
    // ════════════════════════════════════════════════════════════════════════════

    class TestElementA(val name: String = "A") : AsyncContextElement() {
        override val key = TestElementAKey
        var value = name
    }
    object TestElementAKey : kotlinx.coroutines.CoroutineContext.Key<TestElementA>

    class TestElementB(val name: String = "B") : AsyncContextElement() {
        override val key = TestElementBKey
        var value = name
    }
    object TestElementBKey : kotlinx.coroutines.CoroutineContext.Key<TestElementB>

    class TestElementC : AsyncContextElement() {
        override val key = TestElementCKey
        var config: String = "default"
    }
    object TestElementCKey : kotlinx.coroutines.CoroutineContext.Key<TestElementC>

    // ════════════════════════════════════════════════════════════════════════════
    // BASIC TESTS
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `fresh element creation`() = runTest {
        val context = ccekContext {
            element(TestElementAKey) { TestElementA("fresh") }
        }
        val elem = context[TestElementAKey]
        assertNotNull(elem)
        assertEquals("fresh", elem.value)
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `reuse element from context`() = runTest {
        val preExisting = TestElementA("preexisting")
        val baseContext = kotlinx.coroutines.EmptyCoroutineContext + preExisting
        
        val context = ccekContext {
            reuse(TestElementAKey) { TestElementA("should not be used") }
        }.update(baseContext) as kotlinx.coroutines.CoroutineContext
        
        val elem = context[TestElementAKey]
        assertNotNull(elem)
        assertEquals("preexisting", elem.value) // reused existing, not created new
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `reuse or create when not in context`() = runTest {
        val context = ccekContext {
            reuse(TestElementAKey) { TestElementA("created new") }
        }
        val elem = context[TestElementAKey]
        assertNotNull(elem)
        assertEquals("created new", elem.value)
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `require throws when element missing`() = runTest {
        assertThrows<IllegalStateException> {
            ccekContext {
                require(TestElementAKey)
            }
        }
    }

    @Test
    fun `require succeeds when element exists`() = runTest {
        val preExisting = TestElementA("required")
        val baseContext = kotlinx.coroutines.EmptyCoroutineContext + preExisting
        
        val context = ccekContext {
            require(TestElementAKey)
        }.update(baseContext) as kotlinx.coroutines.CoroutineContext
        
        val elem = context[TestElementAKey]
        assertNotNull(elem)
        assertEquals("required", elem.value)
    }

    @Test
    fun `replace always creates fresh`() = runTest {
        val preExisting = TestElementC().apply { config = "old" }
        val baseContext = kotlinx.coroutines.EmptyCoroutineContext + preExisting
        
        val context = ccekContext {
            replace(TestElementCKey) { TestElementC().apply { config = "new" } }
        }.update(baseContext) as kotlinx.coroutines.CoroutineContext
        
        val elem = context[TestElementCKey]
        assertNotNull(elem)
        assertEquals("new", elem.config)
        assertEquals(ElementState.OPEN, elem.state)
        assertEquals(ElementState.CLOSED, preExisting.state) // old drained
    }

    @Test
    fun `replace if predicate matches`() = runTest {
        val preExisting = TestElementC().apply { config = "match" }
        val baseContext = kotlinx.coroutines.EmptyCoroutineContext + preExisting
        
        val context = ccekContext {
            replaceIf(TestElementCKey) { it.config == "match" } { TestElementC().apply { config = "replaced" } }
        }.update(baseContext) as kotlinx.coroutines.CoroutineContext
        
        val elem = context[TestElementCKey]
        assertNotNull(elem)
        assertEquals("replaced", elem.config)
        assertEquals(ElementState.CLOSED, preExisting.state)
    }

    @Test
    fun `replace if predicate no match keeps existing`() = runTest {
        val preExisting = TestElementC().apply { config = "no-match" }
        val baseContext = kotlinx.coroutines.EmptyCoroutineContext + preExisting
        
        val context = ccekContext {
            replaceIf(TestElementCKey) { it.config == "match" } { TestElementC().apply { config = "replaced" } }
        }.update(baseContext) as kotlinx.coroutines.CoroutineContext
        
        val elem = context[TestElementCKey]
        assertNotNull(elem)
        assertEquals("no-match", elem.config) // unchanged
        assertEquals(ElementState.OPEN, preExisting.state) // not drained
    }

    @Test
    fun `dependency ordering respected`() = runTest {
        val context = ccekContext {
            element(TestElementAKey) { TestElementA("A") }
                .dependsOn(TestElementBKey) // A depends on B
            element(TestElementBKey) { TestElementB("B") }
        }
        
        val a = context[TestElementAKey]
        val b = context[TestElementBKey]
        assertNotNull(a)
        assertNotNull(b)
    }

    @Test
    fun `getOrInstall extension`() = runTest {
        val context = kotlinx.coroutines.EmptyCoroutineContext
        
        val elem = context.getOrInstall(TestElementAKey) { TestElementA("installed") }
        assertEquals("installed", elem.value)
        assertEquals(ElementState.OPEN, elem.state)
        
        val same = context.getOrInstall(TestElementAKey) { TestElementA("ignored") }
        assertEquals("installed", same.value) // returned existing
    }

    @Test
    fun `drainAndCloseElements extension`() = runTest {
        val a = TestElementA()
        val b = TestElementB()
        val ctx = kotlinx.coroutines.EmptyCoroutineContext + a + b
        
        ctx.drainAndCloseElements(TestElementAKey, TestElementBKey)
        
        assertEquals(ElementState.CLOSED, a.state)
        assertEquals(ElementState.CLOSED, b.state)
    }
}
