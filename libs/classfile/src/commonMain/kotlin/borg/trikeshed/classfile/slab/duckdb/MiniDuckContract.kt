package borg.trikeshed.classfile.slab.duckdb

import borg.trikeshed.classfile.slab.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * miniduck: DuckDB execution seams as pure Cursor transforms.
 * No 3rd party deps — direct JNI/FFI to DuckDB C API.
 * Each seam = a Cursor → Cursor projection preserving facet metadata.
 */

// ==================== DUCKDB HANDLE TYPES ====================
inline  class DuckDB(val ptr: Long)
inline  class DuckDBConnection(val ptr: Long)
inline  class DuckDBConfig(val ptr: Long)
inline  class DuckDBResult(val ptr: Long)

// ==================== DUCKDB C API CONSTANTS ====================
object DuckDBApi {
    const val OPEN = 0
    const val OPEN_EXT = 1
    const val CLOSE = 2
    const val CONNECT = 3
    const val DISCONNECT = 4
    const val QUERY = 5
    const val RESULT_ERROR = 6
    const val RESULT_ROW_COUNT = 7
    const val RESULT_COLUMN_COUNT = 8
    const val RESULT_COLUMN_TYPE = 9
    const val RESULT_COLUMN_NAME = 10
    const val FETCH = 11
    const val FETCH_N = 12
    const val CREATE_SCALAR_FUNC = 13
    const val REGISTER_TABLE_FUNC = 14
    const val ADD_REPLACEMENT_SCAN = 15
    const val CHECKPOINT = 16
    const val FORCE_CHECKPOINT = 17
    const val COPY = 18
    const val PREFETCH = 19
    const val CREATE_DATA_CHUNK = 20
    const val DESTROY_DATA_CHUNK = 21
    const val DATA_CHUNK_GET_VECTOR = 22
    const val DATA_CHUNK_SET_SIZE = 23
}

// ==================== PRAGMA / CONFIG KEYS ====================
object DuckDBPragma {
    const val WAL_AUTOCHECKPOINT = "wal_autocheckpoint"
    const val DISABLE_CHECKPOINT_ON_SHUTDOWN = "disable_checkpoint_on_shutdown"
    const val CHECKPOINT_ON_SHUTDOWN = "checkpoint_on_shutdown"
    const val THREADS = "threads"
    const val MAX_MEMORY = "max_memory"
    const val TEMP_DIRECTORY = "temp_directory"
    const val ENABLE_EXTERNAL_ACCESS = "enable_external_access"
    const val FORCE_INDEX_JOIN = "force_index_join"
    const val CUSTOM_OPTIONS = "custom_options"
}

// ==================== DATA CHUNK TYPES (vectorized execution unit) ====================
inline  class DataChunk(val ptr: Long)
inline  class Vector(val ptr: Long)

data class LogicalType(val id: Int, val info: Long)
object DuckDBTypes {
    val INVALID = LogicalType(0, 0)
    val BOOLEAN = LogicalType(1, 0)
    val TINYINT = LogicalType(2, 0)
    val SMALLINT = LogicalType(3, 0)
    val INTEGER = LogicalType(4, 0)
    val BIGINT = LogicalType(5, 0)
    val UTINYINT = LogicalType(6, 0)
    val USMALLINT = LogicalType(7, 0)
    val UINTEGER = LogicalType(8, 0)
    val UBIGINT = LogicalType(9, 0)
    val FLOAT = LogicalType(10, 0)
    val DOUBLE = LogicalType(11, 0)
    val VARCHAR = LogicalType(12, 0)
    val BLOB = LogicalType(13, 0)
    val TIMESTAMP = LogicalType(21, 0)
    val DATE = LogicalType(23, 0)
    val TIME = LogicalType(24, 0)
    val INTERVAL = LogicalType(27, 0)
    val LIST = LogicalType(32, 0)
    val STRUCT = LogicalType(33, 0)
    val MAP = LogicalType(34, 0)
    val UNION = LogicalType(35, 0)
    val DECIMAL = LogicalType(36, 0)
    val JSON = LogicalType(37, 0)
    val INTEGER_ARRAY = LogicalType(100 + 4, 0)
    val VARCHAR_ARRAY = LogicalType(100 + 12, 0)
}

// ==================== CURSOR TRANSFORMS (pure projections) ====================

/** Open/create database → SlabCursor of tables/views */
fun openDatabase(path: String?): DuckDB = TODO("duckdb_open(path)")

/** Open with config (pragma) → DuckDB handle */
fun openDatabaseConfig(path: String?, config: DuckDBConfig): DuckDB = TODO("duckdb_open_ext(path, config)")

/** Close database → cleanup */
fun closeDatabase(db: DuckDB): Unit = TODO("duckdb_close(db)")

/** Connect to database → connection handle */
fun connect(db: DuckDB): DuckDBConnection = TODO("duckdb_connect(db)")

/** Disconnect → release connection */
fun disconnect(conn: DuckDBConnection): Unit = TODO("duckdb_disconnect(conn)")

/** Execute SQL → result cursor */
fun query(conn: DuckDBConnection, sql: String): DuckDBResult = TODO(
    "duckdb_query(conn, sql) → FacetedCursor{rows, columns, facet=PERSISTENT}"
)

/** Execute with auto-checkpoint pragmas → result + WAL facet */
fun queryDurable(conn: DuckDBConnection, sql: String): DuckDBResult = TODO(
    "SET disable_checkpoint_on_shutdown=false; SET wal_autocheckpoint='1GB'; query → result{WAL_BUFFER}"
)

/** Checkpoint (flush WAL to data file) → WAL facet cleared */
fun checkpoint(db: DuckDB): Unit = TODO("CHECKPOINT → facet = facet andNot WAL_BUFFER")

/** Force checkpoint (abort running transactions) → clean flush */
fun forceCheckpoint(db: DuckDB): Unit = TODO("FORCE CHECKPOINT")

/** COPY TO (Parquet/CSV/Arrow) → external Slab with COLUMNAR_EXPORT facet */
fun exportSlab(conn: DuckDBConnection, sql: String, path: String, format: String = "parquet"): SlabExtent = TODO(
    "COPY ($sql) TO '$path' (FORMAT PARQUET) → SlabExtent{offset=0, length=fileSize, facet=COLUMNAR_EXPORT}"
)

/** COPY FROM (Parquet/CSV/Arrow) → in-memory Slab */
fun importSlab(conn: DuckDBConnection, path: String, sqlHint: String? = null): FacetedCursor = TODO(
    "CREATE VIEW tmp AS SELECT * FROM '$path'; query → FacetedCursor{facet=COLUMNAR_EXPORT}"
)

/** Register table function (custom file format) → replacement scan hook */
fun registerTableFunction(conn: DuckDBConnection, name: String, fn: Any): Unit = TODO("duckdb_register_table_function")

/** Register replacement scan (intercept file opens) → custom protocol handler */
fun addReplacementScan(conn: DuckDBConnection, prefix: String, callback: Any): Unit = TODO(
    "duckdb_add_replacement_scan → intercept 'prefix://bucket/slab/*'"
)

/** Create vectorized UDF → apply over DataChunk series */
fun createVectorizedUDF(
    db: DuckDB,
    name: String,
    inputTypes: Series<LogicalType>,
    outputType: LogicalType,
    func: (DataChunk) -> Vector
): Unit = TODO(
    "duckdb_create_vectorized_function → SlabExtent{facet=COMPUTED}"
)

/** Create DataChunk (2048-row vectorized batch) → execution unit */
fun createDataChunk(types: Series<LogicalType>): DataChunk = TODO("duckdb_create_data_chunk(types, count=2048)")

/** Get vector from chunk → 2048-row column data */
fun getVector(chunk: DataChunk, colIndex: Int): Vector = TODO("duckdb_data_chunk_get_vector(chunk, col)")

/** Set chunk size (valid rows) → execution boundary */
fun setChunkSize(chunk: DataChunk, size: Int): Unit = TODO("duckdb_data_chunk_set_size(chunk, size)")

/** Fetch next batch → DataChunk for pipeline */
fun fetchBatch(conn: DuckDBConnection, result: DuckDBResult): DataChunk = TODO("duckdb_fetch_batch(conn, result)")

/** ATTACH database (multi-database) → cross-slab query */
fun attachDatabase(conn: DuckDBConnection, path: String, alias: String): Unit = TODO("ATTACH '$path' AS $alias")

/** BEGIN transaction → isolation boundary */
fun beginTransaction(conn: DuckDBConnection): Unit = TODO("BEGIN (or implicit)")

/** COMMIT → durability boundary (triggers WAL write) */
fun commitTransaction(conn: DuckDBConnection): Unit = TODO("COMMIT")

/** ROLLBACK → undo (not yet committed) */
fun rollbackTransaction(conn: DuckDBConnection): Unit = TODO("ROLLBACK")

/** VACUUM (reclaim deleted row space) → SlabCursor with reclaimed extents */
fun vacuum(db: DuckDB): Series<SlabExtent> = TODO("VACUUM → check dropped row groups")

// ==================== ZONE MAP / STATISTICS PROJECTION ====================

/** Get column min/max for predicate pushdown → zone map for SlabCursor */
fun columnStats(conn: DuckDBConnection, table: String, column: String): Join<Any?, Any?> = TODO(
    "DuckDB statistics → min/max for zone map skip"
)

/** Parquet row group metadata → SlabCursor for tiering */
fun parquetRowGroups(path: String): Series<Join<Long, Join<Long, SlabFacet>>> = TODO(
    "Parquet metadata: offset, length, null_count → zone maps + facet derivation"
)

// ==================== PARALLELISM / MORSEL DISTRIBUTION ====================

/** Get optimal thread count for vectorized scan → parallelism factor */
fun optimalThreads(db: DuckDB): Int = TODO("DuckDB TaskScheduler::NumberOfThreads()")

/** Schedule morsel (work unit) → parallel scan over SlabCursor */
fun scheduleMorsel(start: Long, end: Long, threadId: Int): Unit = TODO(
    "MorselDriven: partition SlabCursor[$start until $end] for thread[$threadId]"
)

// ==================== GRAALPY / GRAALJS INTEROP ====================

/** Evaluate GraalJS expression over SlabCursor → computed facet */
fun graalEval(jsExpr: String, cursor: FacetedCursor): FacetedCursor = TODO(
    "GraalJS eval over cursor[column] → FacetedCursor{rows, columns, facet=COMPUTED}"
)

/** Pointcut: intercept cursor column access via FieldSynapse → GraalJS handler */
fun pointcutColumn(synapse: Any, column: String): Any = TODO(
    "FieldSynapse(L_GET, column) → GraalJS handler returns Series values"
)
