package borg.trikeshed.reflink

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceCounterTest {
    @Test
    fun testReferenceCounting() {
        val counter = InMemoryReferenceCounter()
        val cid = ContentId.of("data".encodeToByteArray())
        
        assertEquals(0L, counter.getCount(cid))
        
        counter.increment(cid)
        assertEquals(1L, counter.getCount(cid))
        
        counter.increment(cid)
        assertEquals(2L, counter.getCount(cid))
        
        counter.decrement(cid)
        assertEquals(1L, counter.getCount(cid))
        
        counter.decrement(cid)
        assertEquals(0L, counter.getCount(cid))
        
        // Decrement when 0 should be safe / no-op (or at least keep it 0)
        counter.decrement(cid)
        assertEquals(0L, counter.getCount(cid))
    }
}
