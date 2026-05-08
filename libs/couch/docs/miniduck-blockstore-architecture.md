# MiniDuck Block-Store Architecture

```mermaid
graph TB
    subgraph Query["Query Layer"]
        SQL["SqlParser<br/><i>Kursive combinators</i>"]
        Plan["QueryPlan<br/><i>sealed tree: Scan, Filter, Project, Order, Limit</i>"]
        Nodes["PlanNodes<br/><i>TableScan → Filter → Project → Limit</i>"]
        Exec["ExecutionContext<br/><i>SchemaManager + TableSource</i>"]
        SQL --> Plan --> Nodes --> Exec
    end

    subgraph Cursor["Cursor Algebra"]
        MC["MiniCursor = Series&lt;MiniRowVec&gt;<br/><i>lazy indexed row stream</i>"]
        Where["where(pred)"]
        GroupBy["groupBy(key, aggs)"]
        Join["hashJoin(left, right, key)"]
        OrderBy["orderBy(specs)"]
        MC --> Where --> GroupBy --> Join --> OrderBy
    end

    subgraph TS["Tablespace"]
        Tspace["Tablespace<br/><i>named region collection</i>"]
        Reg["Region<br/><i>locality name + BlockStore</i>"]
        Scan["scan(collection)<br/><i>merges blocks across regions</i>"]
        Tspace --> Reg --> Scan
    end

    subgraph Store["BlockStore SPI"]
        Put["put(collection, sealed block) → blockId"]
        Get["get(collection, blockId) → BlockRowVec"]
        List["list(collection) → blockIds"]
        Remove["remove(collection, blockId)"]
    end

    subgraph Block["Block Lifecycle"]
        direction LR
        Mutable["MUTABLE<br/>one writer<br/>append allowed"]
        Seal["seal()"]
        Sealed["SEALED<br/>many readers<br/>immutable"]
        Mutable --> Seal --> Sealed
    end

    subgraph WAL["WAL — Write-Ahead Log"]
        Append["append(WalEntry) → seq"]
        ReadRange["read(from, to) → entries"]
        Snap["snapshot(seq) → WalSnapshot"]
        Compact["compact(keepFrom)"]
        Seq["sequence numbers<br/>monotonic, gap-free"]
        Append --> Seq
    end

    subgraph MVCC["MVCC — Multi-Version Concurrency"]
        SnapId["MvccSnapshot(seq, blockId)"]
        ReadAt["readAt(snapshot) → consistent view"]
        Conflict["conflict detection<br/>write-write, read-write"]
        SnapId --> ReadAt --> Conflict
    end

    subgraph LSMR["LSMR — Log-Structured Merge"]
        direction TB
        L0["Level 0: In-Memory Buffer<br/><i>new writes land here</i>"]
        L1["Level 1: On-Disk Sorted Runs<br/><i>flushed from L0</i>"]
        L2["Level 2: Merged Runs<br/><i>compacted from L1</i>"]
        Flush["flush() when L0 full"]
        Merge["merge() dedup + sort"]
        CompactLSMR["compact() reduce read amp"]
        L0 --> Flush --> L1 --> Merge --> L2 --> CompactLSMR
    end

    subgraph Families["RowVec Families"]
        Doc["DocRowVec<br/>flat fields + children"]
        View["ViewRowVec<br/>id/key/value/doc"]
        Json["JsonRowVec<br/>parse-tree node"]
        Yaml["YamlRowVec<br/>parse-tree node"]
        BlockRV["BlockRowVec<br/>chunky sealed block"]
        Blob["BlobRowVec<br/>opaque payload"]
    end

    Exec --> MC
    MC --> Tspace
    Scan --> Put & Get & List
    Put --> Block
    Get --> Block
    Put --> WAL
    WAL --> MVCC
    WAL --> LSMR
    Sealed --> Families
    Get --> MC
```

## Data Flow

### Write Path
```
DocRowVec → BlockRowVec.append() → seal() → WAL.append() → BlockStore.put()
                  ↑                                    ↑
            MUTABLE state                    sequence number assigned
```

### Read Path
```
BlockStore.get() → BlockRowVec.child → MiniCursor → CursorOps → result
                                                        ↑
                                              where/groupBy/join/orderBy
```

### Recovery Path
```
WAL.readFrom(0) → replay entries → reconstruct BlockStore state
                                        ↓
                              MVCC snapshot at last durable seq
```

### Compaction Path
```
L0 (memory) → flush → L1 (disk runs)
L1 runs     → merge → L2 (merged runs)
WAL entries → compact → trim sequences older than snapshot
```

## Key Invariants

1. **Seal-before-put**: BlockStore never receives mutable blocks
2. **Append-only WAL**: Writes are sequenced, never reordered
3. **MVCC isolation**: Readers see a consistent snapshot at their sequence number
4. **LSMR read amplification**: Compaction reduces the number of runs to scan
5. **Block = sync boundary**: Sealing is the handoff from writer to readers
