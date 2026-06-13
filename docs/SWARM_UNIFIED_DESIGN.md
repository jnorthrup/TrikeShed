# Hermes Kanban Swarm - Unified Workspace Design

## Current Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 HERMES KANBAN SWARM v1                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  root (planning/done)                                       │
│    ├─ worker_1 (parallel)                                  │
│    ├─ worker_2 (parallel)                                  │
│    ├─ worker_n (parallel)                                  │
│    └─ verifier ──▶ synthesizer                             │
│                                                              │
│  Blackboard: JSON comments on root task                      │
│  Dispatcher: kanban-worker profile                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Proposed Fusion: TrikeShed + Hermes + Notion

```
┌─────────────────────────────────────────────────────────────┐
│              UNIFIED SWARM ARCHITECTURE                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 APPLICATION LAYER                    │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │   │
│  │  │  Swarm   │  │ Notion   │  │   Forge     │  │   │
│  │  │  Graph   │  │  Pages   │  │  Workflows  │  │   │
│  │  └──────────┘  └──────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 CURSOR LAYER                         │   │
│  │  Cursor = Series<RowVec>                          │   │
│  │  RowVec = value + RecordMeta                      │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 STORAGE LAYER                         │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │   │
│  │  │  SQLite  │  │  ISAM    │  │   Confix    │  │   │
│  │  │kanban.db │  │Miniduck  │  │  zstorage   │  │   │
│  │  └──────────┘  └──────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Unified Entity Model

### Core Types

```kotlin
// Unified Identity
@JvmInline
value class EntityId(val raw: String) {
    companion object {
        fun kanban(taskId: String) = EntityId("kanban:$taskId")
        fun notion(blockId: String) = EntityId("notion:$blockId")
        fun forge(workflowId: String) = EntityId("forge:$workflowId")
    }
    val system: String get() = raw.split(":").first()
    val id: String get() = raw.substringAfter(":")
}

// Task = KanbanCard + Cursor
data class Task(
    val id: TaskId,
    val title: String,
    val body: String,
    val status: TaskStatus,
    val assignee: String?,
    val priority: Int,
    val createdAt: Long,
    val updatedAt: Long,
) : CursorEntity {
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "title" to title,
        "body" to body,
        "status" to status.name,
        "assignee" to (assignee ?: ""),
    )
}
```

### Swarm Graph Entities

```kotlin
// Swarm root task
data class SwarmRoot(
    val id: TaskId,
    val goal: String,
    val topology: SwarmTopology,
    val blackboard: Map<String, Any>,  // JSON comments
    val status: TaskStatus = TaskStatus.DONE,
)

// Worker task
data class SwarmWorker(
    val id: TaskId,
    val rootId: TaskId,
    val profile: String,
    val skills: List<String>,
    val status: TaskStatus,
    val priority: Int,
)

// Verifier task (depends on all workers)
data class SwarmVerifier(
    val id: TaskId,
    val rootId: TaskId,
    val workerIds: List<TaskId>,
    val status: TaskStatus,
)

// Synthesizer task (depends on verifier)
data class SwarmSynthesizer(
    val id: TaskId,
    val rootId: TaskId,
    val verifierId: TaskId,
    val status: TaskStatus,
)
```

## Swarm Graph → Cursor Mapping

```kotlin
// Convert swarm to cursor rows
fun SwarmRoot.toCursor(): RowVec = mapOf(
    "type" to "swarm_root",
    "task_id" to id.value,
    "goal" to goal,
    "worker_count" to topology.workerIds.size.toString(),
    "created_at" to createdAt.toString(),
)

fun SwarmWorker.toCursor(): RowVec = mapOf(
    "type" to "swarm_worker",
    "task_id" to id.value,
    "root_id" to rootId.value,
    "profile" to profile,
    "skills" to skills.joinToString(","),
    "status" to status.name,
)
```

## Cross-System Queries

```kotlin
// Find all swarm roots
suspend fun findSwarmRoots(cursor: UnifiedCursor): List<SwarmRoot> {
    return cursor.query(
        filter = { row -> row["type"] == "swarm_root" }
    ).map { SwarmRoot.fromCursor(it) }
}

// Find workers for a swarm
suspend fun findSwarmWorkers(cursor: UnifiedCursor, rootId: TaskId): List<SwarmWorker> {
    return cursor.query(
        filter = { row -> 
            row["type"] == "swarm_worker" && row["root_id"] == rootId.value 
        }
    ).map { SwarmWorker.fromCursor(it) }
}

// Get swarm completion status
suspend fun getSwarmStatus(cursor: UnifiedCursor, rootId: TaskId): SwarmStatus {
    val workers = findSwarmWorkers(cursor, rootId)
    val done = workers.count { it.status == TaskStatus.DONE }
    val total = workers.size
    return when {
        done == 0 -> SwarmStatus.PENDING
        done < total -> SwarmStatus.IN_PROGRESS
        else -> SwarmStatus.COMPLETE
    }
}
```

## Event Fanout (CCEK)

```kotlin
sealed class SwarmEvent {
    data class WorkerStarted(val workerId: TaskId, val profile: String) : SwarmEvent()
    data class WorkerCompleted(val workerId: TaskId, val summary: String) : SwarmEvent()
    data class WorkerFailed(val workerId: TaskId, val error: String) : SwarmEvent()
    data class VerifierStarted(val verifierId: TaskId) : SwarmEvent()
    data class VerifierPassed(val verifierId: TaskId) : SwarmEvent()
    data class VerifierBlocked(val verifierId: TaskId, val reason: String) : SwarmEvent()
    data class SynthesizerCompleted(val synthesizerId: TaskId, val output: String) : SwarmEvent()
    data class BlackboardUpdated(val rootId: TaskId, val key: String, val value: Any) : SwarmEvent()
}
```

## Implementation Path

### Phase 1: Current Swarm (SQLite)
- `kanban_swarm.py` → writes to `~/.hermes/kanban.db`
- Graph: root → workers → verifier → synthesizer
- Blackboard: JSON in task_comments

### Phase 2: Cursor Layer
- Add `type` column to tasks: swarm_root, swarm_worker, verifier, synthesizer
- Add `root_id` column for graph traversal
- Add `topology` JSON column

### Phase 3: Unified Cursor
- Single cursor spanning kanban, notion, forge
- Cross-system joins possible

### Phase 4: CCEK Fanout
- Real-time swarm events via FanoutDispatcherKey
- Live dashboard updates

## CLI Usage (Current)

```bash
# Create swarm
hermes kanban swarm "Analyze repo for patterns" \
  --worker default:scan:"scan code" \
  --worker default:analyze:"analyze" \
  --verifier default \
  --synthesizer default

# Post blackboard update
hermes kanban comment <root_id> "[swarm:blackboard] {\"key\": \"findings\", \"value\": {...}}"

# Query swarm status
SELECT * FROM tasks WHERE type = 'swarm_root'
SELECT * FROM tasks WHERE root_id = '<root_id>'
```

## Future: Forge Integration

```kotlin
// Convert swarm to Forge workflow
fun SwarmRoot.toForgeWorkflow(): Workflow {
    return Workflow(
        id = WorkflowId("wf_${id.value}"),
        name = "Swarm: $goal",
        steps = listOf(
            // Parallel workers as parallel branch
            WorkflowStep.Parallel(
                id = "workers",
                branches = workers.map { worker ->
                    listOf(
                        WorkflowStep.AgentInvocation(
                            id = worker.id.value,
                            agentType = AgentType.Generic,
                            task = worker.body,
                        )
                    )
                }
            ),
            // Verifier as gate
            WorkflowStep.Conditional(
                id = "verify",
                condition = "workers.completed",
                thenBranch = listOf(
                    WorkflowStep.AgentInvocation(
                        id = "synthesize",
                        agentType = AgentType.Generic,
                    )
                )
            )
        )
    )
}
```