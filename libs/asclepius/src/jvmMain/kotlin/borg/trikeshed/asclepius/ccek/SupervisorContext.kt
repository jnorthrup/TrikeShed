package borg.trikeshed.asclepius.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.ElementState.ACTIVE
import borg.trikeshed.context.ElementState.CLOSED
import borg.trikeshed.context.ElementState.CREATED
import borg.trikeshed.context.ElementState.DRAINING
import borg.trikeshed.context.ElementState.OPEN
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import kotlin.coroutines.CoroutineContext

/**
 * SupervisorContext is the Asclepius CCEK harness:
 * Coroutine -> Context -> Element -> Key.
 *
 * It owns effect lifecycles, while pointcut and data surfaces stay algebraic:
 * FieldSynapse streams, Cursor/RowVec, Arrow-shaped slabs, and SQLite WAL rows.
 */
abstract class SupervisorContext(
    initialState: ElementState = CREATED,
    parentJob: Job? = null,
) : AsyncContextElement(initialState, parentJob) {
    companion object Key : AsyncContextKey<SupervisorContext>()

    override val key: CoroutineContext.Key<*> get() = Key

    protected val children = mutableListOf<AsyncContextElement>()

    override val fanoutSubscribers: List<AsyncContextElement>
        get() = children

    suspend fun <E : AsyncContextElement> adopt(factory: () -> E): E {
        val element = factory()
        children.add(element)
        element.open()
        return element
    }

    suspend fun release(child: AsyncContextElement) {
        if (children.remove(child)) {
            child.drain()
            child.close()
        }
    }

    fun childrenInState(state: ElementState): List<AsyncContextElement> =
        children.filter { it.lifecycleState == state }

    override suspend fun drain() {
        if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
            state = DRAINING
            for (child in children.asReversed()) child.drain()
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            for (child in children.asReversed()) child.close()
            children.clear()
            supervisor.cancel()
            state = CLOSED
        }
    }

    fun buildContext(): CoroutineContext =
        children.fold<AsyncContextElement, CoroutineContext>(this) { acc, element -> acc + element }
}

/** GraalVM Context/Engine as a CCEK element. */
class GraalIsolateElement(
    private val language: String = "python",
    private val engineConfig: Engine.Builder.() -> Unit = {},
    private val contextConfig: Context.Builder.() -> Unit = {},
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {
    companion object Key : AsyncContextKey<GraalIsolateElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private var engine: Engine? = null
    private var context: Context? = null
    private val isolateJob = SupervisorJob(parentJob)

    override val supervisor: CompletableJob = isolateJob

    val graalContext: Context
        get() = context ?: error("Graal isolate is not open")

    override suspend fun open() {
        if (state != CREATED) return
        super.open()
        val builtEngine = Engine.newBuilder().apply(engineConfig).build()
        engine = builtEngine
        context = Context.newBuilder(language)
            .engine(builtEngine)
            .allowAllAccess(true)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .apply(contextConfig)
            .build()
        state = ACTIVE
    }

    suspend fun eval(source: String): Any? = withContext(supervisor) {
        polyglotToKotlin(graalContext.eval(language, source))
    }

    fun bind(name: String, value: Any) {
        graalContext.getBindings(language).putMember(name, value)
    }

    fun <T> export(name: String, fn: T) {
        graalContext.getBindings(language).putMember(name, fn)
    }

    override suspend fun drain() {
        if (state.isAtLeast(ACTIVE) && state.isLessThan(DRAINING)) {
            state = DRAINING
            isolateJob.cancel()
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            runCatching { context?.close() }
            runCatching { engine?.close() }
            isolateJob.cancel()
            context = null
            engine = null
            state = CLOSED
        }
    }
}

/** SQLite WAL connection as a CCEK element. */
class SqliteElement(
    private val dbPath: String,
    private val walMode: Boolean = true,
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {
    companion object Key : AsyncContextKey<SqliteElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private var connection: Connection? = null

    val sqliteConnection: Connection
        get() = connection ?: error("SQLite is not open")

    override suspend fun open() {
        if (state != CREATED) return
        super.open()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        if (walMode) sqliteConnection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA cache_size=-32768")
            stmt.execute("PRAGMA temp_store=MEMORY")
        }
        state = ACTIVE
    }

    fun query(sql: String): Cursor {
        sqliteConnection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return resultSetToCursor(rs)
            }
        }
    }

    fun execute(sql: String): Int =
        sqliteConnection.createStatement().use { stmt -> stmt.executeUpdate(sql) }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            if (walMode) runCatching {
                sqliteConnection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            }
            runCatching { connection?.close() }
            connection = null
            state = CLOSED
        }
    }
}

/** Arrow off-heap allocator as a CCEK element. */
class ArrowElement(
    private val initialCapacity: Long = 1024L * 1024L,
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {
    companion object Key : AsyncContextKey<ArrowElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private var allocator: org.apache.arrow.memory.BufferAllocator? = null
    private val vectors = mutableListOf<org.apache.arrow.vector.ValueVector>()

    val arrowAllocator: org.apache.arrow.memory.BufferAllocator
        get() = allocator ?: error("Arrow allocator is not open")

    override suspend fun open() {
        if (state != CREATED) return
        super.open()
        allocator = org.apache.arrow.memory.RootAllocator(initialCapacity)
        state = ACTIVE
    }

    fun <V : org.apache.arrow.vector.ValueVector> allocate(
        vectorFactory: (org.apache.arrow.memory.BufferAllocator) -> V,
    ): V {
        val vector = vectorFactory(arrowAllocator)
        vectors.add(vector)
        return vector
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            vectors.forEach { it.close() }
            vectors.clear()
            runCatching { allocator?.close() }
            allocator = null
            state = CLOSED
        }
    }
}

/** Placeholder Foundation IO reactor element; later binds :libs:uring directly. */
class UringReactorElement(
    val ringSize: Int = 4096,
    parentJob: Job? = null,
) : AsyncContextElement(CREATED, parentJob) {
    companion object Key : AsyncContextKey<UringReactorElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    override suspend fun open() {
        if (state != CREATED) return
        super.open()
        state = ACTIVE
    }
}

/** Top-level Asclepius supervisor composing Foundation IO, GraalVM, SQLite, and Arrow. */
class AsclepiusSupervisor(
    private val dbPath: String = ":memory:",
    parentJob: Job? = null,
) : SupervisorContext(CREATED, parentJob) {
    lateinit var uring: UringReactorElement
        private set
    lateinit var graal: GraalIsolateElement
        private set
    lateinit var sqlite: SqliteElement
        private set
    lateinit var arrow: ArrowElement
        private set

    suspend fun initialize() {
        if (state == CREATED) open()
        uring = adopt { UringReactorElement(parentJob = supervisor) }
        arrow = adopt { ArrowElement(parentJob = supervisor) }
        sqlite = adopt { SqliteElement(dbPath, parentJob = supervisor) }
        graal = adopt { GraalIsolateElement(parentJob = supervisor) }
        graal.bind("arrow", arrow.arrowAllocator)
        graal.bind("sqlite", sqlite.sqliteConnection)
        graal.bind("asclepius", this)
        state = ACTIVE
    }

    suspend fun runHermes(script: String): Any? = graal.eval(script)

    override suspend fun close() {
        if (this::graal.isInitialized) graal.close()
        if (this::sqlite.isInitialized) sqlite.close()
        if (this::arrow.isInitialized) arrow.close()
        if (this::uring.isInitialized) uring.close()
        super.close()
    }
}

private fun resultSetToCursor(rs: ResultSet): Cursor {
    val meta = rs.metaData
    val columnCount = meta.columnCount
    val columnNames = Array(columnCount) { i -> meta.getColumnLabel(i + 1) }
    val columnTypes = Array(columnCount) { i -> meta.getColumnType(i + 1) }

    val columnMetaSeries: Series<ColumnMeta> = columnCount j { i: Int ->
        ColumnMeta(columnNames[i], columnTypeToIOMemento(columnTypes[i]))
    }
    val metaSupplierSeries: Series<() -> ColumnMeta> = columnCount j { i: Int ->
        { columnMetaSeries[i] }
    }

    val rows = mutableListOf<RowVec>()
    while (rs.next()) {
        val values = Array<Any?>(columnCount) { i -> rs.getObject(i + 1) }
        val valueSeries: Series<Any?> = values.size j { i: Int -> values[i] }
        rows += ReifiedSplitSeries2(valueSeries, metaSupplierSeries)
    }

    return rows.size j { i: Int -> rows[i] }
}

private fun columnTypeToIOMemento(sqlType: Int): IOMemento = when (sqlType) {
    Types.BOOLEAN, Types.BIT -> IOMemento.IoBoolean
    Types.TINYINT -> IOMemento.IoByte
    Types.SMALLINT -> IOMemento.IoShort
    Types.INTEGER -> IOMemento.IoInt
    Types.BIGINT -> IOMemento.IoLong
    Types.REAL, Types.FLOAT -> IOMemento.IoFloat
    Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> IOMemento.IoDouble
    Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR -> IOMemento.IoString
    Types.DATE -> IOMemento.IoLocalDate
    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> IOMemento.IoInstant
    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> IOMemento.IoByteArray
    else -> IOMemento.IoObject
}

private fun polyglotToKotlin(value: Value?): Any? {
    if (value == null || value.isNull) return null
    return when {
        value.isBoolean -> value.asBoolean()
        value.isString -> value.asString()
        value.isNumber -> runCatching { value.asInt() }
            .recoverCatching { value.asLong() }
            .recoverCatching { value.asDouble() }
            .getOrElse { value.toString() }
        value.isHostObject -> runCatching { value.asHostObject<Any?>() }.getOrElse { value.toString() }
        else -> value.toString()
    }
}
