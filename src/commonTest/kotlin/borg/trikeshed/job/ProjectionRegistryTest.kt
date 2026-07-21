package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectionRegistryTest {
    @Test
    fun testProject() {
        val store = CasStore.inMemory()
        
        val btreeBytes = """{"tag": "btree-page", "data": "dummy"}""".encodeToByteArray()
        val causalBytes = """{"tag": "causal-node", "data": "dummy"}""".encodeToByteArray()
        val manifestBytes = """{"tag": "treedoc-manifest", "data": "dummy"}""".encodeToByteArray()
        val cursorBytes = """{"tag": "cursor", "data": "dummy"}""".encodeToByteArray()
        val unknownBytes = """{"tag": "unknown-tag", "data": "dummy"}""".encodeToByteArray()
        val noTagBytes = """{"other": "data"}""".encodeToByteArray()
        
        val btreeCid = store.put(btreeBytes)
        val causalCid = store.put(causalBytes)
        val manifestCid = store.put(manifestBytes)
        val cursorCid = store.put(cursorBytes)
        val unknownCid = store.put(unknownBytes)
        val noTagCid = store.put(noTagBytes)
        
        val registry = ProjectionRegistry(store)
        
        assertEquals(BtreePage, registry.project(btreeCid))
        assertEquals(CausalNode, registry.project(causalCid))
        assertEquals(Manifest, registry.project(manifestCid))
        assertEquals(Cursor, registry.project(cursorCid))
        
        assertEquals(Raw, registry.project(unknownCid))
        assertEquals(Raw, registry.project(noTagCid))
    }
}
