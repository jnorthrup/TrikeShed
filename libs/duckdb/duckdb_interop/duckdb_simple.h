/**
 * Simplified DuckDB header for Kotlin/Native cinterop
 *
 * This header provides a minimal interface that cinterop can parse
 * while delegating to the actual DuckDB C API
 */

// Include standard headers for types
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// Forward declare opaque types
typedef struct duckdb_database_impl duckdb_database;
typedef struct duckdb_connection_impl duckdb_connection;
typedef struct duckdb_prepared_statement_impl duckdb_prepared_statement;
typedef struct duckdb_result_impl duckdb_result;

// State enum
typedef enum {
    DuckDBSuccess = 0,
    DuckDBError = 1
} duckdb_state;

// Type enum
typedef enum {
    DUCKDB_TYPE_INVALID = 0,
    DUCKDB_TYPE_BOOLEAN = 1,
    DUCKDB_TYPE_TINYINT = 2,
    DUCKDB_TYPE_SMALLINT = 3,
    DUCKDB_TYPE_INTEGER = 4,
    DUCKDB_TYPE_BIGINT = 5,
    DUCKDB_TYPE_UTINYINT = 6,
    DUCKDB_TYPE_USMALLINT = 7,
    DUCKDB_TYPE_UINTEGER = 8,
    DUCKDB_TYPE_UBIGINT = 9,
    DUCKDB_TYPE_FLOAT = 10,
    DUCKDB_TYPE_DOUBLE = 11,
    DUCKDB_TYPE_TIMESTAMP = 12,
    DUCKDB_TYPE_DATE = 13,
    DUCKDB_TYPE_TIME = 14,
    DUCKDB_TYPE_INTERVAL = 15,
    DUCKDB_TYPE_HUGEINT = 16,
    DUCKDB_TYPE_VARCHAR = 17,
    DUCKDB_TYPE_BLOB = 18,
    DUCKDB_TYPE_DECIMAL = 19,
    DUCKDB_TYPE_TIMESTAMP_S = 20,
    DUCKDB_TYPE_TIMESTAMP_MS = 21,
    DUCKDB_TYPE_TIMESTAMP_NS = 22,
    DUCKDB_TYPE_ENUM = 23,
    DUCKDB_TYPE_LIST = 24,
    DUCKDB_TYPE_STRUCT = 25,
    DUCKDB_TYPE_MAP = 26,
    DUCKDB_TYPE_UUID = 27,
    DUCKDB_TYPE_UNION = 28,
    DUCKDB_TYPE_BIT = 29,
    DUCKDB_TYPE_TIME_TZ = 30,
    DUCKDB_TYPE_TIMESTAMP_TZ = 31,
    DUCKDB_TYPE_UHUGEINT = 32,
    DUCKDB_TYPE_ARRAY = 33,
    DUCKDB_TYPE_ANY = 34,
    DUCKDB_TYPE_BIGNUM = 35,
    DUCKDB_TYPE_SQLNULL = 36,
    DUCKDB_TYPE_STRING_LITERAL = 37,
    DUCKDB_TYPE_INTEGER_LITERAL = 38,
    DUCKDB_TYPE_TIME_NS = 39
} duckdb_type;

// Types
typedef uint64_t idx_t;

// Database functions
duckdb_state duckdb_open(const char* path, duckdb_database* out_database);
void duckdb_close(duckdb_database* database);

// Connection functions
duckdb_state duckdb_connect(duckdb_database database, duckdb_connection* out_connection);
void duckdb_disconnect(duckdb_connection* connection);

// Prepared statement functions
duckdb_state duckdb_prepare(duckdb_connection connection, const char* query, duckdb_prepared_statement* out_prepared_statement);
void duckdb_destroy_prepare(duckdb_prepared_statement* prepared_statement);
duckdb_state duckdb_execute_prepared(duckdb_prepared_statement prepared_statement, duckdb_result* out_result);

// Result functions
void duckdb_destroy_result(duckdb_result* result);
idx_t duckdb_column_count(duckdb_result* result);
idx_t duckdb_row_count(duckdb_result* result);
idx_t duckdb_rows_changed(duckdb_result* result);
const char* duckdb_column_name(duckdb_result* result, idx_t col);
duckdb_type duckdb_column_type(duckdb_result* result, idx_t col);
void* duckdb_column_data(duckdb_result* result, idx_t col);
bool* duckdb_nullmask_data(duckdb_result* result, idx_t col);
const char* duckdb_result_error(duckdb_result* result);

// Bind functions
duckdb_state duckdb_bind_boolean(duckdb_prepared_statement prepared_statement, idx_t param_idx, bool val);
duckdb_state duckdb_bind_int8(duckdb_prepared_statement prepared_statement, idx_t param_idx, int8_t val);
duckdb_state duckdb_bind_int16(duckdb_prepared_statement prepared_statement, idx_t param_idx, int16_t val);
duckdb_state duckdb_bind_int32(duckdb_prepared_statement prepared_statement, idx_t param_idx, int32_t val);
duckdb_state duckdb_bind_int64(duckdb_prepared_statement prepared_statement, idx_t param_idx, int64_t val);
duckdb_state duckdb_bind_float(duckdb_prepared_statement prepared_statement, idx_t param_idx, float val);
duckdb_state duckdb_bind_double(duckdb_prepared_statement prepared_statement, idx_t param_idx, double val);
duckdb_state duckdb_bind_varchar(duckdb_prepared_statement prepared_statement, idx_t param_idx, const char* val);
duckdb_state duckdb_bind_null(duckdb_prepared_statement prepared_statement, idx_t param_idx);