package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaBackboneConfig
import borg.trikeshed.narsese.JevaEncoder
import borg.trikeshed.parse.reifyMap

/**
 * The Qwen3.5 text backbone of an MLX checkpoint directory (config.json, model.safetensors), on the GPU stream of [session].
 * Weights are named once at construction and read by MLX on first use; [encode] returns the final-normalized hidden
 * states [tokens, hidden] as host cells, with every device intermediate freed before it returns.
 */
class MlxBackbone(private val session: MlxMatrixSession, directory: String) : JevaEncoder {
    private val root = "language_model.model."
    private val text = Files.readString("$directory/config.json")
    val config: JevaBackboneConfig = JevaBackboneConfig.parse(text)
    private val quantization: Map<*, *> = requireNotNull(reifyMap(text)["quantization"] as? Map<*, *>) { "Missing quantization" }
    private val tensors: Map<String, MlxTensor>

    init {
        val wanted = LinkedHashSet<String>()
        fun plain(name: String) { wanted += root + name }
        fun quantized(name: String) { for (part in listOf("weight", "scales", "biases")) wanted += "$root$name.$part" }
        quantized("embed_tokens"); plain("norm.weight")
        for (layer in 0 until config.layers.size) {
            val at = "layers.$layer."
            plain(at + "input_layernorm.weight"); plain(at + "post_attention_layernorm.weight")
            for (projection in listOf("gate_proj", "up_proj", "down_proj")) quantized(at + "mlp." + projection)
            if (config.layers[layer] == "full_attention") {
                for (projection in listOf("q_proj", "k_proj", "v_proj", "o_proj")) quantized(at + "self_attn." + projection)
                plain(at + "self_attn.q_norm.weight"); plain(at + "self_attn.k_norm.weight")
            } else {
                for (projection in listOf("in_proj_qkv", "in_proj_z", "out_proj")) quantized(at + "linear_attn." + projection)
                for (name in listOf("in_proj_a.weight", "in_proj_b.weight", "conv1d.weight", "A_log", "dt_bias", "norm.weight")) {
                    plain(at + "linear_attn." + name)
                }
            }
        }
        val names = wanted.toList()
        tensors = names.zip(session.load("$directory/model.safetensors", *names.toTypedArray())).toMap()
    }

    private fun vector(name: String): MlxTensor = requireNotNull(tensors[root + name]) { "Missing tensor $name" }

    private fun matrix(name: String): MlxQuantized {
        val module = quantization[root + name] as? Map<*, *> ?: quantization
        fun setting(key: String) = (module[key] as? Number ?: quantization[key] as Number).toInt()
        return MlxQuantized(vector("$name.weight"), vector("$name.scales"), vector("$name.biases"), setting("group_size"), setting("bits"))
    }

    override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit): MetaSeries<Tensor, Float> = session.scoped {
        val ids = IntArray(tokens.size) { tokens[it] }
        require(ids.isNotEmpty() && ids.all { it in 0 until config.vocabSize }) { "Qwen3.5 token outside vocabulary" }
        check()
        val table = matrix("embed_tokens")
        var hidden = session.embed(ids, table.weight, table.scales, table.biases, table.groupSize, table.bits)
        val epsilon = config.rmsEpsilon
        for (layer in 0 until config.layers.size) {
            check(); progress(layer, 0)
            val at = "layers.$layer."
            val input = session.rmsNorm(hidden, vector(at + "input_layernorm.weight"), epsilon)
            val mixed = if (config.layers[layer] == "full_attention") {
                val m = at + "self_attn."
                session.fullAttention(input, matrix(m + "q_proj"), matrix(m + "k_proj"), matrix(m + "v_proj"), matrix(m + "o_proj"),
                    vector(m + "q_norm.weight"), vector(m + "k_norm.weight"), config.attentionHeads, config.keyValueHeads,
                    config.headDim, config.rotaryDim, config.ropeTheta.toFloat(), epsilon)
            } else {
                val m = at + "linear_attn."
                session.linearAttention(input, matrix(m + "in_proj_qkv"), matrix(m + "in_proj_z"), vector(m + "in_proj_b.weight"),
                    vector(m + "in_proj_a.weight"), matrix(m + "out_proj"), vector(m + "conv1d.weight"), vector(m + "A_log"),
                    vector(m + "dt_bias"), vector(m + "norm.weight"), config.linearKeyHeads, config.linearValueHeads,
                    config.linearKeyDim, config.linearValueDim, config.convKernel, epsilon)
            }
            hidden = session.add(hidden, mixed)
            val gated = session.rmsNorm(hidden, vector(at + "post_attention_layernorm.weight"), epsilon)
            val gate = session.linear(gated, matrix(at + "mlp.gate_proj"))
            val up = session.linear(gated, matrix(at + "mlp.up_proj"))
            hidden = session.add(hidden, session.linear(session.times(session.silu(gate), up), matrix(at + "mlp.down_proj")))
        }
        check(); progress(config.layers.size, 0)
        val result = session.rmsNorm(hidden, vector("norm.weight"), epsilon)
        tensorOf(result.a, FloatArray(result.size).also { result.read(0, it) })
    }
}
