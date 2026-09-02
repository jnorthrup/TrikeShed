package borg.trikeshed.jules

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The usage ledger is `$HERMES_HOME/state.db` — the profile the daemon shares
 * with Hermes — read newest-first with no ranking of its own. Shapes mirror
 * `~/.hermes/profiles/src-trikeshed/state.db` on 2026-09-01.
 */
class HermesModelUsageTest {

    private val t0 = 1_788_300_000.0

    private data class Row(val model: String, val provider: String, val baseUrl: String, val task: String, val calls: Int, val lastSeen: Double)

    private fun mkLedger(dir: File, vararg rows: Row): File {
        dir.mkdirs()
        val db = File(dir, "state.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE session_model_usage (
                        session_id TEXT NOT NULL,
                        model TEXT NOT NULL,
                        billing_provider TEXT NOT NULL DEFAULT '',
                        billing_base_url TEXT NOT NULL DEFAULT '',
                        billing_mode TEXT NOT NULL DEFAULT '',
                        task TEXT NOT NULL DEFAULT '',
                        api_call_count INTEGER NOT NULL DEFAULT 0,
                        input_tokens INTEGER NOT NULL DEFAULT 0,
                        output_tokens INTEGER NOT NULL DEFAULT 0,
                        first_seen REAL,
                        last_seen REAL,
                        PRIMARY KEY (session_id, model, billing_provider, billing_base_url, billing_mode, task)
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement(
                "INSERT INTO session_model_usage(session_id, model, billing_provider, billing_base_url, task, api_call_count, input_tokens, output_tokens, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                rows.forEachIndexed { i, r ->
                    ps.setString(1, "s$i"); ps.setString(2, r.model); ps.setString(3, r.provider); ps.setString(4, r.baseUrl)
                    ps.setString(5, r.task); ps.setInt(6, r.calls); ps.setLong(7, 100L); ps.setLong(8, 50L)
                    ps.setDouble(9, r.lastSeen - 10); ps.setDouble(10, r.lastSeen)
                    ps.executeUpdate()
                }
            }
        }
        return db
    }

    private val zaiCoding = "https://api.z.ai/api/coding/paas/v4"
    private val synthetic = "https://api.synthetic.new/openai/v1/"

    private fun fixture(): File = mkLedger(
        Files.createTempDirectory("hermes-ledger").toFile(),
        Row("glm-5.3-flash", "zai", zaiCoding, "", 65, t0 + 100),
        Row("zai-org/GLM-4.7-Flash", "custom:api.synthetic.new", synthetic, "approval", 11, t0 + 200),
        Row("glm-5.3", "zai", zaiCoding, "", 8, t0 + 50),
        Row("google/gemini-3.6-flash", "", "", "prompt-refinement", 4, t0 + 20),
    )

    @Test
    fun recentIsNewestFirstWithNoRankingOfItsOwn() {
        val rows = HermesModelUsage.recent(fixture())
        assertEquals(listOf("zai-org/GLM-4.7-Flash", "glm-5.3-flash", "glm-5.3", "google/gemini-3.6-flash"), rows.map { it.model })
        assertEquals(listOf("approval", "", "", "prompt-refinement"), rows.map { it.task })
        assertTrue(rows.all { it.ledger.endsWith("state.db") })
        assertEquals(listOf("zai-org/GLM-4.7-Flash", "glm-5.3-flash"), HermesModelUsage.recent(fixture(), limit = 2).map { it.model })
    }

    @Test
    fun lastUsedIsTheNewestRowWhateverItsTask() {
        val last = HermesModelUsage.lastUsed(fixture())!!
        assertEquals("zai-org/GLM-4.7-Flash", last.model)
        assertEquals("approval", last.task)
        assertEquals(11, last.calls)
    }

    @Test
    fun provenEndpointsAreDistinctNewestFirstAndSkipBlankUrls() {
        assertEquals(
            listOf("custom:api.synthetic.new" to synthetic, "zai" to zaiCoding),
            HermesModelUsage.provenEndpoints(fixture()),
        )
    }

    @Test
    fun missingOrBrokenLedgersAnswerEmpty() {
        val root = Files.createTempDirectory("hermes-ledger-missing").toFile()
        val missing = File(root, "state.db")
        assertEquals(emptyList(), HermesModelUsage.recent(missing))
        assertNull(HermesModelUsage.lastUsed(missing))
        assertEquals(emptyList(), HermesModelUsage.provenEndpoints(missing))
        missing.writeText("this is not a database")
        assertEquals(emptyList(), HermesModelUsage.recent(missing))
    }

    @Test
    fun hermesHomeFollowsHermesHomeEnvThenDefault() {
        val userHome = System.getProperty("user.home")
        assertEquals("/x/profiles/p", HermesModelUsage.hermesHome { if (it == "HERMES_HOME") "/x/profiles/p" else null })
        assertEquals("$userHome/.hermes", HermesModelUsage.hermesHome { null })
        assertEquals("$userHome/.hermes", HermesModelUsage.hermesHome { if (it == "HERMES_HOME") "  " else null })
        assertEquals(File("/x/profiles/p", "state.db"), HermesModelUsage.stateDb("/x/profiles/p"))
    }
}
