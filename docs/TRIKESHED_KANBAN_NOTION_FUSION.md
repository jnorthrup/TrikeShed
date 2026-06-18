# TrikeShed + Hermes Kanban + Notion Fusion

## Vision

**Unified workspace**: TrikeShed kernel algebra + Hermes task execution + Notion-style databases.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        FUSED WORKSPACE ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                         APPLICATION LAYER                              │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │
│  │  │   Kanban    │  │   Notion    │  │       Forge             │  │  │
│  │  │   Board     │  │   Pages     │  │    Workflows            │  │  │
│  │  │  Columns    │  │  Databases  │  │    Cascades             │  │  │
│  │  │  Cards      │  │  Blocks     │  │    Agents               │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                        CURSOR LAYER                                   │  │
│  │  ┌───────────────────────────────────────────────────────────────┐  │  │
│  │  │  Cursor = Series<RowVec>                                   │  │  │
│  │  │  RowVec = value + RecordMeta                               │  │  │
│  │  │  MetaSeries = codec/filter/selector/domain transducer       │  │  │
│  │  └───────────────────────────────────────────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                      STORAGE LAYER                                   │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │
│  │  │   SQLite    │  │   ISAM      │  │      Confix            │  │  │
│  │  │  (Hermes)   │  │  (Miniduck) │  │    (zstorage)         │  │  │
│  │  │ kanban.db   │  │  data files │  │   typed rows          │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                      CCEK LAYER                                      │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │
│  │  │    NIO       │  │  io_uring   │  │   Fanout               │  │  │
│  │  │  Channels    │  │   Submissions│  │   Dispatcher           │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Unified Data Model

### Core Types

```kotlin
// ============================================================
// UNIFIED IDENTITIES
// ============================================================

/**
 * Unified entity ID - spans all three systems.
 */
inline  class EntityId(val raw: String) {
    companion object {
        // Kanban IDs: t_<hex>
        fun kanban(taskId: String) = EntityId("kanban:$taskId")
        
        // Notion IDs: page_<hex>, db_<hex>, block_<hex>
        fun notion(blockId: String) = EntityId("notion:$blockId")
        
        // Forge IDs: wf_<hex>, cascade_<hex>
        fun forge(workflowId: String) = EntityId("forge:$workflowId")
    }
    
    val system: String get() = raw.split(":").first()
    val id: String get() = raw.substringAfter(":")
}

/**
 * Unified entity - any item in the fused workspace.
 */
sealed class Entity {
    abstract val id: EntityId
    abstract val createdAt: Long
    abstract val updatedAt: Long
    abstract val metadata: Map<String, String>
}

/**
 * Cursor-backed entity.
 */
interface CursorEntity : Entity {
    fun toCursor(): RowVec
    companion object {
        fun fromCursor(row: RowVec): CursorEntity
    }
}
```

### Kanban → Cursor Mapping

```kotlin
// ============================================================
// KANBAN ENTITIES (from Hermes kanban.db)
// ============================================================

/**
 * Task = KanbanCard + Cursor
 */
data class Task(
    val id: TaskId,
    val title: String,
    val body: String,
    val status: TaskStatus,
    val assignee: String?,
    val priority: Int,
    val workspaceKind: WorkspaceKind,
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = mapOf(
        "assignee" to (assignee ?: ""),
        "workspaceKind" to workspaceKind.name,
        "priority" to priority.toString(),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "title" to title,
        "body" to body,
        "status" to status.name,
        "assignee" to (assignee ?: ""),
        "priority" to priority.toString(),
        "workspaceKind" to workspaceKind.name,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
    
    companion object {
        fun fromCursor(row: RowVec): Task = Task(
            id = TaskId(row["id"] ?: error("missing id")),
            title = row["title"] ?: error("missing title"),
            body = row["body"] ?: "",
            status = TaskStatus.valueOf(row["status"] ?: "todo"),
            assignee = row["assignee"]?.takeIf { it.isNotBlank() },
            priority = row["priority"]?.toIntOrNull() ?: 0,
            workspaceKind = WorkspaceKind.valueOf(row["workspaceKind"] ?: "scratch"),
            createdAt = row["createdAt"]?.toLongOrNull() ?: 0,
            updatedAt = row["updatedAt"]?.toLongOrNull() ?: 0,
        )
    }
}

/**
 * Task run history.
 */
data class TaskRun(
    val id: Int,
    val taskId: TaskId,
    val profile: String,
    val status: RunStatus,
    val outcome: RunOutcome?,
    val summary: String?,
    val startedAt: Long,
    val endedAt: Long?,
) : CursorEntity {
    override val id: EntityId = EntityId.kanban("run:$id")
    override val createdAt: Long = startedAt
    override val updatedAt: Long = endedAt ?: startedAt
    override val metadata: Map<String, String> = mapOf(
        "taskId" to taskId.value,
        "profile" to profile,
        "outcome" to (outcome?.name ?: ""),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.toString(),
        "taskId" to taskId.value,
        "profile" to profile,
        "status" to status.name,
        "outcome" to (outcome?.name ?: ""),
        "summary" to (summary ?: ""),
        "startedAt" to startedAt.toString(),
        "endedAt" to (endedAt?.toString() ?: ""),
    )
}

/**
 * Task status enum.
 */
enum class TaskStatus { TODO, READY, RUNNING, DONE, BLOCKED, ARCHIVED }

/**
 * Run status.
 */
enum class RunStatus { RUNNING, DONE, BLOCKED, CRASHED, TIMED_OUT, FAILED, RELEASED }

/**
 * Run outcome.
 */
enum class RunOutcome { COMPLETED, BLOCKED, CRASHED, TIMED_OUT, SPAWN_FAILED, GAVE_UP, RECLAIMED }

/**
 * Workspace kind.
 */
enum class WorkspaceKind { SCRATCH, DIR, WORKTREE }
```

### Notion → Cursor Mapping

```kotlin
// ============================================================
// NOTION ENTITIES (from CursorDrivenNotion)
// ============================================================

/**
 * Notion page.
 */
data class Page(
    val id: PageId,
    val title: String,
    val parentId: PageId?,
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = mapOf(
        "parentId" to (parentId?.value ?: ""),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "title" to title,
        "parentId" to (parentId?.value ?: ""),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
}

/**
 * Notion block (paragraph, heading, database, etc).
 */
data class Block(
    val id: BlockId,
    val pageId: PageId,
    val kind: BlockKind,
    val content: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = properties
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "pageId" to pageId.value,
        "kind" to kind.name,
        "content" to content,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    ) + properties.mapValues { it.value }
}

/**
 * Notion database (table).
 */
data class NotionDatabase(
    val id: DatabaseId,
    val pageId: PageId,
    val name: String,
    val schema: DatabaseSchema,
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = mapOf(
        "pageId" to pageId.value,
        "fieldCount" to schema.fields.size.toString(),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "pageId" to pageId.value,
        "name" to name,
        "fields" to schema.fields.joinToString(",") { it.name },
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
}

/**
 * Database schema.
 */
data class DatabaseSchema(
    val fields: List<DatabaseField>
)

data class DatabaseField(
    val name: String,
    val type: FieldType,
)

enum class BlockKind { PARAGRAPH, HEADING_1, HEADING_2, HEADING_3, DATABASE, DATABASE_ROW, CODE, IMAGE, FILE }
enum class FieldType { TEXT, NUMBER, SELECT, MULTI_SELECT, DATE, CHECKBOX, URL, EMAIL, PHONE, RELATION }
```

### Forge → Cursor Mapping

```kotlin
// ============================================================
// FORGE ENTITIES
// ============================================================

/**
 * Forge workflow.
 */
data class Workflow(
    val id: WorkflowId,
    val name: String,
    val steps: List<WorkflowStep>,
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = mapOf(
        "stepCount" to steps.size.toString(),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "name" to name,
        "steps" to steps.size.toString(),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
}

/**
 * Cascade (map/reduce pipeline).
 */
data class Cascade(
    val id: CascadeId,
    val name: String,
    val stages: List<CascadeStage>,
    val keyHierarchy: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
) : Entity, CursorEntity {
    
    override val metadata: Map<String, String> = mapOf(
        "stageCount" to stages.size.toString(),
        "keyDepth" to keyHierarchy.size.toString(),
    )
    
    fun toCursor(): RowVec = mapOf(
        "id" to id.value,
        "name" to name,
        "stages" to stages.size.toString(),
        "keyHierarchy" to keyHierarchy.joinToString("."),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
}
```

## Unified Cursor Operations

```kotlin
// ============================================================
// UNIFIED CURSOR OPERATIONS
// ============================================================

/**
 * Unified workspace cursor - spans Kanban, Notion, Forge.
 */
class UnifiedCursor {
    
    /**
     * Put entity to cursor.
     */
    suspend fun put(entity: CursorEntity) {
        val row = entity.toCursor()
        // Store to appropriate backend based on entity ID system
    }
    
    /**
     * Get entity by ID.
     */
    suspend fun get(id: EntityId): CursorEntity? {
        return when (id.system) {
            "kanban" -> getTask(id.id)
            "notion" -> getNotionBlock(id.id)
            "forge" -> getWorkflow(id.id)
            else -> null
        }
    }
    
    /**
     * Query by system and filter.
     */
    suspend fun query(
        system: String? = null,
        filter: (RowVec) -> Boolean = { true },
        limit: Int = 100,
    ): List<CursorEntity> {
        // Delegate to appropriate backend
    }
    
    /**
     * Join across systems.
     */
    suspend fun join(
        left: CursorEntity,
        right: CursorEntity,
        on: (RowVec, RowVec) -> Boolean,
    ): Join<CursorEntity, CursorEntity> {
        return left j right
    }
}

/**
 * Unified workspace service.
 */
class UnifiedWorkspace(
    private val cursor: UnifiedCursor,
    private val fanout: FanoutDispatcherElement,
) : ForgeWorkspace by KanbanWorkspace(cursor) {
    
    // ============================================================
    // KANBAN OPERATIONS
    // ============================================================
    
    suspend fun createTask(title: String, body: String, assignee: String?): Task {
        val task = Task(
            id = TaskId("t_${generateId()}"),
            title = title,
            body = body,
            status = TaskStatus.TODO,
            assignee = assignee,
            priority = 0,
            workspaceKind = WorkspaceKind.SCRATCH,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        cursor.put(task)
        fanout.emit(KanbanEvent.CardCreated(task.id, title))
        return task
    }
    
    suspend fun completeTask(taskId: TaskId, summary: String): Task? {
        // Update status, emit event
    }
    
    // ============================================================
    // NOTION OPERATIONS
    // ============================================================
    
    suspend fun createPage(title: String, parentId: PageId?): Page {
        // Create Notion page
    }
    
    suspend fun createDatabase(name: String, schema: DatabaseSchema): NotionDatabase {
        // Create Notion database
    }
    
    // ============================================================
    // FORGE OPERATIONS
    // ============================================================
    
    suspend fun createWorkflow(name: String, steps: List<WorkflowStep>): Workflow {
        // Create Forge workflow
    }
    
    suspend fun executeCascade(cascadeId: CascadeId): Flow<CascadeProgress> {
        // Execute map/reduce cascade
    }
}
```

## Cross-System Queries

```kotlin
// ============================================================
// CROSS-SYSTEM QUERIES
// ============================================================

/**
 * Find Notion pages linked to completed kanban tasks.
 */
suspend fun findCompletedTaskPages(cursor: UnifiedCursor): List<Page> {
    // Join: tasks.status = DONE AND tasks.id in page.metadata.linkedTaskId
}

/**
 * Find workflows that process tasks with certain priority.
 */
suspend fun findHighPriorityWorkflows(cursor: UnifiedCursor, minPriority: Int): List<Workflow> {
    // Query: tasks.priority >= minPriority → cascade inputs
}

/**
 * Get task burndown from run history.
 */
suspend fun getTaskBurndown(cursor: UnifiedCursor, taskId: TaskId): Series<Double> {
    // Query task_runs, compute completion rate over time
}
```

## Event Fanout

```kotlin
// ============================================================
// UNIFIED EVENTS (spans all systems)
// ============================================================

sealed class UnifiedEvent {
    // Kanban events
    data class TaskCreated(val task: Task) : UnifiedEvent()
    data class TaskCompleted(val task: Task, val summary: String) : UnifiedEvent()
    data class TaskBlocked(val task: Task, val reason: String) : UnifiedEvent()
    data class RunStarted(val taskId: TaskId, val profile: String) : UnifiedEvent()
    data class RunCompleted(val taskId: TaskId, val outcome: RunOutcome) : UnifiedEvent()
    
    // Notion events
    data class PageCreated(val page: Page) : UnifiedEvent()
    data class BlockUpdated(val block: Block) : UnifiedEvent()
    data class DatabaseSchemaChanged(val db: NotionDatabase) : UnifiedEvent()
    
    // Forge events
    data class WorkflowCreated(val workflow: Workflow) : UnifiedEvent()
    data class CascadeExecuted(val cascade: Cascade, val result: CascadeExecutionResult) : UnifiedEvent()
}
```

## Implementation Path

### Phase 1: Wire Hermes Kanban → Cursor
- Read `~/.hermes/kanban.db` into Cursor
- Implement `Task`, `TaskRun` as CursorEntities

### Phase 2: Wire Notion → Cursor  
- Connect `CursorDrivenNotion` to unified cursor
- Implement `Page`, `Block`, `NotionDatabase` as CursorEntities

### Phase 3: Wire Forge → Cursor
- Connect `ForgeWorkspace` to unified cursor
- Implement `Workflow`, `Cascade` as CursorEntities

### Phase 4: Unify
- Single `UnifiedCursor` spanning all backends
- Cross-system joins and queries
- Unified event fanout via CCEK