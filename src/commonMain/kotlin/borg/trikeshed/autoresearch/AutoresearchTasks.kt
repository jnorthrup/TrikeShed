package borg.trikeshed.autoresearch

import kotlin.math.PI
import kotlin.math.sin

object AutoresearchTasks {
    fun tasksForTheme(theme: String): List<AutoresearchTask> =
        when (theme) {
            AutoresearchThemes.M0_IDENTITY -> listOf(
                AutoresearchTask.X_TO_X,
                AutoresearchTask.SCALAR_1X1_TO_16X16,
            )

            AutoresearchThemes.M1_SINE -> listOf(
                AutoresearchTask.SINGLE_SINE,
                AutoresearchTask.MIXED_SINE,
                AutoresearchTask.NOISY_SINE,
                AutoresearchTask.PIECEWISE_SINE,
            )

            else -> error("Unsupported autoresearch theme: $theme")
        }

    fun defaultTaskForTheme(theme: String): AutoresearchTask = tasksForTheme(theme).first()

    fun load(task: AutoresearchTask, config: AutoresearchRunConfig): List<AutoresearchExample> {
        val sampleCount = config.fixedRunBudget.coerceAtLeast(1)
        return List(sampleCount) { sampleIndex ->
            val input = inputFor(task, sampleIndex, sampleCount, config.seed)
            AutoresearchExample(
                input = input,
                target = referenceTarget(task, input, sampleIndex, config.seed),
            )
        }
    }

    fun referenceTarget(
        task: AutoresearchTask,
        input: DoubleArray,
        sampleIndex: Int,
        seed: Int,
    ): DoubleArray =
        when (task) {
            AutoresearchTask.X_TO_X -> input.copyOf()
            AutoresearchTask.SCALAR_1X1_TO_16X16 -> DoubleArray(16 * 16) { input[0] }
            AutoresearchTask.SINGLE_SINE -> doubleArrayOf(sin(input[0]))
            AutoresearchTask.MIXED_SINE -> doubleArrayOf(sin(input[0]) + 0.35 * sin(input[0] * 3.0))
            AutoresearchTask.NOISY_SINE -> doubleArrayOf(
                sin(input[0]) + 0.20 * sin(input[0] * 5.0) + deterministicNoise(sampleIndex, seed),
            )

            AutoresearchTask.PIECEWISE_SINE -> doubleArrayOf(
                if (input[0] < 0.0) {
                    sin(input[0])
                } else {
                    0.6 * sin(input[0] * 2.0) + 0.2
                },
            )
        }

    private fun inputFor(
        task: AutoresearchTask,
        sampleIndex: Int,
        sampleCount: Int,
        seed: Int,
    ): DoubleArray =
        when (task) {
            AutoresearchTask.X_TO_X -> DoubleArray(4) { offset ->
                scalarInput(sampleIndex, sampleCount, seed, offset * 0.11)
            }

            AutoresearchTask.SCALAR_1X1_TO_16X16 -> doubleArrayOf(scalarInput(sampleIndex, sampleCount, seed))
            AutoresearchTask.SINGLE_SINE,
            AutoresearchTask.MIXED_SINE,
            AutoresearchTask.NOISY_SINE,
            AutoresearchTask.PIECEWISE_SINE,
            -> doubleArrayOf(phase(sampleIndex, sampleCount, seed))
        }

    private fun scalarInput(
        sampleIndex: Int,
        sampleCount: Int,
        seed: Int,
        offset: Double = 0.0,
    ): Double {
        val normalized = if (sampleCount == 1) 0.0 else sampleIndex.toDouble() / (sampleCount - 1).toDouble()
        return -1.0 + (2.0 * normalized) + (seed * 0.0005) + offset
    }

    private fun phase(sampleIndex: Int, sampleCount: Int, seed: Int): Double {
        val normalized = if (sampleCount == 1) 0.0 else sampleIndex.toDouble() / (sampleCount - 1).toDouble()
        return (-PI) + (2.0 * PI * normalized) + (seed * 0.002)
    }

    private fun deterministicNoise(sampleIndex: Int, seed: Int): Double {
        val lane = ((sampleIndex * 17) + (seed * 13)) % 11
        return (lane - 5).toDouble() * 0.01
    }
}
