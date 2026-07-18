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
        val snapshotCid = casStore.put(canonicalSnapshot)
        check(casStore.get(snapshotCid)?.contentEquals(canonicalSnapshot) == true) {
            "CAS failed read-back verification for $snapshotCid"
        }

        val sequence = currentSequence + 1L
        log.append(sequence, JobRepositoryRecordCodec.encode(snapshot, snapshotCid, payload))
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
        val tailSnapshots = mutableMapOf<JobId, JobSnapshot>()
        var latestCheckpoint: JobCheckpoint? = null

        val lastSequence = log.replay { _, payload ->
            val checkpoint = JobCheckpointCodec.decode(payload)
            if (checkpoint != null) {
                latestCheckpoint = checkpoint
                tailSnapshots.clear() // Discard preceding tail records
                return@replay
            }
            val record = JobRepositoryRecordCodec.decode(payload) ?: return@replay
            val canonicalSnapshot = CanonicalCbor.encode(record.snapshot)
            val stored = runCatching { casStore.get(record.snapshotCid) }.getOrNull()
            if (
                ContentId.of(canonicalSnapshot) == record.snapshotCid &&
                stored?.contentEquals(canonicalSnapshot) == true
            ) {
                tailSnapshots[record.snapshot.jobId] = record.snapshot
            }
        }
        currentSequence = lastSequence

        val recoveredSnapshots = mutableMapOf<JobId, JobSnapshot>()

        if (latestCheckpoint != null) {
            val checkpoint = latestCheckpoint!!
            // Verify schemaCid exists
            casStore.get(checkpoint.schemaCid) ?: throw IllegalStateException("Checkpoint schemaCid ${checkpoint.schemaCid} not found in CAS")

            // Verify full B+Tree and restore snapshots
            fun verifyAndHydrateTree(cid: ContentId) {
                val bytes = casStore.get(cid) ?: throw IllegalStateException("Checkpoint B+Tree node $cid not found in CAS")
                val node = CowBPlusTreeCodec.decode(bytes)
                when (node) {
                    is BTreeNode.Internal -> {
                        node.children.forEach { verifyAndHydrateTree(it) }
                    }
                    is BTreeNode.Leaf -> {
                        node.values.forEach { value ->
                            val snapshotBytes = casStore.get(value.cid) ?: throw IllegalStateException("Checkpoint JobSnapshot ${value.cid} not found in CAS")
                            val snapshot = CanonicalCbor.decodeJobSnapshot(snapshotBytes)
                            recoveredSnapshots[snapshot.jobId] = snapshot
                        }
                    }
                }
            }
            verifyAndHydrateTree(checkpoint.rootCid)

            // Verify metadata
            checkpoint.metadata.values.forEach { cid ->
                casStore.get(cid) ?: throw IllegalStateException("Checkpoint metadata cid $cid not found in CAS")
            }
        }

        // Apply tail logs, which take precedence
        recoveredSnapshots.putAll(tailSnapshots)

        return RecoveryResult(lastSequence, latestCheckpoint, recoveredSnapshots)
    }

    fun injectCorruptionAfter(sequence: Long) {
        log.injectCorruptionAfter(sequence)
    }
}

private data class JobRepositoryRecord(
    val snapshot: JobSnapshot,
    val snapshotCid: ContentId,
    val payload: ByteArray,
)

/** Deterministic length-prefixed binary codec; Confix remains the sole serializer. */
private object JobRepositoryRecordCodec {
    private val magic = byteArrayOf(0x4A, 0x52, 0x31, 0x00) // JR1\0
    private const val version = 1
    private const val maxFieldBytes = 16 * 1024 * 1024

    fun encode(snapshot: JobSnapshot, snapshotCid: ContentId, payload: ByteArray): ByteArray {
        val fields = buildList {
            add(snapshotCid.value.encodeToByteArray())
            add(snapshot.jobId.value.encodeToByteArray())
            add(snapshot.causalKey.encodeToByteArray())
            add(snapshot.lifecycle.encodeToByteArray())
            snapshot.dependencies.forEach { add(it.value.encodeToByteArray()) }
            snapshot.parentJobId?.let { add(it.value.encodeToByteArray()) }
            add(snapshot.attemptId.encodeToByteArray())
            add(payload)
        }
        val size = 4 + 4 + 8 + 4 + 4 + 1 + fields.sumOf { 4 + it.size }
        val out = ByteArray(size)
        val writer = Writer(out)
        writer.bytes(magic)
        writer.int(version)
        writer.field(fields[0])
        writer.field(fields[1])
        writer.long(snapshot.revision)
        writer.field(fields[2])
        writer.field(fields[3])
        writer.int(snapshot.dependencies.size)
        var fieldIndex = 4
        repeat(snapshot.dependencies.size) { writer.field(fields[fieldIndex++]) }
        writer.int(snapshot.attemptCount)
        writer.byte(if (snapshot.parentJobId == null) 0 else 1)
        if (snapshot.parentJobId != null) writer.field(fields[fieldIndex++])
        writer.field(fields[fieldIndex++])
        writer.field(fields[fieldIndex])
        check(writer.offset == out.size)
        return out
    }

    fun decode(bytes: ByteArray): JobRepositoryRecord? = runCatching {
        val reader = Reader(bytes)
        require(reader.bytes(4).contentEquals(magic))
        require(reader.int() == version)
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
        val payload = reader.field()
        require(reader.exhausted)
        JobRepositoryRecord(
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
            payload = payload,
        )
    }.getOrNull()

    private class Writer(private val out: ByteArray) {
        var offset: Int = 0
            private set

        fun byte(value: Int) {
            out[offset++] = value.toByte()
        }

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

        fun long(value: Long) {
            repeat(8) { shift -> out[offset++] = (value ushr (56 - shift * 8)).toByte() }
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
