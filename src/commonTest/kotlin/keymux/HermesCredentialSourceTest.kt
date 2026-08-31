package keymux

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are hand-authored JSON in the shape Hermes' `agent/credential_pool.py`
 * (`PooledCredential.to_dict`) actually writes to `<hermesHome>/auth.json` — never
 * the real file. `epoch seconds` fields (`last_status_at`, `last_error_reset_at`)
 * match Hermes' `time.time()` convention, not milliseconds.
 */
class HermesCredentialSourceTest {

    private fun fakeFs(authJson: String): KeyMuxTest.FakeFileOperations =
        KeyMuxTest.FakeFileOperations(mutableMapOf("/hermes/auth.json" to authJson.encodeToByteArray()))

    @Test
    fun resolvesKeyAndBaseUrlForLowestPriorityActiveEntry() = runTest {
        val json = """
            {"credential_pool": {"openrouter": [
                {"provider":"openrouter","id":"b","label":"backup","priority":5,
                 "auth_type":"api_key","source":"manual","access_token":"sk-backup",
                 "base_url":"https://openrouter.ai/api/v1","last_status":"ok"},
                {"provider":"openrouter","id":"a","label":"primary","priority":0,
                 "auth_type":"api_key","source":"manual","access_token":"sk-primary",
                 "base_url":"https://openrouter.ai/api/v1","last_status":"ok"}
            ]}}
        """.trimIndent()
        val src = HermesCredentialSource("/hermes", fakeFs(json))
        assertEquals("sk-primary", src.read("llm.openrouter.key".toKeyPath()), "priority 0 beats priority 5")
        assertEquals("https://openrouter.ai/api/v1", src.read("llm.openrouter.base_url".toKeyPath()))
    }

    @Test
    fun skipsDeadEntriesEntirely() = runTest {
        val json = """
            {"credential_pool": {"zai": [
                {"provider":"zai","id":"a","label":"dead-key","priority":0,
                 "auth_type":"api_key","source":"manual","access_token":"sk-dead",
                 "last_status":"dead"},
                {"provider":"zai","id":"b","label":"live-key","priority":9,
                 "auth_type":"api_key","source":"manual","access_token":"sk-live",
                 "last_status":"ok"}
            ]}}
        """.trimIndent()
        val src = HermesCredentialSource("/hermes", fakeFs(json))
        assertEquals("sk-live", src.read("llm.zai.key".toKeyPath()), "dead never wins even at top priority")
    }

    @Test
    fun exhaustedKeyIsSkippedUntilItsOwnCooldownClears() = runTest {
        val future = (kotlinx.datetime.Clock.System.now().toEpochMilliseconds() / 1000.0) + 3600.0
        val past = (kotlinx.datetime.Clock.System.now().toEpochMilliseconds() / 1000.0) - 60.0
        val stillCoolingDown = """
            {"credential_pool": {"minimax": [
                {"provider":"minimax","id":"a","label":"exhausted","priority":0,
                 "auth_type":"api_key","source":"manual","access_token":"sk-exhausted",
                 "last_status":"exhausted","last_error_reset_at":$future},
                {"provider":"minimax","id":"b","label":"fallback","priority":9,
                 "auth_type":"api_key","source":"manual","access_token":"sk-fallback",
                 "last_status":"ok"}
            ]}}
        """.trimIndent()
        val cooled = stillCoolingDown.replace(future.toString(), past.toString())

        assertEquals(
            "sk-fallback",
            HermesCredentialSource("/hermes", fakeFs(stillCoolingDown)).read("llm.minimax.key".toKeyPath()),
            "still cooling down — the exhausted key must not win despite lower priority",
        )
        assertEquals(
            "sk-exhausted",
            HermesCredentialSource("/hermes", fakeFs(cooled)).read("llm.minimax.key".toKeyPath()),
            "cooldown elapsed — the exhausted key is usable again and outranks fallback by priority",
        )
    }

    @Test
    fun allExhaustedAndUncooledFallsBackToBestByPriorityRatherThanNull() = runTest {
        val future = (kotlinx.datetime.Clock.System.now().toEpochMilliseconds() / 1000.0) + 3600.0
        val json = """
            {"credential_pool": {"deepseek": [
                {"provider":"deepseek","id":"a","label":"only","priority":0,
                 "auth_type":"api_key","source":"manual","access_token":"sk-only",
                 "last_status":"exhausted","last_error_reset_at":$future}
            ]}}
        """.trimIndent()
        val src = HermesCredentialSource("/hermes", fakeFs(json))
        assertEquals(
            "sk-only",
            src.read("llm.deepseek.key".toKeyPath()),
            "no usable entry exists — degrade to best-by-priority rather than leaving the caller with nothing",
        )
    }

    @Test
    fun unknownProviderAndMissingFileAreNullNotError() = runTest {
        val src = HermesCredentialSource("/hermes", fakeFs("""{"credential_pool":{}}"""))
        assertNull(src.read("llm.nonexistent.key".toKeyPath()))
        assertNull(HermesCredentialSource("/nowhere", fakeFs("{}")).read("llm.openrouter.key".toKeyPath()))
    }

    @Test
    fun writeIsRefused() = runTest {
        val src = HermesCredentialSource("/hermes", fakeFs("""{"credential_pool":{}}"""))
        var threw = false
        try {
            src.write("llm.openrouter.key".toKeyPath(), "sk-anything")
        } catch (_: UnsupportedOperationException) {
            threw = true
        }
        assertTrue(threw, "Hermes owns its pool file — this source must never write")
    }

    @Test
    fun keyEntriesProjectsEveryPoolRowForQuotaLegion() = runTest {
        val json = """
            {"credential_pool": {"xai": [
                {"provider":"xai","id":"a","label":"one","priority":0,
                 "auth_type":"api_key","source":"manual","access_token":"sk-a",
                 "last_status":"ok","request_count":42},
                {"provider":"xai","id":"b","label":"two","priority":1,
                 "auth_type":"api_key","source":"manual","access_token":"sk-b",
                 "last_status":"exhausted"}
            ]}}
        """.trimIndent()
        val entries = HermesCredentialSource("/hermes", fakeFs(json)).keyEntries()
        assertEquals(2, entries.size)
        val byId = (0 until entries.size).map { entries[it] }.associateBy { it.keyId }
        assertEquals(MuxKeyStatus.ACTIVE, byId.getValue("xai:a").status)
        assertEquals(42L, byId.getValue("xai:a").accessCount)
        assertEquals(MuxKeyStatus.BACKOFF, byId.getValue("xai:b").status)
    }

    @Test
    fun builderConvenienceBindsUnderLlmWildcard() = runTest {
        val json = """{"credential_pool": {"gemini": [
            {"provider":"gemini","id":"a","label":"g","priority":0,
             "auth_type":"api_key","source":"manual","access_token":"sk-gem","last_status":"ok"}
        ]}}"""
        val mux = KeyMux {
            bind("llm.*.*", HermesCredentialSource("/hermes", fakeFs(json)))
        }
        assertEquals("sk-gem", mux.get("llm.gemini.key"))
    }

    // ── env:<VAR> indirection — hermes externalizes pool secrets ─────────

    /** Source whose env lookup rides the test [envScope] (JVM getenv is read-only). */
    private fun srcWithEnv(hermesHome: String, fs: KeyMuxTest.FakeFileOperations) =
        HermesCredentialSource(hermesHome, fs) { envScope.lookup(it) }

    @Test
    fun envIndirectionAnswersFromProcessEnv() = runTest {
        val json = """{"credential_pool": {"opencode-go": [
            {"provider":"opencode-go","id":"a","label":"go","priority":0,
             "auth_type":"api_key","source":"env:HERMES_POOL_TEST_VAR",
             "base_url":"https://opencode.ai/zen/go/v1","last_status":"ok"}
        ]}}"""
        assertEquals("sk-pooltest", envScope.with(mapOf("HERMES_POOL_TEST_VAR" to "sk-pooltest")) {
            srcWithEnv("/hermes", fakeFs(json)).read("llm.opencode-go.key".toKeyPath())
        }, "env:VAR must follow the operator's process env")
    }

    @Test
    fun envIndirectionFallsBackToHermesDotenv() = runTest {
        // VAR absent from process env — only the hermes .env chain carries it.
        val json = """{"credential_pool": {"opencode-zen": [
            {"provider":"opencode-zen","id":"a","label":"zen","priority":0,
             "auth_type":"api_key","source":"env:HERMES_POOL_TEST_ZEN",
             "base_url":"https://opencode.ai/zen/v1","last_status":"ok"}
        ]}}"""
        val fs = KeyMuxTest.FakeFileOperations(mutableMapOf(
            "/hermes/auth.json" to json.encodeToByteArray(),
            "/home/.hermes/.env" to "HERMES_POOL_TEST_ZEN=sk-dotenv\n".encodeToByteArray(),
        ))
        val getenv = { name: String ->
            when (name) {
                "HOME" -> "/home"          // pins the dotenv chain to the fake fs
                "HERMES_POOL_TEST_ZEN" -> null  // not in process env
                else -> null
            }
        }
        val src = HermesCredentialSource("/hermes", fs, getenv)
        assertEquals("sk-dotenv", src.read("llm.opencode-zen.key".toKeyPath()),
            "env:VAR with no process value must fall through to the hermes .env files")
    }

    @Test
    fun unresolvableEnvEntryDoesNotWin() = runTest {
        // Priority-0 row references a VAR nobody set; priority-9 row is inline.
        // The unresolvable row must NOT win — the answerable one does.
        val json = """{"credential_pool": {"nvidia": [
            {"provider":"nvidia","id":"a","label":"env-missing","priority":0,
             "auth_type":"api_key","source":"env:HERMES_POOL_TEST_MISSING","last_status":"ok"},
            {"provider":"nvidia","id":"b","label":"inline","priority":9,
             "auth_type":"api_key","source":"manual","access_token":"sk-inline","last_status":"ok"}
        ]}}"""
        val src = HermesCredentialSource("/hermes", fakeFs(json), { null })
        assertEquals("sk-inline", src.read("llm.nvidia.key".toKeyPath()),
            "a pool row whose env:VAR cannot be resolved must fall through to the next answerable entry")
    }

    @Test
    fun defaultPoolIsMergedInWhenProfilePoolIsSparse() = runTest {
        // Provider only in the profile pool; another provider only in default.
        val profile = """{"credential_pool": {"zai": [
            {"provider":"zai","id":"a","label":"z","priority":0,
             "auth_type":"api_key","source":"manual","access_token":"sk-zai","last_status":"ok"}
        ]}}"""
        val default = """{"credential_pool": {"copilot": [
            {"provider":"copilot","id":"gh","label":"gh","priority":0,
             "auth_type":"api_key","source":"manual","access_token":"sk-gh","last_status":"ok"}
        ]}}"""
        val fs = KeyMuxTest.FakeFileOperations(mutableMapOf(
            "/hermes/auth.json" to profile.encodeToByteArray(),
            "~/.hermes/auth.json" to default.encodeToByteArray(),
        ))
        val src = HermesCredentialSource("/hermes", fs)
        assertEquals("sk-zai", src.read("llm.zai.key".toKeyPath()), "profile pool provider")
        assertEquals("sk-gh", src.read("llm.copilot.key".toKeyPath()), "default-only provider must be reachable")
    }
}

/**
 * The JVM's `System.getenv` is read-only, so the source's env lookup is a
 * constructor seam. This object re-routes lookups for the duration of a test
 * (single-threaded runTest): a pinned var wins, everything else falls through
 * to the real process env.
 */
private class EnvScope {
    private val pinned = mutableMapOf<String, String>()

    fun lookup(name: String): String? = pinned[name] ?: SystemOperations.default.getenv(name)

    suspend fun <T> with(pinnedVars: Map<String, String>, block: suspend () -> T): T {
        pinned.putAll(pinnedVars)
        try {
            return block()
        } finally {
            pinned.keys.removeAll { pinnedVars.containsKey(it) }
        }
    }
}

private val envScope = EnvScope()
