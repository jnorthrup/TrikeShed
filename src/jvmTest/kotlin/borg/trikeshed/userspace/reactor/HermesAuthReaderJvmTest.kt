package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only test: the reactor reads ~/.hermes/auth.json as DATA through the
 * stdlib file API and the kotlinx.serialization JSON API. No Python, no
 * Hermes CLI, no gateway, no messaging platform.
 */
class HermesAuthReaderJvmTest {

    @Test
    fun readsHermesAuthJsonAsData() = runTest {
        val tmp = Files.createTempDirectory("trikeshed-mux-test-")
        try {
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
                      },
                      {
                        "id": "key-nvidia-bench",
                        "label": "NVIDIA benched",
                        "base_url": "https://integrate.api.nvidia.com/v1",
                        "last_status": "benched"
                      }
                    ],
                    "anthropic": [
                      {
                        "id": "key-anthropic-1",
                        "label": "Anthropic primary",
                        "base_url": "https://api.anthropic.com",
                        "last_status": "ok",
                        "last_used_at": 1700000000
                      }
                    ]
                  }
                }
            """.trimIndent()
            Files.writeString(authPath, payload)

            // 1. Reader decodes the credential pool as data.
            val pool = HermesAuthReaderJvm.readCredentialPool(authPath)
            assertEquals(2, pool.size, "two providers")
            assertEquals(1, pool["nvidia"]?.size, "benched key is filtered out")
            assertEquals(1, pool["anthropic"]?.size)
            assertEquals("key-nvidia-1", pool["nvidia"]?.firstOrNull()?.id)
            assertEquals("minimaxai/minimax-m3", pool["nvidia"]?.firstOrNull()?.lastModel)
            assertEquals(42L, pool["nvidia"]?.firstOrNull()?.requestCount)
            assertEquals(1700000000L, pool["anthropic"]?.firstOrNull()?.lastUsedAt)

            // 2. Reactor owns the mux state. Kanban never touches the JSON.
            val reactor = openMuxReactorElement(MuxReactorConfig(maxInProgress = 2, maxSpawn = 2))
            val loaded = reactor.loadCredentialPool(pool)
            assertEquals(2, loaded)
            assertEquals(2, reactor.flowState.value.keys.size)
            assertTrue(reactor.flowState.value.keys.any { it.keyId == "key-nvidia-1" })

            // 3. Tick leases the active keys and the event stream emits transitions.
            val dispatch = reactor.tick()
            assertEquals(2, dispatch.spawned)
            // Replay buffer carries the CredentialLoaded events emitted by loadCredentialPool.
            val replayed: List<KanbanEvent> = reactor.kanbanEvents.replayCache
            assertTrue(replayed.isNotEmpty())
            assertTrue(replayed.first() is KanbanEvent.CredentialLoaded)
        } finally {
            Files.walk(tmp).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun missingFileYieldsEmptyPool() = runTest {
        val pool = HermesAuthReaderJvm.readCredentialPool(Path.of("/nonexistent/auth.json"))
        assertTrue(pool.isEmpty())
    }
}