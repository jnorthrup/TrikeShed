package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionProofTest {
    private val source = DictionarySource("Black's Law Dictionary", "1st", 1891)

    /** A one-sentence parse written by hand: words as `word/lemma/TAG`, edges as `governor>dependent>relation`, 1-based. */
    private fun parsed(words: String, edges: String): NlpDocument {
        val tokens = words.split(' ')
        var at = 0
        val parts = tokens.mapIndexed { i, t ->
            val (word, lemma, tag) = t.split('/')
            NlpToken(i + 1, at, at + word.length, word, lemma, tag, "O").also { at += word.length + 1 }
        }
        val text = tokens.joinToString(" ") { it.substringBefore('/') } + "."
        val deps = edges.split(' ').map { e -> e.split('>').let { NlpDependency(it[0].toInt(), it[1].toInt(), it[2]) } }
        return NlpDocument(text, listOf(NlpSentence(0, 0, text.length, parts.toSeries(), deps.toSeries())).toSeries())
    }

    /** The definition A delivery of goods in trust upon a contract. */
    private val definition = parsed(
        "A/a/DT delivery/delivery/NN of/of/IN goods/good/NNS in/in/IN trust/trust/NN upon/upon/IN a/a/DT contract/contract/NN",
        "0>2>root 2>1>det 2>4>nmod:of 4>3>case 2>6>nmod:in 6>5>case 2>9>nmod:upon 9>7>case 9>8>det",
    )

    private val rexDeliversGoodsOfTrust = parsed(
        "Rex/Rex/NNP makes/make/VBZ a/a/DT delivery/delivery/NN in/in/IN trust/trust/NN",
        "0>2>root 2>1>nsubj 2>4>obj 4>3>det 4>6>nmod:in 6>5>case",
    )
    private val rexMakesDeliveryOfGoods = parsed(
        "Rex/Rex/NNP makes/make/VBZ a/a/DT delivery/delivery/NN of/of/IN goods/good/NNS",
        "0>2>root 2>1>nsubj 2>4>obj 4>3>det 4>6>nmod:of 6>5>case",
    )
    private val rexMakesDeliveryUponContract = parsed(
        "Rex/Rex/NNP makes/make/VBZ a/a/DT delivery/delivery/NN upon/upon/IN a/a/DT contract/contract/NN",
        "0>2>root 2>1>nsubj 2>4>obj 4>7>nmod:upon 7>5>case 7>6>det",
    )
    private val rexDoesNotMakeDeliveryUponContract = parsed(
        "Rex/Rex/NNP does/do/VBZ not/not/RB make/make/VB a/a/DT delivery/delivery/NN upon/upon/IN a/a/DT contract/contract/NN",
        "0>4>root 4>1>nsubj 4>2>aux 4>3>advmod 4>6>obj 6>5>det 6>9>nmod:upon 9>7>case 9>8>det",
    )

    @Test fun everyElementPositivelyMetGivesAppliesWithOneStepPerElement() {
        val application = DefinitionProof.apply(
            source, "BAILMENT", definition,
            listOf(rexMakesDeliveryOfGoods, rexDeliversGoodsOfTrust, rexMakesDeliveryUponContract), emptyMap(),
        )
        assertEquals(DefinitionConclusion.APPLIES, application.conclusion)
        assertEquals(3, application.steps.size)
        assertTrue(application.steps.all { it.sign == EvidenceSign.POSITIVE })
        assertEquals(emptyList(), application.withoutEvidence)
        assertEquals(emptyList(), application.setAside)
    }

    @Test fun negatedFactIsNegativeEvidenceAndDoesNotApply() {
        val application = DefinitionProof.apply(
            source, "BAILMENT", definition,
            listOf(rexMakesDeliveryOfGoods, rexDeliversGoodsOfTrust, rexDoesNotMakeDeliveryUponContract), emptyMap(),
        )
        assertEquals(DefinitionConclusion.DOES_NOT_APPLY, application.conclusion)
        assertEquals(1, application.named.size)
        assertEquals(listOf("contract"), application.named.single().element.alternatives)
        assertEquals(EvidenceSign.NEGATIVE, application.named.single().sign)
        assertEquals("Rex does not make a delivery upon a contract.", application.named.single().fact)
    }

    @Test fun elementWithNoFactGivesCannotBeToldAndNamesIt() {
        val application = DefinitionProof.apply(
            source, "BAILMENT", definition, listOf(rexMakesDeliveryOfGoods, rexDeliversGoodsOfTrust), emptyMap(),
        )
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(listOf("contract"), application.withoutEvidence.single().alternatives)
    }

    @Test fun positiveAndNegativeEvidenceOnOneElementIsConflicted() {
        val application = DefinitionProof.apply(
            source, "BAILMENT", definition,
            listOf(rexMakesDeliveryUponContract, rexDoesNotMakeDeliveryUponContract), emptyMap(),
        )
        assertEquals(DefinitionConclusion.CONFLICTED, application.conclusion)
        assertEquals(2, application.named.size)
        assertEquals(setOf(EvidenceSign.POSITIVE, EvidenceSign.NEGATIVE), application.named.map { it.sign }.toSet())
    }

    @Test fun orElementMetByOneAlternativeIsMet() {
        val orDefinition = parsed(
            "A/a/DT delivery/delivery/NN of/of/IN goods/good/NNS or/or/CC chattels/chattel/NNS in/in/IN trust/trust/NN",
            "0>2>root 2>1>det 2>4>nmod:of 4>3>case 4>6>conj:or 6>5>cc 2>8>nmod:in 8>7>case",
        )
        val rexMakesDeliveryOfChattels = parsed(
            "Rex/Rex/NNP makes/make/VBZ a/a/DT delivery/delivery/NN of/of/IN chattels/chattel/NNS",
            "0>2>root 2>1>nsubj 2>4>obj 4>3>det 4>6>nmod:of 6>5>case",
        )
        val application = DefinitionProof.apply(
            source, "BAILMENT", orDefinition, listOf(rexMakesDeliveryOfChattels, rexDeliversGoodsOfTrust), emptyMap(),
        )
        assertEquals(DefinitionConclusion.APPLIES, application.conclusion)
        assertEquals(listOf("good", "chattel"), application.elements.first { it.relation == "nmod:of" }.alternatives)
        val orStep = application.steps.single { it.element.relation == "nmod:of" }
        assertEquals(EvidenceSign.POSITIVE, orStep.sign)
        assertEquals("delivery nmod:of chattel", orStep.dependency)
    }

    @Test fun deliveredDoesNotMeetDeliveryWithoutEqualLemmaAndMeetsWithIt() {
        val delivers = parsed(
            "Rex/Rex/NNP delivers/deliver/VBZ goods/good/NNS in/in/IN trust/trust/NN",
            "0>2>root 2>1>nsubj 2>3>obj 2>5>nmod:in 5>4>case",
        )
        val without = DefinitionProof.apply(source, "BAILMENT", definition, listOf(delivers), emptyMap())
        assertEquals(emptyList(), without.steps)
        assertEquals(listOf("Rex delivers goods in trust."), without.setAside.map { it.fact })

        val with = DefinitionProof.apply(source, "BAILMENT", definition, listOf(delivers), mapOf("deliver" to "delivery"))
        assertEquals(1, with.steps.size)
        assertEquals("deliver=delivery", with.steps.single().equalLemma)
    }

    @Test fun modalFactIsSetAsideNotEvidence() {
        val modal = parsed(
            "Rex/Rex/NNP may/may/MD deliver/deliver/VB goods/good/NNS in/in/IN trust/trust/NN",
            "0>3>root 3>1>nsubj 3>2>aux 3>4>obj 3>6>nmod:in 6>5>case",
        )
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf(modal), mapOf("deliver" to "delivery"))
        assertEquals(emptyList(), application.steps)
        assertEquals(listOf("Rex may deliver goods in trust."), application.setAside.map { it.fact })
        assertTrue(application.setAside.single().reason.contains("modal"))
    }
}
