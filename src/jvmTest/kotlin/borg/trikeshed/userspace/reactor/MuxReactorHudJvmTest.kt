package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only test: demonstrates the complete wire from ~/.hermes/auth.json 
 * through to HUD display of live reactor state.
 * 
 * This proves:
 * 1. Keymux reads keys from ~/.hermes/auth.json (read as DATA, not Hermes CLI)
 * 2. Modelmux cache hits/misses are tracked
 * 3. HUD displays live reactor state (not fake telemetry)
 */
class MuxReactorHudJvmTest {

    @Test
    fun fullWireAuthJsonToHud() = runTest {
        val tmp = Files.createTempDirectory("trikeshed-hud-test-")
        try {
            // 1. Create auth.json with credentials
            val authPath = tmp.resolve("auth.json")
            val payload = """
                {
                  "credential_pool": {
                    "nvidia": [
                      {
                        "id": "key-nvidia-1",
                        "label": "NVIDIA primary",
                        "base_url": "https://integrate.api.nvidia.com/v1",
                        "last_status": "ok",
                        "last_model": "minimaxai/minimax-m3",
                        "request_count": 42
                      }
                    ],
                    "anthropic": [
                      {
                        "id": "key-anthropic-1", 
                        "label": "Anthropic primary",
                        "base_url": "https://api.anthropic.com",
                        "last_status": "ok",
                        "last_model": "claude-3-5-sonnet",
                        "request_count": 10
                      }
                    ]
                  }
                }
            """.trimIndent()
            Files.writeString(authPath, payload)

            // 2. Read credentials from auth.json as DATA (not Hermes CLI)
            val pool = HermesAuthReaderJvm.readCredentialPool(authPath)
            assertEquals(2, pool.size, "two providers loaded")
            assertEquals(1, pool["nvidia"]?.size)
            assertEquals(1, pool["anthropic"]?.size)

            // 3. Initialize reactor with credentials (keymux wired)
            val reactor = openMuxReactorElement(MuxReactorConfig(maxInProgress = 2, maxSpawn = 2))
            val loaded = reactor.loadCredentialPool(pool)
            assertEquals(2, loaded, "both keys loaded")

            // 4. Tick to lease keys
            val dispatch = reactor.tick()
            assertEquals(2, dispatch.spawned, "two keys leased")

            // 5. Modelmux cache hits/misses - not fake telemetry
            // First lookup misses
            val miss = reactor.lookupModel("nvidia", "minimaxai/minimax-m3")
            assertTrue(miss is CacheLookup.Miss, "first lookup misses")

            // Store and second lookup hits
            reactor.cacheModel("nvidia", "minimaxai/minimax-m3", """{"ctx":128000}""")
            val hit = reactor.lookupModel("nvidia", "minimaxai/minimax-m3")
            assertTrue(hit is CacheLookup.Hit, "second lookup hits")

            // 6. Collect kanban events to get FSM state
            val events = reactor.kanbanEvents.replayCache.toList()
            val cacheTicks = events.filterIsInstance<KanbanEvent.CacheTick>()
            assertTrue(cacheTicks.isNotEmpty(), "cache events emitted")

            // Reduce events to get KanbanState
            var kanbanState = KanbanState()
            for (event in events) {
                kanbanState = KanbanFSM.reduce(event, kanbanState)
            }

            // 7. Create HUD from live reactor state (not fake telemetry)
            val hudState = MuxReactorHud.fromState(reactor.flowState.value, kanbanState)

            // Verify HUD shows real cache behavior (not 100% hits)
            assertTrue(hudState.hasRealCacheBehavior, "HUD shows real cache behavior")
            assertTrue(hudState.cacheMissCount > 0, "HUD shows cache misses occurred")
            assertTrue(hudState.cacheHitCount > 0, "HUD shows cache hits occurred")
            assertTrue(hudState.cacheHitRatio in 1..99, "HUD shows mixed hit/miss ratio")

            // Verify HUD shows leased key
            assertTrue(hudState.leasedKeys.isNotEmpty(), "HUD shows leased keys")
            val nvidiaKey = hudState.leasedKeys.find { it.provider == "nvidia" }
            assertEquals("key-nvidia-1", nvidiaKey?.keyId, "NVIDIA key is leased")

            // 8. Render HUD to string
            val rendered = MuxReactorHud.render(hudState)
            assertTrue(rendered.contains("MUX REACTOR HUD"), "HUD renders correctly")
            assertTrue(rendered.contains("Hits:"), "HUD shows cache hits")
            assertTrue(rendered.contains("Misses:"), "HUD shows cache misses")
            assertTrue(rendered.contains("NVIDIA"), "HUD shows provider name")

            // Verify no fake telemetry markers in output
            assertTrue(!rendered.contains("fake"), "No fake telemetry")
            assertTrue(!rendered.contains("mock"), "No mock data")
            assertTrue(!rendered.contains("dummy"), "No dummy data")
        } finally {
            Files.walk(tmp).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
