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

/**
 * Rule admission end to end: the daemon boots [CausalityReteElement] over ZERO
 * rules — an empty alpha network fires nothing forever (dead spin). Admission
 * through [CausalityReteElement.admit] and the [RuleNodes] runners is what
 * brings the live rete to fire against the bag as it exists.
 */
class RuleAdmissionTest {

    private fun node(type: String, params: Map<String, String> = emptyMap()) =
        LcncNode(id = "n", type = type, params = params)

    private fun angularOf(subject: String, obj: String) =
        KgTriplet(subject, "asserts", obj).angularIdentity()

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
        val runner = RuleNodes.ruleAdmitRunner(element)
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
    fun rulesFromKgRunnerBridgesAdmitsAndRefusesTemporal() = runTest {
        val (bag, element) = liveElement()
        val out = RuleNodes.rulesFromKgRunner(element).run(
            node("nal.rules.fromKg"),
            mapOf("kgText" to "(=> fire smoke)\n(precedes rain flood)"),
        )
        assertEquals(2, (out["rules"] as List<*>).size, "both statements bridge to rules")
        assertEquals(1, out["admitted"], "only the eternal implication is admitted")
        assertEquals(1, out["rejectedTemporal"], "the temporal copula is counted and refused, never reinterpreted")
        assertEquals(1, element.rules.size)

        val landed = element.fireLive()
        assertEquals(1, landed.size, "the bridged eternal rule fires against the live assertion")
        val (_, gloss) = landed[0]
        assertTrue(gloss.startsWith("fire ==> smoke"), "gloss must caption the bridged rule, was '$gloss'")
        bag.drain()
    }
}
