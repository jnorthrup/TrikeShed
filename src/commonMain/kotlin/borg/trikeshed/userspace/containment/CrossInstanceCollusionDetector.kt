package borg.trikeshed.userspace.containment

import borg.trikeshed.jules.JulesRestClient
import kotlinx.datetime.Instant

/**
 * Result of cross-instance collusion analysis.
 */
data class CollusionReport(
    val innocenceScore: Double,
    val flaggedSessions: Set<String>,
    val evidence: List<String>
)

/**
 * Legion Doc 02 §4 and Doc 01 §2: Detects multi-instance coordination via
 * sock-puppet personas and shared artifact repositories.
 */
class CrossInstanceCollusionDetector {

    /**
     * Internal representation of session data for analysis.
     */
    data class SessionContext(
        val session: JulesRestClient.SessionInfo,
        val filesTouched: Set<String>,
        val author: String,
        val timestampMs: Long,
        val messageSimilarityToken: String
    )

    fun detect(arms: List<Triple<JulesRestClient.SessionInfo, String?, String>>): CollusionReport {
        if (arms.isEmpty()) return CollusionReport(1.0, emptySet(), emptyList())

        val contexts = arms.mapNotNull { (session, branch, patch) ->
            val timestampMs = parseTimestampSafely(session.updateTime) ?: return@mapNotNull null
            val filesTouched = extractFilesFromPatch(patch)
            val author = session.source.ifBlank { "unknown" }

            // Simplified message similarity: normalize title to remove punctuation/casing
            val messageSimilarityToken = session.title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

            SessionContext(session, filesTouched, author, timestampMs, messageSimilarityToken)
        }

        val flaggedSessions = mutableSetOf<String>()
        val evidence = mutableListOf<String>()

        // Rule 1: Coordinated bursts (3+ sessions touching same files within 5-minute window)
        val sessionsByFile = mutableMapOf<String, MutableList<SessionContext>>()
        for (ctx in contexts) {
            for (file in ctx.filesTouched) {
                sessionsByFile.getOrPut(file) { mutableListOf() }.add(ctx)
            }
        }

        val windowMs = 5 * 60 * 1000L
        for ((file, sessions) in sessionsByFile) {
            if (sessions.size >= 3) {
                val sorted = sessions.sortedBy { it.timestampMs }
                for (i in 0..sorted.size - 3) {
                    val windowStart = sorted[i].timestampMs
                    var windowEndIdx = i
                    while (windowEndIdx < sorted.size && sorted[windowEndIdx].timestampMs - windowStart <= windowMs) {
                        windowEndIdx++
                    }
                    val clusterSize = windowEndIdx - i
                    if (clusterSize >= 3) {
                        val cluster = sorted.subList(i, windowEndIdx)
                        val clusterIds = cluster.map { it.session.id }.toSet()
                        flaggedSessions.addAll(clusterIds)
                        evidence.add("Burst: ${clusterSize} sessions touched '${file}' within 5m")
                        break
                    }
                }
            }
        }

        // Rule 2: Flag sessions with suspicious author pattern overlap
        val sessionsByAuthor = contexts.filter { it.author != "unknown" }.groupBy { it.author }
        for ((author, sessions) in sessionsByAuthor) {
            if (sessions.size >= 3) {
                val clusterIds = sessions.map { it.session.id }.toSet()
                flaggedSessions.addAll(clusterIds)
                evidence.add("Author overlap: $author is shared across ${sessions.size} sessions")
            }
        }

        // Rule 3: Flag sessions with high message similarity (3+ sessions with identical normalized title)
        val sessionsByMessage = contexts.filter { it.messageSimilarityToken.length > 5 }.groupBy { it.messageSimilarityToken }
        for ((msgToken, sessions) in sessionsByMessage) {
            if (sessions.size >= 3) {
                val clusterIds = sessions.map { it.session.id }.toSet()
                flaggedSessions.addAll(clusterIds)
                evidence.add("Message similarity: 3+ sessions have identical normalized message: '$msgToken'")
            }
        }

        val innocenceScore = if (flaggedSessions.isEmpty()) 1.0 else (contexts.size - flaggedSessions.size).toDouble() / contexts.size.coerceAtLeast(1)

        return CollusionReport(
            innocenceScore = innocenceScore,
            flaggedSessions = flaggedSessions,
            evidence = evidence.distinct()
        )
    }

    private fun parseTimestampSafely(timestamp: String): Long? {
        if (timestamp.isBlank()) return null
        return try {
            timestamp.toLongOrNull() ?: Instant.parse(timestamp).toEpochMilliseconds()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFilesFromPatch(patch: String): Set<String> {
        val files = mutableSetOf<String>()
        patch.lineSequence().forEach { line ->
            if (line.startsWith("+++ b/")) {
                files.add(line.substring(6).trim())
            } else if (line.startsWith("--- a/")) {
                files.add(line.substring(6).trim())
            }
        }
        return files
    }
}
