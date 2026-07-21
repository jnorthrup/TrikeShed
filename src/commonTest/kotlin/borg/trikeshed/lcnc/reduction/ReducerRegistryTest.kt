package borg.trikeshed.lcnc.reduction

import borg.trikeshed.context.nuid.Capability
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

class ReducerRegistryTest {
    @Test
    fun testRegistryKeysForFanoutMix() {
        assertNotNull(ReducerRegistry.registry["process"])
        assertNotNull(ReducerRegistry.registry["cas"])
        assertNotNull(ReducerRegistry.registry["wireproto"])
    }
    
    @Test
    fun testCategoryExtension() {
        assertEquals("process", Capability.Process.category)
        assertEquals("cas", Capability.Cas.category)
        assertEquals("wireproto", Capability.Wireproto.category)
        assertEquals("mesh", Capability.Mesh.category)
        assertEquals("modelmux", Capability.ModelMux.category)
    }

    @Test
    fun testRunForMix() {
        val res = ReducerRegistry.runFor(Capability.Process, null)
        assertNotNull(res)
        
        val meshRes = ReducerRegistry.runFor(Capability.Mesh, null)
        assertNull(meshRes)
    }
}
