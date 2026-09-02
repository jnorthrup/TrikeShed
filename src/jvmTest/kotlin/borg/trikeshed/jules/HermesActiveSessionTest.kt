package borg.trikeshed.jules

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HermesActiveSession answers what `hermes sessions` / `hermes resume` would:
 * the open, user-visible, most recently active row — never a delegate subagent
 * run or a compression continuation — and that row's runtime read exactly as
 * `SessionDB.session_gateway_runtime` reads it. Shapes mirror the trikeshed
 * profile's state.db on 2026-09-01: an open CLI session on glm-5.3-flash/zai
 * with newer delegate children on a different model.
 */
class HermesActiveSessionTest {

    private val t0 = 1_788_300_000.0
    private val zaiCoding = "https://api.z.ai/api/coding/paas/v4"

    private data class Row(
        val id: String,
        val source: String,
        val model: String?,
        val modelConfig: String?,
        val parent: String?,
        val billingProvider: String?,
        val startedAt: Double,
        val endedAt: Double?,
        val lastActivityAt: Double?,
    )

    private fun mkDb(dir: File, vararg rows: Row): File {
        dir.mkdirs()
        val db = File(dir, "state.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE sessions (
                        id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        model TEXT,
                        model_config TEXT,
                        parent_session_id TEXT,
                        started_at REAL NOT NULL,
                        ended_at REAL,
                        end_reason TEXT,
                        message_count INTEGER DEFAULT 0,
                        api_call_count INTEGER DEFAULT 0,
                        cwd TEXT,
                        billing_provider TEXT,
                        billing_base_url TEXT,
                        last_activity_at REAL
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement(
                "INSERT INTO sessions(id, source, model, model_config, parent_session_id, billing_provider, started_at, ended_at, last_activity_at, message_count, api_call_count, cwd) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                rows.forEachIndexed { i, r ->
                    ps.setString(1, r.id)
                    ps.setString(2, r.source)
                    ps.setString(3, r.model)
                    ps.setString(4, r.modelConfig)
                    ps.setString(5, r.parent)
                    ps.setString(6, r.billingProvider)
                    ps.setDouble(7, r.startedAt)
                    if (r.endedAt == null) ps.setNull(8, java.sql.Types.REAL) else ps.setDouble(8, r.endedAt)
                    if (r.lastActivityAt == null) ps.setNull(9, java.sql.Types.REAL) else ps.setDouble(9, r.lastActivityAt)
                    ps.setInt(10, 10 * (i + 1))
                    ps.setInt(11, i + 1)
                    ps.setString(12, "/Users/jim/work/TrikeShed")
                    ps.executeUpdate()
                }
            }
        }
        return db
    }

    private val liveConfig = """{"max_iterations": 500, "model": "glm-5.3-flash", "gateway_runtime": {"provider": "zai", "base_url": "$zaiCoding", "api_mode": "chat_completions"}, "provider": "zai", "base_url": "$zaiCoding", "api_mode": "chat_completions"}"""

    private fun fixture(): File {
        val root = Files.createTempDirectory("hermes-sessions").toFile()
        return mkDb(
            File(root, "profiles/src-trikeshed"),
            // The operator's CLI: open, root, resolved runtime in gateway_runtime.
            Row("cli-live", "cli", "glm-5.3-flash", liveConfig, null, "zai", t0, null, t0 + 100),
            // A NEWER delegate subagent on another model: hidden from listings.
            Row("sub-newer", "subagent", "gpt-5.6-sol-900k", """{"_delegate_from": "cli-live", "max_iterations": 250}""", "cli-live", "openai-codex", t0 + 150, null, t0 + 200),
            // A NEWER compression continuation (parented, unmarked): hidden too.
            Row("compress-cont", "cli", "glm-5.3-flash", """{"model": "glm-5.3-flash"}""", "cli-live", "zai", t0 + 250, null, t0 + 300),
            // A /branch child, ended: user-visible, but ended rows rank after open ones.
            Row("branch-ended", "cli", "glm-5.3", """{"_branched_from": "cli-live", "provider": "openrouter", "base_url": "https://openrouter.ai/api/v1"}""", "cli-live", "openrouter", t0 + 60, t0 + 90, t0 + 80),
            // An old root with no model_config at all: runtime from billing_provider.
            Row("old-root-billing", "cli", "stealth/ox-alpha", null, null, "nous", t0 - 1000, t0 - 900, null),
            // An older root whose billing bucket is bare: no routable provider.
            Row("old-root-bare", "cli", "kimi-k3", null, null, "custom", t0 - 2000, t0 - 1900, null),
        )
    }

    @Test
    fun currentIsTheOpenUserVisibleSessionNotTheNewerDelegate() {
        val s = HermesActiveSession.current(fixture())!!
        assertEquals("cli-live", s.id)
        assertTrue(s.isOpen)
        assertEquals("glm-5.3-flash", s.model)
        assertEquals(HermesActiveSession.Runtime("zai", zaiCoding, "chat_completions"), s.runtime)
        assertTrue(s.runtime.speaksChatCompletions)
        assertEquals(t0 + 100, s.recencyEpochSeconds)
        assertEquals("/Users/jim/work/TrikeShed", s.cwd)
        assertTrue(s.ledger.endsWith("state.db"))
    }

    @Test
    fun recentListsOpenFirstThenByRecencyAndHidesChildren() {
        val ids = HermesActiveSession.recent(fixture(), limit = 8).map { it.id }
        assertEquals(listOf("cli-live", "branch-ended", "old-root-billing", "old-root-bare"), ids)
        assertEquals(listOf("cli-live"), HermesActiveSession.recent(fixture(), limit = 1).map { it.id })
    }

    @Test
    fun withNoOpenSessionCurrentIsWhatResumeWouldReopen() {
        val root = Files.createTempDirectory("hermes-sessions-ended").toFile()
        val db = mkDb(
            root,
            Row("older", "cli", "a", liveConfig, null, "zai", t0, t0 + 10, t0 + 5),
            Row("newer", "cli", "b", liveConfig, null, "zai", t0 - 500, t0 + 60, t0 + 50),
            Row("sub", "subagent", "c", """{"_delegate_from": "newer"}""", "newer", "zai", t0 + 70, t0 + 80, t0 + 75),
        )
        val s = HermesActiveSession.current(db)!!
        assertEquals("newer", s.id)
        assertFalse(s.isOpen)
    }

    @Test
    fun listablePredicateMirrorsHermes() {
        assertTrue(HermesActiveSession.listable(null, null), "a root is visible")
        assertTrue(HermesActiveSession.listable(null, mapOf("model" to "x")))
        assertFalse(HermesActiveSession.listable(null, mapOf("_delegate_from" to "p")), "a delegate is hidden even as a root")
        assertFalse(HermesActiveSession.listable("p", null), "a parented, unmarked row is a continuation")
        assertFalse(HermesActiveSession.listable("p", mapOf("model" to "x")))
        assertTrue(HermesActiveSession.listable("p", mapOf("_branched_from" to "p")), "a /branch child is visible")
        assertFalse(HermesActiveSession.listable("p", mapOf("_branched_from" to "p", "_delegate_from" to "q")))
    }

    @Test
    fun runtimeMirrorsSessionGatewayRuntimeFallbacks() {
        val rt = HermesActiveSession::runtime
        // gateway_runtime wins when it names a provider; None values are dropped.
        assertEquals(
            HermesActiveSession.Runtime("zai", zaiCoding, "chat_completions"),
            rt(HermesActiveSession.parseModelConfig(liveConfig), "nous"),
        )
        assertEquals(
            HermesActiveSession.Runtime("zai", null, null),
            rt(mapOf("gateway_runtime" to mapOf("provider" to "zai", "base_url" to null)), "nous"),
        )
        // gateway_runtime without a provider falls to the top-level keys.
        assertEquals(
            HermesActiveSession.Runtime("openrouter", "https://openrouter.ai/api/v1", null),
            rt(mapOf("gateway_runtime" to mapOf("base_url" to "x"), "provider" to "openrouter", "base_url" to "https://openrouter.ai/api/v1"), "nous"),
        )
        // Any single top-level key is an answer (Hermes returns the partial dict).
        assertEquals(HermesActiveSession.Runtime(null, null, "responses"), rt(mapOf("api_mode" to "responses"), "nous"))
        // Nothing routable in model_config: billing_provider alone, no base url.
        assertEquals(HermesActiveSession.Runtime("nous", null, null), rt(mapOf("model" to "m"), "nous"))
        assertEquals(HermesActiveSession.Runtime("nous", null, null), rt(null, "  nous "))
        // Bare billing buckets are not identities.
        assertEquals(HermesActiveSession.Runtime(null, null, null), rt(null, "custom"))
        assertEquals(HermesActiveSession.Runtime(null, null, null), rt(null, "AUTO"))
        assertEquals(HermesActiveSession.Runtime(null, null, null), rt(null, null))
        // Blank strings are absent, like Hermes' falsy filter.
        assertEquals(HermesActiveSession.Runtime("zai", null, null), rt(mapOf("gateway_runtime" to mapOf("provider" to "zai", "base_url" to "  ")), null))
    }

    @Test
    fun apiModeGatesWhatBrainClientCanBorrow() {
        assertTrue(HermesActiveSession.Runtime("zai", zaiCoding, null).speaksChatCompletions)
        assertTrue(HermesActiveSession.Runtime("zai", zaiCoding, "chat_completions").speaksChatCompletions)
        assertFalse(HermesActiveSession.Runtime("openai-codex", "https://chatgpt.com/backend-api/codex", "responses").speaksChatCompletions)
        assertFalse(HermesActiveSession.Runtime("anthropic", "https://api.anthropic.com", "anthropic_messages").speaksChatCompletions)
    }

    @Test
    fun unparsableModelConfigFallsBackToBillingAndBrokenDbsAnswerEmpty() {
        assertNull(HermesActiveSession.parseModelConfig(null))
        assertNull(HermesActiveSession.parseModelConfig("   "))
        assertNull(HermesActiveSession.parseModelConfig("{not json"))
        assertNull(HermesActiveSession.parseModelConfig("[1,2]"), "a non-object is not a model_config")
        val root = Files.createTempDirectory("hermes-sessions-missing").toFile()
        assertNull(HermesActiveSession.current(File(root, "state.db")))
        assertEquals(emptyList(), HermesActiveSession.recent(File(root, "state.db")))
        File(root, "state.db").writeText("not a database")
        assertNull(HermesActiveSession.current(File(root, "state.db")))
        // A db without a sessions table at all.
        val bare = File(root, "bare").also { it.mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(bare, "state.db").absolutePath}").use { it.createStatement().executeUpdate("CREATE TABLE t(x)") }
        assertNull(HermesActiveSession.current(File(bare, "state.db")))
    }

    @Test
    fun stateDbFollowsTheSharedHermesHome() {
        assertEquals(File("/x/profiles/p", "state.db"), HermesActiveSession.stateDb("/x/profiles/p"))
        assertEquals(HermesModelUsage.stateDb(), HermesActiveSession.stateDb())
    }
}
