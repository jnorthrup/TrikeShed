package borg.trikeshed.narsese

import borg.trikeshed.parse.jsonOf

enum class JevaRuntime { KOTLIN, PYTHON }

/** Local-only inference; no constructor or status check downloads checkpoints.
 * Kotlin model directories contain config.yaml, tokenizer/tokenizer.json, backbone_full
 * safetensors/index/config and flattened head.safetensors. Python is explicit compatibility mode.
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
) {
    init {
        require(python.isNotBlank() && model.isNotBlank() && device.isNotBlank())
        require(maxStateLength > 0 && maxOptionLength > 0 && timeoutMs > 0)
    }

    fun fields(): Map<String, Any?> = mapOf(
        "python" to python, "model" to model, "source" to source, "device" to device,
        "revision" to revision, "max_state_length" to maxStateLength,
        "max_option_length" to maxOptionLength, "local_files_only" to true,
        "timeout_ms" to timeoutMs,
        "truncation" to "error",
        "env" to env,
        "runtime" to runtime.name.lowercase(),
    )

    val identity: String get() = jsonOf(fields())

    companion object {
        fun configured(property: (String) -> String?, environment: (String) -> String?): JevaConfig {
            fun value(name: String) = property("trikeshed.jeva.$name")?.takeIf { it.isNotBlank() }
                ?: environment("TRIKESHED_JEVA_${name.uppercase()}")?.takeIf { it.isNotBlank() }
            return JevaConfig(python = value("python") ?: "python3", model = value("model") ?: Jeva.MODEL,
                source = value("source"), device = value("device") ?: "cpu", revision = value("revision"),
                env = listOf("GRAAL_PYTHON_VM_ARGS", "PYTORCH_MPS_HIGH_WATERMARK_RATIO", "PYTORCH_MPS_LOW_WATERMARK_RATIO")
                    .mapNotNull { name -> environment(name)?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap(),
                runtime = value("runtime")?.let { JevaRuntime.valueOf(it.uppercase()) } ?: JevaRuntime.KOTLIN)
        }
    }
}
