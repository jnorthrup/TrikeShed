package borg.trikeshed.profile

import borg.trikeshed.build.BuildScan
import borg.trikeshed.build.TaskGraph

interface GradleProfilerIntegration {
    fun startProfiling(): String
    fun stopProfiling(sessionId: String): BuildScan
    fun analyzeTaskGraph(graph: TaskGraph): List<String>
}

class DefaultGradleProfilerIntegration : GradleProfilerIntegration {
    private val sessions = mutableMapOf<String, Long>()

    override fun startProfiling(): String {
        val sessionId = "session-\${kotlin.random.Random.nextInt()}"
        sessions[sessionId] = 0L // Mocking start time
        return sessionId
    }

    override fun stopProfiling(sessionId: String): BuildScan {
        val startTime = sessions.remove(sessionId) ?: throw IllegalArgumentException("Invalid session ID")
        return BuildScan(sessionId, 1000L, 50, true) // Mocked return
    }

    override fun analyzeTaskGraph(graph: TaskGraph): List<String> {
        val issues = mutableListOf<String>()
        if (graph.dependencies.size > 100) {
            issues.add("Task graph is too complex, consider splitting modules.")
        }
        return issues
    }
}
