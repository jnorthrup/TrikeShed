package borg.trikeshed.kanban

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.primitive
import kotlinx.serialization.json.string
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.decodeFromString
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteKanbanStore(
    private val boardDir: Path,
    private val boardId: KanbanBoardId,
) : KanbanStore {

    private val dbPath = boardDir.resolve("kanban.db")
    private val dataSource: DataSource = HikariDataSource(dbPath.toString())
    private val _events = MutableSharedFlow<KanbanEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: SharedFlow<KanbanEvent> = _events.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        boardDir.toFile().mkdirs()
        initSchema()
    }

    private fun initSchema() = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        conn.use {
            it.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT DEFAULT '',
                    column_id TEXT NOT NULL,
                    task_order INTEGER DEFAULT 0,
                    assignee TEXT,
                    priority TEXT DEFAULT 'MEDIUM',
                    dependencies TEXT DEFAULT '[]',
                    tags TEXT DEFAULT '[]',
                    metadata TEXT DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    workspace_kind TEXT DEFAULT 'SCRATCH',
                    workspace_path TEXT,
                    branch_name TEXT,
                    tenant TEXT,
                    skills TEXT DEFAULT '[]',
                    model_override TEXT,
                    max_retries INTEGER,
                    session_id TEXT,
                    workflow_template_id TEXT,
                    current_step_key TEXT
                )
            """.trimIndent())

            it.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    profile TEXT NOT NULL,
                    step_key TEXT,
                    status TEXT DEFAULT 'started',
                    outcome TEXT,
                    summary TEXT,
                    error TEXT,
                    metadata TEXT DEFAULT '{}',
                    worker_pid INTEGER,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    FOREIGN KEY(task_id) REFERENCES tasks(id)
                )
            """.trimIndent())

            it.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    author TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES tasks(id)
                )
            """.trimIndent())

            it.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS columns (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    column_order INTEGER NOT NULL,
                    wip_limit INTEGER
                )
            """.trimIndent())

            // Insert default columns if empty
            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM columns")
            rs.next()
            if (rs.getInt(1) == 0) {
                val defaultColumns = listOf(
                    "triage" to "Triage",
                    "todo" to "To Do",
                    "ready" to "Ready",
                    "running" to "Running",
                    "review" to "Review",
                    "blocked" to "Blocked",
                    "scheduled" to "Scheduled",
                    "done" to "Done",
                    "archived" to "Archived",
                )
                defaultColumns.forEachIndexed { index, (id, name) ->
                    it.prepareStatement("INSERT INTO columns (id, name, column_order) VALUES (?, ?, ?)")
                        .use { stmt ->
                            stmt.setString(1, id)
                            stmt.setString(2, name)
                            stmt.setInt(3, index)
                            stmt.executeUpdate()
                        }
                }
            }
        }
    }

    private suspend fun <T> db(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        conn.use { block(it) }
    }

    override suspend fun create(task: KanbanTask): KanbanTask = db { conn ->
        conn.prepareStatement("""
            INSERT INTO tasks (
                id, title, body, column_id, task_order, assignee, priority,
                dependencies, tags, metadata, created_at, updated_at,
                workspace_kind, workspace_path, branch_name, tenant,
                skills, model_override, max_retries, session_id,
                workflow_template_id, current_step_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, task.id.value)
            stmt.setString(2, task.title)
            stmt.setString(3, task.body)
            stmt.setString(4, task.columnId.value)
            stmt.setInt(5, task.order)
            stmt.setString(6, task.assignee)
            stmt.setString(7, task.priority.name)
            stmt.setString(8, json.encodeToString(task.dependencies.map { it.value }))
            stmt.setString(9, json.encodeToString(task.tags))
            stmt.setString(10, json.encodeToString(task.metadata))
            stmt.setLong(11, task.createdAt)
            stmt.setLong(12, task.updatedAt)
            stmt.setString(13, task.workspaceKind.name)
            stmt.setString(14, task.workspacePath)
            stmt.setString(15, task.branchName)
            stmt.setString(16, task.tenant)
            stmt.setString(17, json.encodeToString(task.skills))
            stmt.setString(18, task.modelOverride)
            stmt.setInt(19, task.maxRetries ?: 0)
            stmt.setString(20, task.sessionId)
            stmt.setString(21, task.workflowTemplateId)
            stmt.setString(22, task.currentStepKey)
            stmt.executeUpdate()
        }
        _events.emit(KanbanEvent.TaskCreated(task))
        task
    }

    override suspend fun get(id: KanbanTaskId): KanbanTask? = db { conn ->
        conn.prepareStatement("SELECT * FROM tasks WHERE id = ?").use { stmt ->
            stmt.setString(1, id.value)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    override suspend fun update(task: KanbanTask): KanbanTask = db { conn ->
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        conn.prepareStatement("""
            UPDATE tasks SET
                title = ?, body = ?, column_id = ?, task_order = ?, assignee = ?,
                priority = ?, dependencies = ?, tags = ?, metadata = ?,
                workspace_kind = ?, workspace_path = ?, branch_name = ?, tenant = ?,
                skills = ?, model_override = ?, max_retries = ?, session_id = ?,
                workflow_template_id = ?, current_step_key = ?, updated_at = ?
            WHERE id = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, updated.title)
            stmt.setString(2, updated.body)
            stmt.setString(3, updated.columnId.value)
            stmt.setInt(4, updated.order)
            stmt.setString(5, updated.assignee)
            stmt.setString(6, updated.priority.name)
            stmt.setString(7, json.encodeToString(updated.dependencies.map { it.value }))
            stmt.setString(8, json.encodeToString(updated.tags))
            stmt.setString(9, json.encodeToString(updated.metadata))
            stmt.setString(10, updated.workspaceKind.name)
            stmt.setString(11, updated.workspacePath)
            stmt.setString(12, updated.branchName)
            stmt.setString(13, updated.tenant)
            stmt.setString(14, json.encodeToString(updated.skills))
            stmt.setString(15, updated.modelOverride)
            stmt.setInt(16, updated.maxRetries ?: 0)
            stmt.setString(17, updated.sessionId)
            stmt.setString(18, updated.workflowTemplateId)
            stmt.setString(19, updated.currentStepKey)
            stmt.setLong(20, updated.updatedAt)
            stmt.setString(21, updated.id.value)
            stmt.executeUpdate()
        }
        updated
    }

    override suspend fun delete(id: KanbanTaskId): Boolean = db { conn ->
        val affected = conn.prepareStatement("DELETE FROM tasks WHERE id = ?").use { stmt ->
            stmt.setString(1, id.value)
            stmt.executeUpdate()
        }
        if (affected > 0) _events.emit(KanbanEvent.TaskDeleted(id))
        affected > 0
    }

    override suspend fun list(columnId: KanbanColumnId?, assignee: String?): List<KanbanTask> = db { conn ->
        val sql = buildString {
            append("SELECT * FROM tasks WHERE 1=1")
            columnId?.let { append(" AND column_id = '${it.value}'") }
            assignee?.let { append(" AND assignee = '$it'") }
            append(" ORDER BY task_order, created_at")
        }
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<KanbanTask>()
                while (rs.next()) list.add(mapRow(rs))
                list
            }
        }
    }

    override suspend fun search(query: String): List<KanbanTask> = db { conn ->
        val sql = "SELECT * FROM tasks WHERE title LIKE ? OR body LIKE ? ORDER BY updated_at DESC"
        val like = "%$query%"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, like)
            stmt.setString(2, like)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<KanbanTask>()
                while (rs.next()) list.add(mapRow(rs))
                list
            }
        }
    }

    override suspend fun createRun(run: KanbanRun): KanbanRun = db { conn ->
        conn.prepareStatement("""
            INSERT INTO runs (task_id, profile, step_key, status, outcome, summary, error, metadata, worker_pid, started_at, ended_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, run.taskId.value)
            stmt.setString(2, run.profile)
            stmt.setString(3, run.stepKey)
            stmt.setString(4, run.status)
            stmt.setString(5, run.outcome)
            stmt.setString(6, run.summary)
            stmt.setString(7, run.error)
            stmt.setString(8, json.encodeToString(run.metadata))
            stmt.setInt(9, run.workerPid ?: 0)
            stmt.setLong(10, run.startedAt)
            stmt.setLong(11, run.endedAt ?: 0)
            stmt.executeUpdate()
        }
        _events.emit(KanbanEvent.RunStarted(run))
        run
    }

    override suspend fun getRun(id: KanbanRunId): KanbanRun? = db { conn ->
        conn.prepareStatement("SELECT * FROM runs WHERE id = ?").use { stmt ->
            stmt.setInt(1, id.value)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRun(rs) else null
            }
        }
    }

    override suspend fun updateRun(run: KanbanRun): KanbanRun = db { conn ->
        conn.prepareStatement("""
            UPDATE runs SET status = ?, outcome = ?, summary = ?, error = ?, metadata = ?, ended_at = ?
            WHERE id = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, run.status)
            stmt.setString(2, run.outcome)
            stmt.setString(3, run.summary)
            stmt.setString(4, run.error)
            stmt.setString(5, json.encodeToString(run.metadata))
            stmt.setLong(6, run.endedAt ?: 0)
            stmt.setInt(7, run.id.value)
            stmt.executeUpdate()
        }
        if (run.status == "completed" || run.outcome != null) {
            _events.emit(KanbanEvent.RunCompleted(run))
        }
        run
    }

    override suspend fun listRuns(taskId: KanbanTaskId): List<KanbanRun> = db { conn ->
        conn.prepareStatement("SELECT * FROM runs WHERE task_id = ? ORDER BY started_at DESC").use { stmt ->
            stmt.setString(1, taskId.value)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<KanbanRun>()
                while (rs.next()) list.add(mapRun(rs))
                list
            }
        }
    }

    override suspend fun addComment(comment: KanbanComment): KanbanComment = db { conn ->
        conn.prepareStatement("""
            INSERT INTO comments (task_id, author, body, created_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, comment.taskId.value)
            stmt.setString(2, comment.author)
            stmt.setString(3, comment.body)
            stmt.setLong(4, comment.createdAt)
            stmt.executeUpdate()
        }
        _events.emit(KanbanEvent.CommentAdded(comment))
        comment
    }

    override suspend fun listComments(taskId: KanbanTaskId): List<KanbanComment> = db { conn ->
        conn.prepareStatement("SELECT * FROM comments WHERE task_id = ? ORDER BY created_at").use { stmt ->
            stmt.setString(1, taskId.value)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<KanbanComment>()
                while (rs.next()) {
                    list.add(KanbanComment(
                        id = KanbanCommentId(rs.getInt("id")),
                        taskId = KanbanTaskId(rs.getString("task_id")),
                        author = rs.getString("author"),
                        body = rs.getString("body"),
                        createdAt = rs.getLong("created_at"),
                    ))
                }
                list
            }
        }
    }

    override suspend fun addDependency(blocker: KanbanTaskId, blocked: KanbanTaskId) = db { conn ->
        conn.prepareStatement("""
            UPDATE tasks SET dependencies = json_insert(dependencies, '$[#]', ?)
            WHERE id = ? AND json_type(dependencies) = 'array' AND json_array_length(dependencies) >= 0
        """.trimIndent()).use { stmt ->
            stmt.setString(1, blocker.value)
            stmt.setString(2, blocked.value)
            stmt.executeUpdate()
        }
        // Also update in-memory by re-fetching and emitting event
        get(blocked).let { task ->
            if (task != null) {
                val deps = json.decodeFromString<List<String>>(task.dependencies.map { it.value }.toString())
                    .toMutableSet()
                deps.add(blocker.value)
                val updated = task.copy(dependencies = deps.map(::KanbanTaskId).toList())
                update(updated)
                _events.emit(KanbanEvent.DependencyAdded(blocker, blocked))
            }
        }
    }

    override suspend fun removeDependency(blocker: KanbanTaskId, blocked: KanbanTaskId) = db { conn ->
        // Simplified: re-fetch and update
        get(blocked).let { task ->
            if (task != null) {
                val deps = task.dependencies.filter { it != blocker }
                val updated = task.copy(dependencies = deps)
                update(updated)
                _events.emit(KanbanEvent.DependencyRemoved(blocker, blocked))
            }
        }
    }

    override suspend fun getDependencies(taskId: KanbanTaskId): List<KanbanTaskId> = db { conn ->
        get(taskId)?.dependencies ?: emptyList()
    }

    override suspend fun getDependents(taskId: KanbanTaskId): List<KanbanTaskId> = db { conn ->
        conn.prepareStatement("SELECT id, dependencies FROM tasks").use { stmt ->
            stmt.executeQuery().use { rs ->
                val dependents = mutableListOf<KanbanTaskId>()
                while (rs.next()) {
                    val id = rs.getString("id")
                    val depsJson = rs.getString("dependencies")
                    if (depsJson.contains(taskId.value)) {
                        dependents.add(KanbanTaskId(id))
                    }
                }
                dependents
            }
        }
    }

    override suspend fun getColumns(): List<KanbanColumn> = db { conn ->
        conn.prepareStatement("SELECT * FROM columns ORDER BY column_order").use { stmt ->
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<KanbanColumn>()
                while (rs.next()) {
                    list.add(KanbanColumn(
                        id = KanbanColumnId(rs.getString("id")),
                        name = rs.getString("name"),
                        order = rs.getInt("column_order"),
                        wipLimit = rs.getInt("wip_limit").takeIf { it > 0 },
                    ))
                }
                list
            }
        }
    }

    override suspend fun addColumn(column: KanbanColumn): KanbanColumn = db { conn ->
        conn.prepareStatement("INSERT INTO columns (id, name, column_order, wip_limit) VALUES (?, ?, ?, ?)").use { stmt ->
            stmt.setString(1, column.id.value)
            stmt.setString(2, column.name)
            stmt.setInt(3, column.order)
            stmt.setInt(4, column.wipLimit ?: -1)
            stmt.executeUpdate()
        }
        column
    }

    override suspend fun getBoard(boardId: KanbanBoardId = KanbanBoardId("tshed")): KanbanBoard = db { conn ->
        val tasks = list(null, null)
        val columns = getColumns()
        KanbanBoard(
            id = boardId,
            name = "Kanban ($boardId)",
            columns = columns,
            cards = tasks,
        )
    }

    override fun close() {
        // DataSource has no close in this simple impl
    }

    private fun mapRow(rs: ResultSet): KanbanTask {
        val deps = json.decodeFromString<List<String>>(rs.getString("dependencies"))
        val tags = json.decodeFromString<Set<String>>(rs.getString("tags"))
        val metadata = json.decodeFromString<Map<String, String>>(rs.getString("metadata"))
        val skills = json.decodeFromString<List<String>>(rs.getString("skills"))
        return KanbanTask(
            id = KanbanTaskId(rs.getString("id")),
            title = rs.getString("title"),
            body = rs.getString("body") ?: "",
            columnId = KanbanColumnId(rs.getString("column_id")),
            order = rs.getInt("task_order"),
            assignee = rs.getString("assignee"),
            priority = TaskPriority.valueOf(rs.getString("priority") ?: "MEDIUM"),
            dependencies = deps.map(::KanbanTaskId),
            tags = tags,
            metadata = metadata,
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            workspaceKind = WorkspaceKind.valueOf(rs.getString("workspace_kind") ?: "SCRATCH"),
            workspacePath = rs.getString("workspace_path"),
            branchName = rs.getString("branch_name"),
            tenant = rs.getString("tenant"),
            skills = skills,
            modelOverride = rs.getString("model_override"),
            maxRetries = rs.getInt("max_retries").takeIf { it > 0 },
            sessionId = rs.getString("session_id"),
            workflowTemplateId = rs.getString("workflow_template_id"),
            currentStepKey = rs.getString("current_step_key"),
        )
    }

    private fun mapRun(rs: ResultSet): KanbanRun {
        val metadata = json.decodeFromString<Map<String, String>>(rs.getString("metadata"))
        return KanbanRun(
            id = KanbanRunId(rs.getInt("id")),
            taskId = KanbanTaskId(rs.getString("task_id")),
            profile = rs.getString("profile"),
            stepKey = rs.getString("step_key"),
            status = rs.getString("status") ?: "started",
            outcome = rs.getString("outcome"),
            summary = rs.getString("summary"),
            error = rs.getString("error"),
            metadata = metadata,
            workerPid = rs.getInt("worker_pid").takeIf { it > 0 },
            startedAt = rs.getLong("started_at"),
            endedAt = rs.getLong("ended_at").takeIf { it > 0 },
        )
    }
}

// Simple HikariCP DataSource wrapper
class HikariDataSource(private val dbUrl: String) : DataSource {
    private val ds = com.zaxxer.hikari.HikariDataSource().apply {
        jdbcUrl = "jdbc:sqlite:$dbUrl"
        maximumPoolSize = 1
    }
    override fun getConnection(): Connection = ds.connection
    override fun getConnection(username: String?, password: String?): Connection = getConnection()
    override fun getParentLogger() = java.util.logging.Logger.getLogger("kanban")
    override fun <T> unwrap(iface: Class<T>): T = ds.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = ds.isWrapperFor(iface)
}