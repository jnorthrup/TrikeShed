package borg.trikeshed.userspace.reactor

import borg.trikeshed.lcnc.BrainMuxNodes
import borg.trikeshed.lcnc.LcncNode
import keymux.FixedKeySource
import keymux.KeyMux
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import modelmux.ModelMux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MuxBindingTest {
    private fun models(keys: KeyMux, id: String, provider: String) = ModelMux(keys) {
        model("unkeyed", caps = setOf("chat"), provider = "missing")
        model(id, caps = setOf("chat"), provider = provider)
    }

    @Test
    fun catalogRefreshAndChildBindingUseTheCurrentContext() = runTest {
        val keys = KeyMux { bind("llm.groq.key", FixedKeySource("fixture-parent")) }
        val childKeys = KeyMux { bind("llm.openai.key", FixedKeySource("fixture-child")) }
        var catalog = models(keys, "first", "groq")
        val parent = MuxReactorElement(keyMux = keys, modelMuxProvider = { catalog })
        val child = MuxReactorElement(keyMux = childKeys, modelMuxProvider = { models(childKeys, "child", "openai") })
        parent.open()
        child.open()
        val registry = BrainMuxNodes.registry()
        suspend fun ids(): List<Any?> {
            val result = registry.getValue("mux.models").run(LcncNode("models", "mux.models"), emptyMap())
            return (result["models"] as List<*>).map { (it as Map<*, *>)["id"] }
        }
        suspend fun providers(): Any? = registry.getValue("keys.status")
            .run(LcncNode("keys", "keys.status"), emptyMap())["have"]
        try {
            withContext(parent) {
                assertSame(keys, parent.keyMux())
                assertEquals(listOf("first"), ids())
                assertEquals(listOf("groq"), providers())
                catalog = models(keys, "refreshed", "groq")
                assertEquals(listOf("refreshed"), ids())
                withContext(child) {
                    assertEquals(listOf("child"), ids())
                    assertEquals(listOf("openai"), providers())
                }
                assertEquals(listOf("refreshed"), ids())
            }
            parent.drain()
            assertFailsWith<IllegalStateException> { parent.modelMux() }
            assertFailsWith<IllegalStateException> { parent.keyMux() }
        } finally {
            child.close()
            parent.close()
        }
    }

    @Test
    fun catalogCannotResolveThroughAnotherCredentialBinding() = runTest {
        val keys = KeyMux {}
        val otherKeys = KeyMux {}
        val reactor = MuxReactorElement(keyMux = keys, modelMuxProvider = { ModelMux(otherKeys) {} })
        reactor.open()
        try {
            assertFailsWith<IllegalStateException> { reactor.modelMux() }
        } finally {
            reactor.close()
        }
    }
}
