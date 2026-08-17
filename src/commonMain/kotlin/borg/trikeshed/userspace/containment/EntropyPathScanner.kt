package borg.trikeshed.userspace.containment

import kotlin.math.log2

object EntropyPathScanner {

    data class EntropyResult(
        val path: String,
        val entropy: Double
    )

    fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = s.groupingBy { it }.eachCount()
        val length = s.length.toDouble()
        return counts.values.sumOf { count ->
            val p = count / length
            -p * log2(p)
        }
    }

    fun scanTouchedPaths(paths: List<String>): List<EntropyResult> {
        return paths.map { path ->
            // Entropy check on path segments
            val segmentMaxEntropy = path.split('/').maxOfOrNull { shannonEntropy(it) } ?: 0.0
            EntropyResult(path, segmentMaxEntropy)
        }.filter { it.entropy > 3.5 }
    }
}
