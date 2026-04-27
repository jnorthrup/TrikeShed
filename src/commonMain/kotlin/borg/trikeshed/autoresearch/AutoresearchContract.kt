package borg.trikeshed.autoresearch

import borg.trikeshed.parse.json.JsonParser
import borg.trikeshed.lib.toSeries

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
            val parsedAny = try { JsonParser.reify(jsonLine.toSeries()) } catch (e: Exception) {
                throw IllegalArgumentException("Invalid AutoresearchResult JSON: $e")
            }
            val parsed = parsedAny as? Map<*, *> ?: error("AutoresearchResult JSON is not an object")
            val metricsAny = parsed["metrics"] as? Map<*, *> ?: error("Autoresearch result missing object field: metrics")
            val metricsMap: Map<String, String> = metricsAny.entries.associate { (k, v) ->
                (k as? String ?: k.toString()) to (v as? String ?: (v as? Number)?.toString() ?: v?.toString() ?: "")
            }

            return AutoresearchResult(
                experimentId = parsed["experiment_id"] as? String ?: error("Autoresearch result missing string field: experiment_id"),
                branch = parsed["branch"] as? String ?: error("Autoresearch result missing string field: branch"),
                stage = parsed["stage"] as? String ?: error("Autoresearch result missing string field: stage"),
                theme = parsed["theme"] as? String ?: error("Autoresearch result missing string field: theme"),
                timestamp = parsed["timestamp"] as? String ?: error("Autoresearch result missing string field: timestamp"),
                metrics = AutoresearchMetrics.fromJsonObject(metricsMap),
                verdict = AutoresearchVerdict.fromWireName(parsed["verdict"] as? String ?: error("Autoresearch result missing string field: verdict")),
                evidencePath = parsed["evidence_path"] as? String ?: error("Autoresearch result missing string field: evidence_path"),
                schema = parsed["schema"] as? String ?: SCHEMA,
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
fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":\"").append(value.escapeJson()).append('"')
}
fun String.escapeJson(): String = buildString(length + 8) {
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
fun extractJsonString(jsonLine: String, key: String): String {
    val parsed = JsonParser.reify(jsonLine.toSeries()) as? Map<*, *> ?: error("Invalid JSON")
    return parsed[key] as? String ?: error("Autoresearch result missing string field: $key")
}
fun extractJsonObject(jsonLine: String, key: String): Map<String, String> {
    val parsed = JsonParser.reify(jsonLine.toSeries()) as? Map<*, *> ?: error("Invalid JSON")
    val obj = parsed[key] as? Map<*, *> ?: error("Autoresearch result missing object field: $key")
    return obj.entries.associate { (k, v) ->
        (k as? String ?: k.toString()) to (v as? String ?: (v as? Number)?.toString() ?: v?.toString() ?: "")
    }
}
fun Map<String, String>.requireDouble(key: String): Double =
    this[key]?.toDoubleOrNull() ?: error("Autoresearch result missing numeric field: $key")
fun Map<String, String>.requireInt(key: String): Int = requireDouble(key).toInt()
