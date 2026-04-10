package borg.literbike.betanet

/**
 * Adaptive typing system - IoMemento + Evidence counters.
 * Ported from literbike/src/betanet/adaptive_typing.rs.
 */

/**
 * IoMemento - runtime type representation with evidence tracking.
 */
enum class IoMemento {
    IoString,
    IoInt,
    IoLong,
    IoFloat,
    IoDouble,
    IoBoolean,
    IoInstant,
    IoLocalDate,
    IoBytes,
    IoUuid;

    /** Network size for serialization (null = variable length) */
    fun networkSize(): Int? = when (this) {
        IoString -> null
        IoInt -> 4
        IoLong -> 8
        IoFloat -> 4
        IoDouble -> 8
        IoBoolean -> 1
        IoInstant -> 8
        IoLocalDate -> 4
        IoBytes -> null
        IoUuid -> 16
    }

    /** SIMD-friendly types for autovec optimization */
    fun isSimdFriendly(): Boolean = this in setOf(IoInt, IoLong, IoFloat, IoDouble)

    /** Check if type supports direct mmap casting */
    fun isMmapSafe(): Boolean = networkSize() != null
}

/**
 * Evidence counter for adaptive type inference.
 */
class Evidence(
    private val confidenceThreshold: Double = 0.8
) {
    private val typeCounts = mutableMapOf<IoMemento, Long>()
    private var totalObservations: Long = 0
    private var currentType: IoMemento? = null
    val promotionHistory = mutableListOf<Triple<IoMemento, IoMemento, Long>>()

    companion object {
        fun withThreshold(threshold: Double): Evidence = Evidence(threshold)
    }

    /** Add evidence for a specific type. Returns true if type was promoted */
    fun addEvidence(memento: IoMemento): Boolean {
        typeCounts[memento] = typeCounts.getOrDefault(memento, 0) + 1
        totalObservations++

        val previousType = currentType
        updateCurrentType()

        if (previousType != null && currentType != null && previousType != currentType) {
            promotionHistory.add(Triple(previousType, currentType!!, totalObservations))
            return true
        }
        return false
    }

    /** Get current best type with confidence */
    fun currentTypeWithConfidence(): Pair<IoMemento, Double>? {
        val type = currentType ?: return null
        val count = typeCounts[type] ?: 0
        val confidence = count.toDouble() / totalObservations
        return type to confidence
    }

    /** Check if we have sufficient evidence for the current type */
    fun isConfident(): Boolean {
        val (_, confidence) = currentTypeWithConfidence() ?: return false
        return confidence >= confidenceThreshold
    }

    /** Get type distribution for debugging */
    fun typeDistribution(): List<Pair<IoMemento, Double>> {
        return typeCounts.map { (memento, count) ->
            memento to (count.toDouble() / totalObservations)
        }.sortedByDescending { it.second }
    }

    /** Suggest optimal SIMD strategy based on evidence */
    fun simdStrategy(): SIMDStrategy {
        val (memento, confidence) = currentTypeWithConfidence() ?: return SIMDStrategy.Adaptive
        return if (confidence >= confidenceThreshold && memento.isSimdFriendly()) {
            when (memento) {
                IoMemento.IoInt -> SIMDStrategy.AVX2_I32
                IoMemento.IoLong -> SIMDStrategy.AVX2_I64
                IoMemento.IoFloat -> SIMDStrategy.AVX2_F32
                IoMemento.IoDouble -> SIMDStrategy.AVX2_F64
                else -> SIMDStrategy.Scalar
            }
        } else {
            SIMDStrategy.Scalar
        }
    }

    /** Reset evidence */
    fun reset() {
        typeCounts.clear()
        totalObservations = 0
        currentType = null
    }

    private fun updateCurrentType() {
        if (totalObservations == 0) {
            currentType = null
            return
        }
        currentType = typeCounts.maxByOrNull { it.value }?.key
    }
}

/**
 * SIMD strategy based on evidence.
 */
enum class SIMDStrategy {
    Scalar,
    AVX2_I32,
    AVX2_I64,
    AVX2_F32,
    AVX2_F64,
    Adaptive;

    fun registerWidth(): Int = when (this) {
        Scalar -> 1
        AVX2_I32, AVX2_F32 -> 8
        AVX2_I64, AVX2_F64 -> 4
        Adaptive -> 1
    }

    fun elementSize(): Int = when (this) {
        Scalar, Adaptive -> 8
        AVX2_I32, AVX2_F32 -> 4
        AVX2_I64, AVX2_F64 -> 8
    }
}
