package borg.trikeshed.asclepius.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.ElementState.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * SupervisorContext — CCEK (Coroutine → Context → Element → Key) harness
 * for managing GraalVM isolate lifecycle and TrikeShed Foundation IO.
 * 
 * The SupervisorContext IS a CoroutineContext.Element that holds a tree of
 * AsyncContextElements. Each element follows the forward-only lifecycle FSM:
 * CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * 
 * This harness bridges:
 * - TrikeShed's NioSupervisor/UringReactor (io_uring + eBPF)
 * - GraalVM Context/Engine isolates
 * - SQLite WAL connections
 * - Arrow vector allocations (off-heap)
 * 
 * Fanout semantics: SupervisorContext can fanout work to N downstream
 * isolates via SupervisorJob, with structured cancellation.
 */
abstract class SupervisorContext(
    initialState: ElementState = CREATED,
    parentJob: Job? = null,
) : AsyncContextElement(initialState, parentJob) {

    /** All child elements managed by this supervisor. */
    protected val children = mutableListOf<AsyncContextElement>()

    /** Add a child element and transition it to OPEN. */
    suspend fun <E : AsyncContextElement> adopt(factory: () -> E): E {
        val el = factory()
        children.add(el)
        el.open()
        return el
    }

    /** Remove a child and transition it to CLOSED. */
    suspend fun release(child: AsyncContextElement) {
        children.remove(child)
        child.drain()
        child.close()
    }

    /** Get all children in a given state. */
    fun childrenInState(state: ElementState): List<AsyncContextElement> =
        children.filter { it.lifecycleState == state }

    /** Force drain all children. */
    override suspend fun drain() {
        if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
            state = DRAINING
            // Drain all children in parallel via supervisor
            supervisor.children?.forEach { it.cancel() }
            children.forEach { it.drain() }
            close()
        }
    }

    /** Force close all children. */
    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            children.forEach { it.close() }
            supervisor.cancel()
            state = CLOSED
        }
    }

    /** Build the combined CoroutineContext of all children. */
    fun buildContext(): CoroutineContext =
        children.fold<AsyncContextElement, CoroutineContext>(coroutineContext) { acc, el -> acc + el }
}

/**
 * GraalIsolateElement — GraalVM Context/Engine as a CCEK Element.
 * 
 * Lifecycle maps 1:1 to ElementState FSM:
 * - CREATED: Engine/Context not yet created
 * - OPEN: Engine built, Context created, not yet entered
 * - ACTIVE: Context entered, executing polyglot code
 * - DRAINING: Stopping new work, completing in-flight
 * - CLOSED: Context closed, Engine closed
 * 
 * The isolate owns its own SupervisorJob for structured concurrency
 * within the isolate's coroutine scope.
 */
class GraalIsolateElement(
    private val language: String = "python",
    private val engineConfig: org.graalvm.polyglot.Engine.Builder.() -> Unit = {},
    private val contextConfig: org.graalvm.polyglot.Context.Builder.() -> Unit = {},
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {

    private var engine: org.graalvm.polyglot.Engine? = null
    private var context: org.graalvm.polyglot.Context? = null
    private val isolateJob = SupervisorJob(parentJob)

    override val supervisor: CompletableJob = isolateJob

    /** Get the GraalVM context (only valid in OPEN..CLOSED states). */
    val graalContext: org.graalvm.polyglot.Context
        get() = context ?: throw IllegalStateException("Isolate not open")

    override suspend fun open() {
        super.open()
        engine = org.graalvm.polyglot.Engine.newBuilder().apply(engineConfig).build()
        context = engine!!.newContext().apply(contextConfig)
        state = ACTIVE
    }

    /** Execute code in this isolate's context. */
    suspend fun eval(source: String): Any? =
        withContext(supervisor) { context!!.eval(language, source)?.asHostObject() }

    /** Bind a host object into this isolate's global scope. */
    fun bind(name: String, value: Any) {
        context?.getBindings(language)?.putMember(name, value)
    }

    /** Export a Kotlin function to the polyglot language. */
    fun <T> export(name: String, fn: T) {
        context?.getBindings(language)?.putMember(name, fn)
    }

    override suspend fun drain() {
        if (state.isAtLeast(ACTIVE) && state.isLessThan(DRAINING)) {
            state = DRAINING
            // Cancel any in-flight evaluations
            isolateJob.cancel()
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            context?.close()
            engine?.close()
            isolateJob.cancel()
            state = CLOSED
        }
    }
}

/**
 * SqliteElement — SQLite WAL connection as a CCEK Element.
 * 
 * Provides embedded analytical DBMS with Arrow vector integration.
 * The connection is opened in OPEN state, used in ACTIVE, and closed
 * in CLOSED with WAL checkpoint.
 */
class SqliteElement(
    private val dbPath: String,
    private val walMode: Boolean = true,
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {

    private var connection: java.sql.Connection? = null

    override suspend fun open() {
        super.open()
        connection = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        if (walMode) {
            connection!!.createStatement().execute("PRAGMA journal_mode=WAL")
            connection!!.createStatement().execute("PRAGMA synchronous=NORMAL")
            connection!!.createStatement().execute("PRAGMA cache_size=-32768") // 32MB
            connection!!.createStatement().execute("PRAGMA temp_store=MEMORY")
        }
        state = ACTIVE
    }

    /** Get the SQLite connection (only valid in OPEN..CLOSED states). */
    val sqliteConnection: java.sql.Connection
        get() = connection ?: throw IllegalStateException("SQLite not open")

    /** Execute a query and return result as Cursor (Series<RowVec>). */
    fun query(sql: String): borg.trikeshed.cursor.Cursor =
        withContext(supervisor) {
            val stmt = connection!!.createStatement()
            val rs = stmt.executeQuery(sql)
            ResultSetToCursor(rs)
        }

    /** Execute an update. */
    fun execute(sql: String): Int =
        withContext(supervisor) {
            connection!!.createStatement().executeUpdate(sql)
        }

    /** Checkpoint WAL and close. */
    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            if (walMode) {
                try { connection?.createStatement()?.execute("PRAGMA wal_checkpoint(TRUNCATE)") } catch (_: Exception) {}
            }
            connection?.close()
            state = CLOSED
        }
    }
}

/**
 * ArrowElement — Arrow vector allocator as a CCEK Element.
 * 
 * Manages off-heap Arrow memory pools for analytical workloads.
 * Vectors are allocated from Netty-managed buffers for zero-copy
 * interchange with SQLite and GraalVM.
 */
class ArrowElement(
    private val initialCapacity: Int = 1024 * 1024, // 1MB default
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {

    private var allocator: org.apache.arrow.memory.BufferAllocator? = null
    private val vectors = mutableListOf<org.apache.arrow.vector.ValueVector>()

    override suspend fun open() {
        super.open()
        allocator = org.apache.arrow.memory.RootAllocator(initialCapacity.toLong())
        state = ACTIVE
    }

    val arrowAllocator: org.apache.arrow.memory.BufferAllocator
        get() = allocator ?: throw IllegalStateException("Arrow not open")

    /** Allocate a new Arrow vector. */
    fun <V : org.apache.arrow.vector.ValueVector> allocate(vectorFactory: (org.apache.arrow.memory.BufferAllocator) -> V): V {
        val vector = vectorFactory(allocator!!)
        vectors.add(vector)
        return vector
    }

    /** Release all vectors and close allocator. */
    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            vectors.forEach { it.close() }
            vectors.clear()
            allocator?.close()
            allocator = null
            state = CLOSED
        }
    }
}

/**
 * UringReactorElement — io_uring + eBPF reactor as a CCEK Element.
 * 
 * Provides the Foundation IO layer: async file/socket IO via io_uring,
 * with eBPF programs attached for packet filtering and XDP.
 */
class UringReactorElement(
    private val ringSize: Int = 4096,
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {

    // Placeholder for actual io_uring integration
    // Would integrate with :libs:uring
    private var reactorHandle: Any? = null

    override suspend fun open() {
        super.open()
        // reactorHandle = UringReactor.open(ringSize)
        state = ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            // UringReactor.close(reactorHandle)
            state = CLOSED
        }
    }
}

/**
 * AsclepiusSupervisor — Top-level supervisor composing all Foundation IO elements.
 * 
 * Typical composition:
 *   UringReactorElement (Foundation IO)
 *   GraalIsolateElement (Hermes Python/JS)
 *   SqliteElement (Analytical DBMS)
 *   ArrowElement (Off-heap vectors)
 * 
 * All elements share a SupervisorJob for structured cancellation.
 */
class AsclepiusSupervisor(
    private val dbPath: String = ":memory:",
    parentJob: Job? = null,
) : SupervisorContext(CREATED, parentJob) {

    val uring: UringReactorElement by lazy { adopt { UringReactorElement(parentJob = supervisor) } }
    val graal: GraalIsolateElement by lazy { adopt { GraalIsolateElement(parentJob = supervisor) } }
    val sqlite: SqliteElement by lazy { adopt { SqliteElement(dbPath, parentJob = supervisor) } }
    val arrow: ArrowElement by lazy { adopt { ArrowElement(parentJob = supervisor) } }

    /** Initialize all elements in dependency order. */
    suspend fun initialize() {
        uring.open()
        arrow.open()
        sqlite.open()
        graal.open()
        // Bind Arrow allocator and SQLite into Graal context
        graal.bind("arrow", arrow.arrowAllocator)
        graal.bind("sqlite", sqlite.sqliteConnection)
        graal.bind("asclepius", this)
    }

    /** Execute a Hermes script with full Foundation IO available. */
    suspend fun runHermes(script: String): Any? = graal.eval(script)

    /** Shutdown all elements in reverse dependency order. */
    override suspend fun close() {
        graal.close()
        sqlite.close()
        arrow.close()
        uring.close()
        super.close()
    }
}

/**
 * Convert JDBC ResultSet to TrikeShed Cursor (Series<RowVec>).
 * RowVec = Series2<Any?, () -> ColumnMeta> — Arrow/Feather isomorphic.
 */
private fun ResultSetToCursor(rs: java.sql.ResultSet): borg.trikeshed.cursor.Cursor {
    val meta = rs.metaData
    val columnCount = meta.columnCount
    val columnNames = Array(columnCount) { i -> meta.getColumnLabel(i + 1) }
    val columnTypes = Array(columnCount) { i -> meta.getColumnType(i + 1) }

    // Build ColumnMeta series (row 0 = exemplar)
    val columnMetaSeries = (columnCount) j { i ->
        val name = columnNames[i]
        val type = columnTypeToIOMemento(columnTypes[i])
        borg.trikeshed.cursor.ColumnMeta(name, type)
    }

    // Read all rows into arrays
    val rows = mutableListOf<borg.trikeshed.cursor.RowVec>()
    while (rs.next()) {
        val values = Array(columnCount) { i -> rs.getObject(i + 1) }
        val rowVec = borg.trikeshed.cursor.ReifiedSplitSeries2(
            leftSeries = values.size j { i -> values[i] },
            rightSeries = columnMetaSeries.right
        ) as borg.trikeshed.cursor.RowVec
        rows.add(rowVec)
    }

    return rows.size j { i -> rows[i] }
}

private fun columnTypeToIOMemento(sqlType: Int): borg.trikeshed.isam.meta.IOMemento {
    return when (sqlType) {
        java.sql.Types.BOOLEAN -> borg.trikeshed.isam.meta.IOMemento.IoBoolean
        java.sql.Types.TINYINT -> borg.trikeshed.isam.meta.IOMemento.IoByte
        java.sql.Types.SMALLINT -> borg.trikeshed.isam.meta.IOMemento.IoShort
        java.sql.Types.INTEGER -> borg.trikeshed.isam.meta.IOMemento.IoInt
        java.sql.Types.BIGINT -> borg.trikeshed.isam.meta.IOMemento.IoLong
        java.sql.Types.REAL, java.sql.Types.FLOAT -> borg.trikeshed.isam.meta.IOMemento.IoFloat
        java.sql.Types.DOUBLE -> borg.trikeshed.isam.meta.IOMemento.IoDouble
        java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.LONGVARCHAR -> borg.trikeshed.isam.meta.IOMemento.IoString
        java.sql.Types.DATE -> borg.trikeshed.isam.meta.IOMemento.IoLocalDate
        java.sql.Types.TIMESTAMP -> borg.trikeshed.isam.meta.IOMemento.IoInstant
        java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY -> borg.trikeshed.isam.meta.IOMemento.IoBytes
        else -> borg.trikeshed.isam.meta.IOMemento.IoObject
    }
}