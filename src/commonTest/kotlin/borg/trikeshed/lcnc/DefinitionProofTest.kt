package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every parse below is copied token for token and edge for edge from the daemon's answer saved as
 * parse-NAME.json in the lane's parses directory. Expectations were written from the sentences before the run.
 */
class DefinitionProofTest {
    private val source = DictionarySource("Black's Law Dictionary", "1st", 1891)
    private val equalDeliver = mapOf("deliver" to "delivery")

    /** A one-sentence parse: words as `word/lemma/TAG`, edges as `governor>dependent>relation`, 1-based. */
    private fun parsed(words: String, edges: String): NlpDocument {
        val text = StringBuilder()
        val parts = words.split(' ').mapIndexed { i, t ->
            val (word, lemma, tag) = t.split('/')
            if (text.isNotEmpty() && word !in setOf(".", ",")) text.append(' ')
            val begin = text.length
            text.append(word)
            NlpToken(i + 1, begin, text.length, word, lemma, tag, "O")
        }
        val deps = edges.split(' ').map { e -> e.split('>').let { NlpDependency(it[0].toInt(), it[1].toInt(), it[2]) } }
        return NlpDocument(text.toString(), listOf(NlpSentence(0, 0, text.length, parts.toSeries(), deps.toSeries())).toSeries())
    }

    // parse-definition.json
    private val definition = parsed(
        "A/a/DT delivery/delivery/NN of/of/IN goods/goods/NNS in/in/IN trust/trust/NN upon/upon/IN a/a/DT contract/contract/NN ,/,/, expressed/express/VBN or/or/CC implied/imply/VBN ,/,/, that/that/IN the/the/DT trust/trust/NN shall/shall/MD be/be/VB faithfully/faithfully/RB executed/execute/VBN on/on/IN the/the/DT part/part/NN of/of/IN the/the/DT bailee/bailee/NN ././.",
        "0>2>root 2>1>det 4>3>case 2>4>nmod:of 6>5>case 2>6>nmod:in 9>7>case 9>8>det 2>9>nmod:upon 2>10>punct 2>11>amod 13>12>cc 2>13>amod 11>13>conj:or 2>14>punct 21>15>mark 17>16>det 21>17>nsubj:pass 21>18>aux 21>19>aux:pass 21>20>advmod 2>21>dep 24>22>case 24>23>det 21>24>obl:on 27>25>case 27>26>det 24>27>nmod:of 2>28>punct",
    )

    // parse-orchattels.json
    private val orDefinition = parsed(
        "A/a/DT delivery/delivery/NN of/of/IN goods/goods/NNS or/or/CC chattels/chattel/NNS in/in/IN trust/trust/NN ././.",
        "0>2>root 2>1>det 4>3>case 2>4>nmod:of 6>5>cc 2>6>nmod:of 4>6>conj:or 8>7>case 2>8>nmod:in 2>9>punct",
    )

    // parse-delivered_smith.json
    private val deliveredSmith = parsed(
        "Rex/Rex/NNP delivered/deliver/VBD the/the/DT goods/goods/NNS to/to/IN Smith/Smith/NNP in/in/IN trust/trust/NN upon/upon/IN a/a/DT contract/contract/NN ././.",
        "0>2>root 2>1>nsubj 4>3>det 2>4>obj 6>5>case 2>6>obl:to 8>7>case 2>8>obl:in 11>9>case 11>10>det 2>11>obl:upon 2>12>punct",
    )

    // parse-not_deliver.json
    private val notDeliver = parsed(
        "Rex/Rex/NNP did/do/VBD not/not/RB deliver/deliver/VB the/the/DT goods/goods/NNS ././.",
        "0>4>root 4>1>nsubj 4>2>aux 4>3>advmod 6>5>det 4>6>obj 4>7>punct",
    )

    // parse-delivered_only.json
    private val deliveredOnly = parsed(
        "Rex/Rex/NNP delivered/deliver/VBD the/the/DT goods/goods/NNS to/to/IN Smith/Smith/NNP ././.",
        "0>2>root 2>1>nsubj 4>3>det 2>4>obj 6>5>case 2>6>obl:to 2>7>punct",
    )

    // parse-may.json
    private val may = parsed(
        "Rex/Rex/NNP may/may/MD deliver/deliver/VB the/the/DT goods/goods/NNS in/in/IN trust/trust/NN ././.",
        "0>3>root 3>1>nsubj 3>2>aux 5>4>det 3>5>obj 7>6>case 3>7>obl:in 3>8>punct",
    )

    // parse-passive.json
    private val passive = parsed(
        "The/the/DT goods/goods/NNS were/be/VBD delivered/deliver/VBN to/to/IN Smith/Smith/NNP in/in/IN trust/trust/NN upon/upon/IN a/a/DT contract/contract/NN ././.",
        "0>4>root 2>1>det 4>2>nsubj:pass 4>3>aux:pass 6>5>case 4>6>obl:to 8>7>case 4>8>obl:in 11>9>case 11>10>det 4>11>obl:upon 4>12>punct",
    )

    // parse-delivers.json
    private val delivers = parsed(
        "Rex/Rex/NNP delivers/deliver/VBZ goods/goods/NNS in/in/IN trust/trust/NN ././.",
        "0>2>root 2>1>nsubj 2>3>obj 5>4>case 2>5>obl:in 2>6>punct",
    )

    @Test fun definitionElementsAreReadFromTheParseAndTheConjOrMemberIsNotAnElementOfItsOwn() {
        val elements = DefinitionProof.elements(definition)
        assertEquals(
            listOf("nmod:of", "nmod:in", "nmod:upon", "amod", "dep"),
            elements.map { it.relation },
        )
        assertEquals(listOf("express", "imply"), elements.first { it.relation == "amod" }.alternatives)
        assertEquals(listOf("execute"), elements.first { it.relation == "dep" }.alternatives)
        assertEquals(
            listOf("goods", "chattel"),
            DefinitionProof.elements(orDefinition).first { it.relation == "nmod:of" }.alternatives,
        )
    }

    @Test fun smithSentenceMeetsGoodsTrustAndContractButNotExpressedImpliedOrExecuted() {
        // Expected by reading: applies. Real parse: the definition also names "expressed or implied" and the
        // relative clause "executed", which this fact does not speak to, so the proof cannot tell it.
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(deliveredSmith), equalDeliver)
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(setOf("nmod:of", "nmod:in", "nmod:upon"), application.steps.map { it.element.relation }.toSet())
        assertTrue(application.steps.all { it.sign == EvidenceSign.POSITIVE })
        assertEquals(listOf("amod", "dep"), application.withoutEvidence.map { it.relation })
    }

    @Test fun notDeliverIsNegativeEvidenceOnGoodsAndDoesNotApply() {
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(notDeliver), equalDeliver)
        assertEquals(DefinitionConclusion.DOES_NOT_APPLY, application.conclusion)
        assertEquals(1, application.named.size)
        assertEquals("nmod:of", application.named.single().element.relation)
        assertEquals(listOf("goods"), application.named.single().element.alternatives)
        assertEquals(EvidenceSign.NEGATIVE, application.named.single().sign)
        assertEquals("Rex did not deliver the goods.", application.named.single().fact)
        assertEquals("obj", application.named.single().dependency.substringAfter(" ").substringBefore(" "))
    }

    @Test fun deliveredOnlyLeavesTrustAndContractWithoutEvidence() {
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(deliveredOnly), equalDeliver)
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(
            listOf("nmod:in", "nmod:upon", "amod", "dep"),
            application.withoutEvidence.map { it.relation },
        )
    }

    @Test fun deliveredAndNotDeliveredOnTheSameGoodsIsConflicted() {
        val application = DefinitionProof.apply(
            source, "BAILMENT", definition, listOf(deliveredOnly, notDeliver), equalDeliver,
        )
        assertEquals(DefinitionConclusion.CONFLICTED, application.conclusion)
        assertEquals(2, application.named.size)
        assertEquals(setOf(EvidenceSign.POSITIVE, EvidenceSign.NEGATIVE), application.named.map { it.sign }.toSet())
    }

    @Test fun modalSentenceIsSetAsideWithItsReasonAndIsNotEvidence() {
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(may), equalDeliver)
        assertEquals(emptyList(), application.steps)
        assertEquals(listOf("Rex may deliver the goods in trust."), application.setAside.map { it.fact }.distinct())
        assertTrue(application.setAside.all { it.reason.contains("modal") })
    }

    @Test fun passiveSentenceMeetsGoodsThroughItsPassiveSubject() {
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(passive), equalDeliver)
        assertTrue(application.steps.all { it.sign == EvidenceSign.POSITIVE })
        val goods = application.steps.single { it.element.relation == "nmod:of" }
        assertEquals("nsubj:pass", goods.dependency.substringAfter(" ").substringBefore(" "))
        assertEquals("of-phrase is the verb's passive subject", goods.joinedBy)
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(listOf("amod", "dep"), application.withoutEvidence.map { it.relation })
    }

    @Test fun orElementMetByOneAlternativeIsMetOnTheRealParse() {
        val application = DefinitionProof.apply(source, "BAILMENT", orDefinition, listOf(delivers), equalDeliver)
        assertEquals(DefinitionConclusion.APPLIES, application.conclusion)
        assertEquals(listOf("goods", "chattel"), application.elements.first { it.relation == "nmod:of" }.alternatives)
        val orStep = application.steps.single { it.element.relation == "nmod:of" }
        assertEquals(EvidenceSign.POSITIVE, orStep.sign)
        assertEquals("deliver obj goods", orStep.dependency)
    }

    @Test fun deliversWithoutEqualLemmaMeetsNothing() {
        val application = DefinitionProof.apply(source, "BAILMENT", orDefinition, listOf(delivers), emptyMap())
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(emptyList(), application.steps)
        assertEquals(listOf("Rex delivers goods in trust."), application.setAside.map { it.fact }.distinct())
        assertTrue(application.setAside.all { it.reason == "speaks to no element" })
    }
}
