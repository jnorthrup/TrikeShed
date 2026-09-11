package borg.trikeshed.pointcut

import borg.trikeshed.cursor.FieldSynapse
import borg.trikeshed.cursor.SynapseRing
import borg.trikeshed.cursor.TypedefProductionSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubgraalSourceFilterTest {
    @Test
    fun namedMemorySourcePublishesAndExcludedSourceDoesNot() {
        val priorActive = TypedefProductionSystem.active
        val priorSubscriber = TypedefProductionSystem.synapseRing.subscriber
        val events = mutableListOf<FieldSynapse>()
        TypedefProductionSystem.synapseRing.flush("source-filter-before")
        TypedefProductionSystem.synapseRing.subscriber = object : SynapseRing.SlabSubscriber {
            override fun onSlab(slab: Array<out FieldSynapse>, count: Int, epoch: Long, nanoStart: Long, nanoEnd: Long) {
                repeat(count) { events.add(slab[it]) }
            }
        }
        TypedefProductionSystem.active = true
        try {
            SubgraalPointcutRunner(languages = setOf("js")).use { runner ->
                runner.arm("selected.js")
                assertEquals(22, runner.eval("js", "function selected(x) { return 3*x+1; } selected(7)", "selected.js").asInt())
                TypedefProductionSystem.synapseRing.flush("source-filter-selected")
                assertTrue(events.any { TypedefProductionSystem.InternPool.resolve(it.methodIdx) == "selected" && it.phase == 0.toByte() })
                assertTrue(events.any { TypedefProductionSystem.InternPool.resolve(it.methodIdx) == "selected" && it.phase == 1.toByte() })
                val count = events.size
                assertEquals(42, runner.eval("js", "21+21", "excluded.js").asInt())
                TypedefProductionSystem.synapseRing.flush("source-filter-excluded")
                assertEquals(count, events.size)
            }
        } finally {
            TypedefProductionSystem.synapseRing.flush("source-filter-after")
            TypedefProductionSystem.active = priorActive
            TypedefProductionSystem.synapseRing.subscriber = priorSubscriber
        }
    }
}
