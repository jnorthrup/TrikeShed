package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lcnc.CurationReviewQuotation
import borg.trikeshed.lcnc.CurationReviewSelection
import borg.trikeshed.lib.*
import kotlin.test.*

class ConditionAssessmentTest {
    private val source = "No fee is charged. At any time, subject to outages, you may withdraw. As long as you hold assets, Coinbase shall retain keys. 😀"
    private val bytes = source.encodeToByteArray()
    private val textCid = ContentId.of(bytes)
    private val originalCid = ContentId.of("pdf".encodeToByteArray())
    private val snapshotCid = ContentId.of("snapshot".encodeToByteArray())

    private fun selection(id: String, quote: String, proposedCondition: String? = null): CurationReviewSelection {
        val begin = source.indexOf(quote)
        require(begin >= 0)
        return CurationReviewSelection(id, "relationship", _m["condition" j proposedCondition],
            CurationReviewQuotation(originalCid, textCid, begin, begin + quote.length, quote))
    }

    private fun span(text: String): ConditionSpan {
        val begin = source.indexOf(text)
        require(begin >= 0)
        return ConditionSpan(textCid, begin, begin + text.length, text)
    }

    @Test fun absentAssessmentsStayUnresolvedRegardlessOfParserCondition() {
        val first = selection("fee", "No fee is charged.")
        val second = selection("retain", "As long as you hold assets, Coinbase shall retain keys.", "if guessed")
        val result = ConditionAssessmentCheck.validate(snapshotCid, _l[first, second], emptyList(), _m[textCid j bytes])
        assertEquals(ConditionAssessmentStatus.UNRESOLVED, result.status)
        assertEquals(_l["fee", "retain"], result.unresolved)
        val one = ConditionAssessment(snapshotCid, "fee", ConditionAssessmentState.UNCONDITIONAL)
        val partial = ConditionAssessmentCheck.validate(snapshotCid, _l[first, second], _l[one], _m[textCid j bytes])
        assertEquals(_l["retain"], partial.unresolved)
    }

    @Test fun explicitConditionalPreservesAnExactSourceSpanWithoutInferringAPremise() {
        val item = selection("withdraw", "At any time, subject to outages, you may withdraw.")
        val proposed = ConditionAssessment(snapshotCid, item.id, ConditionAssessmentState.CONDITIONAL,
            _l[span("subject to outages")])
        assertEquals(ConditionAssessmentStatus.COMPLETE,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[proposed], _m[textCid j bytes]).status)
        val unresolved = proposed.copy(state = ConditionAssessmentState.UNRESOLVED)
        assertEquals(ConditionAssessmentStatus.UNRESOLVED,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[unresolved], _m[textCid j bytes]).status)
        assertEquals(ConditionAssessmentStatus.INVALID,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[proposed.copy(spans = emptyList())], _m[textCid j bytes]).status)
        assertEquals(ConditionAssessmentStatus.INVALID,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[proposed.copy(state = ConditionAssessmentState.UNCONDITIONAL)], _m[textCid j bytes]).status)
    }

    @Test fun refusesOtherSnapshotsDuplicateSelectionsAndTamperedText() {
        val item = selection("withdraw", "At any time, subject to outages, you may withdraw.")
        val assessment = ConditionAssessment(snapshotCid, item.id, ConditionAssessmentState.CONDITIONAL, _l[span("subject to outages")])
        fun check(items: List<CurationReviewSelection>, decisions: List<ConditionAssessment>, data: Map<ContentId, ByteArray>) =
            ConditionAssessmentCheck.validate(snapshotCid, items, decisions, data).status
        assertEquals(ConditionAssessmentStatus.INVALID, check(_l[item], _l[assessment.copy(snapshotCid = originalCid)], _m[textCid j bytes]))
        assertEquals(ConditionAssessmentStatus.INVALID, check(_l[item], _l[assessment.copy(selectionId = "other")], _m[textCid j bytes]))
        assertEquals(ConditionAssessmentStatus.INVALID, check(_l[item], _l[assessment, assessment], _m[textCid j bytes]))
        assertEquals(ConditionAssessmentStatus.INVALID, check(_l[item, item], _l[assessment], _m[textCid j bytes]))
        assertEquals(ConditionAssessmentStatus.UNAVAILABLE, check(_l[item], _l[assessment], emptyMap()))
        assertEquals(ConditionAssessmentStatus.INTEGRITY_MISMATCH, check(_l[item], _l[assessment], _m[textCid j "altered".encodeToByteArray()]))
        val wrongSpan = assessment.copy(spans = _l[span("subject to outages").copy(quote = "subject to delays")])
        assertEquals(ConditionAssessmentStatus.INVALID, check(_l[item], _l[wrongSpan], _m[textCid j bytes]))
    }

    @Test fun validUnicodeIsAllowedButSplitUtf16SpansAreNot() {
        val item = selection("emoji", "😀")
        val correct = ConditionAssessment(snapshotCid, item.id, ConditionAssessmentState.CONDITIONAL, _l[span("😀")])
        assertEquals(ConditionAssessmentStatus.COMPLETE,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[correct], _m[textCid j bytes]).status)
        val whole = span("😀")
        val split = whole.copy(end = whole.begin + 1, quote = source.substring(whole.begin, whole.begin + 1))
        assertEquals(ConditionAssessmentStatus.INVALID,
            ConditionAssessmentCheck.validate(snapshotCid, _l[item], _l[correct.copy(spans = _l[split])], _m[textCid j bytes]).status)
    }

    @Test fun assessmentCodecIsCanonicalBoundedAndPreservesUnicode() {
        val assessment = ConditionAssessment(snapshotCid, "emoji", ConditionAssessmentState.CONDITIONAL, _l[span("😀")])
        val bytes = ConditionAssessmentCodec.encode(snapshotCid, _l[assessment])
        val restored = ConditionAssessmentCodec.decode(bytes)
        assertEquals(snapshotCid, restored.snapshotCid)
        assertEquals(_l[assessment], restored.assessments)
        assertFails { ConditionAssessmentCodec.decode(bytes + _a[0.toByte()]) }
        assertFails { ConditionAssessmentCodec.encode(snapshotCid, _l[assessment, assessment]) }
        assertFails { ConditionAssessmentCodec.encode(snapshotCid, _l[assessment.copy(
            spans = _l[assessment.spans.single().copy(quote = "\uD800")])]) }
        val root = CanonicalCbor.decodeMap(bytes).toMutableMap()
        val row = ((root["assessments"] as List<*>).single() as Map<*, *>).entries
            .associate { it.key as String to it.value }.toMutableMap()
        val item = ((row["spans"] as List<*>).single() as Map<*, *>).entries
            .associate { it.key as String to it.value }.toMutableMap()
        item["begin"] = 1.5
        row["spans"] = _l[item]
        root["assessments"] = _l[row]
        assertFails { ConditionAssessmentCodec.decode(CanonicalCbor.encodeMap(root)) }
    }
}
