package borg.trikeshed.sleeve

import borg.trikeshed.pointcut.VmFacet
import kotlin.test.*

class AgentSleeveRegistryTest {
    @Test fun observedAttemptReplacesThePriorManifestAndCards() {
        val id = "registry-fixture"
        val prior = AgentSleeveManifest(id, VmFacet.GRAAL_PYTHON, "fixture.encoder", "/fixture/sleeve", "/fixture/donor",
            listOf(PolyglotTrap("$id/import", "fixture.encoder", "import", TrapShape.UNRESOLVED,
                TrapCatchability.GUEST_CATCHABLE, "fixture/encoder.py", "fixture.import", "ImportError: fixture import")))
        val current = prior.copy(traps = listOf(prior.traps.single().copy(id = "$id/call", symbol = "encode",
            redTest = "fixture.encode", evidence = "RuntimeError: fixture encode")))
        try {
            AgentSleeveRegistry.record(prior)
            AgentSleeveRegistry.record(current)
            assertEquals(current, AgentSleeveRegistry.byId(id))
            assertEquals(listOf(current), AgentSleeveRegistry.ALL.filter { it.id == id })
            val cards = SleeveTddRedKanban.cards().filter { it.metadata["sleeve"] == id }
            assertEquals(listOf(SleeveTddRedKanban.cardId(current.traps.single())), cards.map { it.id })
            assertTrue(cards.single().description.contains(current.traps.single().evidence))
            val summary = AgentSleeveRegistry.summarize()["sleeves"] as List<*>
            assertEquals(current.toMap(), summary.filterIsInstance<Map<*, *>>().single { it["id"] == id })
        } finally { AgentSleeveRegistry.clear(id) }
        assertNull(AgentSleeveRegistry.byId(id))
    }

    @Test fun observedOverrideDoesNotDuplicateOrChangeTheDeclaredManifest() {
        val declared = AgentSleeveRegistry.HERMES
        val observed = declared.copy(entryModule = "fixture.attempt")
        try {
            AgentSleeveRegistry.record(observed)
            assertEquals(observed, AgentSleeveRegistry.byId(declared.id))
            assertEquals(listOf(observed), AgentSleeveRegistry.ALL.filter { it.id == declared.id })
            assertEquals("hermes_cli.main", declared.entryModule)
        } finally { AgentSleeveRegistry.clear(declared.id) }
        assertEquals(declared, AgentSleeveRegistry.byId(declared.id))
    }
}
