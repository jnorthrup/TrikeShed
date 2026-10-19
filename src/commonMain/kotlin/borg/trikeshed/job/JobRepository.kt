package borg.trikeshed.job

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.collections.btree.CowBPlusTree
import borg.trikeshed.collections.btree.BTreeNode
import borg.trikeshed.collections.btree.CowBPlusTreeCodec

/** Result of recovering committed repository heads from the durable log. */
data class RecoveryResult(
    val committedSequence: Long,
    val checkpoint: JobCheckpoint? = null,
    private val snapshots: Map<JobId, JobSnapshot>,
) {
    fun snapshot(jobId: JobId): JobSnapshot? = snapshots[jobId]
    fun snapshot(jobIdStr: String): JobSnapshot? = snapshots[JobId.of(jobIdStr)]
}

/** CAS-before-WAL repository for committed job snapshots. */
class JobRepository(
    private val log: DurableAppendLog,
    private val casStore: CasStore,
) {
    private var currentSequence: Long = 0L

    suspend fun commit(jobId: JobId, snapshot: JobSnapshot, payload: ByteArray) {
        require(jobId == snapshot.jobId) {
            "commit jobId $jobId does not match snapshot ${snapshot.jobId}"
        }
        val canonicalSnapshot = CanonicalCbor.encode(snapshot)
        val snapshotCid = ContentId.of(canonicalSnapshot)
        check(casStore.put(canonicalSnapshot) == snapshotCid) {
            "CAS acknowledgement digest mismatch for $snapshotCid"
        }
        check(casStore.get(snapshotCid)?.contentEquals(canonicalSnapshot) == true) {
            "CAS failed read-back verification for $snapshotCid"
        }
        snapshot.stateCid?.let { stateCid ->
            casStore.verifyState(stateCid, synchronize = true)
            check(casStore.get(snapshotCid)?.contentEquals(canonicalSnapshot) == true) {
                "CAS failed synchronized read-back verification for $snapshotCid"
            }
        }

        val sequence = currentSequence + 1L
        log.append(sequence, JobRepositoryRecordCodec.encode(snapshotCid, canonicalSnapshot, payload))
        log.flush()
        currentSequence = sequence
    }

    suspend fun commit(jobIdStr: String, snapshot: JobSnapshot, payload: ByteArray) {
        commit(JobId.of(jobIdStr), snapshot, payload)
    }

    fun checkpoint(checkpoint: JobCheckpoint) {
        val sequence = currentSequence + 1L
        log.append(sequence, JobCheckpointCodec.encode(checkpoint))
        log.flush()
        currentSequence = sequence
    }

    suspend fun recover(): RecoveryResult {
        val tailRecords = mutableMapOf<JobId, JobRepositoryRecord>()
        var latestCheckpoint: JobCheckpoint? = null

        var replayCount = 0
        val lastSequence = log.replay { _, payload ->
            if (++replayCount % 100 == 0) kotlinx.coroutines.yield()
            val checkpoint = JobCheckpointCodec.decode(payload)
            if (checkpoint != null) {
                latestCheckpoint = checkpoint
                tailRecords.clear() // Discard preceding tail records
                return@replay
            }
            val record = JobRepositoryRecordCodec.decode(payload) ?: return@replay
            if (record.snapshot.stateCid != null) {
                // Select the actual post-checkpoint head before requiring its referenced blobs.
                tailRecords[record.snapshot.jobId] = record
            } else {
                val canonicalSnapshot = CanonicalCbor.encode(record.snapshot)
                val stored = runCatching { casStore.get(record.snapshotCid) }.getOrNull()
                if (ContentId.of(canonicalSnapshot) == record.snapshotCid &&
                    stored?.contentEquals(canonicalSnapshot) == true) {
                    tailRecords[record.snapshot.jobId] = record
                }
            }
        }

        val recoveredSnapshots = mutableMapOf<JobId, JobSnapshot>()

        if (latestCheckpoint != null) {
            val checkpoint = latestCheckpoint!!
            // Verify schemaCid exists
            casStore.get(checkpoint.schemaCid) ?: throw IllegalStateException("Checkpoint schemaCid ${checkpoint.schemaCid} not found in CAS")

            // Verify full B+Tree and restore snapshots
            var yieldCounter = 0
            suspend fun verifyAndHydrateTree(cid: ContentId) {
                if (++yieldCounter % 100 == 0) kotlinx.coroutines.yield()
                val bytes = casStore.get(cid) ?: throw IllegalStateException("Checkpoint B+Tree node $cid not found in CAS")
                check(ContentId.of(bytes) == cid) { "Checkpoint B+Tree node $cid failed digest verification" }
                val node = CowBPlusTreeCodec.decode(bytes)
                when (node) {
                    is BTreeNode.Internal -> {
                        node.children.forEach { verifyAndHydrateTree(it) }
                    }
                    is BTreeNode.Leaf -> {
                        node.values.forEach { value ->
                            if (++yieldCounter % 100 == 0) kotlinx.coroutines.yield()
                            val snapshotBytes = casStore.get(value.cid) ?: throw IllegalStateException("Checkpoint JobSnapshot ${value.cid} not found in CAS")
                            check(ContentId.of(snapshotBytes) == value.cid) {
                                "Checkpoint JobSnapshot ${value.cid} failed digest verification"
                            }
                            val snapshot = CanonicalCbor.decodeJobSnapshot(snapshotBytes)
                            snapshot.stateCid?.let { casStore.verifyState(it) }
                            recoveredSnapshots[snapshot.jobId] = snapshot
                        }
                    }
                }
            }
            verifyAndHydrateTree(checkpoint.rootCid)

            // Verify metadata
            var metadataCounter = 0
            checkpoint.metadata.values.forEach { cid ->
                if (++metadataCounter % 100 == 0) kotlinx.coroutines.yield()
                casStore.get(cid) ?: throw IllegalStateException("Checkpoint metadata cid $cid not found in CAS")
            }
        }

        // Apply tail logs, which take precedence
        for (record in tailRecords.values) {
            record.snapshot.stateCid?.let { stateCid ->
                val canonicalSnapshot = CanonicalCbor.encode(record.snapshot)
                check(ContentId.of(canonicalSnapshot) == record.snapshotCid &&
                    casStore.get(record.snapshotCid)?.contentEquals(canonicalSnapshot) == true) {
                    "Repository snapshot ${record.snapshotCid} failed CAS verification"
                }
                casStore.verifyState(stateCid)
            }
            recoveredSnapshots[record.snapshot.jobId] = record.snapshot
        }

        currentSequence = lastSequence
        return RecoveryResult(lastSequence, latestCheckpoint, recoveredSnapshots)
    }

    fun injectCorruptionAfter(sequence: Long) {
        log.injectCorruptionAfter(sequence)
    }
}

private data class JobRepositoryRecord(
    val snapshot: JobSnapshot,
    val snapshotCid: ContentId,
)

/** Deterministic length-prefixed binary codec; Confix remains the sole serializer. */
private object JobRepositoryRecordCodec {
    private val magic = byteArrayOf(0x4A, 0x52, 0x31, 0x00) // JR1\0
    private const val version = 2
    private const val maxFieldBytes = 16 * 1024 * 1024

    fun encode(snapshotCid: ContentId, canonicalSnapshot: ByteArray, payload: ByteArray): ByteArray {
        check(ContentId.of(canonicalSnapshot) == snapshotCid) { "Repository snapshot $snapshotCid failed digest verification" }
        val fields = arrayOf(snapshotCid.value.encodeToByteArray(), canonicalSnapshot, payload)
        for (field in fields) require(field.size <= maxFieldBytes) { "Repository field exceeds $maxFieldBytes bytes" }
        val out = ByteArray(8 + fields.sumOf { 4 + it.size })
        val writer = Writer(out)
        writer.bytes(magic)
        writer.int(version)
        for (field in fields) writer.field(field)
        check(writer.offset == out.size)
        return out
    }

    fun decode(bytes: ByteArray): JobRepositoryRecord? {
        val reader = Reader(bytes)
        val recordVersion = runCatching {
            require(reader.bytes(4).contentEquals(magic))
            reader.int()
        }.getOrNull() ?: return null
        return when (recordVersion) {
            1 -> runCatching { decodeV1(reader) }.getOrNull()
            version -> {
                // A recognized v2 record must not silently erase an authenticated state head.
                val cid = ContentId(reader.field().decodeToString())
                val canonicalSnapshot = reader.field()
                reader.field() // Payload belongs to the log; recovery restores the authenticated head.
                require(reader.exhausted) { "Repository record $cid has trailing bytes" }
                check(ContentId.of(canonicalSnapshot) == cid) { "Repository snapshot $cid failed digest verification" }
                val snapshot = CanonicalCbor.decodeJobSnapshot(canonicalSnapshot)
                check(CanonicalCbor.encode(snapshot).contentEquals(canonicalSnapshot)) {
                    "Repository snapshot $cid is not canonical"
                }
                JobRepositoryRecord(snapshot, cid)
            }
            else -> null
        }
    }

    private fun decodeV1(reader: Reader): JobRepositoryRecord {
        val cid = ContentId(reader.field().decodeToString())
        val jobId = JobId.of(reader.field().decodeToString())
        val revision = reader.long()
        val causalKey = reader.field().decodeToString()
        val lifecycle = reader.field().decodeToString()
        val dependencyCount = reader.int()
        require(dependencyCount in 0..1_000_000)
        val dependencies = List(dependencyCount) { JobId.of(reader.field().decodeToString()) }
        val attemptCount = reader.int()
        val parentJobId = when (reader.byte()) {
            0 -> null
            1 -> JobId.of(reader.field().decodeToString())
            else -> error("invalid parent marker")
        }
        val attemptId = reader.field().decodeToString()
        reader.field()
        require(reader.exhausted)
        return JobRepositoryRecord(
            snapshot = JobSnapshot(
                jobId = jobId,
                revision = revision,
                causalKey = causalKey,
                lifecycle = lifecycle,
                dependencies = dependencies,
                attemptCount = attemptCount,
                parentJobId = parentJobId,
                attemptId = attemptId,
            ),
            snapshotCid = cid,
        )
    }

    private class Writer(private val out: ByteArray) {
        var offset: Int = 0
            private set

        fun bytes(value: ByteArray) {
            value.copyInto(out, offset)
            offset += value.size
        }

        fun field(value: ByteArray) {
            require(value.size <= maxFieldBytes)
            int(value.size)
            bytes(value)
        }

        fun int(value: Int) {
            repeat(4) { shift -> out[offset++] = (value ushr (24 - shift * 8)).toByte() }
        }
    }

    private class Reader(private val input: ByteArray) {
        private var offset = 0
        val exhausted: Boolean get() = offset == input.size

        fun byte(): Int {
            require(offset < input.size)
            return input[offset++].toInt() and 0xFF
        }

        fun bytes(size: Int): ByteArray {
            require(size >= 0 && offset + size <= input.size)
            return input.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun field(): ByteArray {
            val size = int()
            require(size in 0..maxFieldBytes)
            return bytes(size)
        }

        fun int(): Int {
            var value = 0
            repeat(4) { value = (value shl 8) or byte() }
            return value
        }

        fun long(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or byte().toLong() }
            return value
        }
    }
}
