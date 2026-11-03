package borg.trikeshed.narsese

import borg.trikeshed.parse.jsonOf

enum class JevaRuntime { KOTLIN, PYTHON }

/** Local-only inference; no constructor or status check downloads checkpoints.
 * Kotlin model directories contain config.yaml, tokenizer/tokenizer.json, backbone_full
 * safetensors (indexed shards or model.safetensors)/config and flattened head.safetensors. Python is explicit compatibility mode.
 */
data class JevaConfig(
    val python: String = "python3",
    val model: String = Jeva.MODEL,
    val source: String? = null,
    val device: String = "cpu",
    val revision: String? = null,
    val maxStateLength: Int = 9_000,
    val maxOptionLength: Int = 8_192,
    val timeoutMs: Long = 600_000,
    val env: Map<String, String> = emptyMap(),
    val runtime: JevaRuntime = JevaRuntime.KOTLIN,
    /** Explicit local worker executable; its process owns native inference and its deadline. */
    val worker: String? = null,
    val mlxMaxTokens: Int? = null,
    val mlxCheckpointLimitBytes: Long? = null,
    /** Optional sampled physical-footprint limit for the isolated macOS worker. */
    val mlxMaxFootprintBytes: Long? = null,
) {
    init {
        require(python.isNotBlank() && model.isNotBlank() && device.isNotBlank())
        require(maxStateLength > 0 && maxOptionLength > 0 && timeoutMs > 0)
        require(worker == null || (worker.isNotBlank() && runtime == JevaRuntime.KOTLIN))
        require(mlxMaxTokens == null || mlxMaxTokens > 0)
        require(mlxCheckpointLimitBytes == null || mlxCheckpointLimitBytes >= 0)
        require(mlxMaxFootprintBytes == null || mlxMaxFootprintBytes in 1..9_007_199_254_740_991L)
    }

    fun fields(): Map<String, Any?> = mapOf(
        "python" to python, "model" to model, "source" to source, "device" to device,
        "revision" to revision, "max_state_length" to maxStateLength,
        "max_option_length" to maxOptionLength, "local_files_only" to true,
        "timeout_ms" to timeoutMs,
        "truncation" to "error",
        "env" to env,
        "runtime" to runtime.name.lowercase(),
    ) + (worker?.let { mapOf("worker" to it) } ?: emptyMap()) +
        (mlxMaxTokens?.let { mapOf("mlx_max_tokens" to it) } ?: emptyMap()) +
        (mlxCheckpointLimitBytes?.let { mapOf("mlx_checkpoint_limit_bytes" to it) } ?: emptyMap()) +
        (mlxMaxFootprintBytes?.let { mapOf("mlx_max_footprint_bytes" to it) } ?: emptyMap())

    /** Diagnostic only; identifiers are never downloaded or silently resolved as remote models. */
    fun layoutError(detail: String): String =
        "Jeva local model='${model.take(4096)}', runtime=${runtime.name.lowercase()}, device='$device': ${detail.take(2048)}. " +
        "Kotlin CPU requires config.yaml, tokenizer/tokenizer.json, backbone_full/config.json, " +
        "backbone_full/model.safetensors.index.json plus shards OR backbone_full/model.safetensors, and head.safetensors. " +
        "A repository ID alone does not install these files. Packed MLX/Q4 uses a separately selected device=mlx " +
        "with an available native EncoderOperations provider and backbone_mlx/head.pt; no Python switch or download is automatic."

    val identity: String get() = jsonOf(fields())

    companion object {
        fun configured(property: (String) -> String?, environment: (String) -> String?): JevaConfig {
            fun value(name: String) = property("trikeshed.jeva.$name")?.takeIf { it.isNotBlank() }
                ?: environment("TRIKESHED_JEVA_${name.uppercase()}")?.takeIf { it.isNotBlank() }
            return JevaConfig(python = value("python") ?: "python3", model = value("model") ?: Jeva.MODEL,
                source = value("source"), device = value("device") ?: "cpu", revision = value("revision"),
                env = listOf("GRAAL_PYTHON_VM_ARGS", "PYTORCH_MPS_HIGH_WATERMARK_RATIO", "PYTORCH_MPS_LOW_WATERMARK_RATIO")
                    .mapNotNull { name -> environment(name)?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap(),
                runtime = value("runtime")?.let { JevaRuntime.valueOf(it.uppercase()) } ?: JevaRuntime.KOTLIN,
                worker = value("worker"),
                mlxMaxTokens = environment("TRIKESHED_MLX_MAX_TOKENS")?.let { requireNotNull(it.toIntOrNull()) { "Invalid MLX max tokens" } },
                mlxCheckpointLimitBytes = environment("TRIKESHED_MLX_CHECKPOINT_LIMIT_BYTES")?.let { requireNotNull(it.toLongOrNull()) { "Invalid MLX checkpoint limit" } },
                mlxMaxFootprintBytes = environment("TRIKESHED_MLX_MAX_FOOTPRINT_BYTES")?.let { requireNotNull(it.toLongOrNull()) { "Invalid MLX physical footprint limit" } },
                timeoutMs = value("timeout_ms")?.let { requireNotNull(it.toLongOrNull()) { "Invalid Jeva timeout_ms" } } ?: 600_000)
        }
    }
}
