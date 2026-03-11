package borg.trikeshed.autoresearch

enum class AutoresearchTask(val wireName: String) {
    X_TO_X("x_to_x"),
    SCALAR_1X1_TO_16X16("scalar_1x1_to_16x16"),
    SINGLE_SINE("single_sine"),
    MIXED_SINE("mixed_sine"),
    NOISY_SINE("noisy_sine"),
    PIECEWISE_SINE("piecewise_sine"),
    ;

    companion object {
        fun fromWireName(value: String): AutoresearchTask =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown autoresearch task: $value")
    }
}

object AutoresearchStages {
    const val CONVERGENCE_4X4: String = "convergence_4x4"
}

object AutoresearchThemes {
    const val M0_IDENTITY: String = "M0_identity"
    const val M1_SINE: String = "M1_sine"
}

data class AutoresearchExample(
    val input: DoubleArray,
    val target: DoubleArray,
)

data class AutoresearchRunConfig(
    val stage: String,
    val theme: String,
    val fixedRunBudget: Int,
    val seed: Int,
    val resultsLogPath: String,
    val evidenceDirectory: String,
    val experimentId: String,
    val branch: String,
)

data class AutoresearchMetrics(
    val mse: Double,
    val mae: Double,
    val maxAbsError: Double,
    val sampleCount: Int,
    val outputWidth: Int,
    val budget: Int,
) {
    internal fun toJsonObject(): String = buildString {
        append('{')
        append("\"mse\":").append(mse)
        append(",\"mae\":").append(mae)
        append(",\"max_abs_error\":").append(maxAbsError)
        append(",\"sample_count\":").append(sampleCount)
        append(",\"output_width\":").append(outputWidth)
        append(",\"budget\":").append(budget)
        append('}')
    }

    companion object {
        internal fun fromJsonObject(metrics: Map<String, String>): AutoresearchMetrics =
            AutoresearchMetrics(
                mse = metrics.requireDouble("mse"),
                mae = metrics.requireDouble("mae"),
                maxAbsError = metrics.requireDouble("max_abs_error"),
                sampleCount = metrics.requireInt("sample_count"),
                outputWidth = metrics.requireInt("output_width"),
                budget = metrics.requireInt("budget"),
            )
    }
}

enum class AutoresearchVerdict(val wireName: String) {
    PROMOTE("promote"),
    HOLD("hold"),
    ;

    companion object {
        fun fromWireName(value: String): AutoresearchVerdict =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown autoresearch verdict: $value")
    }
}

data class AutoresearchResult(
    val experimentId: String,
    val branch: String,
    val stage: String,
    val theme: String,
    val timestamp: String,
    val metrics: AutoresearchMetrics,
    val verdict: AutoresearchVerdict,
    val evidencePath: String,
    val schema: String = SCHEMA,
) {
    init {
        require(schema == SCHEMA) { "Unsupported result schema: $schema" }
    }

    fun toJsonLine(): String = buildString {
        append('{')
        appendJsonField("schema", schema)
        append(',')
        appendJsonField("experiment_id", experimentId)
        append(',')
        appendJsonField("branch", branch)
        append(',')
        appendJsonField("stage", stage)
        append(',')
        appendJsonField("theme", theme)
        append(',')
        appendJsonField("timestamp", timestamp)
        append(",\"metrics\":")
        append(metrics.toJsonObject())
        append(',')
        appendJsonField("verdict", verdict.wireName)
        append(',')
        appendJsonField("evidence_path", evidencePath)
        append('}')
    }

    companion object {
        const val SCHEMA: String = "trikeshed.autoresearch.result.v1"

        fun fromJsonLine(jsonLine: String): AutoresearchResult {
            val metricsObject = extractJsonObject(jsonLine, "metrics")
            return AutoresearchResult(
                experimentId = extractJsonString(jsonLine, "experiment_id"),
                branch = extractJsonString(jsonLine, "branch"),
                stage = extractJsonString(jsonLine, "stage"),
                theme = extractJsonString(jsonLine, "theme"),
                timestamp = extractJsonString(jsonLine, "timestamp"),
                metrics = AutoresearchMetrics.fromJsonObject(metricsObject),
                verdict = AutoresearchVerdict.fromWireName(extractJsonString(jsonLine, "verdict")),
                evidencePath = extractJsonString(jsonLine, "evidence_path"),
                schema = extractJsonString(jsonLine, "schema"),
            )
        }
    }
}

fun interface AutoresearchModel {
    fun predict(input: DoubleArray, sampleIndex: Int): DoubleArray
}

fun interface AutoresearchExperimentSurface {
    fun train(
        task: AutoresearchTask,
        config: AutoresearchRunConfig,
        samples: List<AutoresearchExample>,
    ): AutoresearchModel
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":\"").append(value.escapeJson()).append('"')
}

private fun String.escapeJson(): String = buildString(length + 8) {
    for (ch in this@escapeJson) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private fun extractJsonString(jsonLine: String, key: String): String {
    val marker = "\"$key\":\""
    val start = jsonLine.indexOf(marker)
    require(start >= 0) { "Autoresearch result missing string field: $key" }
    val builder = StringBuilder()
    var index = start + marker.length
    var escaped = false
    while (index < jsonLine.length) {
        val ch = jsonLine[index++]
        if (escaped) {
            builder.append(
                when (ch) {
                    '\\' -> '\\'
                    '"' -> '"'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> ch
                },
            )
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> escaped = true
            '"' -> return builder.toString()
            else -> builder.append(ch)
        }
    }
    error("Autoresearch result field did not terminate: $key")
}

private fun extractJsonObject(jsonLine: String, key: String): Map<String, String> {
    val marker = "\"$key\":{"
    val start = jsonLine.indexOf(marker)
    require(start >= 0) { "Autoresearch result missing object field: $key" }
    val bodyStart = start + marker.length
    var depth = 1
    var index = bodyStart
    while (index < jsonLine.length && depth > 0) {
        when (jsonLine[index]) {
            '{' -> depth++
            '}' -> depth--
        }
        index++
    }
    require(depth == 0) { "Autoresearch result object field did not terminate: $key" }
    val objectBody = jsonLine.substring(bodyStart, index - 1)
    return Regex("\"([^\"]+)\":([^,}]+)")
        .findAll(objectBody)
        .associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
}

private fun Map<String, String>.requireDouble(key: String): Double =
    this[key]?.toDoubleOrNull() ?: error("Autoresearch result missing numeric field: $key")

private fun Map<String, String>.requireInt(key: String): Int = requireDouble(key).toInt()
