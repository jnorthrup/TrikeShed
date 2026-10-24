package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaConfig
import borg.trikeshed.narsese.JevaElement
import borg.trikeshed.narsese.JevaRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in: drives the REAL local 4-bit MLX Jeva through the production [JevaElement] -> Python MLX boundary.
 *
 *   source config/jeva-mlx.env.sh && TRIKESHED_TEST_JEVA_MLX=1 ./gradlew --offline :jvmTest --rerun \
 *       --tests 'borg.trikeshed.agent.modelmux.JevaOversightMlxIntegrationTest'
 *
 * Without the flag it is SKIPPED (not passed). Nothing is downloaded: the config is local-only by construction.
 */
class JevaOversightMlxIntegrationTest {
    @Test
    fun realMlxJevaRanksTheRelevantExcerptAboveTheIrrelevantOne() = runBlocking {
        assumeTrue(System.getenv("TRIKESHED_TEST_JEVA_MLX") == "1", "set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")

        val config = JevaConfig.configured({ null }, { System.getenv(it) })
        assertEquals("mlx", config.device, "source config/jeva-mlx.env.sh first")
        assertEquals(JevaRuntime.PYTHON, config.runtime)

        // Lexically both lines are 'manifest' lines, so the heuristic selects both; only meaning separates them.
        val snap = snapshotOf(
            "notes.md" to "The manifest must be verified against its sha256 before any block is cached.\n",
            "cargo.md" to "Cargo note: the manifest of the container ship lists forty crates of oranges.\n",
        )
        val question = "How is the manifest verified before blocks are cached?"
        val projection = BreadcrumbProjector.project(snap, question)
        assertEquals(2, projection.excerpts.size)

        val owner = SupervisorJob()
        val jeva = JevaElement(CoroutineScope(coroutineContext + owner), config)
        try {
            val receipt = JevaOversight { state, questions -> jeva.ask(state, questions) }.review(question, projection)

            println("MLX-JEVA model=${receipt.model} status=${receipt.status} detail=${receipt.detail}")
            for (i in 0 until projection.excerpts.size) {
                println("MLX-JEVA ${projection.excerpts[i].document} p=${receipt.probabilities[i]} verdict=${receipt.verdicts[i]}")
            }
            assertEquals(OversightStatus.REVIEWED, receipt.status, receipt.detail)
            assertNotNull(receipt.model)

            val byDoc = (0 until projection.excerpts.size).associate { projection.excerpts[it].document to receipt.probabilities[it]!! }
            assertTrue(byDoc.getValue("notes.md") > byDoc.getValue("cargo.md"), "relevant ${byDoc["notes.md"]} should exceed irrelevant ${byDoc["cargo.md"]}")
        } finally {
            jeva.drain()
            owner.cancel()
        }
    }
}
