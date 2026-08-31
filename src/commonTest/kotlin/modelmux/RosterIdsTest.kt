package modelmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The roster-collision gate.
 *
 * A duplicate model id in the roster is not an ambiguity — [ModelMux.session]
 * takes the first match, so it silently deletes the later provider's route while
 * still listing it in the panel. These pin the assignment that makes every
 * endpoint reachable.
 */
class RosterIdsTest {

    @Test
    fun `distinct models keep their bare ids`() {
        val ids = disambiguateModelIds(listOf(
            RosterEntry("groq", "llama-3.3-70b-versatile"),
            RosterEntry("deepseek", "deepseek-chat"),
        ))
        assertEquals(listOf("llama-3.3-70b-versatile", "deepseek-chat"), ids)
    }

    @Test
    fun `a collision qualifies the later endpoint, not the first`() {
        // The live defect: nvidia registers z-ai/glm-5.2 first, so openrouter's
        // copy — the one with working credit — was unreachable.
        val ids = disambiguateModelIds(listOf(
            RosterEntry("nvidia", "z-ai/glm-5.2"),
            RosterEntry("openrouter", "z-ai/glm-5.2"),
        ))
        assertEquals(listOf("z-ai/glm-5.2", "openrouter/z-ai/glm-5.2"), ids)
    }

    @Test
    fun `three way collision yields three reachable ids`() {
        // hermes lists one model against three different endpoints.
        val ids = disambiguateModelIds(listOf(
            RosterEntry("hermes-go", "nousresearch/hermes-3-llama-3.1-405b"),
            RosterEntry("hermes-nvidia", "nousresearch/hermes-3-llama-3.1-405b"),
            RosterEntry("hermes-synth", "nousresearch/hermes-3-llama-3.1-405b"),
        ))
        assertEquals(3, ids.toSet().size, "every endpoint must be addressable")
        assertEquals("nousresearch/hermes-3-llama-3.1-405b", ids[0])
    }

    @Test
    fun `same provider listing one model twice still disambiguates`() {
        val ids = disambiguateModelIds(listOf(
            RosterEntry("nvidia", "m"),
            RosterEntry("openrouter", "m"),
            RosterEntry("openrouter", "m"),
        ))
        assertEquals(3, ids.toSet().size)
        assertEquals(listOf("m", "openrouter/m", "openrouter/m#2"), ids)
    }

    @Test
    fun `ids are unique for every entry`() {
        val roster = listOf(
            RosterEntry("nvidia", "a"), RosterEntry("openrouter", "a"),
            RosterEntry("nvidia", "b"), RosterEntry("zai", "b"),
            RosterEntry("groq", "c"),
        )
        val ids = disambiguateModelIds(roster)
        assertEquals(roster.size, ids.size, "one id per endpoint — nothing dropped")
        assertEquals(roster.size, ids.toSet().size, "no id shared by two endpoints")
    }

    @Test
    fun `assignment is deterministic`() {
        // Ids reach receipts, cache keys and saved presets; a roster that
        // renumbered between boots would invalidate all three.
        val roster = listOf(
            RosterEntry("nvidia", "x"), RosterEntry("openrouter", "x"), RosterEntry("zai", "y"),
        )
        assertEquals(disambiguateModelIds(roster), disambiguateModelIds(roster))
    }

    @Test
    fun `shadowedEntries names exactly the previously unreachable routes`() {
        val roster = listOf(
            RosterEntry("nvidia", "z-ai/glm-5.2"),
            RosterEntry("openrouter", "z-ai/glm-5.2"),
            RosterEntry("groq", "llama-3.3-70b-versatile"),
        )
        val shadowed = shadowedEntries(roster)
        assertEquals(1, shadowed.size)
        assertEquals("openrouter", shadowed[0].first.provider)
        assertEquals("openrouter/z-ai/glm-5.2", shadowed[0].second)
    }

    @Test
    fun `an empty roster is not an error`() {
        assertTrue(disambiguateModelIds(emptyList()).isEmpty())
        assertTrue(shadowedEntries(emptyList()).isEmpty())
    }
}
