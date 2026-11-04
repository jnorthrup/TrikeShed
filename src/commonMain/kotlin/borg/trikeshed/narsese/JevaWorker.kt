package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.jvm.JvmInline

/** A sampler is owned by the worker job and runs independently of inference. */
internal suspend fun monitorFootprint(owner: Job, sample: () -> Unit, revoke: () -> Unit) {
    try {
        while (true) { delay(50); sample() }
    } finally {
        // A sampler that stops before its owner has shut down cannot protect inference.
        if (owner.isActive) revoke()
    }
}

/** The child owns exactly one inference instance; the caller validates every returned distribution. */
internal class JevaWorker(private val load: suspend (JevaConfig) -> JevaInference) {
    companion object {
        const val FOOTPRINT_MARKER = "TRIKESHED_MLX_FOOTPRINT_LIMIT"
        const val FOOTPRINT_SAMPLE_MARKER = "TRIKESHED_MLX_FOOTPRINT_SAMPLE_FAILED"
    }
    @JvmInline private value class NumberLiteral(val text: String)
    private var inference: JevaInference? = null

    /** Convert a JSON number to Long only when its exact decimal value is integral and in range. */
    private fun integer(text: String): Long? {
        val exponentAt = text.indexOfFirst { it == 'e' || it == 'E' }.let { if (it < 0) text.length else it }
        val negative = text.startsWith('-')
        val first = if (negative) 1 else 0
        val exponentLimit = text.length.toLong() + 20
        val exponent = if (exponentAt == text.length) 0L else {
            var at = exponentAt + 1
            val minus = text[at] == '-'
            if (text[at] == '-' || text[at] == '+') at++
            var magnitude = 0L
            while (at < text.length && magnitude < exponentLimit) {
                val digit = text[at++].code - '0'.code
                magnitude = if (magnitude > (exponentLimit - digit) / 10) exponentLimit else magnitude * 10 + digit
            }
            if (minus) -magnitude else magnitude
        }
        var fractional = 0
        var afterPoint = false
        var digits = 0
        var leading = 0
        var trailing = 0
        var nonzero = false
        for (i in first until exponentAt) {
            val c = text[i]
            if (c == '.') { afterPoint = true; continue }
            digits++
            if (afterPoint) fractional++
            if (c != '0') { nonzero = true; trailing = 0 }
            else { if (!nonzero) leading++; trailing++ }
        }
        if (!nonzero) return 0L
        val shift = exponent - fractional.toLong()
        val drop = if (shift < 0) -shift else 0
        if (drop > trailing) return null
        val append = if (shift > 0) shift else 0
        val retained = digits.toLong() - drop
        if (retained - leading + append > 19) return null
        val value = StringBuilder(21)
        if (negative) value.append('-')
        var ordinal = 0
        for (i in first until exponentAt) {
            val c = text[i]
            if (c == '.') continue
            if (ordinal >= leading && ordinal.toLong() < retained) value.append(c)
            ordinal++
        }
        repeat(append.toInt()) { value.append('0') }
        return value.toString().toLongOrNull()
    }

    suspend fun exchange(line: String): String {
        val request = requireNotNull(reifyStrict(line) { NumberLiteral(it) } as? Map<*, *>) { "Invalid Jeva worker request" }
        val result = when (request["op"]) {
            "init" -> {
                check(inference == null) { "Jeva worker is already initialized" }
                val fields = requireNotNull(request["config"] as? Map<*, *>) { "Missing Jeva worker configuration" }
                // Preserve exact JSON numbers so rounding cannot change a limit.
                fun integer(name: String, raw: Any?, min: Long, max: Long): Long {
                    val number = requireNotNull((raw as? NumberLiteral)?.text?.let { this@JevaWorker.integer(it) }) { "Invalid $name" }
                    require(number in min..max) { "Invalid $name" }
                    return number
                }
                fun positive(name: String): Int = integer(name, fields[name], 1, Int.MAX_VALUE.toLong()).toInt()
                val timeout = integer("timeout_ms", fields["timeout_ms"], 1, Long.MAX_VALUE)
                val tokens = fields["mlx_max_tokens"]?.let { integer("mlx_max_tokens", it, 1, Int.MAX_VALUE.toLong()).toInt() }
                val checkpoint = fields["mlx_checkpoint_limit_bytes"]?.let { integer("mlx_checkpoint_limit_bytes", it, 0, Long.MAX_VALUE) }
                val footprint = fields["mlx_max_footprint_bytes"]?.let { integer("mlx_max_footprint_bytes", it, 1, 9_007_199_254_740_991L) }
                val config = JevaConfig(model = Jeva.text(fields["model"], "model"),
                    device = Jeva.text(fields["device"], "device"), revision = fields["revision"] as? String,
                    maxStateLength = positive("max_state_length"), maxOptionLength = positive("max_option_length"),
                    timeoutMs = timeout, runtime = JevaRuntime.KOTLIN,
                    mlxMaxTokens = tokens,
                    mlxCheckpointLimitBytes = checkpoint,
                    mlxMaxFootprintBytes = footprint)
                load(config).also { inference = it }
                status()
            }
            "status" -> status()
            "infer" -> {
                val runtime = requireNotNull(inference) { "Jeva worker is not initialized" }
                val state = requireNotNull(request["state"] as? String) { "Missing state" }
                require(state.length <= Jeva.MAX_TEXT_CHARS) { "State is too long" }
                val rows = requireNotNull(request["questions"] as? Map<*, *>) { "Missing questions" }
                require(rows.size in 1..Jeva.MAX_QUESTIONS)
                val questions = rows.entries.associate { (key, value) ->
                    val id = Jeva.text(key, "question name")
                    val row = requireNotNull(value as? Map<*, *>) { "Invalid question" }
                    val type = Jeva.text(row["decision_type"], "decision type")
                    require(type == "noul" || type == "choice")
                    val options = requireNotNull(row["options"] as? List<*>) { "Missing options" }
                        .map { Jeva.text(it, "option") }
                    require(options.size in 1..255 && (type != "noul" || options.size == 2))
                    val allowNull = requireNotNull(row["allow_null"] as? Boolean)
                    require(type != "noul" || !allowNull)
                    id to JevaQuestion(type, Jeva.text(row["question"], "instructions"),
                        options.toSeries(), options.toSeries(), if (allowNull) Jeva.NONE else null)
                }
                runtime.inferRaw(state, questions)
            }
            else -> error("Unknown Jeva worker operation")
        }
        return jsonOf(result)
    }

    private fun status(): Map<String, Any?> {
        val runtime = requireNotNull(inference) { "Jeva worker is not initialized" }
        return _m["available" j true, "loaded" j true, "model_identity" j runtime.identity,
            "runtime" j "kotlin", "device" j runtime.config.device, "mapped_weights" j true,
            "arithmetic" j "float32", "matrix" j runtime.model.decision.matrix.identity, "error" j null]
    }

    fun close() { inference?.close(); inference = null }
}
