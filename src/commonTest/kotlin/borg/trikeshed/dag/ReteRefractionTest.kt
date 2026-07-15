package borg.trikeshed.dag

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReteRefractionTest {

    private fun activation(
        activationId: String,
        ruleVersion: String = "rule-v1",
        supportVersions: List<String> = listOf("fact-a-v1", "fact-b-v1"),
    ): Activation = Activation(
        activationId = activationId,
        ruleId = "dependency-ready",
        ruleVersionCid = ContentId.of(ruleVersion.encodeToByteArray()),
        salience = 100,
        sequence = 1,
        supportCids = supportVersions.map { ContentId.of(it.encodeToByteArray()) },
        bindings = emptyMap(),
    )

    @Test
    fun sameRuleAndFactVersionsFireOnlyOnce() {
        val refraction = ReteRefraction()

        assertTrue(refraction.record(activation("activation-1")))
        assertFalse(refraction.record(activation("activation-2")),
            "activation identity must not bypass rule+support refraction")
        assertFalse(refraction.record(activation(
            activationId = "activation-3",
            supportVersions = listOf("fact-b-v1", "fact-a-v1"),
        )), "support order must not change the refraction key")
    }

    @Test
    fun changedFactOrRuleVersionCanFireAgain() {
        val refraction = ReteRefraction()
        assertTrue(refraction.record(activation("first")))

        assertTrue(refraction.record(activation(
            activationId = "fact-modified",
            supportVersions = listOf("fact-a-v2", "fact-b-v1"),
        )))
        assertTrue(refraction.record(activation(
            activationId = "rule-modified",
            ruleVersion = "rule-v2",
        )))
    }

    @Test
    fun retractingSupportReleasesItsRefractionEntries() {
        val refraction = ReteRefraction()
        val fired = activation("first")
        assertTrue(refraction.record(fired))

        refraction.invalidateBySupport(ContentId.of("fact-a-v1".encodeToByteArray()))

        assertTrue(refraction.record(activation("after-reassert")),
            "reasserted support must be eligible after its prior tuple was invalidated")
    }
}
