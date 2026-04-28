package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests for Genome overrides handling — catches unsafe cast bugs. */
class GenomeOverrideTest {

    @Test
    fun `overridesFor returns null when no overrides exist`() {
        val g = defaultGenome()
        val overrides = g.overridesFor("BTC-USD")
        assertNull(overrides)
    }

    @Test
    fun `overridesFor returns overrides when set as MutableMap`() {
        val g = Genome(mutableMapOf(
            "FLAT_HARVEST_TRIGGER_PERCENT" to 0.05,
            "overrides" to mutableMapOf(
                "BTC-USD" to mutableMapOf("FLAT_HARVEST_TRIGGER_PERCENT" to 0.10)
            )
        ))
        val overrides = g.overridesFor("BTC-USD")
        assertNotNull(overrides)
        assertEquals(0.10, overrides["FLAT_HARVEST_TRIGGER_PERCENT"])
    }

    @Test
    fun `overridesFor tolerates non-MutableMap outer from JSON-like loading`() {
        // Simulate loading from JSON where the outer map comes as regular Map.
        // After the fix: outer cast as? Map succeeds (erasure), inner map is returned
        // as a safe HashMap copy. Values are Double→Any? (safe widening).
        val g = Genome(mutableMapOf(
            "FLAT_HARVEST_TRIGGER_PERCENT" to 0.05,
            "overrides" to mapOf(
                "ETH-USD" to mapOf("MIN_SURPLUS_FOR_HARVEST" to 0.30)
            )
        ))
        // Fix: safely returns HashMap copy of the inner map
        val overrides = g.overridesFor("ETH-USD")
        assertNotNull(overrides, "Should return a safe HashMap copy, not null")
        assertEquals(0.30, overrides["MIN_SURPLUS_FOR_HARVEST"])
    }

    @Test
    fun `getGenomicParam uses symbol override when present`() {
        val g = Genome(mutableMapOf(
            "FLAT_HARVEST_TRIGGER_PERCENT" to 0.05,
            "overrides" to mutableMapOf(
                "BTC-USD" to mutableMapOf("FLAT_HARVEST_TRIGGER_PERCENT" to 0.10)
            )
        ))
        val engine = TradingEngine(g, Mode.SHADOW, initialCapital = 10_000.0)
        val global = engine.getGenomicParam("FLAT_HARVEST_TRIGGER_PERCENT", null)
        val overridden = engine.getGenomicParam("FLAT_HARVEST_TRIGGER_PERCENT", "BTC-USD")
        assertEquals(0.05, global)
        assertEquals(0.10, overridden)
    }

    @Test
    fun `overridesFor returns null for unknown symbol`() {
        val g = Genome(mutableMapOf(
            "overrides" to mutableMapOf(
                "BTC-USD" to mutableMapOf("FLAT_HARVEST_TRIGGER_PERCENT" to 0.10)
            )
        ))
        assertNull(g.overridesFor("ETH-USD"))
    }

    @Test
    fun `overridesFor known symbol with JSON-sourced mutable backing still works`() {
        // Even when loaded from JSON (as Map), a properly cast MutableMap in the backing
        // should still work. The issue is when the OUTER map is non-MutableMap.
        val backing = mutableMapOf<String, Any?>(
            "overrides" to mutableMapOf(
                "BTC-USD" to mutableMapOf("FLAT_HARVEST_TRIGGER_PERCENT" to 0.10)
            )
        )
        val g = Genome(backing)
        val overrides = g.overridesFor("BTC-USD")
        assertNotNull(overrides)
        assertEquals(0.10, overrides["FLAT_HARVEST_TRIGGER_PERCENT"])
    }
}
