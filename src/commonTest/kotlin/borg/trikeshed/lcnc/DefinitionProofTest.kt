package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals

class DefinitionProofTest {
    /** Black's Law Dictionary, 1st edition, 1891: the sentence the BAILMENT entry prints from 3 Bl. Comm. 451. OCR as printed. */
    private val source = DictionarySource("Black's Law Dictionary", "1st", 1891)
    private val definition = "A delivery of goods in trust upon a contract, expressed or Implied, that the trust shall be faithfully executed on the part of the bailee."

    @Test fun coreIsTheWordsBeforeTheFirstComma() {
        assertEquals(listOf("delivery", "goods", "trust", "contract"), DefinitionProof.core(definition))
    }

    @Test fun everyCoreWordMetGivesAppliesWithOneStepPerFact() {
        val facts = listOf("Rex made a delivery to Ann.", "The goods were in her hands.", "The trust was set up.", "Ann and Rex signed a contract.")
        val application = DefinitionProof.apply(source, "BAILMENT", definition, facts, complete = true)
        assertEquals(DefinitionConclusion.APPLIES, application.conclusion)
        assertEquals(emptyList(), application.unmet)
        assertEquals(
            listOf(
                DefinitionProofStep(listOf("delivery"), 0, "Rex made a delivery to Ann."),
                DefinitionProofStep(listOf("goods"), 1, "The goods were in her hands."),
                DefinitionProofStep(listOf("trust"), 2, "The trust was set up."),
                DefinitionProofStep(listOf("contract"), 3, "Ann and Rex signed a contract."),
            ),
            application.steps,
        )
    }

    @Test fun completeFactsMissingCoreWordsGiveDoesNotApplyAndNameThem() {
        val facts = listOf("Rex made a delivery of goods to Ann.", "Ann paid Rex in cash.")
        val application = DefinitionProof.apply(source, "BAILMENT", definition, facts, complete = true)
        assertEquals(DefinitionConclusion.DOES_NOT_APPLY, application.conclusion)
        assertEquals(listOf("trust", "contract"), application.unmet)
        assertEquals(listOf(DefinitionProofStep(listOf("delivery", "goods"), 0, "Rex made a delivery of goods to Ann.")), application.steps)
    }

    @Test fun incompleteFactsMissingCoreWordsGiveCannotBeTold() {
        val facts = listOf("Rex made a delivery of goods to Ann.", "Ann paid Rex in cash.")
        val application = DefinitionProof.apply(source, "BAILMENT", definition, facts, complete = false)
        assertEquals(DefinitionConclusion.CANNOT_BE_TOLD, application.conclusion)
        assertEquals(listOf("trust", "contract"), application.unmet)
    }

    @Test fun resultCarriesDictionaryEditionAndYear() {
        val application = DefinitionProof.apply(source, "BAILMENT", definition, listOf("Rex made a delivery."), complete = false)
        assertEquals(DictionarySource("Black's Law Dictionary", "1st", 1891), application.source)
        assertEquals("BAILMENT", application.headword)
    }
}
