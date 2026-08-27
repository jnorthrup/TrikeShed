package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 gate, W3.5: the Venn answers "what can this seat run right now"
 * from presence-only facts — key paths counted, never carried.
 */
class MuxVennTest {

    @Test
    fun runnableNowIsTheThreeWayIntersection() {
        val venn = MuxVenn(
            keyLinkedProviders = setOf("openai", "zai"),
            discoverableModels = mapOf("gpt-4o" to "openai", "glm-5" to "zai", "claude-4" to "anthropic"),
            muxableProviders = setOf("openai", "anthropic"),
        )
        val runnable = venn.runnableNow
        assertTrue(runnable.contains("gpt-4o"), "linked+rostered provider's model runs now: $runnable")
        assertTrue(!runnable.contains("glm-5"), "glm-5's provider zai linked but NOT rostered: $runnable")
        assertTrue(!runnable.contains("claude-4"), "anthropic rostered but key NOT linked: $runnable")
    }

    @Test
    fun noKeysMeansNothingRuns() {
        val venn = MuxVenn(
            keyLinkedProviders = emptySet(),
            discoverableModels = mapOf("m1" to "p1", "m2" to "p1"),
            muxableProviders = setOf("p1"),
        )
        assertEquals(0, venn.runnableNow.size, "zero present keys ⇒ zero runnable")
    }

    @Test
    fun documentLeaksNoKeyMaterial() {
        val venn = MuxVenn(
            keyLinkedProviders = setOf("openai", "zai"),
            discoverableModels = mapOf("gpt-4o" to "openai"),
            muxableProviders = setOf("openai"),
        )
        val doc = venn.document().toString()
        assertTrue(!doc.contains("sk-"), "no key values in the Venn document")
        assertTrue(!doc.uppercase().contains("API_KEY="), "no credential assignments in the Venn document")
        // Presence facts are fine to publish:
        assertTrue(doc.contains("openai"))
    }

    @Test
    fun linkedButNotRosteredIsAlsoNotRunnable() {
        val venn = MuxVenn(
            keyLinkedProviders = setOf("openrouter"),
            discoverableModels = mapOf("gpt-4o" to "openai"),
            muxableProviders = setOf("openai"),
        )
        // Key link exists for a provider with no roster entry; gpt-4o's provider
        // (openai) has no link → not runnable.
        assertTrue(!venn.runnableNow.contains("gpt-4o"))
    }
}
