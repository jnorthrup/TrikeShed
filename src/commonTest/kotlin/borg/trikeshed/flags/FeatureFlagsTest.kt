package borg.trikeshed.flags

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FeatureFlagsTest {
    @Test
    fun testPercentageRollout() {
        val manager = FeatureFlagManager()
        
        manager.setFlag(FeatureFlag("always_on", 100))
        manager.setFlag(FeatureFlag("always_off", 0))
        manager.setFlag(FeatureFlag("fifty_fifty", 50))
        
        assertTrue(manager.isEnabled("always_on", "user1"))
        assertFalse(manager.isEnabled("always_off", "user1"))
        
        // Let's test stable hashing. We can rely on deterministic strings for tests.
        val onCount = (1..100).count { manager.isEnabled("fifty_fifty", "user$it") }
        
        // Distribution might not be exactly 50% for 100 iterations due to hash collisions, 
        // but it should be somewhere in the middle (e.g. between 30 and 70).
        assertTrue(onCount in 30..70, "Distribution should be somewhat even, got $onCount")
    }
}
