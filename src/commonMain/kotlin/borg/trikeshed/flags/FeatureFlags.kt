package borg.trikeshed.flags

import kotlin.math.abs

data class FeatureFlag(
    val key: String,
    val rolloutPercentage: Int // 0 to 100
) {
    init {
        require(rolloutPercentage in 0..100) { "Rollout percentage must be between 0 and 100" }
    }
}

class FeatureFlagManager {
    private val flags = mutableMapOf<String, FeatureFlag>()

    fun setFlag(flag: FeatureFlag) {
        flags[flag.key] = flag
    }

    fun getFlag(key: String): FeatureFlag? = flags[key]

    fun isEnabled(key: String, contextId: String): Boolean {
        val flag = flags[key] ?: return false

        if (flag.rolloutPercentage == 0) return false
        if (flag.rolloutPercentage == 100) return true

        // Stable hash function for contextId to distribute buckets
        val hash = abs(contextId.hashCode().toLong() % 100).toInt()
        return hash < flag.rolloutPercentage
    }
}
