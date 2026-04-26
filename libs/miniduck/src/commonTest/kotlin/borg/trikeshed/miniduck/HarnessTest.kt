package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Build a tiny cursor of close prices and run the harness
class HarnessTest {
    @Test
    fun simpleHarnessRuns() = runTest {
        val rows = listOf(
            DocRowVec(keys = listOf("close"), cells = listOf(100.0)),
            DocRowVec(keys = listOf("close"), cells = listOf(101.0)),
            DocRowVec(keys = listOf("close"), cells = listOf(102.0)),
            DocRowVec(keys = listOf("close"), cells = listOf(103.0)),
        )
        val cursor: MiniCursor = rows.size j { i -> rows[i] }

        val transformer = ExampleKernelTransformer()
        val trainer = NoOpTrainer()

        val policies = executeKernelOptimizingHarness(
                symbols = listOf("SYM"),
                timeframes = listOf("1d"),
                searchSpace = listOf<Map<String, Any>>(mapOf("short_ma" to 2, "long_ma" to 3, "vol_window" to 3)),
                cacheProvider = { _, _ -> cursor },
                transformer = transformer,
                trainer = trainer
            )

        assertTrue(policies.isNotEmpty())
    }
}