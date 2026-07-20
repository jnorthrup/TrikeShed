package borg.trikeshed.build

data class BuildScan(
    val id: String,
    val durationMs: Long,
    val tasksExecuted: Int,
    val isCacheHit: Boolean
)

data class TaskGraph(
    val rootTaskId: String,
    val dependencies: Map<String, List<String>>
)
