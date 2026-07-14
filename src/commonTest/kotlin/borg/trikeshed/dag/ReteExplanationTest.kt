package borg.trikeshed.dag

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1 RED — Rete activation explanation exposes rule, matched facets,
 * support CIDs, and conflict rank.
 *
 * The plan: "each activation exposes rule, matched facets, support CIDs,
 * and conflict rank"
 */
class ReteExplanationTest {

    @Test
    fun activationExposesRuleMatchedFacetsSupportCidsAndConflictRank() {
        val activation = Activation(
            activationId = "act-1",
            ruleId = "dep-satisfied",
            ruleVersionCid = ContentId.of("rule-v1".encodeToByteArray()),
            salience = 100,
            sequence = 1,
            supportCids = listOf(ContentId.of("fact-a".encodeToByteArray())),
            bindings = mapOf("jobId" to "j-1", "depId" to "dep-1"),
        )

        val explanation = activation.explain()

        assertTrue(explanation.contains("dep-satisfied"), "explanation must name the rule")
        assertTrue(explanation.contains("j-1"), "explanation must include matched facets/bindings")
        assertTrue(explanation.contains("sha256:"), "explanation must include support CIDs")
        assertNotNull(activation.conflictRank, "activation must have a conflict rank")
    }

    @Test
    fun ruleVersionIsCasAddressed() {
        val rule = ReteProductionRule(
            ruleId = "r1",
            salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { ReteAction(FactId("b", "o"), emptyMap()) },
        )

        val cid = rule.versionCid
        assertNotNull(cid, "rule must have a CAS-addressed version CID")
        assertTrue(cid.value.startsWith("sha256:"))
    }
}

/**
 * P1 RED — Rule versioning: rules are CAS-addressed Confix records.
 * Activation records exact version.
 */
class RuleVersioningTest {

    @Test
    fun differentRuleVersionsProduceDifferentCids() {
        val rule1 = ReteProductionRule(
            ruleId = "r1",
            salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { ReteAction(FactId("b", "o"), emptyMap()) },
        )
        val rule2 = rule1.copy(conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "2")))

        assertNotEquals(rule1.versionCid, rule2.versionCid,
            "different rule versions must produce different CIDs")
    }

    @Test
    fun activationRecordsExactRuleVersion() {
        val rule = ReteProductionRule(
            ruleId = "r1",
            salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { ReteAction(FactId("b", "o"), emptyMap()) },
        )

        val activation = Activation(
            activationId = "a1",
            ruleId = rule.ruleId,
            ruleVersionCid = rule.versionCid,
            salience = rule.salience,
            sequence = 1,
            supportCids = emptyList(),
            bindings = emptyMap(),
        )

        assertEquals(rule.versionCid, activation.ruleVersionCid,
            "activation must record the exact rule version CID")
    }

    private fun assertNotEquals(a: Any?, b: Any?) {
        kotlin.test.assertNotEquals(a, b)
    }
}
