package borg.trikeshed.forge.swarm

import borg.trikeshed.forge.*

/**
 * Swarm entities for unified workspace.
 * 
 * Maps Hermes kanban swarm to Forge:
 * - root task → SwarmRoot
 * - workers → SwarmWorker  
 * - verifier → SwarmVerifier
 * - synthesizer → SwarmSynthesizer
 */

/**
 * Swarm topology metadata.
 */
data class SwarmTopology(
    val rootId: String,
    val workerIds: List<String>,
    val verifierId: String,
    val synthesizerId: String,
    val goal: String,
)

/**
 * Swarm root task.
 */
data class SwarmRoot(
    val id: String,
    val goal: String,
    val topology: SwarmTopology,
    val status: String = "done",
) {
    fun toMap(): Map<String, String> = mapOf(
        "type" to "swarm_root",
        "task_id" to id,
        "goal" to goal,
        "worker_count" to topology.workerIds.size.toString(),
        "verifier_id" to topology.verifierId,
        "synthesizer_id" to topology.synthesizerId,
    )
    
    companion object {
        fun fromMap(row: Map<String, String>): SwarmRoot {
            return SwarmRoot(
                id = row["task_id"] ?: error("missing task_id"),
                goal = row["goal"] ?: "",
                topology = SwarmTopology(
                    rootId = row["task_id"] ?: "",
                    workerIds = emptyList(),
                    verifierId = row["verifier_id"] ?: "",
                    synthesizerId = row["synthesizer_id"] ?: "",
                    goal = row["goal"] ?: "",
                ),
            )
        }
    }
}

/**
 * Swarm worker task.
 */
data class SwarmWorker(
    val id: String,
    val rootId: String,
    val profile: String,
    val skills: List<String>,
    val status: String,
    val priority: Int = 0,
) {
    fun toMap(): Map<String, String> = mapOf(
        "type" to "swarm_worker",
        "task_id" to id,
        "root_id" to rootId,
        "profile" to profile,
        "skills" to skills.joinToString(","),
        "status" to status,
        "priority" to priority.toString(),
    )
    
    companion object {
        fun fromMap(row: Map<String, String>): SwarmWorker {
            return SwarmWorker(
                id = row["task_id"] ?: error("missing task_id"),
                rootId = row["root_id"] ?: error("missing root_id"),
                profile = row["profile"] ?: "",
                skills = row["skills"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                status = row["status"] ?: "todo",
                priority = row["priority"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

/**
 * Swarm verifier task.
 */
data class SwarmVerifier(
    val id: String,
    val rootId: String,
    val workerIds: List<String>,
    val status: String,
) {
    fun toMap(): Map<String, String> = mapOf(
        "type" to "swarm_verifier",
        "task_id" to id,
        "root_id" to rootId,
        "worker_ids" to workerIds.joinToString(","),
        "status" to status,
    )
    
    companion object {
        fun fromMap(row: Map<String, String>): SwarmVerifier {
            return SwarmVerifier(
                id = row["task_id"] ?: error("missing task_id"),
                rootId = row["root_id"] ?: "",
                workerIds = row["worker_ids"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                status = row["status"] ?: "todo",
            )
        }
    }
}

/**
 * Swarm synthesizer task.
 */
data class SwarmSynthesizer(
    val id: String,
    val rootId: String,
    val verifierId: String,
    val status: String,
) {
    fun toMap(): Map<String, String> = mapOf(
        "type" to "swarm_synthesizer",
        "task_id" to id,
        "root_id" to rootId,
        "verifier_id" to verifierId,
        "status" to status,
    )
    
    companion object {
        fun fromMap(row: Map<String, String>): SwarmSynthesizer {
            return SwarmSynthesizer(
                id = row["task_id"] ?: error("missing task_id"),
                rootId = row["root_id"] ?: "",
                verifierId = row["verifier_id"] ?: "",
                status = row["status"] ?: "todo",
            )
        }
    }
}

/**
 * Swarm status enum.
 */
enum class SwarmStatus {
    PENDING,
    IN_PROGRESS,
    VERIFYING,
    COMPLETE,
    BLOCKED,
}

/**
 * Swarm events for fanout.
 */
sealed class SwarmEvent {
    data class WorkerStarted(val workerId: String) : SwarmEvent()
    data class WorkerCompleted(val workerId: String, val summary: String) : SwarmEvent()
    data class WorkerFailed(val workerId: String, val error: String) : SwarmEvent()
    data class VerifierStarted(val verifierId: String) : SwarmEvent()
    data class VerifierPassed(val verifierId: String) : SwarmEvent()
    data class VerifierBlocked(val verifierId: String, val reason: String) : SwarmEvent()
    data class SynthesizerCompleted(val synthesizerId: String, val output: String) : SwarmEvent()
    data class BlackboardUpdated(val rootId: String, val key: String, val value: String) : SwarmEvent()
}

/**
 * Render swarm as Mermaid graph.
 */
fun renderMermaid(root: SwarmRoot, workers: List<SwarmWorker>): String {
    return buildString {
        appendLine("graph TD")
        appendLine("  root[${root.goal.take(40)}...] --> workers")
        
        workers.forEach { worker ->
            val statusIcon = when (worker.status) {
                "done" -> "✓"
                "running" -> "↻"
                "blocked" -> "⊘"
                else -> "○"
            }
            appendLine("  workers --> ${worker.id}[$statusIcon ${worker.profile}]")
        }
        
        appendLine("  workers --> verifier[Verifier]")
        appendLine("  verifier --> synthesizer[Synthesizer]")
    }
}

/**
 * Render swarm as Graphviz DOT.
 */
fun renderDot(root: SwarmRoot, workers: List<SwarmWorker>): String {
    return buildString {
        appendLine("digraph ${root.id} {")
        appendLine("  rankdir=TB;")
        
        appendLine("  root [shape=box,label=\"${root.goal.take(30)}\"];")
        appendLine("  workers [shape=doublecircle,label=\"Workers (${workers.size})\"];")
        appendLine("  verifier [shape=diamond,label=\"Verifier\"];")
        appendLine("  synthesizer [shape=box,label=\"Synthesizer\"];")
        
        appendLine("  root -> workers;")
        
        workers.forEach { worker ->
            val color = when (worker.status) {
                "done" -> "green"
                "running" -> "yellow"
                "blocked" -> "red"
                else -> "gray"
            }
            appendLine("  workers -> ${worker.id} [color=$color,label=\"${worker.profile}\"];")
        }
        
        appendLine("  workers -> verifier;")
        appendLine("  verifier -> synthesizer;")
        appendLine("}")
    }
}