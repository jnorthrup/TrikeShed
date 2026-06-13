# Slab Execution Seams: tinybtrfs + miniduck + FacetedCursor

## Overview

Three-layer contract without 3rd party deps:

| Layer | Technology | Algebraic Shape | LCNC Role |
|-------|------------|-----------------|-----------|
| **Physical** | tinybtrfs | `SlabCursor = Series<SlabExtent>` | COUPLING |
| **Logical** | miniduck | `FacetedCursor = Series<Cell>` | COMPUTATION |
| **Bridge** | FacetedCursor | `Join<Cell, SlabFacet>` | LOGIC + NOTIFICATION |

## btrfs → tinybtrfs mapping

| btrfs ioctl | tinybtrfs Contract | SlabExtent Output | Facet |
|------------|-------------------|-------------------|-------|
| `SUBVOL_CREATE_V2` | `createSubvol()` | `{offset=subvolid, length=0}` | `IMMUTABLE` |
| `SNAP_CREATE_V2` | `createSnapshot()` | `{offset=new_subvolid, length=0}` | `SNAPSHOT_ANCHOR + IMMUTABLE` |
| `SNAP_DESTROY_V2` | `destroySubvol()` | filtered Cursor | — |
| `CLONE_RANGE` | `cloneRange()` | `{offset=dest, length=n}` | `DEDUP_CANDIDATE` |
| `FILE_EXTENT_SAME` | `dedupExtents()` | merged extents | `COMPRESSED_ZSTD` |
| `START_SYNC + WAIT_SYNC` | `startSync() / waitSync()` | transid barrier | `WAL_ACTIVE` |
| `QGROUP_LIMIT` | `setQGroupLimit()` | quota enforced | `HOT/COLD` |
| `GET_SUBVOL_INFO` | `getSubvolInfo()` | `{offset=treeid, length=gen}` | from flags |
| `SUBVOL_SETFLAGS` | `setSubvolFlags()` | mutate facet | `IMMUTABLE` |
| `DEFRAG_RANGE` | `defragRange()` | remapped extents | — |
| `SEND` | `sendStream()` | `Series<Byte>` | cold tier |
| `RECEIVE` | `receiveStream()` | new SlabCursor | — |
| `FIEMAP` | `fiemap()` | `Series<Join<off,len>>` | for tiering |

## DuckDB → miniduck mapping

| DuckDB API / Pragma | miniduck Contract | FacetedCursor Output | Facet |
|--------------------|-------------------|----------------------|-------|
| `duckdb_open()` | `openDatabase()` | `SlabCursor` of tables | `PERSISTENT` |
| `duckdb_query()` | `query()` | `FacetedCursor{rows, cols}` | `PERSISTENT` |
| `duckdb_query()` + pragma | `queryDurable()` | result | `WAL_BUFFER` |
| `CHECKPOINT` | `checkpoint()` | flush WAL | `PERSISTENT` (WAL cleared) |
| `FORCE CHECKPOINT` | `forceCheckpoint()` | clean flush | `PERSISTENT` |
| `COPY TO 'file.parquet'` | `exportSlab()` | `SlabExtent{offset=0, length=fsize}` | `COLUMNAR_EXPORT` |
| `COPY FROM 'file.parquet'` | `importSlab()` | `FacetedCursor` | `COLUMNAR_EXPORT` |
| `duckdb_create_vectorized_function` | `createVectorizedUDF()` | applied over chunk | `COMPUTED` |
| `duckdb_fetch_batch()` | `fetchBatch()` | `DataChunk` (2048 rows) | `COMPUTED` |
| `duckdb_add_replacement_scan` | `addReplacementScan()` | custom protocol | — |
| `duckdb_register_table_function` | `registerTableFunction()` | custom reader | — |
| `ATTACH` | `attachDatabase()` | cross-slab query | — |
| `VACUUM` | `vacuum()` | reclaimed extents | — |
| `fiemap` from Parquet | `parquetRowGroups()` | `Series<Join<off, Join<len, facet>>>` | zone maps |

## FacetedCursor: LCNC Bridge

### Shape
```kotlin
typealias Cell = Join<Any?, ColumnMetaRef>     // value + metadata supplier
typealias FacetedCursor = Series<Join<Cell, SlabFacet>>
```

### LCNC Modes
| Mode | Handler | Input | Output |
|------|---------|-------|--------|
| **LOGIC** | `LogicMode` | `PointcutEvent + FacetedCursor` | pure projection `cursor.α { it }` |
| **COMPUTATION** | `ComputationMode` | `PointcutEvent + FacetedCursor` | DuckDB vectorized result |
| **NOTIFICATION** | `NotificationMode` | `PointcutEvent + FacetedCursor` | CCEK fanout to subscribers |
| **COUPLING** | `CouplingMode` | `PointcutEvent + FacetedCursor` | btrfs ioctl result |

### FieldSynapse Pointcut (24B wireproto)

```kotlin
FieldSynapse(opcode: Int)
// PHASE: BEFORE=0, AFTER=1
// FieldOpcode: L_GET=0xA5, L_SET=0xA6, P_GET=0xA7, P_SET=0xA8
// wireproto: 24B aligned
```

| Opcode | Name | Meaning |
|--------|------|---------|
| `0xA5` | `L_GET` | Logical get — read column (DuckDB) |
| `0xA6` | `L_SET` | Logical set — write column (DuckDB) |
| `0xA7` | `P_GET` | Physical get — read extent (btrfs) |
| `0xA8` | `P_SET` | Physical set — write extent (btrfs) |

### Pointcut Events Flow

```
cursor["column"] → FieldSynapse(L_GET, BEFORE) → GraalJS handler
                   ↓ (intercept)
                   miniduck.query() or tinybtrfs.fiemap()
                   ↓ (after)
                   FieldSynapse(L_GET, AFTER) → Cell{value, meta} + facet
```

## Tradeoffs Summary

| Concern | tinybtrfs | miniduck | Bridge |
|---------|-----------|----------|--------|
| **Latency** | ~5-50μs syscall | ~10-100μs query | async fanout |
| **Concurrency** | global FS locks | MVCC readers | structured |
| **Crash safety** | COW + WAL | WAL + checkpoint | facet-tagged |
| **Programmability** | C/ioctl only | SQL + C API | GraalJS/Py |
| **Predicate pushdown** | none | full (zone maps) | via COMPUTATION |
| **Extent sharing** | CLONE_RANGE | dictionary | facet derivation |
| **Replication** | btrfs send | COPY TO s3 | COLUMNAR_EXPORT |
| **Index** | none | ART index | facet=INDEXED |

## Integration: Hybrid Slab Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    FacetedCursor                            │
│  Series<Join<Cell, SlabFacet>> + FieldSynapse pointcut     │
└──────────────────┬──────────────────────┬──────────────────┘
                   │                      │
          ┌────────▼────────┐    ┌────────▼────────┐
          │   miniduck      │    │   tinybtrfs     │
          │ COMPUTATION mode│    │  COUPLING mode  │
          │                 │    │                 │
          │ query()         │    │ cloneRange()   │
          │ exportSlab()    │    │ sendStream()    │
          │ createVectorized│    │ fiemap()        │
          │ UDF()           │    │ qgroupLimit()   │
          └────────┬────────┘    └────────┬────────┘
                   │                      │
          ┌────────▼────────┐    ┌────────▼────────┐
          │  DuckDB C API   │    │  btrfs ioctl    │
          │  (JNI/FFI)      │    │  (JNR/JNI)      │
          └─────────────────┘    └─────────────────┘
```

## Slab Lifecycle Algebra

```kotlin
// Slab creation: btrfs subvol → DuckDB table
val slab = createSubvol(parentFd, "analytics_v1")
val cursor = query(conn, "SELECT * FROM '$slab'")

// Slab versioning: snapshot + export
val snap = createSnapshot(sourceFd, destFd, "v1_${ts}")
val exported = exportSlab(conn, "SELECT * FROM $snap", "s3://bucket/v1.parquet")

// Slab tiering: cold detection → btrfs send
val cold = cursor.filter { it.facet.has(COLD) }
cold.α { sendStream(snapFd, 0, 0) }.foreach { stream -> upload(stream) }

// Slab deduplication: kernel verify → merge
val deduped = dedupExtents(SameExtentArgs(fds, infos))
val merged = mergeCursor(deduped, cursor)

// Slab pointcut: GraalJS column access
val result = cursor.withPointcut(FieldSynapse(L_GET))
    .inMode(LCNCMode.COMPUTATION)
    .eval("price * quantity * discount")
    .tagged(COMPUTED)
```

## No 3rd Party Deps

| Component | Direct Bindings |
|-----------|-----------------|
| tinybtrfs | JNR (Java Native Runtime) for ioctl syscalls |
| miniduck | JNI to DuckDB C API (`duckdb_*.h`) |
| GraalJS/GraalPy | GraalVM SDK for polyglot eval |
| FacetedCursor | pure Kotlin, no external deps |

## Key Execution Seams

1. **btrfs → DuckDB**: `fiemap()` maps Parquet row groups → btrfs extents for tiering
2. **DuckDB → btrfs**: `CLONE_RANGE` dedupes identical Parquet columns across slabs
3. **Tiering**: Controller reads qgroup usage + DuckDB zone maps → `btrfs send -p` cold slabs
4. **Crash recovery**: DuckDB WAL replay + btrfs `WAIT_SYNC` transid = consistent boundary
5. **Pointcut**: `FieldSynapse(L_GET)` → GraalJS → DuckDB query → Cell + facet