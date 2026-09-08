package borg.trikeshed.lcnc

import borg.trikeshed.ccek.ProjectionKind
import borg.trikeshed.ccek.UserContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CcekChoreographOwnershipTest {
    @Test
    fun ordinaryIncarnationCannotClaimAContextBinding() = runTest {
        val seams = CcekSeams.live(backgroundScope)
        val registry = CcekNodes.registry(seams)
        val contextId = seams.forkContext(null, "owner").getValue("id").toString()
        seams.incarnate("existing", false, 8, ProjectionKind.ALL)
        val existing = assertNotNull(seams.nodeElement("existing"))
        assertNull(existing.executionScope.coroutineContext[UserContext.Key])

        assertFailsWith<IllegalArgumentException> {
            registry.getValue("ccek.choreograph").run(
                LcncNode("bind", "ccek.choreograph", mapOf("title" to "existing")),
                mapOf("contextId" to contextId),
            )
        }
        assertSame(existing, seams.nodeElement("existing"))
    }

    @Test
    fun choreographyCannotAttachToAnotherContextsNode() = runTest {
        val seams = CcekSeams.live(backgroundScope)
        val registry = CcekNodes.registry(seams)
        val firstId = seams.forkContext(null, "first").getValue("id").toString()
        val secondId = seams.forkContext(null, "second").getValue("id").toString()
        val node = LcncNode("bind", "ccek.choreograph", mapOf("title" to "shared"))
        val runner = registry.getValue("ccek.choreograph")
        assertEquals(true, runner.run(node, mapOf("contextId" to firstId))["bound"])
        assertEquals(true, runner.run(node, mapOf("contextId" to firstId))["bound"])
        val existing = assertNotNull(seams.nodeElement("shared"))
        assertSame(seams.contextElement(firstId), existing.executionScope.coroutineContext[UserContext.Key])

        assertFailsWith<IllegalArgumentException> {
            runner.run(node, mapOf("contextId" to secondId))
        }
        assertSame(existing, seams.nodeElement("shared"))
    }
}
