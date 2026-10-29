package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.narsese.Jeva
import borg.trikeshed.narsese.JevaConfig
import borg.trikeshed.narsese.JevaElement
import borg.trikeshed.narsese.JevaInference
import borg.trikeshed.parse.jsonOf
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.coroutines.coroutineContext
import kotlin.test.*

class MlxJevaTest {
    private val task = "How is the manifest verified before blocks are cached?"
    private val state = mapOf("task" to task, "spans" to listOf(
        mapOf("id" to "span-0", "document" to "cargo.md", "lines" to "1-1",
            "text" to "Cargo note: the manifest of the container ship lists forty crates of oranges."),
        mapOf("id" to "span-1", "document" to "notes.md", "lines" to "1-1",
            "text" to "The manifest must be verified against its sha256 before any block is cached.")))
    private val questions = (0..1).associate { i ->
        "span-$i" to mapOf("type" to "noul",
            "instructions" to "Is excerpt span-$i in the request state relevant to the task question?",
            "criteria" to mapOf("true" to "the excerpt helps answer the task question",
                "false" to "the excerpt does not help answer the task question"))
    }

    private fun model(name: String): String? {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED $name: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return null
        }
        return model
    }

    private fun ranked(label: String, answers: Map<*, *>) {
        fun noul(id: String) = ((answers[id] as Map<*, *>)["noul"] as Number).toDouble()
        println("MLX-KOTLIN-JEVA $label cargo.md p=${noul("span-0")} notes.md p=${noul("span-1")}")
        assertTrue(noul("span-1") > noul("span-0"), "relevant ${noul("span-1")} should exceed irrelevant ${noul("span-0")}")
        assertTrue(kotlin.math.abs(noul("span-0") - 0.1539) <= 0.03, "cargo ${noul("span-0")}")
        assertTrue(kotlin.math.abs(noul("span-1") - 0.8356) <= 0.03, "notes ${noul("span-1")}")
    }

    /**
     * Opt-in: the oversight pair of `JevaOversightMlxIntegrationTest` (a cargo excerpt and a manifest-verification excerpt
     * against "How is the manifest verified before blocks are cached?") asked of `JevaInference.loadDevice` with the MLX
     * backbone, the `head.pt` head and no Python. The Python MLX BFloat16 run gave cargo.md 0.1539 and notes.md 0.8356.
     *
     *   source config/jeva-mlx.env.sh && TRIKESHED_TEST_JEVA_MLX=1 ./gradlew --offline macosTest --tests '*MlxJevaTest'
     *
     * Without the flag it is skipped, which is not a pass.
     */
    @Test fun theOversightPairIsRankedWithoutPython() {
        val model = model("theOversightPairIsRankedWithoutPython") ?: return
        val inference = JevaInference.loadDevice(JevaConfig(model = model, device = "mlx"), MlxEncoderOperations())
        try {
            val reply = inference.infer(jsonOf(state), Jeva.questions(questions))
            ranked("inference", reply["answers"] as Map<*, *>)
        } finally { inference.close() }
    }

    /** The same pair through the production `JevaElement` lane: it resolves the MLX provider from the platform NIO services. */
    @Test fun jevaElementOpensTheMlxLaneAndDrains() = runBlocking {
        val model = model("jevaElementOpensTheMlxLaneAndDrains") ?: return@runBlocking
        val owner = SupervisorJob()
        val element = JevaElement(CoroutineScope(coroutineContext + owner), JevaConfig(model = model, device = "mlx"))
        try {
            val reply = element.ask(state, questions)
            ranked("element", reply["answers"] as Map<*, *>)
            assertEquals("mlx", element.status()["device"])
        } catch (failure: Throwable) {
            println("MLX-KOTLIN-JEVA element failure: $failure\n${failure.stackTraceToString()}")
            throw failure
        } finally {
            element.drain()
            owner.cancel()
        }
    }
}
