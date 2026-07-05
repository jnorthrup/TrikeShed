package borg.trikeshed.lcnc.reduction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class TestPatchCable(
    val id: String,
    val sourceModule: String,
    val destinationModule: String,
    val active: Boolean = true,
)

class LcncPatchCablesTest {

    private val binding = patchCableBinding<TestPatchCable>(
        isActive = { it.active },
        sourceModule = { it.sourceModule },
        destinationModule = { it.destinationModule },
    )

    @Test
    fun activeCablesExposePatchCableObjectsAsLcncCarrier() {
        val active = binding.activeCables(cables())

        assertEquals(2, active.size)
        assertEquals("c-a-b", active[0].id)
        assertEquals("c-b-c", active[1].id)
    }

    @Test
    fun reachableModulesFollowOnlyActivePatchCableRoutes() {
        val reachable = binding.reachableModules(cables(), "source")

        assertEquals(setOf("mapper", "sink"), reachable)
        assertTrue("mapper" in reachable)
        assertTrue("sink" in reachable)
        assertFalse("muted" in reachable)
    }

    @Test
    fun groupBySourceModuleKeepsPatchCableObjectIdentity() {
        val grouped = binding.cablesBySourceModule(cables())

        assertEquals(setOf("source", "mapper"), grouped.keys)
        assertEquals("mapper", grouped["source"]!!.get(0).destinationModule)
        assertEquals("sink", grouped["mapper"]!!.get(0).destinationModule)
    }

    private fun cables(): List<TestPatchCable> = listOf(
        TestPatchCable("c-a-b", "source", "mapper"),
        TestPatchCable("c-b-c", "mapper", "sink"),
        TestPatchCable("c-a-muted", "source", "muted", active = false),
    )
}
