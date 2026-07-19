package borg.trikeshed.collections.btree

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CowBPlusTreeBtrfsTest {

    @Test
    fun testSnapshotSendRecv() {
        val cas1 = CasStore.inMemory()
        val tree1 = CowBPlusTree(cas1, maxDegree = 3)
        
        var root1: ContentId? = null
        for (i in 1..5) {
            val key = BTreeKey("f1", byteArrayOf(i.toByte()), JobId.of("j1"), i.toLong())
            val value = BTreeValue(ContentId("sha256:00000000000000000000000000000000000000000000000000000000000000" + i.toString().padStart(2, '0')), i.toLong())
            root1 = tree1.insert(root1, key, value)
        }
        
        val snapshot1 = tree1.snapshot(root1!!)
        
        var root2 = snapshot1
        for (i in 6..10) {
            val key = BTreeKey("f1", byteArrayOf(i.toByte()), JobId.of("j1"), i.toLong())
            val value = BTreeValue(ContentId("sha256:00000000000000000000000000000000000000000000000000000000000000" + i.toString().padStart(2, '0')), i.toLong())
            root2 = tree1.insert(root2, key, value)
        }
        
        val snapshot2 = tree1.snapshot(root2)
        
        val delta = tree1.send(snapshot1, snapshot2)
        
        val cas2 = CasStore.inMemory()
        val tree2 = CowBPlusTree(cas2, maxDegree = 3)
        
        val delta0 = tree1.send(null, snapshot1)
        val recvSnapshot1 = tree2.recv(delta0)
        
        assertEquals(snapshot1, recvSnapshot1)
        
        val recvSnapshot2 = tree2.recv(delta)
        assertEquals(snapshot2, recvSnapshot2)
        
        val val10 = tree2.get(recvSnapshot2, BTreeKey("f1", byteArrayOf(10.toByte()), JobId.of("j1"), 10L))
        assertNotNull(val10)
        assertEquals(10L, val10.sequence)
    }
}
