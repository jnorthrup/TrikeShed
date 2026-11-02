package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Rule admission end to end: the daemon boots [CausalityReteElement] over ZERO
 * rules — an empty alpha network fires nothing forever (dead spin). Admission
 * through [CausalityReteElement.admit] and the [RuleNodes] runners is what
 * brings the live rete to fire against the bag as it exists.
 */
class RuleAdmissionTest {

    @Test
    fun admissionRetainsNegativeEvidenceAndRejectsInvalidOffersBeforeMutation() = runTest {
        val (bag, element) = liveElement()
        val ledger = mutableListOf<EternalRule>()
        val runner = RuleNodes.ruleAdmitRunner(element, policy = RuleNodes.AdmissionPolicy.TRUSTED_HOST_LEGACY) { ledger.add(it) }
        val rule = EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(0, 900), "source-negative")
        val spec = mapOf("antecedent" to "fire", "consequent" to "smoke", "copula" to "==>",
            "positiveEvidence" to 0, "negativeEvidence" to 900, "provenanceCid" to "source-negative", "ruleCid" to rule.ruleCid.value)
        try {
            for (bad in listOf(spec + ("negativeEvidence" to -1), spec + ("negativeEvidence" to 0.5),
                spec + ("negativeEvidence" to 4_294_967_296L), spec - "negativeEvidence", spec + ("copula" to "unknown"),
                spec + ("ruleCid" to "sha256:incorrect"), spec + ("discount" to 0.5))) {
                assertFailsWith<IllegalArgumentException> {
                    runner.run(node("nal.rule.admit"), mapOf("rules" to listOf(spec, bad)))
                }
                assertEquals(0, element.rules.size)
                assertTrue(ledger.isEmpty())
            }
            val temporal = runner.run(node("nal.rule.admit"), mapOf("rules" to listOf(
                (spec - "ruleCid") + ("copula" to "=/>"))))
            assertEquals(0, temporal["admitted"])
            assertEquals(1, temporal["rejected"])
            assertTrue(ledger.isEmpty())
            val out = runner.run(node("nal.rule.admit"), mapOf("rules" to listOf(spec)))
            assertEquals(listOf(rule.ruleCid.value), out["ruleCids"])
            assertEquals(rule, element.rules[0])
            assertEquals(listOf(rule), ledger)
            assertEquals(0, runner.run(node("nal.rule.admit", mapOf("antecedent" to "x", "consequent" to "y")),
                mapOf("rules" to emptyList<Any?>()))["admitted"])
        } finally { element.drain(); bag.drain() }
    }

    private fun node(type: String, params: Map<String, String> = emptyMap()) =
        LcncNode(id = "n", type = type, params = params)

    private fun angularOf(subject: String, obj: String) =
        KgTriplet(subject, "asserts", obj).angularIdentity()

    @Test
    fun strictAdmissionRejectsStrippedCandidatesAndForgedLegacyParameters() = runTest {
        val (bag, element) = liveElement()
        val ledger = mutableListOf<EternalRule>()
        val runner = RuleNodes.ruleAdmitRunner(element, ledger = { ledger.add(it) })
        val stripped = mapOf("antecedent" to "Rain", "consequent" to "floods", "copula" to "==>")
        try {
            assertFailsWith<IllegalArgumentException> {
                runner.run(node("nal.rule.admit"), mapOf("rules" to listOf(stripped)))
            }
            assertFailsWith<IllegalArgumentException> {
                runner.run(node("nal.rule.admit", mapOf("antecedent" to "Rain", "consequent" to "floods")), emptyMap())
            }
            assertFailsWith<IllegalArgumentException> {
                runner.run(node("nal.rule.admit", mapOf("mode" to "TRUSTED_HOST_LEGACY")),
                    mapOf("rules" to listOf(stripped), "policy" to "TRUSTED_HOST_LEGACY"))
            }
            assertFailsWith<IllegalStateException> {
                runner.run(node("nal.rule.admit"), mapOf("releaseCid" to ContentId.of("unverified".encodeToByteArray()).value))
            }
            assertEquals(0, element.rules.size)
            assertTrue(ledger.isEmpty())
        } finally { element.drain(); bag.drain() }
    }

    @Test
    fun strictAdmissionUsesOnlyCurrentReviewCallbackMaterial() = runTest {
        val (bag, element) = liveElement()
        val ledger = mutableListOf<EternalRule>()
        val release = ContentId.of("review-release".encodeToByteArray())
        val wrong = ContentId.of("invalid-release".encodeToByteArray())
        val approved = mapOf("antecedent" to "fire", "consequent" to "smoke", "copula" to "==>")
        val injection = mapOf("antecedent" to "fire", "consequent" to "unreviewed", "copula" to "==>")
        var consumed = false
        val runner = RuleNodes.ruleAdmitRunner(element, review = { cid ->
            require(cid == release) { "Unknown release" }
            check(!consumed) { "Release already consumed" }
            consumed = true
            mapOf("releaseCid" to cid.value, "ruleCandidates" to listOf(mapOf("value" to approved)))
        }, ledger = { ledger.add(it) })
        try {
            assertFailsWith<IllegalArgumentException> {
                runner.run(node("nal.rule.admit"), mapOf("releaseCid" to wrong.value, "rules" to listOf(injection)))
            }
            assertEquals(0, element.rules.size)
            assertTrue(ledger.isEmpty())
            val result = runner.run(node("nal.rule.admit"), mapOf("releaseCid" to release.value, "rules" to listOf(injection)))
            assertEquals(1, result["admitted"])
            assertEquals("smoke", element.rules[0].consequent)
            assertEquals(listOf("smoke"), ledger.map { it.consequent })
            assertFailsWith<IllegalStateException> {
                runner.run(node("nal.rule.admit"), mapOf("releaseCid" to release.value))
            }
            assertEquals(1, element.rules.size)
            assertEquals(1, ledger.size)
        } finally { element.drain(); bag.drain() }
    }

    /** single-consumer intake: poll until the condition holds (Default-dispatcher consumer). */
    private suspend fun await(message: String, cond: () -> Boolean) {
        withTimeout(60_000) { while (!cond()) delay(10) }
        assertTrue(cond(), message)
    }

    /** Bag + dead-spin element (zero rules, exactly how the daemon boots it) with ONE registered live assertion. */
    private suspend fun liveElement(subject: String = "fire", obj: String = "hearth"): Pair<BeliefBagElement, CausalityReteElement> {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val element = CausalityReteElement(bag, rules = emptySeriesOf())
        element.open()
        val angular = angularOf(subject, obj)
        bag.intake.send(
            BeliefIntake.Mint(
                SemanticSignal(
                    angular = angular,
                    evidence = EvidenceCoord(2 * Nal.UNIT, 0L),
                    relation = RelationKind.MATCH,
                    subjectCid = ContentId.of(subject.encodeToByteArray()).value,
                ),
                BudgetCoord(0.8f, 0.5f, 0.6f),
            ),
        )
        await("minted assertion must land") { bag.size >= 1 }
        element.register(angular, subject, obj)
        return bag to element
    }

    @Test
    fun admitEndsDeadSpin() = runTest {
        val (bag, element) = liveElement()
        assertEquals(0, element.fireLive().size, "an empty alpha network must fire nothing")

        val added = element.admit(
            listOf(EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(2 * Nal.UNIT, 0L))).toSeries(),
        )
        assertEquals(1, added)
        assertEquals(1, element.rules.size)

        val landed = element.fireLive()
        assertEquals(1, landed.size, "the admitted rule must fire against the live assertion")
        val (consequentAngular, gloss) = landed[0]
        assertEquals(KgTriplet("fire", "entails", "smoke").angularIdentity(), consequentAngular)
        assertTrue(gloss.startsWith("fire ==> smoke"), "gloss must caption the fired rule, was '$gloss'")

        assertEquals(0, element.fireLive().size, "a seen firing must not re-fire")
        bag.drain()
    }

    @Test
    fun ruleAdmitRunnerAdmitsParamsAndJsonRules() = runTest {
        val (bag, element) = liveElement()
        val runner = RuleNodes.ruleAdmitRunner(element, policy = RuleNodes.AdmissionPolicy.TRUSTED_HOST_LEGACY)
        val singleRule = mapOf("antecedent" to "fire", "consequent" to "smoke", "copula" to "==>")

        val single = runner.run(node("nal.rule.admit", singleRule), emptyMap())
        assertEquals(1, single["admitted"])
        assertEquals(1, (single["ruleCids"] as List<*>).size)

        val duplicate = runner.run(node("nal.rule.admit", singleRule), emptyMap())
        assertEquals(0, duplicate["admitted"], "an exact duplicate of an admitted rule counts zero")

        val json = runner.run(
            node("nal.rule.admit"),
            mapOf(
                "rules" to """[
                    {"antecedent":"heat","consequent":"expansion","copula":"<=>"},
                    {"antecedent":"fire","consequent":"ash","discount":0.25}
                ]""",
            ),
        )
        assertEquals(2, json["admitted"])
        assertEquals(2, (json["ruleCids"] as List<*>).size)
        assertEquals(3, element.rules.size)

        val landed = element.fireLive()
        assertEquals(2, landed.size, "both fire-antecedent rules fire against the live assertion")
        bag.drain()
    }

    @Test
    fun kgRunnerDefaultsToUnreviewedCandidatesEvenWhenGraphRequestsLegacy() = runTest {
        val (bag, element) = liveElement()
        try {
            val runner = RuleNodes.rulesFromKgRunner(element)
            val output = runner.run(
                node("nal.rules.fromKg", mapOf("mode" to "TRUSTED_HOST_LEGACY", "policy" to "TRUSTED_HOST_LEGACY", "copula" to "==>")),
                mapOf("kgText" to "(causes fire smoke)\n(=> fire ash)", "policy" to "TRUSTED_HOST_LEGACY"),
            )
            val candidates = output["rules"] as List<*>
            assertEquals(2, candidates.size)
            for (value in candidates) {
                val candidate = value as Map<*, *>
                assertEquals("unreviewed", candidate["reviewStatus"])
                val source = candidate["sourceEvidence"] as Map<*, *>
                assertEquals("unretained-kg-text", source["kind"])
            }
            assertEquals(0, output["admitted"])
            assertEquals("deferred-unreviewed", output["admissionStatus"])
            val legacyAdmission = RuleNodes.ruleAdmitRunner(element, policy = RuleNodes.AdmissionPolicy.TRUSTED_HOST_LEGACY)
            assertFailsWith<IllegalArgumentException> {
                legacyAdmission.run(node("nal.rule.admit"), mapOf("rules" to candidates))
            }
            assertEquals(0, element.rules.size)
            assertEquals(0, element.fireLive().size)
        } finally { element.drain(); bag.drain() }
    }

    @Test
    fun rulesFromKgRunnerDoesNotTurnLogicalOrTemporalRelationsIntoCausality() = runTest {
        val (bag, element) = liveElement()
        val legacy = RuleNodes.rulesFromKgRunner(element, policy = RuleNodes.KgAdmissionPolicy.TRUSTED_HOST_LEGACY)
        val out = legacy.run(
            node("nal.rules.fromKg"),
            mapOf("kgText" to "(=> fire smoke)\n(precedes rain flood)"),
        )
        assertEquals(2, (out["rules"] as List<*>).size, "both statements bridge to rules")
        assertEquals(0, out["admitted"], "neither statement establishes causality")
        assertEquals(0, out["rejectedTemporal"], "precedence is not a predictive implication")
        assertEquals(2, out["rejectedNonEternal"], "both are refused by the causal rule lane")
        assertEquals(0, element.rules.size)

        val landed = element.fireLive()
        assertEquals(0, landed.size, "no unsupported causal evidence is minted")
        val explicitCause = legacy.run(
            node("nal.rules.fromKg"),
            mapOf("kgText" to "(causes fire smoke)"),
        )
        assertEquals(1, explicitCause["admitted"], "an explicitly causal statement remains eligible")
        assertEquals(1, element.fireLive().size)
        bag.drain()
    }
}
