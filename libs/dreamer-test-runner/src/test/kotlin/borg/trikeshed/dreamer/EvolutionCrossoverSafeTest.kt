package borg.trikeshed.dreamer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Evolution.kt crossover safety with JSON/persistence-loaded genomes.
 *
 * These pin the contract that crossover children must not inherit non-mutable
 * inner maps from parents whose overrides were loaded from JSON or other sources
 * where the backing map may not implement MutableMap.
 *
 * The failure mode: crossover copies overrides1/overrides2 by reference.
 * If the inner maps are non-MutableMap (from JSON), then Genome.overridesFor()
 * on the child returns a non-MutableMap as MutableMap → ClassCastException.
 */
class EvolutionCrossoverSafeTest {

    /**
     * Simulate a genome whose overrides were loaded from JSON (non-MutableMap inner values).
     * The outer backing map is a MutableMap, but inner override values are plain Map.
     */
    private fun genomeWithJsonOverrides(): Genome {
        // Simulate JSON-deserialized overrides: outer MutableMap but inner Map (not MutableMap)
        val innerOverrides = mapOf(
            "BTC-USD" to mapOf(
                "FLAT_HARVEST_TRIGGER_PERCENT" to 0.10,
                "HARVEST_TAKE_PERCENT" to 0.50,
            ),
            "ETH-USD" to mapOf(
                "FLAT_HARVEST_TRIGGER_PERCENT" to 0.08,
            ),
        )
        val backing = mutableMapOf<String, Any?>(
            "FLAT_HARVEST_TRIGGER_PERCENT" to 0.05,
            "HARVEST_TAKE_PERCENT" to 0.60,
            "overrides" to innerOverrides, // non-MutableMap inner values
        )
        return Genome(backing)
    }

    @Test
    fun `crossover with JSON-loaded overrides child genome overridesFor does not throw`() {
        val parent1 = genomeWithJsonOverrides()
        val parent2 = defaultGenome()

        val (child1, child2) = crossover(parent1, parent2, Random(42))

        // Both children should have overrides (inherited from parent1)
        // The inner maps must be mutable (defensively copied by crossover)
        val child1BtcOverrides = child1.overridesFor("BTC-USD")
        val child2BtcOverrides = child2.overridesFor("BTC-USD")

        assertNotNull(child1BtcOverrides, "child1 should have BTC-USD overrides")
        assertNotNull(child2BtcOverrides, "child2 should have BTC-USD overrides")

        // These must not throw ClassCastException — the inner map must be MutableMap
        assertEquals(0.10, child1BtcOverrides["FLAT_HARVEST_TRIGGER_PERCENT"])
        assertEquals(0.50, child1BtcOverrides["HARVEST_TAKE_PERCENT"])
    }

    @Test
    fun `crossover with both parents JSON-loaded overrides child survives overridesFor calls`() {
        val parent1 = genomeWithJsonOverrides()
        // parent2 also has JSON-loaded overrides but for ETH-USD
        val parent2Overrides = mapOf(
            "ETH-USD" to mapOf(
                "FLAT_HARVEST_TRIGGER_PERCENT" to 0.12,
            ),
        )
        val parent2 = Genome(mutableMapOf<String, Any?>(
            "FLAT_HARVEST_TRIGGER_PERCENT" to 0.07,
            "overrides" to parent2Overrides,
        ))

        val (child1, child2) = crossover(parent1, parent2, Random(99))

        // At least one child should have BTC-USD overrides from parent1
        // Call overridesFor on all combinations — must not throw
        listOf(child1, child2).forEach { child ->
            listOf("BTC-USD", "ETH-USD").forEach { symbol ->
                val overrides = child.overridesFor(symbol)
                // If non-null, verify it is a MutableMap (the overridesFor return type)
                if (overrides != null) {
                    assertTrue(overrides is MutableMap, "overridesFor($symbol) must return MutableMap, got ${overrides::class}")
                    // Verify we can mutate without ClassCastException
                    overrides["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.09
                    assertEquals(0.09, overrides["FLAT_HARVEST_TRIGGER_PERCENT"])
                }
            }
        }
    }

    @Test
    fun `crossover deep-copies overrides so child mutation does not affect parent`() {
        val parent1 = genomeWithJsonOverrides()
        val parent2 = defaultGenome()

        val (child, _) = crossover(parent1, parent2, Random(7))
        val parent1BtcBefore = parent1.overridesFor("BTC-USD")?.get("FLAT_HARVEST_TRIGGER_PERCENT")

        // Mutate child's overrides
        val childBtc = child.overridesFor("BTC-USD")
        assertNotNull(childBtc)
        childBtc["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.99

        // Parent should be unchanged
        val parent1BtcAfter = parent1.overridesFor("BTC-USD")?.get("FLAT_HARVEST_TRIGGER_PERCENT")
        assertEquals(parent1BtcBefore, parent1BtcAfter,
            "Parent overrides should not be mutated when child is mutated")
    }
}
