package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class JevaTest {
    val choice = Jev.choice(mapOf("word" to "bank"), linkedMapOf("RiverBank" to "The river edge", "FinancialOrganization" to null,
        "none" to "None of the listed senses"))
    val questions = linkedMapOf("sense" to choice, "stated" to Jev.noul("Does the page state the norm?", "The norm is stated", "The norm is absent"))

    fun answer(kind: String, q: List<Any?>, nullProbability: Any? = 0.0, noul: Any? = null): Map<String, Any?> =
        mapOf("kind" to kind, "candidate_probabilities" to q, "null_probability" to nullProbability, "noul" to noul)
    fun reply(sense: Any? = answer("choice", listOf(0.8, 0.2), 0.5), stated: Any? = answer("noul", listOf(0.1, 0.9), noul = 0.9)) =
        mapOf("model" to "fixture@revision", "answers" to mapOf("sense" to sense, "stated" to stated), "usage" to mapOf("input_tokens" to 20))

    @Test fun canonicalProviderIdsPreserveOpenaiStorageAndAlias() {
        assertEquals(listOf("jeva", "jev", "openai-decision"), DecisionProvider.entries.map { it.id })
        assertEquals(DecisionProvider.OPENAI, DecisionProvider.of("openai"))
        assertEquals(DecisionProvider.OPENAI, DecisionProvider.of(" OPENAI-DECISION "))
        assertEquals(DecisionProvider.JEV, DecisionProvider.of(null))
        assertEquals("openai-${Decisions.MODEL}", DecisionProvider.OPENAI.namespace)
        assertFalse(DecisionProvider.JEVA.requiresKey)
        assertTrue(DecisionProvider.JEV.remote)
        assertFails { DecisionProvider.of("unknown") }
    }

    @Test fun requestsKeepNoulCriteriaAndUseNativeNullWithoutCompetingNoneOption() {
        val prepared = Jeva.questions(questions)
        val request = reifyMap(Jeva.body(mapOf("page" to "The bank is closed."), prepared))
        assertEquals(mapOf("page" to "The bank is closed."), reifyMap(request["state"] as String))
        val encoded = request["questions"] as Map<*, *>
        val sense = encoded["sense"] as Map<*, *>
        assertEquals(listOf("RiverBank: The river edge", "FinancialOrganization"), sense["options"])
        assertEquals(true, sense["allow_null"])
        val noul = encoded["stated"] as Map<*, *>
        assertEquals(listOf("false: The norm is absent", "true: The norm is stated"), noul["options"])
        assertEquals(false, noul["allow_null"])
        assertEquals(1.0, (noul["positive_index"] as Number).toDouble())
        assertEquals(listOf("false", "true"), Jeva.questions(mapOf("q" to Jev.noul("stated?"))).getValue("q").options.toList())
    }

    @Test fun nullMassRemainsInTheDistributionConsumedBySenseMemory() {
        val response = Jeva.answers(reply(), Jeva.questions(questions))
        val answers = response["answers"] as Map<*, *>
        val sense = Jev.answerOf(answers, "sense", choice)
        assertEquals("none", sense["choice"])
        assertEquals(0.5, sense["confidence"])
        assertEquals(mapOf("RiverBank" to 0.4, "FinancialOrganization" to 0.1, "none" to 0.5), sense["probabilities"])
        assertEquals(mapOf("RiverBank" to 0.8, "FinancialOrganization" to 0.2), sense["conditional_probabilities"])
        assertEquals(0.9, Jev.noulOf(answers, "stated"))
        assertEquals("fixture@revision", response["model"])
        assertEquals(mapOf("input_tokens" to 20), response["usage"])
    }

    @Test fun closedChoicesAndNullTiesFollowTheUpstreamDecisionRule() {
        val q = Jeva.questions(mapOf("q" to Jev.choice("which?", linkedMapOf("A" to null, "none" to null))))
        val response = Jeva.answers(mapOf("model" to "m", "answers" to mapOf("q" to answer("choice", listOf(1.0), 0.5))), q)
        assertEquals("none", ((response["answers"] as Map<*, *>)["q"] as Map<*, *>)["choice"])
        val closed = Jeva.questions(mapOf("q" to Jev.choice("which?", linkedMapOf("A" to null, "B" to null))))
        val selected = Jeva.answers(mapOf("model" to "m", "answers" to mapOf("q" to answer("choice", listOf(0.5, 0.5)))), closed)
        val a = ((selected["answers"] as Map<*, *>)["q"] as Map<*, *>)
        assertEquals("A", a["choice"])
        assertEquals(mapOf("A" to 0.5, "B" to 0.5), a["probabilities"])
    }

    @Test fun incompleteOrMalformedJudgmentsCannotBecomeFalseOrCertainEvidence() {
        val prepared = Jeva.questions(questions)
        assertFails { Jeva.answers(reply() + ("answers" to emptyMap<String, Any?>()), prepared) }
        assertFails { Jeva.answers(mapOf("error" to "CUDA out of memory"), prepared) }
        assertFails { Jeva.answers(reply(sense = mapOf("refusal" to "unknown")), prepared) }
        for (invalid in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, "0.8", null)) {
            assertFails { Jeva.answers(reply(sense = answer("choice", listOf(invalid, 0.2))), prepared) }
            assertFails { Jeva.answers(reply(sense = answer("choice", listOf(0.8, 0.2), invalid)), prepared) }
            assertFails { Jeva.answers(reply(stated = answer("noul", listOf(0.1, 0.9), noul = invalid)), prepared) }
        }
        assertFails { Jeva.answers(reply(sense = answer("choice", listOf(0.2, 0.2))), prepared) }
        assertFails { Jeva.answers(reply(sense = answer("choice", listOf(1.0))), prepared) }
        assertFails { Jeva.answers(reply(stated = answer("noul", listOf(0.1, 0.9), 0.2, 0.9)), prepared) }
        assertFails { Jeva.answers(reply(stated = answer("noul", listOf(0.1, 0.9), noul = 0.1)), prepared) }
        assertFails { Jeva.questions(mapOf("q" to Jev.choice("which?", mapOf("A" to 42, "none" to null)))) }
        assertFails { Jeva.questions(mapOf("q" to mapOf("type" to "noul", "instructions" to "stated?", "criteria" to mapOf("true" to 42)))) }
        assertFails { Jeva.questions(mapOf("q" to mapOf("type" to "score", "instructions" to "rate"))) }
    }

    @Test fun configurationHasStableStorageIdentityAndExplicitPropertyPrecedence() {
        val configured = JevaConfig.configured({ if (it == "trikeshed.jeva.model") "/models/jeva" else null },
            { mapOf("TRIKESHED_JEVA_MODEL" to "ignored", "TRIKESHED_JEVA_SOURCE" to "/source/jeva", "TRIKESHED_JEVA_PYTHON" to "/venv/bin/python",
                "GRAAL_PYTHON_VM_ARGS" to "--python.BackgroundGCTaskThreshold=0", "PYTORCH_MPS_HIGH_WATERMARK_RATIO" to "0.7",
                "PYTORCH_MPS_LOW_WATERMARK_RATIO" to " ", "OPENAI_API_KEY" to "excluded-fixture")[it] })
        assertEquals("/models/jeva", configured.model)
        assertEquals("/source/jeva", configured.source)
        assertEquals("/venv/bin/python", configured.python)
        assertEquals(mapOf("GRAAL_PYTHON_VM_ARGS" to "--python.BackgroundGCTaskThreshold=0", "PYTORCH_MPS_HIGH_WATERMARK_RATIO" to "0.7"), configured.env)
        assertEquals(true, reifyMap(configured.identity)["local_files_only"])
        assertNotEquals(configured.identity, configured.copy(revision = "different").identity)
    }
}
