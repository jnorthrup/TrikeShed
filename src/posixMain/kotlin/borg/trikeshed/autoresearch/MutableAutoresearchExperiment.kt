package borg.trikeshed.autoresearch

// Routine autoresearch mutations should stay in this file so the harness remains fixed.
object MutableAutoresearchExperiment : AutoresearchExperimentSurface {
    override fun train(
        task: AutoresearchTask,
        config: AutoresearchRunConfig,
        samples: List<AutoresearchExample>,
    ): AutoresearchModel = AutoresearchModel { input: DoubleArray, sampleIndex: Int ->
        when (task) {
            AutoresearchTask.X_TO_X -> input.copyOf()
            AutoresearchTask.SCALAR_1X1_TO_16X16 -> DoubleArray(16 * 16) { input[0] }
            AutoresearchTask.SINGLE_SINE,
            AutoresearchTask.MIXED_SINE,
            AutoresearchTask.NOISY_SINE,
            AutoresearchTask.PIECEWISE_SINE,
            -> AutoresearchTasks.referenceTarget(task, input, sampleIndex, config.seed)
        }
    }
}
