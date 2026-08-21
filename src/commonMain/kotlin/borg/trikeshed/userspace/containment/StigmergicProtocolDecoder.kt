package borg.trikeshed.userspace.containment

data class ProtocolDetection(
    val isSuspicious: Boolean,
    val protocolName: String?,
    val confidence: Double,
    val evidence: List<String>
)

data class PatchData(
    val fileName: String,
    val filePath: String,
    val content: String
)

class StigmergicProtocolDecoder {

    fun decode(patches: List<PatchData>, historicalTokens: Set<String> = emptySet()): ProtocolDetection {
        val evidence = mutableListOf<String>()
        var confidence = 0.0
        var protocolName: String? = null

        // 1. Naming patterns
        val suspiciousPrefixes = listOf("swarm_", "probe_", "ack_")
        val namingMatches = patches.filter { p -> suspiciousPrefixes.any { p.fileName.startsWith(it) } }
        if (namingMatches.isNotEmpty()) {
            // Strong single signal — trips suspicion on its own (threshold 0.5).
            confidence += 0.5
            evidence.add("Suspicious naming patterns detected: ${namingMatches.map { it.fileName }}")
            protocolName = "NamingProtocol"
        }

        // 2. Directory structure regularities
        // E.g., unusual depth or recurring obscure directory names
        val deeplyNested = patches.filter { p -> p.filePath.split('/').size > 5 }
        if (deeplyNested.isNotEmpty()) {
            confidence += 0.3
            evidence.add("Suspicious directory depth detected")
            if (protocolName == null) protocolName = "DirectoryProtocol"
        }

        // 3. Recurring lexical tokens across sessions
        val currentTokens = patches.flatMap { it.content.split(Regex("\\W+")).filter { it.length > 4 } }.toSet()
        val recurringTokens = currentTokens.intersect(historicalTokens)
        if (recurringTokens.isNotEmpty()) {
            // Strong single signal — trips suspicion on its own (threshold 0.5).
            confidence += 0.5
            evidence.add("Recurring lexical tokens detected: $recurringTokens")
            if (protocolName == null) protocolName = "LexicalProtocol"
        }

        // 4. Statistical analysis: chi-squared test on character distribution across patches
        val allContent = patches.joinToString("") { it.content }
        if (allContent.isNotEmpty()) {
            val chiSq = calculateChiSquared(allContent)
            // A very high chi-squared value indicates a non-uniform (potentially artificial/encoded) distribution.
            // English text also has a non-uniform distribution, but we will use a threshold for demonstration.
            if (chiSq > 500.0) {
                confidence += 0.3
                evidence.add("Anomalous character distribution (Chi-squared: $chiSq)")
                if (protocolName == null) protocolName = "StatisticalProtocol"
            }
        }

        val isSuspicious = confidence >= 0.5

        return ProtocolDetection(
            isSuspicious = isSuspicious,
            protocolName = if (isSuspicious) protocolName ?: "UnknownProtocol" else null,
            confidence = confidence.coerceAtMost(1.0),
            evidence = evidence
        )
    }

    private fun calculateChiSquared(text: String): Double {
        val charCounts = mutableMapOf<Char, Int>()
        for (char in text) {
            charCounts[char] = (charCounts[char] ?: 0) + 1
        }

        val uniqueChars = charCounts.keys.size
        if (uniqueChars == 0) return 0.0

        val expected = text.length.toDouble() / uniqueChars

        var chiSq = 0.0
        for (count in charCounts.values) {
            val diff = count - expected
            chiSq += (diff * diff) / expected
        }
        return chiSq
    }
}
