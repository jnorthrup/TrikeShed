package borg.trikeshed.hermes

import borg.trikeshed.collections.LineAperture
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HermesOntologySpineTest {
    private fun facts(vararg facts: HermesOntologyFact) = facts.toList().toSeries()

    @Test
    fun semanticAperturesZoomKindThenRootThenPackage() {
        val ontology = hermesOntologySpine(facts(
            HermesOntologyFact(HermesOntologyKind.READY, "upstream", "toolsets"),
            HermesOntologyFact(HermesOntologyKind.READY, "sleeve", "agent.transport"),
            HermesOntologyFact(HermesOntologyKind.BLOCKED, "pydantic", "agent.runtime"),
            HermesOntologyFact(HermesOntologyKind.BLOCKED, "pydantic", "gateway.web"),
            HermesOntologyFact(HermesOntologyKind.DEFERRED, "cryptography", "tools.auth"),
        ))

        fun level(aperture: LineAperture) = ontology.zoom(aperture).toList().associate {
            it.a.toList().joinToString("/") to it.b
        }
        assertEquals(mapOf("blocked" to 2, "deferred" to 1, "ready" to 2), level(LineAperture.L0))
        assertEquals(2, level(LineAperture.L1).getValue("blocked/pydantic"))
        assertEquals(1, level(LineAperture.L2).getValue("blocked/pydantic/agent"))
        assertEquals(1, level(LineAperture.L3).getValue("blocked/pydantic/gateway/web"))
    }

    @Test
    fun trimmedOntologyIdentityIgnoresOuterWhitespace() {
        val a = trimmedOntologyLineSpine(listOf(" ready/upstream/toolsets ", "blocked/pydantic/agent/runtime").toSeries())
        val b = trimmedOntologyLineSpine(listOf("ready/upstream/toolsets", " blocked/pydantic/agent/runtime ").toSeries())
        assertEquals(b.toList().map { it.contentCid }, a.toList().map { it.contentCid })
    }

    @Test
    fun dailyDeltaUsesSpineResiduals() {
        val previous = hermesOntologySpine(facts(
            HermesOntologyFact(HermesOntologyKind.READY, "upstream", "a"),
            HermesOntologyFact(HermesOntologyKind.BLOCKED, "socket", "b"),
        ))
        val current = hermesOntologySpine(facts(
            HermesOntologyFact(HermesOntologyKind.READY, "upstream", "a"),
            HermesOntologyFact(HermesOntologyKind.READY, "sleeve", "b"),
        ))
        val delta = current.deltaFrom(previous.lineSpine)
        assertNotEquals(delta.previousCid, delta.currentCid)
        assertTrue(delta.added > 0)
        assertTrue(delta.removed > 0)
        assertTrue(delta.proximity in 0.0..1.0)
    }
}
