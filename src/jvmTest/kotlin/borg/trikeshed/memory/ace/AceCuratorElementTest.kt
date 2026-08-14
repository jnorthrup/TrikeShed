package borg.trikeshed.memory.ace

import borg.trikeshed.context.ElementState
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import borg.trikeshed.lib.get
import kotlinx.coroutines.test.runTest

class AceCuratorElementTest {

    @Test
    fun testLifecycle() = runTest {
        val element = AceCuratorElement()
        element.open()
        assertEquals(ElementState.ACTIVE, element.lifecycleState)
        element.drain()
        assertTrue(element.lifecycleState == ElementState.CLOSED || element.lifecycleState == ElementState.DRAINING)
    }

    @Test
    fun testMergeDeduplication() = runTest {
        val element = AceCuratorElement()
        element.open()
        
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        
        // Playbook has duplicates
        val b1 = BulletId(10) j (cid1 j (HelpfulCount(0) j 1))
        val b2 = BulletId(11) j (cid1 j (HelpfulCount(0) j 1))
        
        val pb = s_[b1, b2]
        
        // Delta has duplicates of cid1 and cid2
        val d1 = BulletId(20) j cid1
        val d2 = BulletId(21) j cid2
        val d3 = BulletId(22) j cid2
        
        val delta = s_[d1, d2, d3]
        
        val merged = element.merge(pb, delta)
        
        assertEquals(2, merged.size)
        
        val set = mutableSetOf<String>()
        var i = 0
        while (i < merged.size) {
            set.add(merged[i].b.a.value)
            i++
        }
        assertTrue(set.contains(cid1.value))
        assertTrue(set.contains(cid2.value))
    }
}
