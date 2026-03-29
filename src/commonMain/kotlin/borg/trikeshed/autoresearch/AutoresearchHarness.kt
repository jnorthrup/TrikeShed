package borg.trikeshed.autoresearch

import borg.trikeshed.common.Files
import borg.trikeshed.common.mkdir
import kotlin.time.Clock
import kotlin.math.abs

object AutoresearchHarness {
    fun runExperiment(
        task: AutoresearchTask,
        config: AutoresearchRunConfig,
        surface: AutoresearchExperimentSurface,
    ): AutoresearchResult {
        require(config.stage == AutoresearchStages.CONVERGENCE_4X4) {
            "Only ${AutoresearchStages.CONVERGENCE_4X4} is supported in the native-first slice"
        }
        require(config.fixedRunBudget > 0) { "Autoresearch fixed run budget must be positive" }
        require(task in AutoresearchTasks.tasksForTheme(config.theme)) {
            "Task ${task.wireName} is not allowed for theme ${config.theme}"
        }

        val samples = AutoresearchTasks.load(task, config)
        val model = surface.train(task, config, samples)
        val metrics = evaluate(samples, model, config.fixedRunBudget)
        val verdict = verdictFor(config.theme, metrics)
        val timestamp = Clock.System.now().toString()
        val experimentId = normalizeExperimentId(config, task)
        val evidencePath = buildEvidencePath(config.evidenceDirectory, experimentId)

        writeEvidence(evidencePath, task, config, metrics, verdict, samples.firstOrNull())

        val result = AutoresearchResult(
            experimentId = experimentId,
            branch = normalizeBranch(config, task),
            stage = config.stage,
            theme = config.theme,
            timestamp = timestamp,
            metrics = metrics,
            verdict = verdict,
            evidencePath = evidencePath,
        )
        appendJsonLine(config.resultsLogPath, result.toJsonLine())
        return result
    }

    fun evaluate(
        samples: List<AutoresearchExample>,
        model: AutoresearchModel,
        fixedRunBudget: Int,
    ): AutoresearchMetrics {
        var squaredError = 0.0
        var absoluteError = 0.0
        var maxAbsError = 0.0
        var pointCount = 0
        var outputWidth = 0

        samples.forEachIndexed { sampleIndex, example ->
            val prediction = model.predict(example.input, sampleIndex)
            require(prediction.size == example.target.size) {
                "Prediction width ${prediction.size} did not match target width ${example.target.size}"
            }
            outputWidth = example.target.size
            for (slot in example.target.indices) {
                val delta = prediction[slot] - example.target[slot]
                val absoluteDelta = abs(delta)
                squaredError += delta * delta
                absoluteError += absoluteDelta
                if (absoluteDelta > maxAbsError) {
                    maxAbsError = absoluteDelta
                }
                pointCount++
            }
        }

        val denominator = pointCount.coerceAtLeast(1).toDouble()
        return AutoresearchMetrics(
            mse = squaredError / denominator,
            mae = absoluteError / denominator,
            maxAbsError = maxAbsError,
            sampleCount = samples.size,
            outputWidth = outputWidth,
            budget = fixedRunBudget,
        )
    }

    fun verdictFor(theme: String, metrics: AutoresearchMetrics): AutoresearchVerdict {
        val (maxMse, maxMae) = when (theme) {
            AutoresearchThemes.M0_IDENTITY -> 1e-9 to 1e-6
            AutoresearchThemes.M1_SINE -> 0.05 to 0.15
            else -> error("Unsupported autoresearch theme: $theme")
        }
        return if (metrics.mse <= maxMse && metrics.mae <= maxMae) {
            AutoresearchVerdict.PROMOTE
        } else {
            AutoresearchVerdict.HOLD
        }
    }

    private fun normalizeExperimentId(
        config: AutoresearchRunConfig,
        task: AutoresearchTask,
    ): String = config.experimentId.ifBlank {
        "${config.stage}_${config.theme}_${task.wireName}_seed${config.seed}_b${config.fixedRunBudget}"
    }

    private fun normalizeBranch(
        config: AutoresearchRunConfig,
        task: AutoresearchTask,
    ): String = config.branch.ifBlank {
        "exp/${config.stage}/${task.wireName}/seed${config.seed}"
    }

    private fun buildEvidencePath(evidenceDirectory: String, experimentId: String): String {
        val trimmed = evidenceDirectory.trimEnd('/')
        return if (trimmed.isEmpty()) {
            "${sanitizeToken(experimentId)}.txt"
        } else {
            "$trimmed/${sanitizeToken(experimentId)}.txt"
        }
    }

    private fun appendJsonLine(path: String, jsonLine: String) {
        ensureParentDirectory(path)
        val existing = if (Files.exists(path)) Files.readString(path) else ""
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        Files.write(path, existing + separator + jsonLine + "\n")
    }

    private fun writeEvidence(
        evidencePath: String,
        task: AutoresearchTask,
        config: AutoresearchRunConfig,
        metrics: AutoresearchMetrics,
        verdict: AutoresearchVerdict,
        preview: AutoresearchExample?,
    ) {
        ensureParentDirectory(evidencePath)
        val body = buildString {
            appendLine("schema=trikeshed.autoresearch.evidence.v1")
            appendLine("task=${task.wireName}")
            appendLine("stage=${config.stage}")
            appendLine("theme=${config.theme}")
            appendLine("budget=${config.fixedRunBudget}")
            appendLine("seed=${config.seed}")
            appendLine("mse=${metrics.mse}")
            appendLine("mae=${metrics.mae}")
            appendLine("max_abs_error=${metrics.maxAbsError}")
            appendLine("sample_count=${metrics.sampleCount}")
            appendLine("output_width=${metrics.outputWidth}")
            appendLine("verdict=${verdict.wireName}")
            preview?.let {
                appendLine("preview_input=${it.input.joinToString(prefix = "[", postfix = "]")}")
                appendLine("preview_target=${it.target.joinToString(prefix = "[", postfix = "]")}")
            }
        }
        Files.write(evidencePath, body)
    }

    private fun ensureParentDirectory(path: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            ensureDirectory(parent)
        }
    }

    private fun ensureDirectory(path: String) {
        if (path.isEmpty() || Files.exists(path)) {
            return
        }
        var current = if (path.startsWith("/")) "/" else ""
        for (segment in path.split('/')) {
            if (segment.isEmpty()) {
                continue
            }
            current = when {
                current.isEmpty() -> segment
                current == "/" -> "/$segment"
                else -> "$current/$segment"
            }
            if (!Files.exists(current) && !mkdir(current) && !Files.exists(current)) {
                error("Failed to create autoresearch directory: $current")
            }
        }
    }

    private fun sanitizeToken(token: String): String = buildString(token.length) {
        for (ch in token) {
            append(
                when {
                    ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' -> ch
                    else -> '_'
                },
            )
        }
    }
}
