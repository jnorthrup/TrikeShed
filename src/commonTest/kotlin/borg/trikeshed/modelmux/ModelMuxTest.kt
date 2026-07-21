package borg.trikeshed.modelmux

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelMuxTest {

    @Test
    fun muxRoutesToMockProvider() = runTest {
        val registry = ProviderRegistry()
        val mock = ModelProvider.Mock(respond = { it.messages.last().content })
        registry.register(mock, ProviderDescriptor("mock", "Mock", 0.0, 0))

        val mux = ModelMux(registry, SelectionRule.SpecificProvider("mock"))
        val response = mux.invoke(Prompt(listOf(PromptMessage.User("what is 2+2? 4")), "test"))
        assertTrue(response.content.contains("4"))
    }

    @Test
    fun muxRoutesToSpecificProvider() = runTest {
        val registry = ProviderRegistry()
        registry.register(ModelProvider.Mock { "" }, ProviderDescriptor("mock", "Mock", 0.0, 0))
        registry.register(ModelProvider.Anthropic, ProviderDescriptor("anthropic", "Anthropic", 0.0, 0))

        val mux = ModelMux(registry, SelectionRule.SpecificProvider("anthropic"))
        val response = mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        assertEquals("anthropic", response.providerId)
    }

    @Test
    fun muxRoutesByMinCost() = runTest {
        val registry = ProviderRegistry()
        registry.register(ModelProvider.OpenAI, ProviderDescriptor("p1", "P1", 0.10, 0))
        registry.register(ModelProvider.Anthropic, ProviderDescriptor("p2", "P2", 0.05, 0))
        registry.register(ModelProvider.Local, ProviderDescriptor("p3", "P3", 0.20, 0))

        val mux = ModelMux(registry, SelectionRule.MinCost)
        val response = mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        assertEquals("p2", response.providerId)
    }

    @Test
    fun muxRoutesByMinLatency() = runTest {
        val registry = ProviderRegistry()
        registry.register(ModelProvider.OpenAI, ProviderDescriptor("p1", "P1", 0.0, 500))
        registry.register(ModelProvider.Anthropic, ProviderDescriptor("p2", "P2", 0.0, 200))
        registry.register(ModelProvider.Local, ProviderDescriptor("p3", "P3", 0.0, 800))

        val mux = ModelMux(registry, SelectionRule.MinLatency)
        val response = mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        assertEquals("p2", response.providerId)
    }

    @Test
    fun muxFallbackWhenPrimaryMissing() = runTest {
        val registry = ProviderRegistry()
        registry.register(ModelProvider.Anthropic, ProviderDescriptor("secondary", "Secondary", 0.0, 0))

        val mux = ModelMux(registry, SelectionRule.Fallback("missing", "secondary"))
        val response = mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        assertEquals("secondary", response.providerId)
    }

    @Test
    fun muxThrowsWhenAllFallbacksMissing() = runTest {
        val registry = ProviderRegistry()
        val mux = ModelMux(registry, SelectionRule.Fallback("missing", "also_missing"))

        val ex = assertFailsWith<IllegalArgumentException> {
            mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        }
        assertEquals("no fallback provider found", ex.message)
    }

    @Test
    fun muxThrowsOnUnknownProviderId() = runTest {
        val registry = ProviderRegistry()
        val mux = ModelMux(registry, SelectionRule.SpecificProvider("nonexistent"))

        val ex = assertFailsWith<IllegalArgumentException> {
            mux.invoke(Prompt(listOf(PromptMessage.User("hi")), "test"))
        }
        assertEquals("no provider with id nonexistent", ex.message)
    }

    @Test
    fun muxPreservesPromptMessages() = runTest {
        var capturedPrompt: Prompt? = null
        val registry = ProviderRegistry()
        val mock = ModelProvider.Mock(respond = {
            capturedPrompt = it
            ""
        })
        registry.register(mock, ProviderDescriptor("mock", "Mock", 0.0, 0))

        val mux = ModelMux(registry, SelectionRule.SpecificProvider("mock"))
        mux.invoke(Prompt(listOf(
            PromptMessage.System("sys"),
            PromptMessage.User("usr"),
            PromptMessage.Assistant("ast")
        ), "test"))

        val msgs = capturedPrompt!!.messages
        assertEquals(3, msgs.size)
        assertTrue(msgs[0] is PromptMessage.System)
        assertTrue(msgs[1] is PromptMessage.User)
        assertTrue(msgs[2] is PromptMessage.Assistant)
    }

    @Test
    fun muxModelUsageTotalsTokens() {
        val usage = ModelUsage(promptTokens = 100, completionTokens = 50)
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun providerRegistryDescriptorsAreRegistered() {
        val registry = ProviderRegistry()
        registry.register(ModelProvider.OpenAI, ProviderDescriptor("p1", "P1", 0.0, 0))
        registry.register(ModelProvider.Anthropic, ProviderDescriptor("p2", "P2", 0.0, 0))

        val descriptors = registry.descriptors()
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.any { it.id == "p1" })
        assertTrue(descriptors.any { it.id == "p2" })
    }
}
