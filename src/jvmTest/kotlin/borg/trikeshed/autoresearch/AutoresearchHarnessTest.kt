package borg.trikeshed.autoresearch

import java.nio.file.Files as JavaFiles
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoresearchHarnessTest {
    @Test
    fun `identity task convergence shape`() {
        val tempDir = JavaFiles.createTempDirectory("autoresearch-identity")
        val config = configFor(tempDir, AutoresearchThemes.M0_IDENTITY, "identity-shape", 24)
        val surface = AutoresearchExperimentSurface { _, _, _ ->
            AutoresearchModel { input: DoubleArray, _: Int -> input.copyOf() }
        }

        val result = AutoresearchHarness.runExperiment(AutoresearchTask.X_TO_X, config, surface)

        assertEquals(AutoresearchVerdict.PROMOTE, result.verdict)
        assertEquals(0.0, result.metrics.mse, 1e-12)
        assertEquals(config.fixedRunBudget, result.metrics.sampleCount)
        assertTrue(JavaFiles.exists(Path.of(result.evidencePath)))
        assertEquals(1, JavaFiles.readAllLines(Path.of(config.resultsLogPath)).size)
    }

    @Test
    fun `sine task loading and execution shape`() {
        val tempDir = JavaFiles.createTempDirectory("autoresearch-sine")
        val config = configFor(tempDir, AutoresearchThemes.M1_SINE, "sine-shape", 18)
        val samples = AutoresearchTasks.load(AutoresearchTask.MIXED_SINE, config)
        val surface = AutoresearchExperimentSurface { task, runConfig, _ ->
            AutoresearchModel { input: DoubleArray, sampleIndex: Int ->
                AutoresearchTasks.referenceTarget(task, input, sampleIndex, runConfig.seed)
            }
        }

        val result = AutoresearchHarness.runExperiment(AutoresearchTask.MIXED_SINE, config, surface)

        assertEquals(18, samples.size)
        assertEquals(1, samples.first().input.size)
        assertEquals(1, samples.first().target.size)
        assertTrue(result.metrics.mse < 1e-12)
        assertEquals(AutoresearchVerdict.PROMOTE, result.verdict)
    }

    @Test
    fun `autoresearch result jsonl roundtrip`() {
        val result = AutoresearchResult(
            experimentId = "exp-42",
            branch = "exp/convergence_4x4/x_to_x/42",
            stage = AutoresearchStages.CONVERGENCE_4X4,
            theme = AutoresearchThemes.M0_IDENTITY,
            timestamp = "2026-03-10T12:00:00Z",
            metrics = AutoresearchMetrics(
                mse = 0.125,
                mae = 0.25,
                maxAbsError = 0.5,
                sampleCount = 32,
                outputWidth = 4,
                budget = 32,
            ),
            verdict = AutoresearchVerdict.HOLD,
            evidencePath = "/tmp/evidence.txt",
        )

        val decoded = AutoresearchResult.fromJsonLine(result.toJsonLine())

        assertEquals(result, decoded)
    }

    @Test
    fun `verdict gating respects theme baselines`() {
        val promote = AutoresearchHarness.verdictFor(
            AutoresearchThemes.M0_IDENTITY,
            AutoresearchMetrics(
                mse = 0.0,
                mae = 0.0,
                maxAbsError = 0.0,
                sampleCount = 16,
                outputWidth = 4,
                budget = 16,
            ),
        )
        val hold = AutoresearchHarness.verdictFor(
            AutoresearchThemes.M1_SINE,
            AutoresearchMetrics(
                mse = 0.5,
                mae = 0.4,
                maxAbsError = 1.0,
                sampleCount = 16,
                outputWidth = 1,
                budget = 16,
            ),
        )

        assertEquals(AutoresearchVerdict.PROMOTE, promote)
        assertEquals(AutoresearchVerdict.HOLD, hold)
    }

    private fun configFor(
        tempDir: Path,
        theme: String,
        experimentId: String,
        budget: Int,
    ): AutoresearchRunConfig =
        AutoresearchRunConfig(
            stage = AutoresearchStages.CONVERGENCE_4X4,
            theme = theme,
            fixedRunBudget = budget,
            seed = 7,
            resultsLogPath = tempDir.resolve("results.jsonl").toString(),
            evidenceDirectory = tempDir.resolve("evidence").toString(),
            experimentId = experimentId,
            branch = "exp/${AutoresearchStages.CONVERGENCE_4X4}/$theme/$experimentId",
        )
}
