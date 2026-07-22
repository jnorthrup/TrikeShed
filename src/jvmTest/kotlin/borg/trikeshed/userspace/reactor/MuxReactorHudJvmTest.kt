package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuxReactorHudJvmTest {
    @Test
    fun credentialPoolCacheAndHudShareTheLiveReactorState() = runTest {
        val pool = mapOf(
            "nvidia" to listOf(MuxCredentialRecord(
                id = "key-nvidia-1",
                label = "NVIDIA primary",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                lastStatus = "ok",
                lastModel = "minimaxai/minimax-m3",
                requestCount = 42,
            )),
            "anthropic" to listOf(MuxCredentialRecord(
                id = "key-anthropic-1",
                label = "Anthropic primary",
                baseUrl = "https://api.anthropic.com",
                lastStatus = "ok",
                lastModel = "claude-3-5-sonnet",
                requestCount = 10,
            )),
        )
        val reactor = openMuxReactorElement(MuxReactorConfig(maxInProgress = 2, maxSpawn = 2))
        assertEquals(2, reactor.loadCredentialPool(pool))
        assertEquals(2, reactor.tick().spawned)

        assertTrue(reactor.lookupModel("nvidia", "minimaxai/minimax-m3") is CacheLookup.Miss)
        reactor.cacheModel("nvidia", "minimaxai/minimax-m3", "{ctx:128000}")
        assertTrue(reactor.lookupModel("nvidia", "minimaxai/minimax-m3") is CacheLookup.Hit)

        val kanbanState = reactor.kanbanEvents.replayCache
            .fold(KanbanState()) { state, event -> KanbanFSM.reduce(event, state) }
        val hud = MuxReactorHud.fromState(reactor.flowState.value, kanbanState)

        assertTrue(hud.hasRealCacheBehavior)
        assertTrue(hud.cacheMissCount > 0)
        assertTrue(hud.cacheHitCount > 0)
        assertTrue(hud.cacheHitRatio in 1..99)
        assertEquals("key-nvidia-1", hud.leasedKeys.find { it.provider == "nvidia" }?.keyId)

        val rendered = MuxReactorHud.render(hud)
        assertTrue(rendered.contains("MUX REACTOR HUD"))
        assertTrue(rendered.contains("NVIDIA primary"))
    }
}
