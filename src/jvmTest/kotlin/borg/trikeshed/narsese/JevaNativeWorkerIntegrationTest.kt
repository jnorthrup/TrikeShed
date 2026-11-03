package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Explicit opt-in: only a tiny, operator-supplied synthetic checkpoint is admitted. */
class JevaNativeWorkerIntegrationTest {
    @Test fun syntheticQ4RunsThroughTheIsolatedWorker(): Unit = runBlocking {
        val worker = System.getenv("TRIKESHED_TEST_JEVA_WORKER")
        val model = System.getenv("TRIKESHED_TEST_JEVA_MODEL")
        assumeTrue(!worker.isNullOrBlank() && !model.isNullOrBlank(), "synthetic native worker paths not supplied")
        val modelPath = requireNotNull(model)
        val root = Path.of(modelPath)
        for (name in _l["config.yaml", "head.pt", "backbone_mlx/config.json", "backbone_mlx/tokenizer.json", "backbone_mlx/model.safetensors"]) {
            val path = root.resolve(name)
            require(Files.isRegularFile(path) && Files.size(path) in 1..65_536) { "Synthetic fixture file is absent or too large: $name" }
        }
        val config = JevaConfig(model = modelPath, device = "mlx", worker = requireNotNull(worker), timeoutMs = 15_000,
            maxStateLength = 64, maxOptionLength = 64, mlxMaxTokens = 64, mlxCheckpointLimitBytes = 65_536)
        val questions = _m["q" j _m["type" j "noul", "instructions" j "Q"]]
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        try {
            val status = withTimeout(20_000) { lane.availability() }
            assertEquals(true, status["available"], status["error"]?.toString())
            assertContains(assertIs<String>(status["matrix"]), "mlx-c:matmul:gpu")
            val identity = assertIs<String>(status["model_identity"])
            val reply = withTimeout(20_000) { lane.ask("X", questions) }
            assertEquals(identity, reply["model"])
            val answers = assertIs<Map<*, *>>(reply["answers"])
            assertEquals(0.5, assertIs<Map<*, *>>(answers["q"])["noul"])
            val confirmed = withTimeout(20_000) { lane.availability() }
            assertEquals(true, confirmed["available"])
            assertEquals(identity, confirmed["model_identity"])
        } finally { lane.drain() }

        val limited = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config.copy(mlxMaxTokens = 1))
        try {
            val failure = assertFails { withTimeout(20_000) { limited.ask("X", questions) } }
            assertContains(failure.message.orEmpty(), "token count exceeds configured MLX limit 1")
            assertNull(limited.process, "the worker must be reaped after the inference error")
        } finally { limited.drain() }
    }
}
