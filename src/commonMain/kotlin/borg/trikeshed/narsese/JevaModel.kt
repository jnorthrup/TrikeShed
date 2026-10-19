/*
 * Kotlin commonMain adaptation of Jeva e2104e0720100f979f2cdd456a1cbbee96da0abd.
 * Copyright 2026 Qi Li and Xinchao Wang. Upstream licensed under Apache-2.0.
 * Modified for Join/Series tensor oracles and bounded mapped inference.
 * See NOTICE.md and src/commonMain/resources/licenses/apache-2.0.txt.
 */
package borg.trikeshed.narsese

import borg.trikeshed.lib.*

enum class JevaPooling {
    LAST, CLS, MEAN;
    companion object {
        fun of(value: String): JevaPooling = when (value) {
            "last" -> LAST
            "cls" -> CLS
            "mean" -> MEAN
            else -> error("Unknown Jeva pooling '$value'")
        }
    }
}

/** Projection and pooling over backbone hidden states; tokenization/backbone execution belong to their own owners. */
class JevaModel(val hiddenSize: Int, val pooling: JevaPooling, val decision: JevaDecision,
                weights: (String) -> MetaSeries<Tensor, Float>) {
    val contextProjection = projection(weights, "context_proj.weight")
    val optionProjection = projection(weights, "option_proj.weight")
    init { require(hiddenSize > 0) }

    fun projection(weights: (String) -> MetaSeries<Tensor, Float>, name: String): JevaLinear = weights(name).let {
        JevaMath.dimensions(it, decision.dim, hiddenSize); JevaLinear(it, decision.matrix)
    }

    /** Accepts [T,H] or [B,T,H], returning [B,H]. LAST follows upstream sum(mask)-1 and right padding. */
    fun pool(hidden: MetaSeries<Tensor, Float>, mask: MetaSeries<Tensor, Float>? = null): MetaSeries<Tensor, Float> {
        require(hidden.a.size == 2 || hidden.a.size == 3)
        val batched = hidden.a.size == 3
        val batch = if (batched) hidden.a[0] else 1
        val tokens = hidden.a[if (batched) 1 else 0]
        require(batch > 0 && tokens > 0 && hidden.a[hidden.a.size - 1] == hiddenSize)
        if (mask != null) {
            if (batched) JevaMath.dimensions(mask, batch, tokens) else JevaMath.dimensions(mask, tokens)
        }
        val accepted = mask?.let(JevaMath::array) ?: FloatArray(batch * tokens) { 1f }
        require(accepted.all { it == 0f || it == 1f }) { "Attention mask must contain only zero or one" }
        val pooled = FloatArray(tensorSize(shapeOf(batch, hiddenSize)))
        var row = 0
        var token = 0
        var channel = 0
        val index = hidden.a.size j { axis: Int -> when (axis) {
            hidden.a.size - 1 -> channel
            hidden.a.size - 2 -> token
            0 -> row
            else -> throw IndexOutOfBoundsException("Hidden state coordinate")
        } }
        for (b in 0 until batch) {
            row = b
            val count = (0 until tokens).count { accepted[b * tokens + it] != 0f }
            when (pooling) {
                JevaPooling.LAST, JevaPooling.CLS -> {
                    token = if (pooling == JevaPooling.CLS) 0 else maxOf(count - 1, 0)
                    for (h in 0 until hiddenSize) {
                        channel = h
                        pooled[b * hiddenSize + h] = hidden.b(index)
                    }
                }
                JevaPooling.MEAN -> for (h in 0 until hiddenSize) {
                    channel = h
                    var sum = 0f
                    for (t in 0 until tokens) {
                        token = t
                        sum += hidden.b(index) * accepted[b * tokens + t]
                    }
                    pooled[b * hiddenSize + h] = sum / maxOf(count, 1)
                }
            }
        }
        return tensorOf(shapeOf(batch, hiddenSize), JevaMath.finite(pooled))
    }

    fun context(hidden: MetaSeries<Tensor, Float>, mask: MetaSeries<Tensor, Float>? = null, check: () -> Unit = {}): MetaSeries<Tensor, Float> {
        check()
        val pooled = pool(hidden, mask)
        return tensorOf(shapeOf(pooled.a[0], decision.dim), contextProjection(JevaMath.array(pooled), check))
    }

    fun options(hidden: MetaSeries<Tensor, Float>, mask: MetaSeries<Tensor, Float>? = null, check: () -> Unit = {}): MetaSeries<Tensor, Float> {
        check()
        val pooled = pool(hidden, mask)
        return tensorOf(shapeOf(pooled.a[0], decision.dim), optionProjection(JevaMath.array(pooled), check))
    }
}
