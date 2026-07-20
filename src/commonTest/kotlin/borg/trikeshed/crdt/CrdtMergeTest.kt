package borg.trikeshed.crdt

import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrdtMergeTest {

    @Test
    fun testSimpleMerge() {
        val crdt = PijulCrdt()

        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val patch1 = Patch(hash1, listOf(Change.Insert(0, "Hello")), emptyList())
        crdt.apply(patch1)

        assertEquals("Hello", crdt.render())

        val hash2 = Blake3Hash.hash(byteArrayOf(2))
        val patch2 = Patch(hash2, listOf(Change.Insert(5, " World")), listOf(Dependency(hash1)))
        crdt.apply(patch2)

        assertEquals("Hello World", crdt.render())
    }

    @Test
    fun testConflictResolution() {
        val crdt = PijulCrdt()

        val hash0 = Blake3Hash.hash(byteArrayOf(0))
        val patch0 = Patch(hash0, listOf(Change.Insert(0, "A")), emptyList())
        crdt.apply(patch0)

        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val patch1 = Patch(hash1, listOf(Change.Insert(1, "B")), listOf(Dependency(hash0)))

        val hash2 = Blake3Hash.hash(byteArrayOf(2))
        val patch2 = Patch(hash2, listOf(Change.Insert(1, "C")), listOf(Dependency(hash0)))

        crdt.apply(patch1)
        crdt.apply(patch2)

        val rendered = crdt.render()
        assertTrue(rendered == "ABC" || rendered == "ACB")
    }

    @Test
    fun testDeletion() {
        val crdt = PijulCrdt()

        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val patch1 = Patch(hash1, listOf(Change.Insert(0, "Hello")), emptyList())
        crdt.apply(patch1)

        val hash2 = Blake3Hash.hash(byteArrayOf(2))
        val patch2 = Patch(hash2, listOf(Change.Delete(0, 2)), listOf(Dependency(hash1)))
        crdt.apply(patch2)

        assertEquals("llo", crdt.render())
    }
}
