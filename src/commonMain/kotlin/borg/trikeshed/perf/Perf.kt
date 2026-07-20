package borg.trikeshed.perf

import borg.trikeshed.build.BuildScan

data class CompilationHotspot(
    val taskId: String,
    val durationMs: Long,
    val recommendation: String
)

interface PerfAnalyzer {
    fun analyze(scan: BuildScan, tasks: List<Pair<String, Long>>): List<CompilationHotspot>
}

class DefaultPerfAnalyzer : PerfAnalyzer {
    override fun analyze(scan: BuildScan, tasks: List<Pair<String, Long>>): List<CompilationHotspot> {
        val hotspots = mutableListOf<CompilationHotspot>()
        for ((taskId, duration) in tasks) {
            if (duration > 5000L) {
                hotspots.add(CompilationHotspot(taskId, duration, "Consider enabling task caching for \$taskId or splitting the module."))
            } else if (duration > 2000L && !scan.isCacheHit) {
                hotspots.add(CompilationHotspot(taskId, duration, "Ensure caching is configured properly for \$taskId."))
            }
        }
        return hotspots.sortedByDescending { it.durationMs }
    }
}
