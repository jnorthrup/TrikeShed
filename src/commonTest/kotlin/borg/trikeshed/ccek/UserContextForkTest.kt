package borg.trikeshed.ccek

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 gates, W3.1: UserContext is the context — fork is the fan-out and
 * role-change primitive. No new context type was invented; no key material
 * can enter a context.
 *
 * Identity gates: `id` is minted per instance (the retired `ctx-$name`
 * collided across forks of the same role name and let one fork's CAS
 * document overwrite another's). Provenance links are the parent's minted
 * id, not a name derivation.
 */
class UserContextForkTest {

    private fun ctx(name: String): UserContext =
        UserContext(name, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun forkCopiesParentFactsAndLinksProvenance() {
        val parent = ctx("case-42")
        parent.assertFact(CausalAssertion("brief:filed", mapOf("docket" to "42")))
        parent.assertFact(CausalAssertion("stance:deny", mapOf("count" to 3)))

        val child = parent.fork("opposing")

        assertTrue(child.id.startsWith("ctx-"), "id is namespaced for the contexts/ plane: ${child.id}")
        assertNotEquals("ctx-opposing", child.id, "id must not derive from the role name (fork collision)")
        assertEquals(parent.id, child.parentId, "provenance link to the parent")
        assertEquals(2, child.factCount, "child starts with the parent's epistemic state")
        // Mutation isolation: asserting in the child must not leak back.
        child.assertFact(CausalAssertion("rebuttal:draft", mapOf("v" to 1)))
        assertEquals(3, child.factCount)
        assertEquals(2, parent.factCount, "parent unchanged by child activity")
    }

    @Test
    fun deepForkChainsProvenance() {
        val root = ctx("root")
        val legal = root.fork("legal")
        val researcher = legal.fork("researcher")
        assertNull(root.parentId)
        assertEquals(root.id, legal.parentId)
        assertEquals(legal.id, researcher.parentId)
    }

    @Test
    fun forkedContextDocumentRoundTrips() {
        val parent = ctx("tribunal")
        parent.assertFact(CausalAssertion("fact:x", mapOf("k" to "v")))
        val child = parent.fork("judge")

        val doc = child.toDocument()
        assertEquals(child.id, doc["id"])
        assertEquals("judge", doc["name"], "the human label travels beside the identity")
        assertEquals(parent.id, doc["parentId"])
        @Suppress("UNCHECKED_CAST")
        val facts = doc["facts"] as List<Map<String, Any?>>
        assertEquals(1, facts.size)
        assertEquals("fact:x", facts[0]["kind"])
    }

    @Test
    fun distinctForksOfSameParentAreDistinctContexts() {
        val parent = ctx("matter")
        val a = parent.fork("legal")
        val b = parent.fork("legal") // SAME role name — the old id scheme collided here
        assertNotEquals(a.id, b.id, "same-name forks are distinct contexts (id no longer derives from name)")
        assertEquals(a.parentId, b.parentId, "same provenance")
    }

    @Test
    fun documentIdIsStableAcrossToDocumentCalls() {
        val c = ctx("stable")
        assertEquals(c.toDocument()["id"], c.toDocument()["id"], "id is minted once per instance")
    }
}
