package borg.trikeshed.pijul

import borg.trikeshed.patch.Blake3Hash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PatchStorageTest {

    @Test
    fun testPatchStorage() {
        val storage = PatchStorage()

        val hash1 = Blake3Hash.hash(byteArrayOf(1))
        val patch1 = Patch(hash1, listOf(Change.Insert(0, "A")), emptyList())

        storage.store(patch1)

        val retrieved = storage.get(hash1)
        assertNotNull(retrieved)
        assertEquals(hash1, retrieved!!.id)
        assertEquals(1, retrieved.changes.size)
    }
}
