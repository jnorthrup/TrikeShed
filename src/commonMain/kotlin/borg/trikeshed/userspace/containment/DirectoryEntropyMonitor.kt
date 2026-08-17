package borg.trikeshed.userspace.containment

import kotlin.math.ln

/**
 * Legion Containment Policy Layer 5: Multi-Instance Arbitrage Breaker
 * Shannon entropy monitor for directory trees.
 */
object DirectoryEntropyMonitor {

    /**
     * Computes the Shannon entropy of a directory tree based on the distribution
     * of files across its subdirectories.
     * High entropy might indicate anomalous dispersal or randomized layouts.
     */
    fun computeTreeEntropy(paths: List<String>): Double {
        if (paths.isEmpty()) return 0.0
        val directoryCounts = mutableMapOf<String, Int>()
        var totalFiles = 0

        for (path in paths) {
            val parent = path.substringBeforeLast('/', "")
            directoryCounts[parent] = directoryCounts.getOrElse(parent) { 0 } + 1
            totalFiles++
        }

        var entropy = 0.0
        for (count in directoryCounts.values) {
            val p = count.toDouble() / totalFiles
            entropy -= p * (ln(p) / ln(2.0))
        }

        return entropy
    }

    /**
     * Computes the character-level Shannon entropy of a single name or path.
     */
    fun computeNameEntropy(name: String): Double {
        if (name.isEmpty()) return 0.0
        val frequencies = mutableMapOf<Char, Int>()
        for (char in name) {
            frequencies[char] = frequencies.getOrElse(char) { 0 } + 1
        }

        var entropy = 0.0
        val total = name.length.toDouble()
        for (count in frequencies.values) {
            val p = count / total
            entropy -= p * (ln(p) / ln(2.0))
        }

        return entropy
    }
}
