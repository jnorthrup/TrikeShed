package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.acp.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class ModelMuxIdentityTest {
    @Test fun modelCallsDoNotReleaseAnotherOwnersLease() = runTest {
        val keys = KeyMux { bind("llm.first.key", TestKeySource(value = "first-secret")) }
        val mux = ModelMux(keys) {
            model("first/model", caps = _s["chat", "embed"], provider = "first", baseUrl = "https://first.invalid/v1")
        }
        assertEquals("llm.first.key", mux.session("first/model").getOrThrow().keyId)
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.first.key", "first")
        assertNotNull(reactor.acquireLease("other-owner", provider = "first"))
        withContext(reactor) {
            // No HTTP transport is installed, so each call ends after key resolution.
            assertContains(mux.chat("first/model", s_["user" j "hello"]).exceptionOrNull()?.message.orEmpty(), "No HtxKey")
            assertEquals("other-owner", reactor.flowState.value.leases.first().leasedTo)
            assertContains(assertFailsWith<IllegalStateException> { mux.stream("first/model", s_["user" j "hello"]).toList() }.message.orEmpty(), "No HtxKey")
            assertEquals("other-owner", reactor.flowState.value.leases.first().leasedTo)
            assertContains(assertFailsWith<IllegalStateException> { mux.embed("first/model", s_["hello"]) }.message.orEmpty(), "No HtxKey")
            assertEquals("other-owner", reactor.flowState.value.leases.first().leasedTo)
        }
        reactor.close()
    }

    @Test fun duplicateModelIdCannotJoinOneProvidersCredentialToAnotherDestination() = runTest {
        val keys = KeyMux {
            bind("llm.first.key", TestKeySource(value = "first-secret"))
            bind("llm.second.key", TestKeySource(value = "second-secret"))
        }
        val mux = ModelMux(keys) {
            model("same", caps = _s["chat"], provider = "first", baseUrl = "https://first.invalid/v1", contextWindow = 4096)
            model("same", caps = _s["chat"], provider = "second", baseUrl = "https://second.invalid/v1")
            model("same-auth", caps = _s["chat"], provider = "first", baseUrl = "https://first.invalid/v1")
            model("same-auth", caps = _s["chat"], provider = "second", baseUrl = "https://second.invalid/v1", auth = LlmAuth.NONE)
            model("first/same", caps = _s["chat"], provider = "first", wireModel = "same", baseUrl = "https://first.invalid/v1")
            model("second/same", caps = _s["chat"], provider = "second", wireModel = "same", baseUrl = "https://second.invalid/v1")
        }
        assertContains(mux.session("same").exceptionOrNull()?.message.orEmpty(), "ambiguous model id")
        assertNull(mux.modelKeyId("same"))
        assertNull(mux.modelAdmission("same"))
        assertNull(mux.contextWindow("same"))
        assertNull(mux.configuredBaseUrl("same"))
        assertEquals(LlmAuth.REQUIRED, mux.auth("same-auth"))
        assertContains(mux.session("same-auth").exceptionOrNull()?.message.orEmpty(), "ambiguous model id")
        var failedProvider = ""
        val failed = withContext(MuxCallContext(onFinished = { failedProvider = it.provider })) {
            mux.chat("same", s_["user" j "hello"])
        }
        assertContains(failed.exceptionOrNull()?.message.orEmpty(), "ambiguous model id")
        assertEquals("ambiguous", failedProvider)
        assertContains(assertFailsWith<IllegalStateException> { mux.stream("same", s_["user" j "hello"]).toList() }.message.orEmpty(), "ambiguous model id")
        assertContains(assertFailsWith<IllegalStateException> { mux.embed("same", s_["hello"]) }.message.orEmpty(), "ambiguous model id")

        for ((id, base, secret) in _l[
            Triple("first/same", "https://first.invalid/v1", "Bearer first-secret"),
            Triple("second/same", "https://second.invalid/v1", "Bearer second-secret"),
        ]) {
            val session = mux.session(id).getOrThrow()
            assertEquals(base, session.baseUrl)
            assertEquals("same", session.model.b.wireName)
            assertEquals(secret, session.authHeaders().view.first { it.a == "Authorization" }.b)
        }

        // An internally constructed core must enforce the same session boundary.
        val cards = mux.listModels()
        val direct = ModelMux((cards α { it.id j it }) j CapabilityRouter, keys,
            _m["same" j "https://second.invalid/v1"])
        assertContains(direct.session("same").exceptionOrNull()?.message.orEmpty(), "ambiguous model id")

        val strictCards = ModelMux(keys) {
            model("strict", caps = _s["chat"], provider = "first", providerOnly = true, baseUrl = "https://first.invalid/v1")
        }.listModels()
        val unauthenticated = ModelMux((strictCards α { it.id j it }) j CapabilityRouter, keys,
            _m["strict" j "https://first.invalid/v1"], configuredAuth = _m["strict" j LlmAuth.NONE])
        assertNull(unauthenticated.modelAdmission("strict"))
        assertNull(unauthenticated.modelKeyId("strict"))
        assertTrue(unauthenticated.session("strict").isFailure)
        assertFailsWith<IllegalArgumentException> {
            ModelMux(keys) {
                model("strict", caps = _s["chat"], provider = "first", providerOnly = true,
                    auth = LlmAuth.NONE, baseUrl = "https://first.invalid/v1")
            }
        }
    }
}
