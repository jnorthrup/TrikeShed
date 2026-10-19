package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.collections.associative.toAny
import kotlin.math.abs
import kotlin.test.*

/** Expected values were produced by upstream Jeva under CPU PyTorch 2.9.1, without a backbone model. */
class JevaDecisionTest {
    val fixture by lazy {
        // Read through Confix's index of the CBOR bytes, as the decision head's maps of tensors.
        val stream = requireNotNull(javaClass.getResourceAsStream("/jeva/decision-head-parity.cbor"))
        val doc = borg.trikeshed.parse.confix.confixDoc(stream.use { it.readBytes() }, borg.trikeshed.parse.confix.Syntax.CBOR)
        borg.trikeshed.job.CanonicalCbor.item(requireNotNull(doc.rootCell)).toAny() as Map<*, *>
    }
    fun cases(): List<Map<*, *>> = (fixture["cases"] as List<*>).map { it as Map<*, *> }
    fun tensor(value: Any?): MetaSeries<Tensor, Float> {
        val map = value as Map<*, *>
        val shape = (map["shape"] as List<*>).map { (it as Number).toInt() }.toSeries()
        val data = (map["data"] as List<*>).map {
            when (it) {
                "-inf" -> Float.NEGATIVE_INFINITY
                "inf" -> Float.POSITIVE_INFINITY
                else -> (it as Number).toFloat()
            }
        }.toFloatArray()
        return tensorOf(shape, data)
    }
    fun weights(case: Map<*, *>): Map<String, MetaSeries<Tensor, Float>> =
        (case["weights"] as Map<*, *>).entries.associate { it.key as String to tensor(it.value) }
    fun decision(case: Map<*, *>): JevaDecision {
        val weights = weights(case)
        return JevaDecision(JevaDecisionConfig.from(case["config"] as Map<*, *>), weights::getValue)
    }
    fun equal(expected: Any?, actual: MetaSeries<Tensor, Float>, label: String) {
        val reference = tensor(expected)
        assertEquals(reference.a.view.toList(), actual.a.view.toList(), "$label shape")
        val e = JevaMath.array(reference); val a = JevaMath.array(actual)
        for (i in e.indices) {
            if (e[i].isInfinite()) assertEquals(e[i], a[i], "$label[$i]")
            else assertTrue(a[i].isFinite() && abs(e[i] - a[i]) <= 2e-5f + 2e-5f * abs(e[i]),
                "$label[$i]: expected ${e[i]}, got ${a[i]}")
        }
    }
    fun equal(expected: Map<*, *>, actual: JevaDecisionOutput, head: JevaDecision) {
        for ((name, value) in listOf("q" to actual.q, "coverage" to actual.coverage, "p_null" to actual.pNull,
            "p_candidates" to actual.pCandidates, "scores" to actual.scores, "z" to actual.z,
            "z_read" to actual.zRead, "decision_logits" to actual.decisionLogits, "log_q" to actual.logQ,
            "coverage_logit" to actual.coverageLogit)) equal(expected[name], value, name)
        equal(expected["tau_dec"], scalarTensor(head.tauDec), "tau_dec")
        equal(expected["tau_cov"], scalarTensor(head.tauCov), "tau_cov")
        val attentions = expected["layer_attentions"] as List<*>
        val alphas = expected["layer_alphas"] as List<*>
        assertEquals(attentions.size, actual.layerAttentions.size)
        assertEquals(alphas.size, actual.layerAlphas.size)
        for (i in attentions.indices) {
            equal(attentions[i], actual.layerAttentions[i], "attention $i")
            equal(alphas[i], scalarTensor(actual.layerAlphas[i]), "alpha $i")
        }
    }

    @Test fun upstreamMaskedHeadsFfnNormalizationAndCoverage() {
        assertEquals(3, cases().size)
        for (case in cases()) {
            val head = decision(case)
            val actual = head.forward(tensor(case["context"]), tensor(case["options"]),
                tensor(case["mask"]), returnLayerAttentions = true)
            equal(case["expected"] as Map<*, *>, actual, head)
            val q = JevaMath.array(actual.q); val mask = JevaMath.array(tensor(case["mask"]))
            for (i in q.indices) if (mask[i] == 0f) assertEquals(0f, q[i])
            val p = JevaMath.array(actual.pCandidates); val nulls = JevaMath.array(actual.pNull)
            val options = actual.q.a[1]
            for (b in nulls.indices) assertTrue(abs(nulls[b] + p.sliceArray(b * options until (b + 1) * options).sum() - 1f) < 2e-5f)
        }
    }

    @Test fun upstreamFactorizedMemoryPreservesDuplicatesAndOrder() {
        for (case in cases()) {
            val head = decision(case)
            val options = tensor(case["options"])
            val first = tensorOf(shapeOf(options.a[1], options.a[2]),
                JevaMath.array(options).copyOfRange(0, options.a[1] * options.a[2]))
            val memories = head.compile(first)
            val order = (case["factorized_order"] as List<*>).map { (it as Number).toInt() }
            val cache = head.assemble(order.size j { memories[order[it]] })
            val c = tensorOf(shapeOf(1, head.dim), JevaMath.array(tensor(case["context"])).copyOfRange(0, head.dim))
            equal(case["factorized_expected"] as Map<*, *>, head.forward(c, cache, true), head)
            assertEquals(4L * head.dim * (1 + 2 * head.config.depth), memories[0].nbytes)
            assertEquals(memories[0].nbytes * order.size + 4L * order.size, cache.nbytes)
        }
    }

    @Test fun upstreamPoolingAndContextOptionProjections() {
        val case = cases().first(); val weights = weights(case); val head = decision(case)
        for (raw in fixture["pooling"] as List<*>) {
            val row = raw as Map<*, *>
            val model = JevaModel(5, JevaPooling.of(row["kind"] as String), head, weights::getValue)
            val hidden = tensor(row["hidden"]); val mask = tensor(row["mask"])
            equal(row["expected"], model.pool(hidden, mask), "pool ${model.pooling}")
            equal(row["context"], model.context(hidden, mask), "context ${model.pooling}")
            equal(row["options"], model.options(hidden, mask), "options ${model.pooling}")
            val single = tensorOf(shapeOf(3, 5), JevaMath.array(hidden).copyOfRange(0, 15))
            val singleMask = tensorOf(shapeOf(3), JevaMath.array(mask).copyOfRange(0, 3))
            assertContentEquals(JevaMath.array(model.pool(hidden, mask)).copyOfRange(0, 5), JevaMath.array(model.pool(single, singleMask)))
        }
    }

    @Test fun missingWeightsInvalidMasksAndCacheDimensionsFailBeforeInference() {
        val case = cases().first(); val head = decision(case)
        val options = tensor(case["options"]); val context = tensor(case["context"])
        assertFails { head.prepare(options, tensorOf(shapeOf(2, 4), FloatArray(8))) }
        assertFails { head.prepare(options, tensorOf(shapeOf(2, 4), FloatArray(8) { Float.NaN })) }
        assertFails { head.forward(tensorOf(shapeOf(1, 4), FloatArray(4)), options, tensor(case["mask"])) }
        assertFails { head.forward(context, options, tensorOf(shapeOf(8), FloatArray(8) { 1f })) }
        val incomplete = weights(case) - "core.blocks.0.q_proj.weight"
        assertFails { JevaDecision(head.config, incomplete::getValue) }
        val malformed = weights(case) + ("core.blocks.0.q_proj.weight" to tensorOf(shapeOf(3, 4), FloatArray(12)))
        assertFails { JevaDecision(head.config, malformed::getValue) }
        assertFails { JevaDecisionConfig(projectionDim = 5, numHeads = 2) }
    }

    @Test fun factorizedStorageAndLruStayWithinTheirOwnershipBounds() {
        val head = decision(cases().first())
        val encoded = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        val memories = head.compile(tensorOf(shapeOf(2, 4), encoded))
        val before = JevaMath.array(memories[0].optionRead)
        encoded.fill(9f)
        assertContentEquals(before, JevaMath.array(memories[0].optionRead))
        val cache = JevaCache<String, ByteArray>(2, 5) { it.size.toLong() }
        cache.put("a", ByteArray(2)); cache.put("b", ByteArray(2)); assertNotNull(cache["a"])
        cache.put("c", ByteArray(2)); assertNull(cache["b"])
        assertEquals(4L, cache.nbytes); assertEquals(2, cache.count)
        cache.put("a", ByteArray(6))
        assertNull(cache["a"]); assertNotNull(cache["c"])
        assertEquals(2L, cache.nbytes); assertEquals(1, cache.count)
        cache.clear(); assertEquals(0, cache.count); assertEquals(0L, cache.nbytes)
        assertEquals(0L, cache.hits); assertEquals(0L, cache.misses)
        val disabled = JevaCache<String, ByteArray>(0, 5) { it.size.toLong() }
        disabled.put("x", ByteArray(1)); assertNull(disabled["x"])
    }

    @Test fun linearBorrowsMappedWeightOracleWithoutReadingOrCopyingItAtConstruction() {
        val data = floatArrayOf(1f, 2f, 3f, 4f)
        var reads = 0
        val weights = shapeOf(2, 2) j { index: Tensor -> reads++; data[index[0] * 2 + index[1]] }
        val linear = JevaLinear(weights)
        assertEquals(0, reads)
        assertContentEquals(floatArrayOf(3f, 7f), linear(floatArrayOf(1f, 1f)))
        assertEquals(4, reads)
    }
}
