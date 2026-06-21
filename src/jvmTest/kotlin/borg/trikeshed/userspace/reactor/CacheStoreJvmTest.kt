package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only test: ~/.hermes/model_cache.json round-trips through stdlib
 * files + kotlinx.serialization. No Python, no Hermes CLI.
 */
class CacheStoreJvmTest {

    @Test
    fun modelCacheRoundTripsThroughTempFile() = runTest {
        val tmp = Files.createTempDirectory("trikeshed-cache-test-")
        try {
            val cachePath = tmp.resolve("model_cache.json")
            assertTrue(CacheStoreJvm.loadEntries(cachePath).isEmpty())

            val entries = listOf(
                CacheEntry(
                    key = "nvidia/minimaxai/minimax-m3",
                    provider = "nvidia",
                    modelId = "minimaxai/minimax-m3",
                    storedAtMs = 1_700_000_000_000L,
                    hits = 3,
                    payload = """{"ctx":128000}""",
                ),
                CacheEntry(
                    key = "anthropic/claude-3-5",
                    provider = "anthropic",
                    modelId = "claude-3-5",
                    storedAtMs = 1_700_000_001_000L,
                    hits = 0,
                    payload = """{"ctx":200000}""",
                ),
            )
            CacheStoreJvm.saveEntries(cachePath, entries)
            assertTrue(Files.exists(cachePath))
            val text = Files.readString(cachePath)
            assertTrue(text.contains("nvidia"))
            assertTrue(text.contains("anthropic"))

            val loaded = CacheStoreJvm.loadEntries(cachePath)
            assertEquals(2, loaded.size)
            assertEquals("nvidia", loaded[0].provider)
            assertEquals(3L, loaded[0].hits)
            assertEquals("""{"ctx":128000}""", loaded[0].payload)
        } finally {
            Files.walk(tmp).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun decodeHandlesEmptyAndBlank() = runTest {
        assertTrue(CacheStoreJvm.decodeEntries("").isEmpty())
        assertTrue(CacheStoreJvm.decodeEntries("   \n  ").isEmpty())
        assertTrue(CacheStoreJvm.decodeEntries("{}").isEmpty())
        assertTrue(CacheStoreJvm.decodeEntries("""{"entries":[]}""").isEmpty())
    }

    @Test
    fun reactorHydratesFromJvmStore() = runTest {
        val tmp = Files.createTempDirectory("trikeshed-cache-hydrate-")
        try {
            val cachePath = tmp.resolve("model_cache.json")
            val seed = listOf(
                CacheEntry(
                    key = "nvidia/minimaxai/minimax-m3",
                    provider = "nvidia",
                    modelId = "minimaxai/minimax-m3",
                    storedAtMs = 1_700_000_000_000L,
                    hits = 0,
                    payload = """{"ctx":128000}""",
                ),
            )
            CacheStoreJvm.saveEntries(cachePath, seed)

            // Reactor hydrates its in-memory cache from the on-disk shape.
            val reactor = openMuxReactorElement(MuxReactorConfig())
            val loaded = CacheStoreJvm.loadEntries(cachePath)
            reactor.cache.hydrate(loaded)

            // First lookup should now hit (proves hydration worked).
            val lookup = reactor.lookupModel("nvidia", "minimaxai/minimax-m3")
            assertEquals(true, lookup is CacheLookup.Hit, "expected Hit after hydration")
        } finally {
            Files.walk(tmp).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}