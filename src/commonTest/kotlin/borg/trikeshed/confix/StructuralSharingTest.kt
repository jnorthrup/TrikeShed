package borg.trikeshed.confix

import borg.trikeshed.parse.confix.*
import kotlin.test.*
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

class StructuralSharingTest {

    @Test
    fun testStructuralSharingWithDeepLeafEdit() {
        val builder = StringBuilder()
        builder.append("[")
        for (i in 0 until 100) {
            builder.append("{\"id\": $i, \"value\": \"test\"}")
            if (i < 99) builder.append(", ")
        }
        builder.append("]")

        val doc1 = confixDoc(builder.toString())
        val index1 = doc1.index
        val structuralNodes1 = index1.facet(ConfixIndexK.StructuralNodes)

        val rootCid1 = structuralNodes1.get(0)
        assertNotNull(rootCid1, "Root should have a CID")

        var targetIndex = -1
        val siblingCids1 = mutableMapOf<Int, String?>()

        val depths = index1.facet(ConfixIndexK.Depths)
        val tags = index1.facet(ConfixIndexK.Tags)

        var objCount = 0
        for (i in 0 until structuralNodes1.size) {
            if (depths.get(i) == 1 && tags.get(i) == borg.trikeshed.cursor.IOMemento.IoObject) {
                siblingCids1[i] = structuralNodes1.get(i)
                if (objCount == 50) {
                    targetIndex = i
                }
                objCount++
            }
        }

        assertTrue(targetIndex != -1, "Should find target index")

        val builder2 = StringBuilder()
        builder2.append("[")
        for (i in 0 until 100) {
            if (i == 50) {
                builder2.append("{\"id\": $i, \"value\": \"edited\"}")
            } else {
                builder2.append("{\"id\": $i, \"value\": \"test\"}")
            }
            if (i < 99) builder2.append(", ")
        }
        builder2.append("]")

        val doc2 = confixDoc(builder2.toString())
        val index2 = doc2.index
        val structuralNodes2 = index2.facet(ConfixIndexK.StructuralNodes)

        val siblingCids2 = mutableMapOf<Int, String?>()
        val depths2 = index2.facet(ConfixIndexK.Depths)
        val tags2 = index2.facet(ConfixIndexK.Tags)

        for (i in 0 until structuralNodes2.size) {
            if (depths2.get(i) == 1 && tags2.get(i) == borg.trikeshed.cursor.IOMemento.IoObject) {
                siblingCids2[i] = structuralNodes2.get(i)
            }
        }

        var matchCount = 0
        var diffCount = 0
        for (i in 0 until 100) {
            val cid1 = siblingCids1.values.elementAt(i)
            val cid2 = siblingCids2.values.elementAt(i)
            assertNotNull(cid1)
            assertNotNull(cid2)
            if (i == 50) {
                assertNotEquals(cid1, cid2, "Target node CID should change")
                diffCount++
            } else {
                assertEquals(cid1, cid2, "Sibling node $i CID should be unchanged")
                matchCount++
            }
        }
        assertEquals(99, matchCount, "99 siblings should match")
        assertEquals(1, diffCount, "1 node should differ")
    }
}
