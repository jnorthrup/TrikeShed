package borg.trikeshed.graal

import borg.trikeshed.lib.asString
import borg.trikeshed.parse.confix.root
import borg.trikeshed.parse.confix.src
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("DEPRECATION") // legacy subscribe shim is exercised deliberately
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // advanceUntilIdle
class ConfixBlackboardTest {
    
    @Test
    fun `empty blackboard starts clean`() {
        val bb = ConfixBlackboard.empty()
        
        assertNull(bb.get("nonexistent"))
        assertTrue(bb.keys().isEmpty())
    }
    
    @Test
    fun `put and get value with provenance`() {
        val bb = ConfixBlackboard.empty()
        
        bb.put("key1", "value1", "host")
        
        assertEquals("value1", bb.get("key1"))
        val prov = bb.getProvenance("key1")
        assertNotNull(prov)
        assertEquals("host", prov.language)
        assertTrue(prov.timestamp > 0)
    }
    
    @Test
    fun `put overwrites previous value`() {
        val bb = ConfixBlackboard.empty()
        
        bb.put("key", "v1", "host")
        bb.put("key", "v2", "js")
        
        assertEquals("v2", bb.get("key"))
        assertEquals("js", bb.getProvenance("key")?.language)
    }
    
    @Test
    fun `remove deletes key and provenance`() {
        val bb = ConfixBlackboard.empty()
        
        bb.put("key", "value", "host")
        bb.remove("key")
        
        assertNull(bb.get("key"))
        assertNull(bb.getProvenance("key"))
        assertTrue(bb.keys().isEmpty())
    }
    
    @Test
    fun `subscribe fires on changes`() {
        val bb = ConfixBlackboard.empty()
        var fired = false
        
        val unsubscribe = bb.subscribe { _ ->
            fired = true
        }
        
        bb.put("key", "value", "host")
        
        assertTrue(fired)
    }
    
    @Test
    fun `subscribe can be unsubscribed`() {
        val bb = ConfixBlackboard.empty()
        var fireCount = 0
        
        val unsubscribe = bb.subscribe { _ ->
            fireCount++
        }
        
        bb.put("k1", "v1", "host")
        assertEquals(1, fireCount)
        
        unsubscribe()
        
        bb.put("k2", "v2", "host")
        assertEquals(1, fireCount) // no additional fire
    }
    
    @Test
    fun `fromMap creates blackboard with initial values`() {
        val bb = ConfixBlackboard.fromMap(mapOf("a" to 1, "b" to 2), "init")

        assertEquals(1, bb.get("a"))
        assertEquals(2, bb.get("b"))
    }

    @Test
    fun `changes flow emits on put remove and merge`() = runTest {
        val bb = ConfixBlackboard.empty()

        // merge of a 1-key doc funnels through put -> one emission; total = put + remove + merge
        val collected = async { bb.changes.take(3).toList() }
        yield() // let the collector subscribe before mutating

        bb.put("k", "v", "host")
        bb.remove("k")
        bb.merge(ConfixBlackboard.empty().put("m", 1, "js").state, "js")

        val snapshots = collected.await()
        assertEquals(3, snapshots.size)
        assertSame(bb.state, snapshots.last(), "the last emission is the current doc truth")
    }

    @Test
    fun `changes flow emission carries current state snapshot`() = runTest {
        val bb = ConfixBlackboard.empty()
        var observed: Any? = null

        val collector = launch {
            observed = bb.changes.take(1).toList().single()
        }
        yield()

        bb.put("k", "v", "host")
        collector.join()

        assertSame(bb.state, observed, "flow must emit the post-mutation doc truth")
    }

    @Test
    fun `legacy subscribe shim and changes flow see the same mutation`() = runTest {
        val bb = ConfixBlackboard.empty()
        var shimFired = 0
        val unsubscribe = bb.subscribe { shimFired++ }

        val collector = launch {
            bb.changes.take(1).toList()
        }
        yield()

        bb.put("k", "v", "host")

        assertEquals(1, shimFired) // shim stays synchronous
        collector.join()           // flow saw it too
        unsubscribe()
    }

    @Test
    fun `tryEmit never blocks mutators without collectors`() {
        val bb = ConfixBlackboard.empty()
        // No collector attached: mutators must stay synchronous and non-suspending
        repeat(200) { bb.put("k$it", it, "host") }
        assertEquals(200, bb.keys().size)
    }

    @Test
    fun `DROP_OLDEST keeps the newest snapshots when a collector lags`() = runTest {
        val bb = ConfixBlackboard.empty()
        val burst = ConfixBlackboard.CHANGE_BUFFER_CAPACITY * 4
        val received = mutableListOf<Any?>()

        val collector = launch { bb.changes.collect { received.add(it) } }
        yield() // collector is subscribed, but StandardTestDispatcher will not resume it

        repeat(burst) { bb.put("k$it", it, "host") }
        assertTrue(received.isEmpty(), "collector must still be lagging mid-burst")
        assertEquals(burst, bb.keys().size, "mutators ran to completion without suspending")

        advanceUntilIdle() // now let the lagging collector drain whatever survived
        collector.cancel()

        assertTrue(
            received.size < burst,
            "back-pressure must have discarded snapshots (got ${'$'}{received.size} of ${'$'}burst)",
        )
        // The discriminating assertion: under SUSPEND or DROP_LATEST the collector would
        // hold the *earliest* snapshots and this would be some stale doc instead.
        assertSame(bb.state, received.last(), "the newest snapshot survives; the oldest are dropped")
    }

    @Test
    fun `replay hands a late subscriber the current snapshot`() = runTest {
        val bb = ConfixBlackboard.empty()

        bb.put("k", "v", "host") // mutation happens with nobody listening

        val collected = async { bb.changes.take(1).toList().single() }
        advanceUntilIdle()

        assertSame(bb.state, collected.await(), "subscribe-after-mutate must not miss current truth")
    }
    @Test
    fun `put escapes JSON-special characters in keys and string values`() {
        val bb = ConfixBlackboard.empty()
        val key = "say \"hi\""
        val value = "back\\slash\nnewline"

        bb.put(key, value, "host")

        // The store keeps the raw text ...
        assertEquals(value, bb.get(key))
        // ... while the emitted doc is well-formed JSON. Unescaped interpolation
        // produced {"say "hi"":"back\slash<LF>newline"}, which is not parseable.
        assertEquals(
            """{"say \"hi\"":"back\\slash\nnewline"}""",
            bb.state.src.asString(),
        )
        assertNotNull(bb.state.root, "a quote-bearing key must still yield a parseable doc")
    }
}
