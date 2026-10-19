package borg.trikeshed.narsese

import borg.trikeshed.lib.*

/**
 * Masked-language-model substitutes after Devlin et al. (2019): the target's WordPieces collapse to one `[MASK]`,
 * the last encoder layer at that row passes the prediction transform (dense, GELU, LayerNorm), and the decoder tied
 * to the word embeddings scores every vocabulary row. Proposals only: no judgment, no stored evidence.
 */
class BertFill(private val weights: BertWeights, val tokenizer: BertWordPiece) {
    private val words: Array<String> = Array(weights.config.vocabSize) { "" }.also { out ->
        for ((word, id) in tokenizer.vocabulary) out[id] = word
    }
    private val mask = requireNotNull(tokenizer.vocabulary["[MASK]"]) { "BERT vocabulary lacks [MASK]" }

    init { for (name in BertWeights.head(weights.config).keys) weights[name] }

    fun word(id: Int): String = words[id]

    /** A whole lowercase word: no `##` continuation, no bracketed special token, letters only. */
    fun whole(id: Int): Boolean = words[id].let { w -> w.length > 1 && w.all { it in 'a'..'z' } }

    /**
     * The [k] most probable whole-word substitutes for [token], ids and softmax probabilities, most probable first;
     * both empty when the target is not placed in its sentence (missing, ambiguous or truncated).
     */
    suspend fun fill(token: BertToken, k: Int): Join<IntArray, FloatArray> {
        val input = tokenizer.encode(token)
        if (input.target.size == 0) return IntArray(0) j FloatArray(0)
        val first = input.target[0]
        val last = input.target[input.target.size - 1]
        val n = input.ids.size - (last - first)
        val ids = IntArray(n) { i -> if (i < first) input.ids[i] else if (i == first) mask else input.ids[i + last - first] }
        val config = weights.config
        val h = config.hiddenSize
        val state = weights.states(ids.toSeries(), 1)
        val row = state.copyOfRange(first * h, first * h + h)
        val dense = BertMath.gelu(BertMath.linear(row, 1, h, weights["cls.predictions.transform.dense.weight"].b, h,
            weights["cls.predictions.transform.dense.bias"].b))
        val transformed = BertMath.layerNorm(dense, 1, h, weights["cls.predictions.transform.LayerNorm.weight"].b,
            weights["cls.predictions.transform.LayerNorm.bias"].b, config.layerNormEps.toFloat())
        val logits = BertMath.linear(transformed, 1, h, weights["embeddings.word_embeddings.weight"].b, config.vocabSize,
            weights["cls.predictions.bias"].b)
        var top = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > top) top = v
        var total = 0.0
        for (i in logits.indices) { val e = kotlin.math.exp((logits[i] - top).toDouble()); logits[i] = e.toFloat(); total += e }
        val best = IntArray(k) { -1 }
        val mass = FloatArray(k)
        var held = 0
        for (id in logits.indices) {
            if (!whole(id)) continue
            val p = (logits[id] / total).toFloat()
            if (held == k && p <= mass[k - 1]) continue
            var at = if (held < k) held++ else k - 1
            while (at > 0 && mass[at - 1] < p) { best[at] = best[at - 1]; mass[at] = mass[at - 1]; at-- }
            best[at] = id; mass[at] = p
        }
        return best.copyOf(held) j mass.copyOf(held)
    }
}
