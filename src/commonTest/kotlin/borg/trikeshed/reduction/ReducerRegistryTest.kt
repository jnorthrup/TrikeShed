package borg.trikeshed.reduction

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
        assertEquals("process", Capability.Process("test").category)
        assertEquals("cas", Capability.Cas("test").category)
        assertEquals("wireproto", Capability.Wireproto("test").category)
        assertEquals("sctp", Capability.Sctp.category)
        assertEquals("modelmux", Capability.Model.category)
        assertEquals("trajectory", Capability.Trajectory.category)
    }

    @Test
    fun testRunForMix() {
        val res = ReducerRegistry.runFor(Capability.Process("test"), null)
        assertNotNull(res)

        val sctpRes = ReducerRegistry.runFor(Capability.Sctp, null)
        assertNull(sctpRes)
    }
}
