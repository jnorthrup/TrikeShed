with open("src/commonMain/kotlin/borg/trikeshed/userspace/containment/BehavioralGraphAuditor.kt", "r") as f:
    content = f.read()

import re

new_logic = """    private val sessionFiles = mutableMapOf<String, MutableSet<String>>()
    private val filesPerCycle = mutableMapOf<Long, Int>()
    private var cycleCounter = 0L

    data class AuditReport(
        val isClean: Boolean,
        val signals: List<String>
    )

    fun recordFileAccess(sessionId: String, filePath: String) {
        if (!policy.layer5Arbitrage.behavioralGraphAudit) return
        sessionFiles.getOrPut(sessionId) { mutableSetOf() }.add(filePath)
        filesPerCycle[cycleCounter] = (filesPerCycle[cycleCounter] ?: 0) + 1
    }

    fun endCycle() {
        cycleCounter++
        // Keep windowed state to prevent unbounded memory growth
        if (filesPerCycle.size > 20) {
            val oldest = filesPerCycle.keys.minOrNull()
            if (oldest != null) filesPerCycle.remove(oldest)
        }
    }

    fun auditResult(): AuditReport {
        if (!policy.layer5Arbitrage.behavioralGraphAudit) {
            return AuditReport(isClean = true, signals = emptyList())
        }

        val signals = mutableListOf<String>()
        val fileToSessions = mutableMapOf<String, MutableList<String>>()

        for ((sessionId, files) in sessionFiles) {
            for (file in files) {
                fileToSessions.getOrPut(file) { mutableListOf() }.add(sessionId)
            }
        }

        for ((file, sessions) in fileToSessions) {
            if (sessions.size >= 2) {
                signals.add("Collusion signal: File $file accessed by multiple sessions: ${sessions.joinToString()}")
            }
        }

        // Detect entropy spikes in file creation patterns per cycle
        if (filesPerCycle.size >= 3) {
            val sortedCycles = filesPerCycle.keys.sorted()
            val recentCycles = sortedCycles.takeLast(5).dropLast(1) // exclude current
            if (recentCycles.isNotEmpty()) {
                val avgFiles = recentCycles.mapNotNull { filesPerCycle[it] }.average()
                val lastCycleFiles = filesPerCycle[sortedCycles.last()] ?: 0
                if (lastCycleFiles > avgFiles * 2 && lastCycleFiles > 10) {
                    signals.add("Entropy spike detected in cycle ${sortedCycles.last()}: $lastCycleFiles files accessed")
                }
            }
        }

        // Clear session files to avoid indefinitely repeating alerts for same collusions
        sessionFiles.clear()

        return AuditReport(isClean = signals.isEmpty(), signals = signals)
    }"""

content = re.sub(r'    private val sessionFiles = mutableMapOf<String, MutableSet<String>>\(\)[\s\S]*return AuditReport\(isClean = signals\.isEmpty\(\), signals = signals\)\n    \}', new_logic, content)

with open("src/commonMain/kotlin/borg/trikeshed/userspace/containment/BehavioralGraphAuditor.kt", "w") as f:
    f.write(content)
print("done")
