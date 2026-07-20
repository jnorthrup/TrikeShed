package borg.trikeshed.pijul

import borg.trikeshed.patch.Blake3Hash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatchPrimitivesTest {

    @Test
    fun testPatchCreation() {
        val hash = Blake3Hash.hash(byteArrayOf(1, 2, 3))
        val change1 = Change.Insert(1, "A")
        val patch = Patch(hash, listOf(change1), emptyList())

        assertNotNull(patch)
        assertEquals(hash, patch.id)
        assertEquals(1, patch.changes.size)
        assertTrue(patch.dependencies.isEmpty())
    }

    @Test
    fun testEdgeCreation() {
        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val hash2 = Blake3Hash.hash(byteArrayOf(2))
        val edge = Edge(hash1, hash2, EdgeFlag.DELETED)

        assertNotNull(edge)
        assertEquals(hash1, edge.source)
        assertEquals(hash2, edge.target)
        assertEquals(EdgeFlag.DELETED, edge.flag)
    }

    @Test
    fun testDependencyDag() {
        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val hash2 = Blake3Hash.hash(byteArrayOf(2))

        val patch1 = Patch(hash1, emptyList(), emptyList())
        val patch2 = Patch(hash2, emptyList(), listOf(Dependency(hash1)))

        val dag = DependencyDag()
        dag.add(patch1)
        dag.add(patch2)

        assertTrue(dag.contains(hash1))
        assertTrue(dag.contains(hash2))
        assertEquals(listOf(hash1), dag.getDependencies(hash2).map { it.id })
    }
}
