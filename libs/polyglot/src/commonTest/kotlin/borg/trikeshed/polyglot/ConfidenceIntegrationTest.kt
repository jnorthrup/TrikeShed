package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceIntegrationTest {

    @Test
    fun testRealSourceSamples() {
        // Dummy classifiers that just echo what we give them to simulate real matching
        // Actually, the real TypeEvidence is generated based on text. So we should test against dummy LangFingerprints.
        val kotlinText = "fun main() { println(\"Hello\") }".toSeries()
        val rustText = "fn main() { println!(\"Hello\"); }".toSeries()

        val kotlinEv = TypeEvidence.sample(kotlinText)
        val rustEv = TypeEvidence.sample(rustText)

        // Let's check confidence between identical things
        val confKotlinSelf = confidence(kotlinEv, kotlinEv)
        assertEquals(1.0, confKotlinSelf, "Confidence of identical TypeEvidence should be 1.0")

        val confRustSelf = confidence(rustEv, rustEv)
        assertEquals(1.0, confRustSelf, "Confidence of identical TypeEvidence should be 1.0")

        // Let's check confidence between different things
        val confKotlinRust = confidence(kotlinEv, rustEv)
        assertTrue(confKotlinRust < 1.0, "Confidence between different sources should be < 1.0")
    }
}
