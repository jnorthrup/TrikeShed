package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticNalExperimentCliTest {
    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    private fun rows(value: Any?): List<Map<String, Any?>> = (value as List<*>).map(::fields)
    private fun cid(fields: Map<String, Any?>) = ContentId.of(CanonicalCbor.encodeMap(fields)).value

    @Test
    fun payloadContainsReconstructableContributionsDecisionsAndReceipts() {
        val report = SyntheticNalExperiment.run()
        val payload = SyntheticNalExperimentCli.payload(report)
        val contributions = rows(payload["contributions"])
        val expected = (report.deductionPremises + report.inductionPremises + report.reductionInputs.values.flatten())
            .associateBy { it.cid.value }
        assertEquals(expected.keys, contributions.map { it["cid"] as String }.toSet())
        assertEquals(expected.size, contributions.size)
        for (item in contributions) {
            val original = expected.getValue(item["cid"] as String)
            val preimage = fields(item["canonicalPreimage"])
            assertEquals(item["cid"], cid(preimage))
            assertTrue(CanonicalCbor.encodeMap(preimage).contentEquals(SourceContributionCodec.encode(original)))
        }
        assertTrue(contributions.any { (it["negative"] as Number).toLong() > 0 })
        assertTrue(contributions.any { (it["basis"] as List<*>).isEmpty() })
        val reductionInputs = fields(payload["reductionInputs"])
        assertEquals(report.reductionInputs.keys, reductionInputs.keys)
        for ((name, inputs) in report.reductionInputs) {
            assertEquals(inputs.map { it.cid.value }, reductionInputs[name])
            assertTrue((reductionInputs.getValue(name) as List<*>).all { it is String && it in expected })
        }

        val evaluator = fields(payload["evaluator"])
        assertEquals(report.evaluatorCid.value, evaluator["cid"])
        assertEquals(evaluator["cid"], cid(fields(evaluator["canonicalPreimage"])))
        val decisions = rows(payload["decisions"])
        assertEquals(report.decisions.map { it.cid.value }.toSet(), decisions.map { it["cid"] }.toSet())
        for (decision in decisions) {
            val preimage = fields(decision["canonicalPreimage"])
            assertEquals(decision["cid"], cid(preimage))
            assertTrue((preimage["first"] as String) in expected)
            assertTrue((preimage["second"] as String) in expected)
        }
        for ((key, derivation) in listOf("deduction" to report.deduction, "induction" to report.induction)) {
            val item = fields(payload[key])
            assertEquals(derivation.receiptCid.value, item["receiptCid"])
            assertEquals(item["receiptCid"], ContentId.of((item["receiptPreimageUtf8"] as String).encodeToByteArray()).value)
            assertEquals(item["propositionCid"], cid(fields(item["propositionPreimage"])))
            assertEquals(report.evaluatorCid.value, item["evaluatorCid"])
        }
    }

    @Test
    fun payloadSpansPointToExactSourceTextAndCarryCanonicalPreimages() {
        val report = SyntheticNalExperiment.run()
        val payload = SyntheticNalExperimentCli.payload(report)
        val sources = rows(payload["sources"]).associate { (it["cid"] as String) to (it["text"] as String) }
        for ((sourceCid, text) in sources) assertEquals(sourceCid, ContentId.of(text.encodeToByteArray()).value)
        val spans = rows(payload["spans"])
        assertEquals(report.spans.size, spans.size)
        for (span in spans) {
            val text = sources.getValue(span["sourceCid"] as String)
            val begin = (span["begin"] as Number).toInt()
            val end = (span["end"] as Number).toInt()
            assertEquals("utf16-code-units", span["offsetUnit"])
            assertEquals(true, span["endExclusive"])
            assertTrue(begin >= 0 && begin < end && end <= text.length)
            assertEquals(text.substring(begin, end), span["quote"])
            for ((name, preimage) in listOf("occurrenceCid" to "occurrencePreimage",
                "propositionCid" to "propositionPreimage", "annotationCid" to "annotationPreimage")) {
                assertEquals(span[name], cid(fields(span[preimage])))
            }
        }
    }
}
