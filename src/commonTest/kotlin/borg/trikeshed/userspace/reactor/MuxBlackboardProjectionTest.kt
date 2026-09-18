package borg.trikeshed.userspace.reactor

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import modelmux.ProviderHealth
import modelmux.QuotaStanding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * mux redaction gate: the board sees fingerprints and operator metadata,
 * never a raw keyId — the ReactorSource lane treats keyId as credential
 * material, so its absence from every key and value is the invariant.
 */
class MuxBlackboardProjectionTest {

    private fun state() = MuxReactorState(
        config = MuxReactorConfig(),
        keys = listOf(
            MuxKeyEntry(keyId = "sk-live-abc123", provider = "openai", label = "pool-0",
                lastModel = "gpt-x", lastUsedMs = 7L, accessCount = 3L, status = MuxKeyStatus.ACTIVE),
            MuxKeyEntry(keyId = "sk-live-def456", provider = "anthropic", label = "pool-1",
                status = MuxKeyStatus.BACKOFF, leasedTo = "sess-1", leaseExpiresAt = 99L),
        ),
        models = listOf(MuxModelEntry(modelId = "gpt-x", provider = "openai", contextWindow = 128000)),
        leases = emptyList(),
        operationalHistory = emptyList(),
        draftMapping = mapOf("sk-live-abc123" to "gpt-x"),
        currentlyRunning = 1,
        availableKeys = 1,
        queueDepth = 0,
        maxInProgress = 3,
        maxSpawn = 3,
        maxPerProvider = 1,
        tickSequence = 42L,
        lastTickMs = 1000L,
    )

    @Test
    fun keysLandByFingerprintAndNoKeyIdLeaks() {
        val entries = MuxBlackboardProjection.entries(
            state(),
            1 j { _: Int ->
                QuotaStanding("sk-live-abc123", "openai", 0L, 86_400_000L, 1000L, 10L, false, 3L)
            },
            1 j { _: Int -> ProviderHealth("openai", 0.9, 120L, 999L) },
        )
        val rendered = ArrayList<String>()
        for (i in 0 until entries.size) {
            rendered.add(entries[i].a)
            rendered.add(entries[i].b.toString())
        }
        val all = rendered.joinToString("\n")
        assertTrue("sk-live-abc123" !in all, "raw keyId never appears in any board key or value")
        assertTrue("sk-live-def456" !in all, "raw keyId never appears in any board key or value")

        val keyEntries = (0 until entries.size).map { entries[it] }.filter { it.a.startsWith("mux/key/") }
        assertEquals(2, keyEntries.size)
        val fp = MuxBlackboardProjection.fingerprint("sk-live-abc123")
        val entry = keyEntries.single { it.a == "mux/key/$fp" }
        assertEquals("openai", entry.b["provider"])
        assertEquals("pool-0", entry.b["label"])
        assertEquals("ACTIVE", entry.b["status"])
        assertEquals(3L, entry.b["accessCount"])

        assertTrue(entries[0].a == "mux/state")
        assertTrue((0 until entries.size).any { entries[it].a == "mux/model/gpt-x" })
        assertTrue((0 until entries.size).any { entries[it].a == "mux/quota/openai" })
        assertTrue((0 until entries.size).any { entries[it].a == "mux/health/openai" })
    }
}
