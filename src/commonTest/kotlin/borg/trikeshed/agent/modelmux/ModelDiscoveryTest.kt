package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import kotlin.test.*

class ModelDiscoveryTest {
    @Test fun llamaCppReportsTheRuntimeContextInsteadOfTheTrainingContext() {
        val models = ModelDiscovery.parseModels("local", """{"data":[{"id":"q4.gguf","meta":{"n_ctx":1024,"n_ctx_train":32768}}]}""")
        assertEquals(1, models.size)
        assertEquals("q4.gguf", models[0].modelId)
        assertEquals(1024, models[0].contextWindow)
    }
}
