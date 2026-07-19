package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.lib.size

class CasProjectionTest {
    @Test
    fun testProjectionRegistry() {
        val store = CasStore.inMemory()

        // 1. CausalNode
        val causalDoc = """{"kind":"causal-node", "nodeId":"n1", "opId":"op1", "opVersion":"v1"}"""
        val cid1 = store.put(causalDoc.encodeToByteArray())
        val lens1 = project(cid1, store)
        assertTrue(lens1 is Lens.CausalNode)
        assertEquals("n1", (lens1 as Lens.CausalNode).doc.value("nodeId"))

        // 2. BtreePage
        val btreeDoc = """{"kind":"btree-page", "keys":[]}"""
        val cid2 = store.put(btreeDoc.encodeToByteArray())
        val lens2 = project(cid2, store)
        assertTrue(lens2 is Lens.BtreePage)

        // 3. Manifest
        val manifestDoc = """{"kind":"manifest", "version":"1.0"}"""
        val cid3 = store.put(manifestDoc.encodeToByteArray())
        val lens3 = project(cid3, store)
        assertTrue(lens3 is Lens.Manifest)

        // 4. Cursor
        val cursorDoc = """[{"a":1},{"a":2}]"""
        val cid4 = store.put(cursorDoc.encodeToByteArray())
        val lens4 = project(cid4, store)
        assertTrue(lens4 is Lens.CursorLens)
        assertEquals(2, (lens4 as Lens.CursorLens).cursor.size)

        // 5. Raw
        val rawBytes = byteArrayOf(0x01, 0x02, 0x03)
        val cid5 = store.put(rawBytes)
        val lens5 = project(cid5, store)
        assertTrue(lens5 is Lens.Raw)
    }
}
