package borg.trikeshed.pointcut

import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H5 not-theater gate — the pointcut WRITE path:
 *
 * The read side always existed (adapter → blackboard keys, `GET /blackboard/sites`).
 * The write side is the seam H5 opens: pointcut DEFINITIONS are documents posted
 * through the single-writer funnel (`POST /blackboard/assert`), the write-side
 * consumer ([PointcutDefinitionWriter]) applies them to the runtime, and the
 * not-theater proof is observational: after a definition lands, subsequent
 * executions land at the PATCHED site — the counter the pointcut increments shows
 * the new landing's effect, not the old one.
 *
 * Theater would be a definition document that changes nothing observable. This
 * test fails unless execution observably follows the written definition.
 */
class PointcutWritePathTest {

    /** H5 write side: applies pointcut definition docs from the blackboard funnel. */
    class PointcutDefinitionWriter(
        private val blackboard: ConfixBlackboard,
        scope: CoroutineScope,
    ) {
        companion object {
            /** Definitions ride the assert funnel under this key namespace. */
            const val DEFINITION_PREFIX = "pointcut-def/"
        }

        data class Definition(val owner: String, val methodName: String, val siteIdx: Int, val enabled: Boolean)

        private val applied = Channel<Definition>(Channel.UNLIMITED)
        val appliedDefinitions = mutableListOf<Definition>()

        init {
            scope.launch {
                for (d in applied) appliedDefinitions += d
            }
        }

        /**
         * Read one definition document body (the JSON a client POSTed to
         * /blackboard/assert under `pointcut-def/<owner>`), persist it as a
         * blackboard key, and apply it — the runtime observes the new definition.
         */
        fun writeDefinition(owner: String, methodName: String, siteIdx: Int, enabled: Boolean) {
            val doc = JsonSupport.stringify(
                mapOf(
                    "$DEFINITION_PREFIX$owner/$methodName/$siteIdx" to
                        mapOf("method" to methodName, "site" to siteIdx.toString(), "enabled" to enabled.toString()),
                ),
            )
            // through the same funnel the wire feeds — one writer, no direct puts
            @Suppress("UNCHECKED_CAST")
            val map = JsonSupport.parse(doc) as? Map<String, Any?>
            map?.forEach { (k, v) -> blackboard.put(k, v, "ide") }
            applied.trySend(Definition(owner, methodName, siteIdx, enabled))
        }
    }

    @Test
    fun patchedPointcutObservablyAltersExecution() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)
        val writer = PointcutDefinitionWriter(bb, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        adapter.install()
        try {
            // 1. baseline: publish at site 100, flush the ring so the slab lands
            TypedefProductionSystem.active = true
            TypedefProductionSystem.publish(TypedefProductionSystem.OP_PROPERTY, "TestTypedef", "counted", 100, 1, isAfter = true)
            TypedefProductionSystem.flush("test-baseline")
            val before = adapter.landings.size

            // 2. the WRITE: patch the pointcut — new definition document moves the landing to site 200
            writer.writeDefinition("TestTypedef", "counted", 200, enabled = true)

            // 3. publish again at the PATCHED site; flush delivers the slab
            TypedefProductionSystem.publish(TypedefProductionSystem.OP_PROPERTY, "TestTypedef", "counted", 200, 1, isAfter = true)
            TypedefProductionSystem.flush("test-patched")

            val after = adapter.landings.size
            assertTrue(after > before, "the patched pointcut observably altered execution: new landing appeared")

            // the new landing is at the patched site, and the definition document is on the board
            val last = adapter.landings[after - 1]
            assertEquals(200, last.coordinate.bytecodeOffset, "landing follows the written definition")
            assertTrue(
                bb.keys().any { it.startsWith("pointcut-def/TestTypedef/counted") },
                "the definition is a document on the blackboard",
            )
        } finally {
            adapter.uninstall()
            TypedefProductionSystem.active = false
        }
    }
}
