package modelmux

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.reactor.MuxReactorElement
import modelmux.acp.AcpRichMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan step 6 gates: affinity is a hint that never resurrects a dead key,
 * the catalog carries LIVE facts when the seat can see them, and the
 * rich-media capability dimensions are declared with env-gated bindings.
 */
class AffinityTest {

    @Test
    fun preferKeyWinsWhileUsable() {
        val r = MuxReactorElement()
        r.recordAccess("cold", "openai")
        r.recordAccess("warm", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 1_000_000L
        // warm has less headroom than cold; affinity overrides the ranking
        legion.applyReceipt("warm", "openai", receipt(500, 300), now) // warm spent 800
        val chosen = legion.nextKey(r.flowState.value, now, preferKey = "warm")
        assertEquals("warm", chosen?.keyId, "the warm lane outranks quota ranking while usable")
    }

    @Test
    fun preferKeyNeverResurrectsExhaustedKey() {
        val r = MuxReactorElement()
        r.recordAccess("cold", "openai")
        r.recordAccess("warm", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 2_000_000L
        legion.applyReceipt("warm", "openai", receipt(0, 0, status = 429), now)
        val chosen = legion.nextKey(r.flowState.value, now, preferKey = "warm")
        assertEquals("cold", chosen?.keyId, "an exhausted warm lane falls through to the ranking")
    }

    @Test
    fun preferKeyHonoursProviderAndExclusions() {
        val r = MuxReactorElement()
        r.recordAccess("warm-anthropic", "anthropic")
        r.recordAccess("cold-openai", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 3_000_000L
        assertEquals(
            "cold-openai",
            legion.nextKey(r.flowState.value, now, provider = "openai", preferKey = "warm-anthropic")?.keyId,
            "a warm key of the wrong provider is skipped — normal dispatch proceeds",
        )
        assertNotEquals(
            "warm-anthropic",
            legion.nextKey(r.flowState.value, now, preferKey = "warm-anthropic", excluding = setOf("warm-anthropic"))?.keyId,
            "the exclusion set still outranks affinity — the hinted key is never returned while excluded",
        )
    }

    @Test
    fun lastContextIdRecordsTheWarmLane() {
        val r = MuxReactorElement()
        r.recordAccess("k1", "openai")
        r.recordContext("k1", "sha256:" + "a".repeat(64))
        val entry = r.flowState.value.keys.first { it.keyId == "k1" }
        assertEquals("sha256:" + "a".repeat(64), entry.lastContextId, "the key remembers its context")
        assertNull(r.recordContext("nope", "x"), "recording against an unknown key is null, not a crash")
    }

    @Test
    fun catalogEntryCarriesAffinityAsNeutralFact() {
        val cold = ModelCatalogEntry("p", "m", freeTier = false, quotaRemaining = 10, latencyEstimateMs = 5)
        val warm = cold.copy(affinityScore = 0.75)
        assertEquals(0.0, cold.affinityScore, "uncomputed affinity is zero, not a guess")
        assertTrue(warm.affinityScore > cold.affinityScore)
    }

    private fun receipt(input: Int, output: Int, status: Int = 200) =
        borg.trikeshed.modelmux.ModelResponseReceipt.mint(
            modelId = "m", providerId = "p", requestHash = "h",
            action = "chat", httpStatus = status, latencyMs = 1,
            inputTokens = input, outputTokens = output,
        )
}

/**
 * Rich-media capability dimensions: declared, env-gated, never silently bound.
 */
class AcpRichMediaTest {

    @Test
    fun dimensionsAreDeclared() {
        assertEquals(setOf("video.gen", "audio.gen", "image.gen", "doc.ingest"), AcpRichMedia.all)
    }

    @Test
    fun bindingsAreEnvGated() {
        val absent: (String) -> Boolean = { false }
        assertFalse(AcpRichMedia.isBound(AcpRichMedia.VIDEO_GEN, absent))
        assertFalse(AcpRichMedia.isBound(AcpRichMedia.AUDIO_GEN, absent))
        assertFalse(AcpRichMedia.isBound(AcpRichMedia.IMAGE_GEN, absent))
        assertTrue(AcpRichMedia.isBound(AcpRichMedia.DOC_INGEST, absent), "the Tika lane ships in-process")
        val allPresent: (String) -> Boolean = { true }
        assertTrue(AcpRichMedia.isBound(AcpRichMedia.VIDEO_GEN, allPresent))
    }

    @Test
    fun unknownCapabilityIsNeverBound() {
        assertFalse(AcpRichMedia.isBound("warp.drive") { true })
    }
}
