package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MuxReactorElementTest {

    @Test
    fun reactorOwnsMuxStateAndTicksLeases() = runTest {
        val reactor = openMuxReactorElement(
            config = MuxReactorConfig(maxInProgress = 1, maxSpawn = 1, maxPerProvider = 1),
        )

        val loaded = reactor.loadCredentialPool(
            mapOf(
                "nvidia" to listOf(
                    MuxCredentialRecord(
                        id = "key-nvidia-1",
                        label = "NVIDIA primary",
                        baseUrl = "https://integrate.api.nvidia.com/v1",
                        lastModel = "minimaxai/minimax-m3",
                        requestCount = 7,
                    ),
                ),
            ),
        )
        assertEquals(1, loaded)

        reactor.recordModel(
            keyId = "key-nvidia-1",
            modelId = "minimaxai/minimax-m3",
            provider = "nvidia",
            contextWindow = 128_000,
        )

        val beforeTick = reactor.flowState.value
        assertEquals(1, beforeTick.availableKeys)
        assertEquals("minimaxai/minimax-m3", beforeTick.draftMapping["key-nvidia-1"])

        val dispatch = reactor.tick()
        assertEquals(1, dispatch.spawned)

        val afterTick = reactor.flowState.value
        assertEquals(1, afterTick.currentlyRunning)
        assertEquals(0, afterTick.availableKeys)
        assertNotNull(afterTick.leases.singleOrNull { it.keyId == "key-nvidia-1" })
    }
}
