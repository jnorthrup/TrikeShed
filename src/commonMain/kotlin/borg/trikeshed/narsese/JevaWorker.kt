package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*

/** The child owns exactly one inference instance; the caller validates every returned distribution. */
internal class JevaWorker(private val load: suspend (JevaConfig) -> JevaInference) {
    private var inference: JevaInference? = null

    suspend fun exchange(line: String): String {
        val request = requireNotNull(reifyStrict(line) as? Map<*, *>) { "Invalid Jeva worker request" }
        val result = when (request["op"]) {
            "init" -> {
                check(inference == null) { "Jeva worker is already initialized" }
                val fields = requireNotNull(request["config"] as? Map<*, *>) { "Missing Jeva worker configuration" }
                fun positive(name: String): Int = requireNotNull((fields[name] as? Number)?.toInt()?.takeIf { it > 0 }) { "Invalid $name" }
                val timeout = requireNotNull((fields["timeout_ms"] as? Number)?.toLong()?.takeIf { it > 0 }) { "Invalid timeout_ms" }
                val config = JevaConfig(model = Jeva.text(fields["model"], "model"),
                    device = Jeva.text(fields["device"], "device"), revision = fields["revision"] as? String,
                    maxStateLength = positive("max_state_length"), maxOptionLength = positive("max_option_length"),
                    timeoutMs = timeout, runtime = JevaRuntime.KOTLIN,
                    mlxMaxTokens = (fields["mlx_max_tokens"] as? Number)?.toInt(),
                    mlxCheckpointLimitBytes = (fields["mlx_checkpoint_limit_bytes"] as? Number)?.toLong())
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
