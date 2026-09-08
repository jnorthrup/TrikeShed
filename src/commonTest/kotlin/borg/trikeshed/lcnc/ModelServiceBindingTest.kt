package borg.trikeshed.lcnc

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.view
import keymux.CouchKeyStore
import keymux.FixedKeySource
import keymux.KeyMux
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import modelmux.ModelMux
import modelmux.MuxCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelServiceBindingTest {
    @Test
    fun modelProviderDefaultIsResolvedPerCallAndCallerKeyOverridesIt() = runTest {
        val keys = KeyMux { bind("*", FixedKeySource("fixture-key")) }
        val first = ModelMux(keys) { model("first", setOf("chat")) }
        val second = ModelMux(keys) { model("second", setOf("chat")) }
        var selected = first
        var defaults = 0
        val registry = BrainMuxNodes.registry(modelMuxProvider = {
            defaults++
            assertNotNull(currentCoroutineContext()[ModelMuxProviderKey])
            assertNotNull(currentCoroutineContext()[LcncNodeKey.of("mux.models")!!])
            selected
        })
        val node = LcncNode("models", "mux.models")
        fun ids(output: Map<String, Any?>) = (output["models"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(listOf("first"), ids(registry.getValue("mux.models").run(node, emptyMap())))
        selected = second
        assertEquals(listOf("second"), ids(registry.getValue("mux.models").run(node, emptyMap())))
        val override = ModelMuxProviderKey { first }
        withContext(override) {
            assertEquals(listOf("first"), ids(registry.getValue("mux.models").run(node, emptyMap())))
            assertSame(override, currentCoroutineContext()[ModelMuxProviderKey])
        }
        withContext(ModelMuxProviderKey { null }) {
            assertTrue(ids(registry.getValue("mux.models").run(node, emptyMap())).isEmpty())
        }
        assertEquals(2, defaults)
    }

    @Test
    fun keyMuxCallerProviderReplacesTheRegistryDefault() = runTest {
        val default = KeyMux { bind("*", FixedKeySource("fixture-key")) }
        val override = KeyMux {}
        val runner = BrainMuxNodes.registry(keyMux = default).getValue("keys.status")
        val node = LcncNode("keys", "keys.status")
        val before = runner.run(node, emptyMap())
        assertTrue((before["have"] as List<*>).isNotEmpty())
        var resolved = false
        withContext(KeyMuxProviderKey {
            resolved = true
            assertNotNull(currentCoroutineContext()[KeyMuxProviderKey])
            override
        }) {
            val after = runner.run(node, emptyMap())
            assertTrue((after["have"] as List<*>).isEmpty())
            assertTrue((after["missing"] as List<*>).isNotEmpty())
        }
        assertTrue(resolved)
    }

    @Test
    fun credentialReadsAndWritesUseTheSelectedBorrowedStore() = runTest {
        val defaultCas = CasStore.inMemory()
        val selectedCas = CasStore.inMemory()
        val default = CouchKeyStore(Couch("default", CouchStoreFactory.casBacked(defaultCas), defaultCas))
        val selected = CouchKeyStore(Couch("selected", CouchStoreFactory.casBacked(selectedCas), selectedCas))
        val registry = BrainMuxNodes.registry(credStore = default)
        val enter = LcncNode("credential", "credential.enter", mapOf(
            "key_type" to "fixture", "key" to "fixture-credential", "url" to "https://example.invalid/v1",
        ))
        val binding = CredentialStoreKey(selected)
        withContext(binding) {
            registry.getValue("credential.enter").run(enter, emptyMap())
            registry.getValue("credential.enter").run(enter, emptyMap())
            val listed = registry.getValue("credential.list").run(LcncNode("list", "credential.list"), emptyMap())
            assertEquals(listOf("fixture"), listed["stored"])
            assertSame(binding, currentCoroutineContext()[CredentialStoreKey])
        }
        assertTrue(default.listProviders().isEmpty())
        assertEquals("fixture-credential", selected.readCredential("fixture")?.get("key"))
        registry.getValue("credential.enter").run(enter, emptyMap())
        assertEquals(listOf("fixture"), default.listProviders())
    }

    @Test
    fun promptChatUsesCallerModelProviderAndKeepsHtxAndAttribution() = runTest {
        val keys = KeyMux { bind("*", FixedKeySource("fixture-key")) }
        val models = ModelMux(keys) { model("selected", setOf("chat"), baseUrl = "https://example.invalid/v1") }
        var requests = 0
        val attribution = MuxCallContext(conversationId = 17, turnId = 23)
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests++
                assertNotNull(currentCoroutineContext()[HtxKey])
                assertEquals(17L, currentCoroutineContext()[MuxCallContext]?.conversationId)
                assertEquals(23L, currentCoroutineContext()[MuxCallContext]?.turnId)
                val response = HtxResponse(200, body = ByteSeries(
                    """{"choices":[{"message":{"content":"selected answer"}}],"usage":{"prompt_tokens":2,"completion_tokens":3}}""".encodeToByteArray()
                ))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        try {
            val runner = BrainMuxNodes.registry(modelMuxProvider = { error("default must not be called") }).getValue("prompt.chat")
            withContext(htx + attribution + ModelMuxProviderKey { models }) {
                repeat(2) { index ->
                    val out = runner.run(LcncNode("chat", "prompt.chat", mapOf("model" to "selected")), mapOf("prompt" to "request-$index"))
                    assertEquals(true, out["ok"])
                    assertEquals("selected answer", out["content"])
                    assertSame(htx, currentCoroutineContext()[HtxKey])
                    assertSame(attribution, currentCoroutineContext()[MuxCallContext])
                    assertTrue(htx.supervisor.isActive)
                }
            }
            assertEquals(2, requests)
        } finally {
            htx.close()
        }
    }

    @Test
    fun brainMuxMetadataNamesOnlyConsumedServiceKeys() {
        val registry = BrainMuxNodes.registry()
        val expected = mapOf(
            "keys.status" to setOf(KeyMuxProviderKey),
            "mux.models" to setOf(ModelMuxProviderKey),
            "mux.meta" to setOf(ModelMuxProviderKey),
            "credential.enter" to setOf(CredentialStoreKey),
            "credential.list" to setOf(CredentialStoreKey),
            "prompt.chat" to setOf(ModelMuxProviderKey, CredentialStoreKey),
        )
        for ((type, keys) in expected) {
            val binding = registry.getValue(type) as LcncServiceBinding
            assertEquals(keys, binding.requiredKeys.view.toSet(), type)
            assertEquals(keys, binding.providedKeys.view.toSet(), type)
        }
        assertFalse(registry.getValue("note") is LcncServiceBinding)
        assertFalse(registry.getValue("result.confirm") is LcncServiceBinding)
    }
}
