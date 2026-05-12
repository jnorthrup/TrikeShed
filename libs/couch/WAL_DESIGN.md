# WAL Design

Goal: define the durability, replay, snapshot, and compaction model for the couch module so the query layer can behave like a tiny DuckDB/Couch hybrid without losing append-only correctness.

Design stance
- append-only first
- replayable and inspectable
- snapshot-friendly
- document/version aware
- portable across KMP targets
- capable of serving both Couch-compatible revision semantics and MiniDuck scan semantics

## 1. Responsibilities

The WAL is responsible for:
- durable append of document mutations and design/view metadata mutations
- ordering by monotonic sequence number
- replay into memtables / indexes / view caches
- snapshot cut points
- compaction eligibility tracking

The WAL is not responsible for:
- serving final query rows directly in the common case
- being the primary long-term query store
- encoding every index artifact inline

## 2. Core interface

```kotlin
interface LSMRWal {
    val headSequence: Long
    val durableSequence: Long

    suspend fun append(entry: WalEntry): Long
    suspend fun read(fromInclusive: Long, toExclusive: Long): Series<WalEntry>
    suspend fun readFrom(fromInclusive: Long): Series<WalEntry>
    suspend fun snapshot(request: SnapshotRequest): WalSnapshot
    suspend fun compact(plan: CompactionPlan): CompactionResult
}
```

Naming note:
- if we keep the earlier `LSMRWAL` spelling, it should still mean a log-structured/memtable-oriented WAL, not an ML artifact

## 3. Entry model

Need a closed entry taxonomy.

```kotlin
sealed interface WalEntry {
    val sequence: Long
    val database: CharSequence
    val txId: CharSequence?
    val timestampMicros: Long
}

data class PutDoc(
    override val sequence: Long,
    override val database: CharSequence,
    val docId: CharSequence,
    val revision: CharSequence,
    val bodyJson: CharSequence,
    val deleted: Boolean = false,
    override val txId: CharSequence? = null,
    override val timestampMicros: Long,
) : WalEntry

data class DeleteDoc(
    override val sequence: Long,
    override val database: CharSequence,
    val docId: CharSequence,
    val revision: CharSequence,
    override val txId: CharSequence? = null,
    override val timestampMicros: Long,
) : WalEntry

data class PutDesignDoc(
    override val sequence: Long,
    override val database: CharSequence,
    val designDocId: CharSequence,
    val bodyJson: CharSequence,
    override val txId: CharSequence? = null,
    override val timestampMicros: Long,
) : WalEntry

data class PutAttachmentMeta(
    override val sequence: Long,
    override val database: CharSequence,
    val docId: CharSequence,
    val revision: CharSequence,
    val attachmentName: CharSequence,
    val contentType: CharSequence,
    val blobRef: CharSequence,
    val length: Long,
    override val txId: CharSequence? = null,
    override val timestampMicros: Long,
) : WalEntry
```

Keep binary attachment payloads outside the WAL where possible; the WAL should hold references and metadata.

## 4. Sequencing and revision semantics

Required invariants:
- `sequence` is globally monotonic per database, or globally monotonic with database included in the keyspace
- appends are visible in append order
- replay must be deterministic
- document revision checks happen before append acceptance
- acknowledged sequence must never move backward

Recommended split:
- `sequence`: storage ordering
- `_rev`: document revision lineage
- `txId`: optional multi-entry transaction correlation

This lets Couch compatibility keep `_rev`, while MiniDuck keeps efficient ordered scans.

## 5. Physical representation

Portable first cut:
- append into mutable NDJSON-backed working blocks
- seal blocks into immutable read-many analytical/storage units
- treat indexes as derived children of sealed blocks, not as the primary design center
- keep a lightweight manifest for block discovery and replay cut points

Suggested on-disk structure:

```text
wal/
  MANIFEST.json
  blocks/
    0000000000000001.ndjson
    0000000000005000.ndjson
  snapshots/
    0000000000005000.snapshot.json
```

Each NDJSON row contains one `WalEntry` envelope, but the important abstraction is the sealed block, not a sparse lazy index.

Example:

```json
{"kind":"PutDoc","sequence":42,"database":"acmevehicle","docId":"veh-1","revision":"2-abc","bodyJson":"{\"brand\":\"vw\"}","timestampMicros":1710000000}
```

Why NDJSON for first cut:
- diffable
- replay-friendly
- easy KMP encoding/decoding
- aligns with prior design direction
- works well as the serialized body format of sealed blocks

## 6. Replay model

Replay consumes WAL entries into block-oriented blackboard state:
- latest document family state
- revision family state
- design doc family state
- JSON/YAML/blob child families
- derived view/group summaries
- block seal candidates

Replay contract:
1. read ordered entries
2. apply idempotently into the current mutable block family
3. seal blocks at configured chunk boundaries or snapshot cuts
4. stop at snapshot cut or head
5. expose resulting state for scans and view materialization

Need a pure applier surface:

```kotlin
interface WalApplier<S> {
    fun apply(state: S, entry: WalEntry): S
}
```

This keeps replay testable and deterministic.

## 7. Snapshot model

Snapshots are the bridge from WAL to queryable state.
They should be understood as carrying two coupled views of the same state:
- semantic/logical view as `Cursor`/document relations
- lowered algebraic view as `Tensor<T> = Join<Shape, (Shape) -> T>` with `Shape = Series<Int>`, plus `Manifold` charts describing how to project between them

```kotlin
typealias Shape = Series<Int>
typealias Tensor<T> = Join<Shape, (Shape) -> T>

data class SnapshotRequest(
    val database: CharSequence,
    val upToSequence: Long = Long.MAX_VALUE,
    val includeDesignDocs: Boolean = true,
)

data class WalSnapshot(
    val database: CharSequence,
    val baseSequence: Long,
    val docs: Cursor,
    val designDocs: Cursor,
    val attachments: Cursor,
    val tensorCharts: Series<Join<CharSequence, Manifold<Int, RowVec>>>,
    val segmentRefs: List<CharSequence>,
)
```

A snapshot should represent a stable cut of state up to a sequence.
It should be enough to:
- answer `_all_docs`
- rebuild design-view state
- seed MiniDuck scan sources
- lower selected relations into tensor/manifold space without guessing shape after the fact

## 8. Locking model

MiniDuck/WAL should model Duck-like many-reader/one-writer discipline around chunky blocks.

Rules:
- exactly one writer owns the mutable current block for a database/partition at a time
- sealed blocks are immutable and therefore read-many without writer contention
- readers never observe a partially sealed block
- block sealing is the synchronization boundary, not every individual row read
- derived children (views, summaries, JSON/YAML/blob projections) are published with the block or after an atomic version step

This gives us:
- simple append discipline
- cheap concurrent scans over sealed blocks
- blockwise replay/snapshot semantics
- no need for eager per-row locks across analytical readers

## 9. Compaction model

Compaction has two jobs:
1. reduce replay cost
2. preserve enough history for correctness and replication semantics

First cut compaction rules:
- keep latest visible doc state per `_id`
- keep latest tombstone for deleted docs
- keep latest design doc state
- keep attachment metadata reachable from retained revisions
- emit a new snapshot/segment baseline
- mark old WAL segments reclaimable only after baseline is durable

Suggested types:

```kotlin
data class CompactionPlan(
    val database: CharSequence,
    val throughSequence: Long,
)

data class CompactionResult(
    val database: CharSequence,
    val compactedThrough: Long,
    val reclaimedSegments: List<CharSequence>,
    val emittedSnapshotRef: CharSequence,
)
```

## 9. Query interaction

MiniDuck should usually query snapshots + mutable memtable, not raw WAL.

Read path:
1. load latest durable snapshot/segments
2. replay tail WAL entries above snapshot
3. expose merged `Cursor`
4. lower to `Tensor<T> = Join<Shape, (Shape) -> T>` when dense execution is beneficial
5. use `Manifold` charts to move between document, row/key, and tensor coordinate systems
6. optionally route grouped/ranged patterns through materialized views

The WAL must therefore support:
- fast tail reads
- sequence cut points
- deterministic replay of only the delta after a snapshot
- stable shape metadata for tensor lowering

## 10. HTX / reactor implications

WAL stays below the transport layer.

However, it should surface enough metadata for HTX/Couch response shaping:
- update sequence
- etag/revision information
- attachment metadata
- last durable sequence

Transport should never need to parse WAL internals directly.
It should ask runtime services for shaped results.

## 11. Failure model

At minimum, WAL append must be atomic at entry granularity.

Crash safety requirements:
- partial trailing line/record at segment end is ignored or repaired on open
- manifest updates are atomic or recoverable
- compaction never deletes old segments before replacement snapshot/manifest is durable
- duplicate replays after crash must remain idempotent

## 12. First GREEN slices

1. Define `WalEntry`, `SnapshotRequest`, `WalSnapshot`, `CompactionPlan`, `CompactionResult`
2. Define in-memory `LSMRWal` fake for RED tests
3. Define NDJSON codec for `WalEntry`
4. Add replay applier for latest-doc state
5. Add snapshot cut generation from replay state
6. Add compaction planner/result objects before physical reclaim logic

## 13. Recommended file targets

When implementation starts, prefer:
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/LSMRWal.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/WalEntry.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/WalSnapshot.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/WalReplay.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/NdjsonWalCodec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/wal/Compaction.kt`

## 14. One-sentence summary

The WAL is the append-only revision-and-sequence source of truth that feeds snapshots and MiniDuck scans, while staying thin enough to compact, replay, and keep Couch-compatible semantics intact.
