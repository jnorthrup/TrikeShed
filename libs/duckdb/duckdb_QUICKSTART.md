# Quick Start Guide - TrikeShed DuckDB POSIX Bridge Integration

## What Is This?

A **native, immutable, cursor-first** DuckDB driver for Kotlin/Native that treats the cursor as the natural wrapper for database results. Designed specifically for TrikeShed's Series abstraction.

**Core Principle:** The cursor IS the wrapper. Not a factory method, not a result object - the cursor itself is your immutable view into DuckDB results.

---

## 30-Second Overview

```kotlin
// Create connection
DuckConnection.memory().use { conn ->
    // Query returns cursor (the wrapper)
    conn.query("SELECT * FROM data").use { cursor ->
        // Get Series directly
        val series = cursor.getSeries(0)
        val map = cursor.toSeriesMap()
    }
}
```

---

## Quick Integration (5 Steps)

### Step 1: Copy Files

```bash
# From freqtrade repo
cd /Users/jim/work/freqtrade/conductor/tracks/trikeshed-duckdb-posix_20260226/

# To trikeshed repo
cp duckdb_cursors.def /path/to/trikeshed/src/posixMain/cinterop/duckdb.def
cp DuckCursor.kt /path/to/trikeshed/src/posixMain/kotlin/org/trikeshed/duckdb/
cp DuckCursorTest.kt /path/to/trikeshed/src/posixMain/kotlin/org/trikeshed/duckdb/
```

### Step 2: Build Configuration

Add to `/path/to/trikeshed/build.gradle.kts`:

```kotlin
nativeTarget.compilations.main.cinterops.create("duckdb") {
    defFile(projectDir.resolve("src/posixMain/cinterop/duckdb.def"))
    includeDirs.headerSearchPaths.addAll("/opt/homebrew/include")
    linkerOpts.addAll("-L/opt/homebrew/lib", "-lduckdb")
}
```

### Step 3: Run Tests

```bash
cd /path/to/trikeshed
./gradlew nativeTest
```

Expected: 50+ tests pass ✅

### Step 4: Use in Code

```kotlin
import org.trikeshed.duckdb.DuckConnection

// Replace pandas code like this:
val indicators = conn.queryIndicators("BTC/USD")
val close = indicators["close"]!!      // Series<Double>
val sma20 = indicators["sma20"]!!      // Series<Double>
val vol20 = indicators["vol20"]!!      // Series<Double>

// Use TrikeShed operations
val returns = close.zip(close.prev(1)) { curr, prev ->
    prev?.let { (curr - it) / it } ?: 0.0
}
```

### Step 5: Validate

```kotlin
// Test that results match expectations
val ohlcv = conn.queryOHLCV("BTC/USD")
assert(ohlcv.size == 6)  // timestamp, open, high, low, close, volume
assert(ohlcv["close"]!!.size() > 0)
```

---

## API Reference

### DuckConnection
```kotlin
// Creation
DuckConnection.memory()  // In-memory database
DuckConnection.open(path)  // File database

// Query operations
conn.query(sql): DuckCursor
conn.querySeries(sql): Map<String, Series<Any?>>

// Trading helpers
conn.queryOHLCV(symbol): Map<String, Series<Any?>>
conn.queryIndicators(symbol): Map<String, Series<Any?>>

// Execute without result
conn.execute(sql): Unit
```

### DuckCursor
```kotlin
// Data access
cursor.getSeries(index): Series<Any?>
cursor.toSeriesMap(): Map<String, Series<Any?>>

// Metadata
cursor.rowCount: Int
cursor.columnCount: Int
cursor.getColumnName(index): String
cursor.getColumnType(index): DuckDBType

// Iteration
for (row in cursor) { /* self-iterating */ }

// Resource management
cursor.close(): Unit
cursor.use { /* auto-close */ }
```

---

## Common Patterns

### Pattern 1: OHLCV Data
```kotlin
val data = conn.queryOHLCV("BTC/USD")
val close = data["close"]!!  // Series<Double>

// Compute returns
val returns = close.zip(close.prev(1)) { curr, prev ->
    prev?.let { (curr - it) / it } ?: 0.0
}
```

### Pattern 2: Moving Averages
```kotlin
val indicators = conn.queryIndicators("BTC/USD")
val sma20 = indicators["sma20"]!!
val rsi = computeRSI(close, 14)  // Custom indicator
```

### Pattern 3: Data Filtering
```kotlin
val data = conn.query("SELECT * FROM candles").use { cursor ->
    cursor.toSeriesMap()
}

val close = data["close"]!!
val volume = data["volume"]!!

// Filter by volume
val highVolume = close[volume.map { it > 1000 }]
```

### Pattern 4: Window Functions
```kotlin
val data = conn.query("""
    SELECT
        close,
        AVG(close) OVER (ORDER BY timestamp ROWS 20 PRECEDING) as sma20,
        STDDEV(close) OVER (ORDER BY timestamp ROWS 20 PRECEDING) as vol20
    FROM candles
    ORDER BY timestamp
""".trimIndent()).use { cursor ->
    cursor.toSeriesMap()
}
```

---

## Type System

| DuckDB Type | Kotlin Type | Example |
|-------------|-------------|---------|
| DOUBLE | `Double` | 10.5 |
| INTEGER | `Int` | 42 |
| BIGINT | `Long` | 1000000L |
| VARCHAR | `String?` | "BTC/USD" |
| BOOLEAN | `Boolean` | true |

All 28 DuckDBType enum values supported.

---

## Troubleshooting

### "DuckDB not installed"
```bash
brew install duckdb
```

### "C header not found"
Check: `/opt/homebrew/include/duckdb.h`

### "Library not found"
Check: `/opt/homebrew/lib/libduckdb.dylib`

### "Tests fail"
Run: `./gradlew clean nativeTest`

---

## Key Differences from JDBC Bridge

| Aspect | JDBC (Old) | Native (New) |
|--------|------------|--------------|
| **Wrapper** | DuckSeries.query() | DuckCursor |
| **Immutability** | Mutable state | Immutable data classes |
| **Type safety** | Runtime checks | DuckDBType enum |
| **Zero-copy** | No | Ready |
| **Resource mgmt** | Manual | AutoCloseable |
| **Series access** | Extra layer | Direct (getSeries) |

---

## Performance Tips

1. **Use toSeriesMap()** for multi-column data
2. **Access columns once** instead of multiple getSeries() calls
3. **Close cursors** with .use {} blocks
4. **Zero-copy** - Future optimization available via duckdb_vector_get_data()

---

## Next Steps After Integration

1. ✅ All tests pass
2. ✅ Replace pandas InstrumentPanel
3. Validate OHLCV results match
4. Benchmark performance
5. Consider zero-copy optimization for large datasets

---

## Documentation

- **README.md** - Full API reference and integration guide
- **SPECIFICATION.md** - Architecture and design decisions
- **USAGE_EXAMPLES.md** - 13 working code examples
- **FINAL_STATUS.md** - Completion summary
- **plan.md** - Execution plan and tracking

---

## Summary

**The cursor IS the wrapper.**

This implementation provides:
- ✅ Immutable cursor architecture
- ✅ Type-safe access (DuckDBType)
- ✅ Direct TrikeShed Series integration
- ✅ Zero-copy ready
- ✅ Resource-safe (RAII)
- ✅ 50+ comprehensive tests
- ✅ Complete documentation

**Ready for production integration into trikeshed repository.**

---

**Integration Path:**
`freqtrade/conductor/tracks/trikeshed-duckdb-posix_20260226/` → `trikeshed/src/posixMain/`