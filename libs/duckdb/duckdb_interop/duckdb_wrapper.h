/**
 * DuckDB C API Wrapper for Kotlin/Native
 *
 * This wrapper re-exports the actual DuckDB C API
 * without redefining types that are already in duckdb.h
 */

#include <stdint.h>
#include <stdbool.h>

// Include the actual DuckDB header
#include <duckdb.h>

#ifdef __cplusplus
extern "C" {
#endif

// Nothing to define - duckdb.h has everything
// We just need to make sure cinterop can see it

#ifdef __cplusplus
}
#endif