package borg.trikeshed.graal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}