# How to Launch the MiniDuck Daemon (LSMR + SQL + BlockStore)

MiniDuck is an embedded analytical database with LSMR (Log-Structured Merge with Runs) storage, a SQL compiler, and a pluggable BlockStore SPI. The "daemon" is a long-running process that holds an `LsmrDatabase` + `BlockStore` + optional CCEK bus for distributed coordination.

## Prerequisites

- JDK 25
- Kotlin 2.4.0
- Dependencies: `miniduck`, `kursive` (parser), `couch` (for tests), `tiny-btrfs` (if using btrfs-backed BlockStore)

## Architecture Overview

```
MiniDuck Daemon
├── LsmrDatabase          — LSMR engine (memtables → sorted runs → compaction)
├── BlockStore            — SPI: InMemory / ObjectStorage (S3/GCS) / ISAM / SQL / IPFS
├── TableSource           — SQL → physical plan (LsmrTableSource, InMemoryTableSource)
├── CCEKBus (optional)    — CCEK reactor bus for fanout/dispatch
└── SQL Compiler          — Parse → Plan → Execute (via kurse)
```

## Quick Start

```bash
# 1. Build
./gradlew :libs:miniduck:compileKotlinJvm :libs:miniduck:jvmJar --no-configuration-cache

# 2. Run the quick validation (smoke test)
./gradlew :libs:miniduck:quickValidate --no-configuration-cache
# Or directly:
java -cp "$(./gradlew :libs:miniduck:jvmRuntimeClasspath --no-configuration-cache -q 2>/dev/null | tr ' ' ':')" \
     borg.trikeshed.miniduck.MiniDuckQuickValidateKt
```

## Minimal Daemon Entry Point

Create `MiniDuckDaemonMain.kt`:

```kotlin
// MiniDuckDaemonMain.kt
package borg.trikeshed.miniduck.daemon

import borg.trikeshed.miniduck.LsmrConfig
import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.miniduck.exec.LsmrTableSource
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.InMemoryBlockStore
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.miniduck.sql.SqlCompiler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.nio.file.Files

fun main(args: Array<String>) = runBlocking {
    val dataDir = args.firstOrNull() ?: Files.createTempDirectory("miniduck").absolutePath
    val port = args.getOrNull(1)?.toIntOrNull() ?: 5432 // placeholder for future SQL server
    
    println("[MiniDuckDaemon] Starting on dataDir=$dataDir")
    
    // 1. Configure LSMR database
    val config = LsmrConfig(
        path = dataDir,
        memtableThreshold = 1024,   // rows before flush
        maxSegments = 8,            // max segments per level
        blockSize = 4096            // block size for BlockStore
    )
    
    // 2. Choose BlockStore implementation
    val blockStore: BlockStore = InMemoryBlockStore() // or ObjectStorageBlockStore for S3/GCS
    
    // 3. Open database (creates memtable, loads existing segments)
    val db = LsmrDatabase(config).apply { open(blockStore) }
    println("[MiniDuckDaemon] LsmrDatabase opened, segments=${db.segmentCount}")
    
    // 4. Create table source for SQL execution
    val tableSource = LsmrTableSource(db, blockSizeThreshold = 128)
    
    // 5. Register schemas (or load from catalog)
    val schema = TableSchema(
        name = "events",
        columns = listOf(
            ColumnSchema("id", ColumnSchema.Type.LONG, nullable = false),
            ColumnSchema("ts", ColumnSchema.Type.LONG, nullable = false),
            ColumnSchema("payload", ColumnSchema.Type.STRING, nullable = true),
        ),
        primaryKey = listOf("id")
    )
    tableSource.registerSchema(schema)
    
    // 6. Initialize SQL compiler
    val sqlCompiler = SqlCompiler(tableSource)
    
    // 7. Daemon loop - accept SQL via stdio, network, or gRPC
    println("[MiniDuckDaemon] Ready. Enter SQL (or 'quit'):")
    daemonLoop(sqlCompiler, db, blockStore)
}

private fun daemonLoop(
    sqlCompiler: SqlCompiler,
    db: LsmrDatabase,
    blockStore: BlockStore
) {
    // Simple REPL for demo; replace with netty/gRPC/HTTP server
    while (true) {
        print("miniduck> ")
        val line = readlnOrNull() ?: break
        if (line.trim().lowercase() in listOf("quit", "exit", "q")) break
        
        try {
            val result = sqlCompiler.execute(line.trim())
            println(result.format())
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
        }
    }
    
    // Graceful shutdown
    println("[MiniDuckDaemon] Flushing memtables...")
    db.flush()
    println("[MiniDuckDaemon] Compacting...")
    db.compact()
    println("[MiniDuckDaemon] Closing BlockStore...")
    blockStore.close()
    println("[MiniDuckDaemon] Shutdown complete")
}
```

## Run the Daemon

```bash
# Option 1: Gradle run task (add to build.gradle.kts first)
./gradlew :libs:miniduck:runDaemon --args="/tmp/miniduck-data" --no-configuration-cache

# Option 2: Direct java execution
./gradlew :libs:miniduck:compileKotlinJvm --no-configuration-cache
RUNTIME_CP=$(./gradlew :libs:miniduck:jvmRuntimeClasspath --no-configuration-cache -q 2>/dev/null | tr ' ' ':')
java -cp "$RUNTIME_CP" -Xmx2g -XX:+UseZGC \
     borg.trikeshed.miniduck.daemon.MiniDuckDaemonMainKt /tmp/miniduck-data
```

## Add `runDaemon` Task to miniduck Build

```kotlin
// In libs/miniduck/build.gradle.kts (or add to trikeshed-lib.gradle case ":libs:miniduck:")
tasks.register("runDaemon", JavaExec) {
    group = "daemon"
    description = "Run MiniDuck LSMR daemon with SQL REPL"
    dependsOn("compileKotlinJvm")
    
    val runtimeConfig = configurations.findByName("jvmRuntimeClasspath")
        ?: configurations.findByName("jvmMainRuntimeClasspath")
        ?: error("No jvm runtime classpath found")
    classpath = runtimeConfig
    
    mainClass = "borg.trikeshed.miniduck.daemon.MiniDuckDaemonMainKt"
    jvmArgs = ["-Xmx2g", "-XX:+UseZGC", "-Dkotlinx.coroutines.debug=on"]
    args(project.gradle.startParameter.commandLineArgs.filter { it != "runDaemon" }.toTypedArray())
}
```

## Using ObjectStorageBlockStore (S3/GCS/MinIO)

```kotlin
import borg.trikeshed.miniduck.objectstore.ObjectStorageBlockStoreFactory
import borg.trikeshed.miniduck.tablespace.BlockStore
import software.amazon.awssdk.services.s3.S3Client
import com.google.cloud.storage.Storage

// S3 (AWS or MinIO)
val s3Client = S3Client.builder()
    .endpointOverride(URI.create("http://localhost:9000")) // MinIO
    .region(Region.US_EAST_1)
    .build()

val blockStore: BlockStore = ObjectStorageBlockStoreFactory.createS3(
    bucket = "miniduck-blocks",
    region = "us-east-1",
    // custom S3Client for MinIO:
    // adapter = S3Adapter(s3Client)
)

// GCS
val gcsStorage = StorageOptions.getDefaultInstance().service
val blockStore: BlockStore = ObjectStorageBlockStoreFactory.createGcs("miniduck-blocks")

// Use with LsmrDatabase
val db = LsmrDatabase(config).apply { open(blockStore) }
```

## Using CCEK Bus for Distributed Coordination

```kotlin
import borg.trikeshed.miniduck.objectstore.CCEKBus
import borg.trikeshed.ccek.CcekContextBuilder

// Build CCEK reactor context
val ccekContext = CcekContextBuilder().build()

// Create bus (fanout dispatcher for block notifications)
val bus: CCEKBus = CCEKBus(ccekContext) // or CCEKBus.NoOp for standalone

// BlockStore with CCEKBus gets notified on seal/compaction
val blockStore = ObjectStorageBlockStoreFactory.createS3("bucket", "region", bus)
```

## Key Classes

| Class | Purpose | Location |
|-------|---------|----------|
| `LsmrDatabase` | LSMR engine (memtable, segments, compaction) | `libs/miniduck/src/commonMain/kotlin/borg/trikeshed/userspace/database/LsmrDatabase.kt` |
| `LsmrConfig` | Configuration (thresholds, paths, block size) | Same file |
| `BlockStore` | SPI: InMemory / ObjectStorage / ISAM / SQL / IPFS | `libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/tablespace/BlockStore.kt` |
| `LsmrTableSource` | SQL → physical plan over LSMR | `libs/miniduck/src/jvmMain/kotlin/borg/trikeshed/miniduck/exec/LsmrTableSource.kt` |
| `SqlCompiler` | Parse → Plan → Execute | `libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/sql/SqlCompiler.kt` |
| `InMemoryBlockWal` | WAL for crash recovery | `libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/tablespace/InMemoryBlockWal.kt` |

## Daemon Lifecycle

```
START
  ├── LsmrDatabase.open(BlockStore)      // Load segments, init memtable
  ├── TableSource.registerSchema(...)    // Catalog registration
  ├── SqlCompiler initialized            // Ready for queries
  ├── SERVE LOOP                         // Accept SQL, execute, return results
  │      ├── INSERT → memtable → flush → BlockStore.seal()
  │      ├── SELECT → LsmrTableSource.scan() → merge runs
  │      └── COMPACT → background merge segments
  └── SHUTDOWN
         ├── flush()     // Persist memtable
         ├── compact()   // Merge all segments
         └── blockStore.close()
```

## Testing the Daemon Components

```bash
# LSMR integration test (real database + BlockStore)
./gradlew :libs:miniduck:jvmTest --tests "borg.trikeshed.miniduck.LsmrMiniduckIntegrationTest" --no-configuration-cache

# SQL integration test
./gradlew :libs:miniduck:jvmTest --tests "borg.trikeshed.miniduck.sql.SqlIntegrationTest" --no-configuration-cache

# Quick validation (encode/decode roundtrip)
./gradlew :libs:miniduck:quickValidate --no-configuration-cache
```

## Production Considerations

1. **JVM Tuning**: `-Xmx2g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:ZCollectionInterval=30`
2. **BlockStore**: Use `ObjectStorageBlockStore` with MinIO/S3 for durability
3. **WAL**: Enable `InMemoryBlockWal` or custom durable WAL for crash recovery
4. **Compaction**: Run `db.compact()` periodically or on segment count threshold
5. **Monitoring**: Expose metrics via `db.segmentCount`, `db.memtableSize`, `blockStore.stats()`
6. **CCEK Bus**: Wire `CCEKBus` for distributed invalidation/notification across nodes