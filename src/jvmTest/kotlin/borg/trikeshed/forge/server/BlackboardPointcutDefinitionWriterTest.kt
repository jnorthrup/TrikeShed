package borg.trikeshed.forge.server

import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.graal.ConfixBlackboard
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R5 not-theater gate — POST /blackboard/assert → production PointcutDefinitionWriter →
 * TypedefProductionSystem suppression.
 *
 * A landed enabled=false document is insufficient. The proof is observational: publishing
 * at that method+site produces no event; enabled=true through the same funnel lifts the
 * suppression and the next publish does produce one.
 */
class BlackboardPointcutDefinitionWriterTest {

    @Test
    fun enabledFalsePostedThroughTheWireSuppressesTheRuntimeSite() = runTest {
        TypedefProductionSystem.reset()
        TypedefProductionSystem.active = true
        val bb = ConfixBlackboard.empty()
        val job = Job()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
        val wire = BlackboardWire(bb, scope)
        try {
            val key = "pointcut-def/TestTypedef/counted/200"
            val disable = "POST /blackboard/assert HTTP/1.1\r\n\r\n" +
                "{\"$key\":{\"method\":\"counted\",\"site\":\"200\",\"enabled\":\"false\"}}"
            assertEquals(200, wire.route("POST", "/blackboard/assert", disable)?.status)
            delay(1) // drain the wire's single-writer channel

            assertNotNull(bb.get(key), "definition document landed on the blackboard")
            assertTrue(TypedefProductionSystem.isSuppressedSite("counted", 200), "enabled=false applied to runtime")
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_PROPERTY, "TestTypedef", "counted", 200, 1, isAfter = true,
            )
            assertEquals(0, TypedefProductionSystem.size(), "suppressed site produces no event")

            val enable = "POST /blackboard/assert HTTP/1.1\r\n\r\n" +
                "{\"$key\":{\"method\":\"counted\",\"site\":\"200\",\"enabled\":\"true\"}}"
            assertEquals(200, wire.route("POST", "/blackboard/assert", enable)?.status)
            delay(1)

            assertTrue(!TypedefProductionSystem.isSuppressedSite("counted", 200), "enabled=true lifted suppression")
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_PROPERTY, "TestTypedef", "counted", 200, 1, isAfter = true,
            )
            assertEquals(1, TypedefProductionSystem.size(), "re-enabled site produces an event")
        } finally {
            TypedefProductionSystem.reset()
            job.cancelAndJoin()
        }
    }
}
