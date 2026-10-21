package modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import keymux.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModelAdmissionTest {
    @Test
    fun catalogAdmissionUsesObservedQuotaAndIncludesCredentialFreeModels() = runTest {
        val key = TestKeySource()
        val keys = KeyMux { bind("llm.provider.key", key) }
        val quota = QuotaLegion()
        val mux = ModelMux(keys) {
            quota(quota)
            model("remote", setOf("chat"), provider = "provider", modelVersion = "revision-1")
            model("unbound", setOf("chat"))
            model("local", setOf("chat"), baseUrl = "http://localhost:8123/v1", auth = LlmAuth.NONE)
        }
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

        assertEquals("llm.provider.key", mux.modelAdmission("remote", now)?.a)
        assertEquals(true, mux.modelAdmission("remote", now)?.b, "Unobserved capacity remains admitted")
        assertNull(mux.modelAdmission("unbound", now))
        assertNull(mux.modelAdmission("absent", now))
        val local = assertNotNull(mux.modelAdmission("local", now))
        assertNull(local.a)
        assertTrue(local.b)

        quota.observeHttp("llm.provider.key", "provider", 429, htxHeaders("Retry-After" j "600"), now)
        assertEquals(false, mux.modelAdmission("remote", now + 1)?.b)
        assertEquals(true, mux.modelAdmission("remote", now + 600_000)?.b)

        val roster = assertNotNull(BrainMuxNodes.registry(keyMux = keys, modelMux = mux)["mux.models"])
        val result = roster.run(LcncNode("models", "mux.models"), emptyMap())
        val models = assertIs<List<*>>(result["models"])
        val remote = models.filterIsInstance<Map<*, *>>().single { it["id"] == "remote" }
        val localRow = models.filterIsInstance<Map<*, *>>().single { it["id"] == "local" }
        assertEquals(2, models.size)
        assertEquals(false, remote["quotaAvailable"])
        assertEquals("revision-1", remote["modelVersion"])
        assertEquals(true, localRow["quotaAvailable"])
        assertNull(localRow["modelVersion"], "An ACP protocol version must not masquerade as a model revision")
    }
}
