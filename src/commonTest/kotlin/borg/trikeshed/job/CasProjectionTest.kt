package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CasProjectionTest {
    @Test
    fun testProjectionDispatch() {
        val store = CasStore.inMemory()

        val btreeBytes = """{"tag": "btree-page", "data": "dummy"}""".encodeToByteArray()
        val causalBytes = """{"tag": "causal-node", "data": "dummy"}""".encodeToByteArray()
        val manifestBytes = """{"tag": "treedoc-manifest", "data": "dummy"}""".encodeToByteArray()

        val btreeCid = store.put(btreeBytes)
        val causalCid = store.put(causalBytes)
        val manifestCid = store.put(manifestBytes)

        // This should not throw, because the tag "btree-page" matches Lens.BtreePage
        project(btreeCid, store, BtreePage)

        // These should throw, because their tags do not match Lens.BtreePage
        assertFailsWith<IllegalArgumentException> {
            project(causalCid, store, BtreePage)
        }

        assertFailsWith<IllegalArgumentException> {
            project(manifestCid, store, BtreePage)
        }
    }
}
